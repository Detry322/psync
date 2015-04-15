package round.runtime

import round._
import io.netty.buffer.ByteBuf
import io.netty.channel.socket._
import dzufferey.utils.LogLevel._
import dzufferey.utils.Logger
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger


class RunTime[IO](val alg: Algorithm[IO],
                  options: RuntimeOptions,
                  defaultHandler: Message => Unit) {

  private var srv: Option[PacketServer] = None

  private val processPool = new ArrayBlockingQueue[InstanceHandler[IO]](options.processPool)

  private var channelIdx = new AtomicInteger
  private def createProcess: InstanceHandler[IO] = {
    assert(srv.isDefined)
    val p = alg.process
    p.setOptions(options)
    val channels = srv.get.channels
    val idx = channelIdx.getAndIncrement.abs % channels.size
    val channel = channels(idx)
    val dispatcher = srv.get.dispatcher
    new InstanceHandler(p, this, channel, dispatcher, options)
  }

  private def getProcess: InstanceHandler[IO] = {
    val proc = processPool.poll
    if (proc == null) {
      Logger("RunTime", Warning, "processPool is running low")
      createProcess
    } else {
      proc
    }
  }

  def recycle(p: InstanceHandler[IO]) {
    processPool.offer(p)
  }

  /** Start an instance of the algorithm. */
  def startInstance(
      instanceId: Short,
      io: IO,
      messages: Iterable[Message] = None)
  {
    Logger("RunTime", Info, "starting instance " + instanceId)
    srv match {
      case Some(s) =>
        //an instance is actually encapsulated by one process
        val grp = s.directory.group
        val process = getProcess
        val messages2 = messages.filter( m => {
          if (!Flags.userDefinable(m.flag) && m.flag != Flags.dummy) {
            true
          } else {
            m.release
            false
          }
        })
        process.start(io, grp, instanceId, messages2)
      case None =>
        sys.error("service not running")
    }
  }

  /** Stop a running instance of the algorithm. */
  def stopInstance(instanceId: Short) {
    Logger("RunTime", Info, "stopping instance " + instanceId)
    srv match {
      case Some(s) =>
        s.dispatcher.findInstance(instanceId).map(_.stop(instanceId))
      case None =>
        sys.error("service not running")
    }
  }

  /** Start the service that ... */
  def startService {
    if (srv.isDefined) {
      //already running
      return
    }
    
    //create the group
    val me = new ProcessID(options.id)
    val grp = Group(me, options.peers)

    //start the server
    val ports = 
      if (grp contains me) grp.get(me).ports
      else Set(options.port)
    Logger("RunTime", Info, "starting service on ports: " + ports.mkString(", "))
    val pktSrv = new PacketServer(ports, grp, defaultHandler, options)
    srv = Some(pktSrv)
    pktSrv.start
    for (i <- 0 until options.processPool) processPool.offer(createProcess)
  }

  def shutdown {
    srv match {
      case Some(s) =>
        Logger("RunTime", Info, "stopping service")
        s.close
      case None =>
    }
    srv = None
  }
  
  def submitTask(fct: Runnable) = {
    srv.get.executor.execute(fct)
  }


  def submitTask(fct: () => Unit) = {
    srv.get.executor.execute(new Runnable{
      def run = fct()
    })
  }
  
  def receiveMessage(msg: Message, withDefault: Boolean = false) = {
    srv.get.dispatcher.findInstance(msg.instance) match {
      case Some(inst) =>
        if (!inst.processPacket(msg.packet)) {
          if (withDefault) defaultHandler(msg)
          else msg.release
        }
      case None =>
        if (withDefault) defaultHandler(msg)
        else msg.release
    }
  }

  /** the first 8 bytes of the payload must be empty */
  def sendMessage(dest: ProcessID, tag: Tag, payload: ByteBuf) = {
    assert(Flags.userDefinable(tag.flag) || tag.flag == Flags.dummy) //TODO in the long term, we might want to remove the dummy
    assert(srv.isDefined)
    val grp = srv.get.directory
    val dst = grp.idToInet(dest, tag.instanceNbr)
    payload.setLong(0, tag.underlying)
    val pkt =
      if (grp.contains(grp.self)) {
        val src = grp.idToInet(grp.self)
        new DatagramPacket(payload, dst, src)
      } else {
        new DatagramPacket(payload, dst)
      }
    val channel = srv.get.channels(0)
    channel.write(pkt, channel.voidPromise())
    channel.flush
  }

  def directory = {
    assert(srv.isDefined)
    srv.get.directory
  }

}
