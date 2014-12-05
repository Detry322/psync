package round.runtime

import round._
import dzufferey.utils.Logger
import dzufferey.utils.LogLevel._

import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import java.nio.ByteBuffer


class PacketServer(
    port: Int,
    initGroup: Group,
    allocator: ByteBufferPool,
    defaultHandler: Message => Unit, //defaultHandler is responsible for releasing the ByteBuffer payload
    options: Map[String, String] = Map.empty)
{

  val directory = new Directory(initGroup)

  if (options.getOrElse("transport layer", "udp").toLowerCase != "udp") {
    Logger("PacketServer", Warning, "transport layer: only UDP supported for the moment")
  }

  private var chan = DatagramChannel.open()
  def channel: DatagramChannel = chan

  val dispatcher = new InstanceDispatcher(defaultHandler, directory)

  private var receiverTask = new Receiver(channel, dispatcher, allocator)
  private var receiver = new Thread(receiverTask)
  private var nbrWorkers = 16 //TODO the size as a fct of the CPU
  private val workers = Array.ofDim[Thread](nbrWorkers)
  private val workerTasks = Array.ofDim[InstanceDispatcherWorker](nbrWorkers)
  for (i <- 0 until nbrWorkers) {
    val t = dispatcher.workerTask
    workerTasks(i) = t
    workers(i) = new Thread(t)
  }

  def close {
    try {
      dispatcher.clear
      receiverTask.running = false
      workerTasks.foreach(_.running = false)
      Thread.sleep(10) //let the guys terminate
    } finally {
      if (chan != null) {
        chan.close
        chan = null
      }
    }
  }

  def start {
    //channel.bind(new InetSocketAddress("127.0.0.1", port))
    channel.bind(new InetSocketAddress(port))
    workers.foreach(_.start)
    receiver.start
  }

}

class Receiver(channel: DatagramChannel,
               dispatcher: InstanceDispatcher,
               allocator: ByteBufferPool) extends Runnable {

  @volatile var running = true

  def run {
    try {
      while(running) {
        val buffer = allocator.get
        val addr = channel.receive(buffer)
        buffer.flip
        val msg = new Message(addr.asInstanceOf[InetSocketAddress], buffer, null, allocator)
        dispatcher.dispatch(msg)
      }
    } catch {
      case _ : java.nio.channels.AsynchronousCloseException =>
        ()
      case e: Exception =>
        Logger("PacketServer", Error, e.toString)
        throw e
    }
  }

}
