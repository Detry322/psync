package round.runtime

import round._
import dzufferey.utils.Logger
import dzufferey.utils.LogLevel._
import io.netty.channel.Channel
import io.netty.buffer.ByteBuf
import io.netty.channel.socket._
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.ForkJoinTask
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.atomic.AtomicReference

//TODO what should be the interface ?
//- val lock = new java.util.concurrent.locks.ReentrantLock
//- @volatile var roundStart: Long
//- var roundDuration: Long //TO is roundStart+roundDuration
//- def newPacket(dp: DatagramPacket): Unit
//- def interrupt(inst: Short): Unit or stop(inst: Short)
//TODO options
//-TO:
//  - non-strict: proceed as soon as there is enough messages
//  - strict: wait until TO to proceed
//-catch-up:
//  - eager: as soon as one message from the next round is received start catching up
//  - new round: after the current round finishes
//TODO break it into smaller parts
//-for learning TO/roundDuration
//  - used a discounted sum / geometric serie: coeff, window, expected RTT
//  - step increment/decrement
//  - fixed
//TODO if making a TCP RT we cannot use DatagramPacket anymore ?
trait InstHandler {
  def newPacket(dp: DatagramPacket): Unit
  def stop(inst: Short): Unit
}

class InstanceHandler[IO](proc: Process[IO],
                          rt: round.runtime.RunTime[IO],
                          channel: Channel,
                          dispatcher: InstanceDispatcher,
                          defaultHandler: DatagramPacket => Unit,
                          executor: ScheduledExecutorService,
                          options: RuntimeOptions) extends InstHandler {
  
  //protected val buffer = new ArrayBlockingQueue[DatagramPacket](options.bufferSize) 
  
  protected var timeout = options.timeout
  protected val adaptative = options.adaptative
  protected var didTimeOut = 0
  protected var roundStart = 0l

  protected var instance: Short = 0
  protected var grp: Group = null

  protected var n = 0
  protected var currentRound = 0
  protected var expected = 0

  protected var messages: Array[DatagramPacket] = null
  protected var from: Array[Boolean] = null
  protected var received = 0

  protected val lastTask = new AtomicReference[HandlerTask]()
  protected var active = false

  abstract class HandlerTask extends ForkJoinTask[Unit] {
    protected def work: Unit
    protected def exec = { 
      val prev = lastTask.getAndSet(this)
      if (prev != null) prev.join
      try {
        work
      } catch {
        case _: TerminateInstance =>
          stop
        case t: Throwable =>
          Logger("InstanceHandler", Error, "got an error " + t + " terminating instance: " + instance + "\n  " + t.getStackTrace.mkString("\n  "))
          stop
      }/* finally {
        lastTask.compareAndSet(this, null)
      } */
      true
    }
    protected def setRawResult(u: Unit) { }
    protected def getRawResult = ()
  }

  class Start(io: IO, g: Group, inst: Short, msgs: Set[Message]) extends HandlerTask {
    protected def work { 
      assert(!active)
      //TODO compare and swap...
      dispatcher.findInstance(inst) match {
        case Some(i) =>
          Logger("InstanceHandler", Warning, "instance " + instance + " already exists. aborting start.")
          msgs.foreach( m => i.newPacket(m.packet) )
        case None =>
          prepare(io, g, inst)
          Logger("InstanceHandler", Info, "starting instance " + instance)
          active = true
          msgs.foreach(p => newPacket(p.packet))
          dispatcher.add(inst, InstanceHandler.this)
          send
          scheduleNextTO
      }
    }
  }
  
  class Stop(inst: Short) extends HandlerTask {
    protected def work { 
      if (running(inst)) {
        stop
      }
    }
  }
  
  class ReceiveMessage(pkt: DatagramPacket) extends HandlerTask {
    protected def work { 
      if(!messageReceived(pkt)) {
        executor.submit(new Runnable{ def run { defaultHandler(pkt) } } )
      } else {
        didTimeOut -= 1
      }
    }
  }
  
  class Timeout(rnd: Int, inst: Short) extends HandlerTask {
    protected def work { 
      if (running(inst)) {
        if (rnd == currentRound) {
          didTimeOut += 1
          deliver
          if (adaptative) {
            //TODO something amortized to avoid oscillations
            if (didTimeOut > 5) {
              didTimeOut = 0
              timeout += 1
            } else if (didTimeOut < -50) {
              didTimeOut = 0
              timeout -= 1
            }
          }
        } else {
          didTimeOut -= 1
        }
        scheduleNextTO
      }
    }
  }
  

  /** A new packet is received and should be processed */
  def newPacket(dp: DatagramPacket) = {
    new ReceiveMessage(dp).fork
  }
  
  def stop(inst: Short) {
    new Stop(inst).fork
  }

  def start(io: IO, g: Group, inst: Short, msgs: Set[Message]) {
    val t = new Start(io, g, inst, msgs)
    t.fork
    t.join //TODO is that part needed ?
  }

  /////////////////
  // actual work //
  /////////////////

  @inline protected final def running(inst: Short) = {
    active && instance == inst
  }
  
  /** Allocate new buffers if needed */
  protected def checkResources {
    if (messages == null || messages.size != n) {
      messages = Array.ofDim[DatagramPacket](n)
      for (i <- 0 until n) messages(i) = null
    }
    if (from == null || from.size != n) {
      from = Array.ofDim[Boolean](n)
      for (i <- 0 until n) from(i) = false
    }
  }

  /** Prepare the handler for a execution.
   *  call this just before giving it to the executor */
  protected def prepare(io: IO, g: Group, inst: Short) {
    freeRemainingMessages

    instance = inst
    proc.setGroup(g)
    proc.init(io)

    grp = g
    n = g.size
    currentRound = 0
    received = 0
    expected = n
    checkResources
  }
  
  protected def freeRemainingMessages {
    var idx = 0
    while (idx < n) {
      if (messages(idx) != null) {
        messages(idx).release
        messages(idx) = null
      }
      idx += 1
    }
    for (i <- 0 until n) from(i) = false
  }

  protected def stop {
    Logger("InstanceHandler", Info, "stopping instance " + instance)
    active = false
    dispatcher.remove(instance)
    freeRemainingMessages
    rt.recycle(this)
  }
  

  ///////////////////
  // current round //
  ///////////////////
  
  //general receive (not sure if it is the correct round, instance, etc.).
  protected def receive(pkt: DatagramPacket) {
    val tag = Message.getTag(pkt.content)
    val round = tag.roundNbr
    try {
      while(round - currentRound > 0) {
        //println(grp.self.id + ", " + tag.instanceNbr + " catching up: " + currentRound + " -> " + round)
        deliver
      }
    } catch {
      case t: Throwable =>
        pkt.release
        throw t
    }
    if (round == currentRound) {
      //println(grp.self.id + ", " + tag.instanceNbr + " delivering: " + currentRound)
      //normal case
      storePacket(pkt)
      if (received >= expected) {
        deliver
      }
    } else {
      pkt.release //packet late
    }
  }

  protected def storePacket(pkt: DatagramPacket) {
    val id = grp.inetToId(pkt.sender).id
    if (!from(id)) {
      from(id) = true
      messages(received) = pkt
      received += 1
      //assert(Message.getTag(pkt.content).roundNbr == currentRound, Message.getTag(pkt.content).roundNbr + " vs " + currentRound)
    } else {
      pkt.release
    }
  }
  
  protected def deliver {
    Logger("Predicate", Debug, grp.self.id + ", " + instance + " delivering for round " + currentRound + " (received = " + received + ")")
    val toDeliver = messages.slice(0, received)
    val msgs = fromPkts(toDeliver)
    currentRound += 1
    clear
    //push to the layer above
    //actual delivery
    val mset = msgs.toSet
    if (proc.update(mset)) {
      //start the next round (if has not exited)
      send
    } else {
      //TODO better!!
      throw new TerminateInstance
    }
  }
  
  protected def clear {
    val r = received
    received = 0
    for (i <- 0 until r) {
      messages(i) = null
    }
    for (i <- 0 until n) {
      from(i) = false
    }
  }
  
  protected def send {
    roundStart = java.lang.System.currentTimeMillis()
    //Logger("Predicate", Debug, "sending for round " + currentRound)
    val myAddress = grp.idToInet(grp.self)
    val pkts = toPkts(proc.send.toSeq)
    expected = proc.expectedNbrMessages
    //println(grp.self.id + ", " + instance + " round: " + currentRound + ", expected " + expected)
    for (pkt <- pkts) {
      if (pkt.recipient() == myAddress) {
        storePacket(pkt)
      } else {
        channel.write(pkt, channel.voidPromise())
      }
    }
    channel.flush
    if (received >= expected) {
      deliver
    }
  }

  protected def messageReceived(pkt: DatagramPacket) = {
    val tag = Message.getTag(pkt.content)
    if (!running(tag.instanceNbr)) {
      pkt.release
      true
    } else if (tag.flag == Flags.normal) {
      receive(pkt)
      true
    } else if (tag.flag == Flags.dummy) {
      Logger("Predicate", Debug, grp.self.id + ", " + instance + " messageReceived: dummy flag (ignoring)")
      pkt.release
      true
    } else if (tag.flag == Flags.error) {
      Logger("Predicate", Warning, "messageReceived: error flag (pushing to user)")
      false
    } else {
      //Logger("Predicate", Warning, "messageReceived: unknown flag -> " + tag.flag + " (ignoring)")
      false
    }
  }
    
  protected def toPkts(msgs: Seq[(ProcessID, ByteBuf)]): Seq[DatagramPacket] = {
    val src = grp.idToInet(grp.self)
    val tag = Tag(instance, currentRound)
    val pkts = msgs.map{ case (dst,buf) =>
      val dst2 = grp.idToInet(dst, instance)
      buf.setLong(0, tag.underlying)
      new DatagramPacket(buf, dst2, src)
    }
    pkts
  }

  protected def fromPkts(pkts: Seq[DatagramPacket]): Seq[(ProcessID, ByteBuf)] = {
    val msgs = pkts.map( pkt => {
      val src = grp.inetToId(pkt.sender)
      val buf = pkt.content
      (src, buf)
    })
    msgs
  }


  class toTask(rnd: Int, inst: Short) extends Runnable {
    def run { new Timeout(rnd, inst).fork }
  }

  protected def scheduleNextTO {
    val delay = roundStart + timeout - java.lang.System.currentTimeMillis
    if (delay >= 0) {
      executor.schedule(new toTask(currentRound, instance), delay, TimeUnit.MILLISECONDS)
    } else {
      new Timeout(currentRound, instance).fork
    }
  }

}
