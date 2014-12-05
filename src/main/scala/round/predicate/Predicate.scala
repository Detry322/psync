package round.predicate

import round._
import round.formula._
import round.runtime._

import dzufferey.utils.Logger
import dzufferey.utils.LogLevel._

import scala.reflect.ClassTag
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.net.InetSocketAddress
import java.util.concurrent.locks.ReentrantLock

abstract class Predicate(
      val grp: Group,
      val instance: Short,
      channel: DatagramChannel,
      dispatcher: InstanceDispatcher, //TODO simpler way of registering with the dispatcher (reduce number of args)
      proc: Process,
      options: Map[String, String] = Map.empty
    )
{

  //what does it guarantee
  val ensures: Formula
  
  protected val lock = new ReentrantLock
  
  //TODO interface between predicate and the algorithm: ...

  //what do predicates implement ?

  //general receive (not sure if it is the correct round).
  //vanilla implementation looks like:
  //  val round = Message.getTag(payload).roundNbr
  //  if (round >= currentRound) {
  //    //we are late, need to catch up
  //    while(currentRound < round) { deliver }
  //    normalReceive(src, payload)
  //  } // else: this is a late message, drop it
  def receive(m: Message): Unit

  //for messages that we know already belong to the current round.
  //vanilla implementation looks like:
  //  val id = grp.inetToId(src)
  //  messages(received) = pkt
  //  received += 1
  //  if (received >= expectedNbrMessage) {
  //    deliver
  //  }
  protected def normalReceive(m: Message)


  val n = grp.size
  var currentRound = 0
  
  val messages = Array.ofDim[Message](n)
  def received: Int
  def resetReceived: Unit

  //register in the channel and send the first set of messages
  def start {
    dispatcher.add(instance, this)
    send
  }

  //things to do when changing round (overridden in sub classes)
  protected def atRoundChange { }
  protected def afterSend { }
  protected def afterUpdate { }
  
  protected def deliver {
    lock.lock
    try {
      //Logger("Predicate", Debug, "instance " + instance +
      //                           ", delivering for round " + currentRound +
      //                           ", received = " + received)
      val msgs = messages.slice(0, received).filter(_ != null)
      if (msgs.size != received) {
        Logger("Predicate", Warning,"instance " + instance +
                                    " round " + currentRound +
                                    " received: " + msgs.size +
                                    " instead of " + received)
      }
      currentRound += 1
      clear
      //push to the layer above
      try {
        //actual delivery
        proc.update(msgs)
        afterUpdate
        //start the next round (if has not exited)
        send
      } catch {
        case e: TerminateInstance =>
          stop
        case e: Throwable =>
          Logger("Predicate", Error, "got an error " + e + " terminating instance: " + instance)
          stop
          throw e
      }
    } finally {
      lock.unlock
    } 
  }
  
  //deregister
  def stop {
    lock.lock
    try {
      Logger("Predicate", Info, "stopping instance " + instance)
      dispatcher.remove(instance)
      var idx = 0
      while (idx < n) {
        if (messages(idx) != null) {
          messages(idx).release
          messages(idx) = null
        }
        idx += 1
      }
      proc.releaseResources
    } finally {
      lock.unlock
    }
  }

  protected def clear {
    assert(lock.isHeldByCurrentThread, "lock.isHeldByCurrentThread")
    val r = received
    resetReceived
    for (i <- 0 until r) {
      messages(i) = null
    }
    assert(received == 0)
  }

  ////////////////
  // utils + IO //
  ////////////////
  
  def send {
    //Logger("Predicate", Debug, "sending for round " + currentRound)
    val myAddress = grp.idToInet(grp.self)
    val pkts = toPkts(proc.send)
    atRoundChange
    for ((dest, payload) <- pkts) {
      payload.flip
      if (dest == myAddress) {
        normalReceive(new Message(dest, payload, grp, null))
      } else {
        channel.send(payload, dest)
        payload.clear
      }
    }
    afterSend
  }

  def messageReceived(m: Message) = {
    val tag = m.tag
    assert(instance == tag.instanceNbr)
    if (tag.flag == Flags.normal) {
      receive(m)
      true
    } else if (tag.flag == Flags.dummy) {
      Logger("Predicate", Debug, "messageReceived: dummy flag (ignoring)")
      m.release
      true
    } else if (tag.flag == Flags.error) {
      Logger("Predicate", Warning, "messageReceived: error flag (pushing to user)")
      false
    } else {
      //Logger("Predicate", Warning, "messageReceived: unknown flag -> " + tag.flag + " (ignoring)")
      false
    }
  }
    
  protected def toPkts(msgs: Iterable[(ProcessID, ByteBuffer)]): Iterable[(InetSocketAddress, ByteBuffer)] = {
    val tag = Tag(instance, currentRound)
    val pkts = msgs.map{ case (dst,buf) =>
      val dst2 = grp.idToInet(dst)
      buf.putLong(0, tag.underlying)
      dst2 -> buf
    }
    pkts
  }

}
