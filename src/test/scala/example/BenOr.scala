package example

import round._
import round.runtime._
import round.macros.Macros._

abstract class BinaryConsensusIO {
  val initialValue: Boolean
  def decide(value: Boolean): Unit
}

//http://www.cs.utexas.edu/~lorenzo/corsi/cs380d/papers/p27-ben-or.pdf
class BenOr extends Algorithm[BinaryConsensusIO] {

  import VarHelper._
  import SpecHelper._

  val x = new LocalVariable[Boolean](false)
  val callback = new LocalVariable[BinaryConsensusIO](null)
  //to make the algorithm terminates as suggested in
  //http://www.cs.toronto.edu/~samvas/teaching/2221/handouts/benor-paper.pdf
  val canDecide = new LocalVariable[Boolean](false)
  val vote = new LocalVariable[Option[Boolean]](None)

  val spec = TrivialSpec

  def process = p(new Process[BinaryConsensusIO]{
      
    def init(io: BinaryConsensusIO) {
      callback <~ io
      x <~ io.initialValue
      canDecide <~ false
    }

    val rounds = Array[Round](
      rnd(new Round{
      
        type A = (Boolean, Boolean)

        def send: Set[((Boolean, Boolean),ProcessID)] = {
          broadcast( (x: Boolean) -> (canDecide: Boolean) )
        }

        def update(mailbox: Set[((Boolean, Boolean), ProcessID)]) {
          if (canDecide) {
            callback.decide(x)
            terminate
          } else if (mailbox.filter(_._1._1).size > n/2 || mailbox.exists(m => m._1._1 && m._1._2)) {
            vote <~ Some(true)
          } else if (mailbox.filter(!_._1._1).size > n/2 || mailbox.exists(m => !m._1._1 && m._1._2)) {
            vote <~ Some(false)
          } else {
            vote <~ None
          }
          canDecide <~ mailbox.exists(_._1._2)
        }

      }),
      
      rnd(new Round{
      
        type A = Option[Boolean]

        def send: Set[(Option[Boolean],ProcessID)] = {
          broadcast( vote )
        }

        def update(mailbox: Set[(Option[Boolean], ProcessID)]) {
          val t = mailbox.filter(m => m._1.isDefined && m._1.get).size
          val f = mailbox.filter(m => m._1.isDefined && !m._1.get).size
          if (t > n/2) {
            x <~ true
            canDecide <~ true
          } else if (f > n/2) {
            x <~ true
            canDecide <~ true
          } else if (t > 1){
            x <~ true
          } else if (f > 1){
            x <~ false
          } else {
            x <~ util.Random.nextBoolean
          }
        }

      })
    )
  })

}

object BenOrRunner extends round.utils.DefaultOptions {
  
  var id = -1
  newOption("-id", dzufferey.arg.Int( i => id = i), "the replica ID")


  var confFile = "src/test/resources/3replicas-conf.xml"
  newOption("--conf", dzufferey.arg.String(str => confFile = str ), "config file")
  
  val usage = "..."
  
  var rt: RunTime[BinaryConsensusIO] = null

  def defaultHandler(msg: Message) {
    msg.release
  }
  
  def main(args: Array[java.lang.String]) {
    apply(args)
    val alg = new BenOr
    rt = new RunTime(alg)
    rt.startService(defaultHandler(_), confFile, Map("id" -> id.toString))

    import scala.util.Random
    val init = Random.nextBoolean
    val io = new BinaryConsensusIO {
      val initialValue = init
      def decide(value: Boolean) {
        Console.println("replica " + id + " decided " + value)
      }
    }
    Thread.sleep(100)
    Console.println("replica " + id + " starting with " + init)
    rt.startInstance(0, io)
  }
  
  Runtime.getRuntime().addShutdownHook(
    new Thread() {
      override def run() {
        rt.shutdown
      }
    }
  )
}