package round.predicate

import round._
import Algorithm._
import round.runtime._
import round.utils.Timer

import io.netty.buffer.ByteBuf
import io.netty.channel._
import io.netty.channel.socket._
import io.netty.util.{TimerTask, Timeout}

import dzufferey.utils.Logger
import dzufferey.utils.LogLevel._


/* A predicate using timeout to deliver (when not all msg are received) */
class ToPredicate(
      grp: Group,
      instance: Short,
      channel: Channel,
      dispatcher: InstanceDispatcher,
      proc: Process,
      options: Map[String, String] = Map.empty
    ) extends Predicate(grp, instance, channel, dispatcher, proc, options)
{

  //safety condition guaranteed by the predicate
  val ensures = round.formula.True() 

  protected var expected = n

  private val from = Array.fill(n)(false)
  private var _received = 0
  def received = _received
  def resetReceived { _received = 0 }
  //var spill = new java.util.concurrent.ConcurrentLinkedQueue[DatagramPacket]()

  private val lock = new scala.concurrent.Lock

  //dealing with the timeout ?
  protected val defaultTO = {
    try {
      options.getOrElse("timeout", "200").toInt
    } catch {
      case e: Exception =>
        Logger("Predicate", Warning, "timeout unspecified or wrong format, using 200")
        200 //milliseconds
    }
  }

  //some flag about being active
  @volatile
  protected var active = true

  //each modification should set this to true, the timer will reset it
  @volatile
  protected var changed = false

  protected val tt = new TimerTask {
    def run(to: Timeout) {
      if (active) {
        if (changed) {
          changed = false
        } else {
          lock.acquire
          try {
            if (!changed) {
              Logger("ToPredicate", Debug, "delivering because of timeout")
              deliver
            } else {
              changed = false
            }
          } finally {
            lock.release
          }
        }
        timeout = Timer.newTimeout(this, defaultTO)
      }
    }
  }
  protected var timeout: Timeout = Timer.newTimeout(tt, defaultTO)


  override def stop {
    active = false
    timeout.cancel
    super.stop
  }

  override protected def clear {
    super.clear
    for (i <- 0 until n) {
      from(i) = false
    }
  }

  override protected def atRoundChange {
    expected = proc.expectedNbrMessages
    Logger("ToPredicate", Debug, "expected # msg: " + expected)
  }

  override protected def afterSend {
    if (received >= expected) {
      deliver
    }
  }

  
  protected def normalReceive(pkt: DatagramPacket) {
    val id = grp.inetToId(pkt.sender)
    //protect from duplicate packet
    if (!from(id)) {
      from(id) = true
      messages(received) = pkt
      _received += 1
      if (received >= expected) {
        deliver
      }
      changed = true
    }
  }

  def receive(pkt: DatagramPacket) {
    val tag = Message.getTag(pkt.content)
    val round = tag.roundNbr
    lock.acquire //TODO less aggressive synchronization
    try {
      //TODO take round overflow into account
      if(round == currentRound) {
        normalReceive(pkt)
      } else if (round > currentRound) {
        //we are late, need to catch up
        while(currentRound < round) {
          deliver //TODO skip the sending ?
        }
        //then back to normal
        normalReceive(pkt)
      } else {
        //late message, drop it
      }
    } finally {
      lock.release
    }
  }

}
