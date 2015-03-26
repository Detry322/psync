package round

abstract class Process[IO] extends RtProcess {

  def init(io: IO)

  val rounds: Array[Round]

  // for the runtime
  def setOptions(options: Map[String, String]) {
    rounds.foreach(_.setOptions(options))
  }

  //////////////////////
  // for verification //
  //////////////////////

  //macros will take care of populating those fields
  val initState: round.formula.Formula
  val globalVariables: List[round.formula.Variable]
  val localVariables: List[round.formula.Variable]
  val ghostVariables: List[round.formula.Variable]
  val beforeProcessing: String
  val afterProcessing: String

}

/* The type indepent parts that are necessary for the runtime */
abstract class RtProcess {

  var id: ProcessID = new ProcessID(-1)

  def setGroup(g: round.runtime.Group): Unit //defined by macros

  protected def setOptions(options: Map[String, String]): Unit
  
  protected def incrementRound: Unit //defined by macros

  protected def currentRound: Round //defined by macros

  protected var allocator: io.netty.buffer.ByteBufAllocator = io.netty.buffer.PooledByteBufAllocator.DEFAULT

  final def send(): Set[(ProcessID, io.netty.buffer.ByteBuf)] = {
    incrementRound
    currentRound.packSend(allocator)
  }

  final def update(msgs: Set[(ProcessID, io.netty.buffer.ByteBuf)]): Boolean = {
    currentRound.unpackUpdate(msgs)
  }

  final def expectedNbrMessages: Int = currentRound.expectedNbrMessages

}
