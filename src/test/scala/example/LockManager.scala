package example

import round._
import round.runtime._
import round.utils.{Logger, Arg, Options}
import round.utils.LogLevel._
import java.net.InetSocketAddress
import io.netty.bootstrap._
import io.netty.buffer._
import io.netty.channel._
import io.netty.channel.nio._
import io.netty.channel.socket._
import io.netty.channel.socket.nio._
import io.netty.util.CharsetUtil
import java.util.concurrent.Semaphore


class LockManager(self: Short,
                  clientPort: Int,
                  confFile: String) {
 
  type ProcessID = Short

  ////////////////
  //local state //
  ////////////////
  @volatile
  private var locked: Option[ProcessID] = None //who has the lock
  @volatile
  private var versionNbr = 0

  ///////////////
  // Consensus //
  ///////////////
  
  private val semaphore = new Semaphore(1, true) //at most one consensus at the time
  private val consensus = new RunTime(new OTR)

  private def onDecideOther(decision: Option[ProcessID]) {
    locked = decision
    versionNbr += 1 
    semaphore.release
  }

  private def onDecideSelf(client: Client, decision: Option[ProcessID]) {
    onDecideOther(decision)
    client.reply(decision)
  }

  private def startConsensus(expectedInstance: Short, io: OtrIO, msgs: Set[Message] = Set.empty) {
    Logger("LockManager", Notice, "starting consensus with value " + io.initialValue)
    //enter critical section
    semaphore.acquire
    //check instanceNbr
    if (expectedInstance == versionNbr + 1) {
      //make the paramerter and start the instance
      consensus.startInstance(expectedInstance, io, msgs)
    } else if (expectedInstance <= versionNbr){
      //msg.sender is late
      //or race on receiving msg and the default handler (message was blocked in the pipeline)
      //our implementation should exclude the later case ...
    } else { //if (expectedInstance > versionNbr+1){
      //we are late
      //start recovery ?
    }
  }

  //////////////////////////
  // setup, shutdown, ... //
  //////////////////////////

  def defaultHandler(msg: Message) = {

    //get the initial value from the msg (to avoid defaulting on -1)
    val content: Int = msg.getContent[Int]

    val io = new OtrIO {
      val initialValue = content
      def decide(value: Int) {
        if (value == -1) onDecideOther(None)
        else onDecideOther(Some(value.toShort))
      }
    }

    startConsensus(msg.instance, io, Set(msg))
  }

  def shutDown {
    consensus.shutdown
    clientChannel.close
  }

  def start() {
    consensus.startService(defaultHandler, confFile, Map("id" -> self.toString))
    listenForClient //this never returns
  }

  //clean-up on ctrl-c
  Runtime.getRuntime().addShutdownHook(
    new Thread() {
      override def run() { shutDown }
    }
  )

  ////////////
  // Client //
  ////////////

  private var clientChannel: Channel = null
  private val reqAcquire = "acquire"
  private val reqRelease = "release"

  //TODO client over TCP ?
  private class Client(val address: InetSocketAddress, val acquire: Boolean) {
    def reply(status: Option[ProcessID]) {
      val success = (status.isEmpty && !acquire) ||
                    (status.isDefined && acquire && status.get == self)
      val message = if (success) "SUCCESS" else "FAILED"
      val pck = new DatagramPacket(Unpooled.copiedBuffer(message, CharsetUtil.UTF_8), address)
      clientChannel.writeAndFlush(pck).sync()
      Logger("LockManager", Notice, "reply to " + success + " to client " + address)
    }

  }

  private class ClientHandler extends SimpleChannelInboundHandler[DatagramPacket] {
    //in Netty version 5.0 will be called: channelRead0 will be messageReceived
    override def channelRead0(ctx: ChannelHandlerContext, pkt: DatagramPacket) {
      val request = pkt.content().toString(CharsetUtil.UTF_8).toLowerCase
      val sender = pkt.sender()
      Logger("LockManager", Notice, "new client request " + request + " from " + sender)
      val initValue = request match {
        case `reqAcquire` => self.toInt
        case `reqRelease` => -1
        case _ => sys.error("unnkown request")
      }
      val client = new Client(sender, initValue != -1)
      val io = new OtrIO {
        val initialValue = initValue
        def decide(value: Int) {
          val dec = if (value == -1) None else Some(value.toShort)
          onDecideSelf(client, dec)
        }
      }
      if ((request == reqAcquire && locked.isEmpty) || 
          (request == reqRelease && locked.isDefined && locked.get == self)) {
        startConsensus((versionNbr+1).toShort, io)
      } else {
        client.reply(None) //fail
      }
    }

  }

  def listenForClient {
    val group = new NioEventLoopGroup();
    try {
      val b = new Bootstrap();
      b.group(group)
        .channel(classOf[NioDatagramChannel])
        .handler(new ClientHandler)

      Logger("LockManager", Notice, "listening for client on " + clientPort)
      clientChannel = b.bind(clientPort).sync().channel()
      clientChannel.closeFuture().await();
    } finally {
      group.shutdownGracefully()
    }
  }


}


class LockManagerClient(myPort: Int, remote: (String, Int)) {

  val address = new InetSocketAddress(remote._1, remote._2)

  private var critical = false

  def nextRequest = if (critical) "release" else "acquire"

  private class ReplyHandler extends SimpleChannelInboundHandler[DatagramPacket] {
    //in Netty version 5.0 will be called: channelRead0 will be messageReceived
    override def channelRead0(ctx: ChannelHandlerContext, pkt: DatagramPacket) {
      val reply = pkt.content().toString(CharsetUtil.UTF_8).toLowerCase
      Logger("LockManagerClient", Notice, "request: " + reply)
      if (reply == "success") {
        critical = !critical
      }
    }

  }
    
  def run {
    val group = new NioEventLoopGroup(1);
    try {
      val b = new Bootstrap();
      b.group(group)
        .channel(classOf[NioDatagramChannel])
        .handler(new ReplyHandler)

      Logger("LockManagerClient", Notice, "bingind to " + myPort)
      val channel = b.bind(myPort).sync().channel()
      try {  
        var input = scala.io.StdIn.readLine()
        while (input != "exit") {
          val req = nextRequest
          Logger("LockManagerClient", Notice, "new request: " + req)
          val pck = new DatagramPacket(Unpooled.copiedBuffer(req, CharsetUtil.UTF_8), address)
          channel.writeAndFlush(pck).sync()
          input = scala.io.StdIn.readLine()
        }
      } finally {
        Logger("LockManagerClient", Notice, "shutting down")
        channel.close
      }
    } finally {
      group.shutdownGracefully()
    }
  }

}

object Main extends Options {

  newOption("-v", Arg.Unit(() => Logger.moreVerbose), "increase the verbosity level.")
  newOption("-q", Arg.Unit(() => Logger.lessVerbose), "decrease the verbosity level.")

  var client = false
  newOption("-c", Arg.Unit(() => client = true), "client mode (default is server mode)")

  var clientPort = 8889
  newOption("-p", Arg.Int( i => clientPort = i), "port")
  var remotePort = 8888
  newOption("-rp", Arg.Int( i => remotePort = i), "remote port")
  var remoteAddress = "127.0.0.1"
  newOption("-ra", Arg.String( str => remoteAddress = str), "replica address")

  var id = -1
  newOption("-id", Arg.Int( i => id = i), "the replica ID")

  var confFile = "src/test/resources/sample-conf.xml"
  newOption("--conf", Arg.String(str => confFile = str ), "config file")

  val usage = "..."

  def main(args: Array[String]) {
    Logger.moreVerbose
    apply(args)
    if (client) {
      val cli = new LockManagerClient(clientPort, (remoteAddress, remotePort))
      cli.run
    } else {
      val srv = new LockManager(id.toShort, clientPort, confFile)
      srv.start
    }
  }

}