package psync.utils.isabelle

// inspired by
// https://github.com/larsrh/libisabelle/blob/master/examples/src/main/scala/Hello_PIDE.scala
// https://github.com/fthomas/libisabelle-example/blob/master/src/main/scala/libisabelle/example/Main.scala

import psync.formula._
import scala.concurrent._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import edu.tum.cs.isabelle._
import edu.tum.cs.isabelle.api._
import edu.tum.cs.isabelle.setup._
import dzufferey.utils.Logger
import dzufferey.utils.LogLevel._

object Session {

  final val version = Version("2016")
  final val timeout = Duration.Inf
  final val _10s = Duration(10, SECONDS)

  def await[T](a: Awaitable[T], to: Duration = timeout): T = {
    Await.result(a, to)
  }

}

class Session {

  import Session.{version,await}

  protected var system: System = null

  protected val logLevel = Notice
  //protected val logLevel = Info

  var timeout = Session.timeout

  def start {
    Logger.assert(system == null, "isabelle.Session", "session has already started")

    Logger("isabelle.Session", logLevel, "Starting Isabelle")
    val setup = Setup.defaultSetup(version) match {
      case cats.data.Xor.Left(err) =>
        sys.error(err.toString)
      case cats.data.Xor.Right(future) =>
        await(future)
    }
    Logger("isabelle.Session", logLevel, "Setup done")

    val resources = Resources.dumpIsabelleResources()
    import java.nio.file.Paths
    val paths = List(Paths.get("src/main/isabelle"))
    val config = resources.makeConfiguration(paths, "PSync")
    val env = await(setup.makeEnvironment)
    Logger("isabelle.Session", logLevel, "Environement done")
    Logger("isabelle.Session", logLevel, "Building session")
    if (!System.build(env, config)) {
      Logger.logAndThrow("isabelle.Session", Error, "Build failed")
    } else {
      Logger("isabelle.Session", logLevel, "Starting " + version + " instance")
      system = await(System.create(env, config))
      Logger("isabelle.Session", logLevel, "Isabelle started")
    }
  }

  def stop {
    Logger.assert(system != null, "isabelle.Session", "session has already ended")
    Logger("isabelle.Session", logLevel, "Stopping Isabelle")
    await(system.dispose)
    system = null
  }

  /* hello world operation to test the system */
  def hello = {
    val response = runCommand(Operation.Hello, "world")
    response.unsafeGet
  }

  protected def runCommand[I, O](op: Operation[I, O], arg: I) = {
    Logger.assert(system != null, "isabelle.Session", "session not yet started or already ended")
    val future = system.invoke(op)(arg)
    await(future, timeout)
  }

  def newTheory(name: String) = {
    Logger("isabelle.Session", logLevel, "new theory " + name)
    runCommand(Operations.startTheory, "PSync" -> name)
  }

  /* get the current state of Isabelle */
  def getCurrentState = {
    ???
  }

  def lemma(name: String,
            hypotheses: List[Formula],
            conclusion: Formula,
            proof: Option[String]) = {
    //XXX do something with the name on the isabelle side ?
    Logger("isabelle.Session", logLevel, "trying to prove " + name)
    val statement = hypotheses match {
      case _ :: _ :: _  => Implies(And(hypotheses:_*), conclusion)
      case h :: Nil => Implies(h, conclusion)
      case Nil => conclusion
    }
    val asTem = TranslateFormula(statement)
    Logger("isabelle.Session", Debug, "translated formula: " + asTem)
    runCommand(Operations.prove, asTem -> proof)
  }

  //TODO commands and stuffs

}
