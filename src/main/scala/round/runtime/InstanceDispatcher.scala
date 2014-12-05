package round.runtime

import round.predicate.Predicate
import dzufferey.utils.Logger
import dzufferey.utils.LogLevel._

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.ArrayBlockingQueue

/** a dispatcher that scales better than putting all the instance in the pipeline */
class InstanceDispatcher(
    val defaultHandler: Message => Unit,
    val dir: Directory,
    options: Map[String, String] = Map.empty)
{

  private val exp = {
    try {
      options.getOrElse("dispatcher", "7").toInt
    } catch { case e: Exception =>
      Logger("Predicate", Warning, "dispatcher unspecified or wrong format, using 7")
      7
    }
  }
  private val mask = {
    var x = 0
    for (_ <- 0 until exp) {
      x = x << 1
      x |= 1
    }
    x
  }
  private val n = {
    val res = 1 << exp // 2^exp
    assert(res >= 0)
    res
  }

  private val locks = Array.ofDim[ReentrantLock](n)
  private val instances = Array.ofDim[List[(Int, Predicate)]](n)

  for ( i <- 0 until n ) {
    locks(i) = new ReentrantLock
    instances(i) = Nil
  }

  private def index(inst: Int): Int = inst & mask

  def add(inst: Int, handler: Predicate ) {
    val i = index(inst)
    val l = locks(i)
    l.lock()
    try {
      val lst = instances(i)
      if (lst exists (_._1 == inst)) {
        sys.error("cannot run more than one instance with the same ID: " + inst)
      }
      val lst2 = (inst, handler) :: lst
      instances(i) = lst2
    } finally {
      l.unlock()
    }
  }
  
  def remove(inst: Int) {
    val i = index(inst)
    val l = locks(i)
    var oldLst: List[(Int,Predicate)] = Nil
    l.lock()
    try {
      oldLst = instances(i)
      val lst2 = oldLst.filter( p => p._1 != inst )
      instances(i) = lst2
    } finally {
      l.unlock()
    }
    if (oldLst forall (_._1 != inst)) {
      Logger("InstanceDispatcher", Info, "dispatcher.remove: instance not found " + inst)
    }
  }

  def findInstance(inst: Int): Option[Predicate] = {
    val i = index(inst)
    instances(i).find( p => p._1 == inst).map(_._2)
  }

  /** remove all the instances from the dispatch table */
  def clear {
    for ( i <- 0 until n ) {
      instances(i) = Nil
    }
  }

  private val messageQueue = new ArrayBlockingQueue[Message](1024)

  def workerTask = new InstanceDispatcherWorker(this, messageQueue)

  //assumes the msg.dir == null, the group will be filled when dispatching
  def dispatch(msg: Message) {
    messageQueue.put(msg)
  }

}

class InstanceDispatcherWorker(dispatcher: InstanceDispatcher, queue: ArrayBlockingQueue[Message]) extends Runnable {

  @volatile var running = true

  def run {
    while(running) {
      var i: Short = 0
      try {
        val msg = queue.take
        i = msg.instance
        dispatcher.findInstance(i) match {
          case Some(inst) =>
            msg.dir = inst.grp
            if (!inst.messageReceived(msg))
              dispatcher.defaultHandler(msg)
          case None => 
            msg.dir = dispatcher.dir.group
            dispatcher.defaultHandler(msg)
        }
      } catch {
        case _:  java.nio.channels.ClosedChannelException =>
          running = false
        case e: Exception =>
          Logger("InstanceDispatcher", Warning, "instance " + i+ ", got " + e + "\n  " + e.getStackTrace.mkString("\n  "))
      }
    }
  }

}
