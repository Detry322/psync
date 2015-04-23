package round.runtime

import round._
import dzufferey.utils.Logger
import dzufferey.utils.LogLevel._

import io.netty.buffer._
import io.netty.channel._
import io.netty.channel.socket._
import io.netty.channel.nio._
import io.netty.channel.socket.nio._
import io.netty.channel.epoll._
import io.netty.channel.oio._
import io.netty.channel.socket.oio._
import io.netty.channel.ChannelHandler.Sharable
import io.netty.bootstrap.Bootstrap
import java.net.InetSocketAddress

class PacketServer(
    ports: Iterable[Int],
    initGroup: Group,
    _defaultHandler: Message => Unit, //defaultHandler is responsible for releasing the ByteBuf payload
    options: RuntimeOptions)
{

  val directory = new Directory(initGroup)

  def defaultHandler(pkt: DatagramPacket) {
    val msg = new Message(pkt, directory.group)
    _defaultHandler(msg)
  }

  Logger.assert(options.protocol == NetworkProtocol.UDP, "PacketServer", "transport layer: only UDP supported for the moment")

  private val group: EventLoopGroup = options.group match {
    case NetworkGroup.NIO   => nbrThread.map( n => new NioEventLoopGroup(n) ).getOrElse( new NioEventLoopGroup() )
    case NetworkGroup.OIO   => nbrThread.map( n => new OioEventLoopGroup(n) ).getOrElse( new OioEventLoopGroup() )
    case NetworkGroup.EPOLL => nbrThread.map( n => new EpollEventLoopGroup(n) ).getOrElse( new EpollEventLoopGroup() )
  }
  private def nbrThread = options.workers match {
    case Factor(n) =>
      val w = n * java.lang.Runtime.getRuntime().availableProcessors()
      Logger("PacketServer", Debug, "using fixed thread pool of size " + w)
      Some(w)
    case Fixed(n) =>
      Logger("PacketServer", Debug, "using fixed thread pool of size " + n)
      Some(n)
    case Adapt => 
      Logger("PacketServer", Debug, "using netty default thread pool")
      None
  }

  def executor: java.util.concurrent.ScheduledExecutorService = group

  private var chans: Array[Channel] = null
  def channels: Array[Channel] = chans

  val dispatcher = new InstanceDispatcher(options)

  def close {
    dispatcher.clear
    try {
      group.shutdownGracefully
    } finally {
      for ( i <- chans.indices) {
        if (chans(i) != null) {
          chans(i).close
          chans(i) = null
        }
      }
    }
  }

  def start {
    val packetSize = options.packetSize
    val b = new Bootstrap()
    b.group(group)
    options.group match {
      case NetworkGroup.NIO =>   b.channel(classOf[NioDatagramChannel])
      case NetworkGroup.OIO =>   b.channel(classOf[OioDatagramChannel])
      case NetworkGroup.EPOLL => b.channel(classOf[EpollDatagramChannel])
    }

    if (packetSize >= 8) {//make sure we have at least space for the tag
      b.option[Integer](ChannelOption.SO_RCVBUF, packetSize)
      b.option(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(packetSize))
    }

    b.handler(new PackerServerHandler(defaultHandler, dispatcher))

    val ps = ports.toArray
    chans = ps.map( p => b.bind(p).sync().channel() )
  }

}

@Sharable
class PackerServerHandler(
    defaultHandler: DatagramPacket => Unit,
    dispatcher: InstanceDispatcher
  ) extends SimpleChannelInboundHandler[DatagramPacket](false) {

  //in Netty version 5.0 will be called: channelRead0 will be messageReceived
  override def channelRead0(ctx: ChannelHandlerContext, pkt: DatagramPacket) {
    try {
    if (!dispatcher.dispatch(pkt))
      defaultHandler(pkt) 
    } catch {
      case t: Throwable =>
        Logger("PacketServerHandler", Warning, "got " + t)
    }
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    cause.printStackTrace()
  }

}


