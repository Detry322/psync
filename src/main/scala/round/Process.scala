package round

import java.nio.ByteBuffer
import runtime.ByteBufferPool
import runtime.Message

abstract class Process(val id: ProcessID) {

  val rounds: Array[Round]



  //////////////////
  // util methods //
  //////////////////

  def setGroup(g: round.runtime.Group): Unit //defined by macros
  def groupSize: Int //defined by macros

  //needed to work around the initialization order
  def postInit = {
    rounds.foreach(r => r.id = id) //set the id in round
  }

  protected def incrementRound: Unit //defined by macros

  protected def currentRound: Round //defined by macros

  protected var allocator: ByteBufferPool = null
  protected var sendBuffers: Array[ByteBuffer] = null

  def allocateResources(pool: ByteBufferPool) = {
    allocator = pool
    var n = groupSize
    sendBuffers = Array.ofDim[ByteBuffer](n)
    while(n > 0) {
      n -= 1
      sendBuffers(n) = pool.get
    }
  }

  def releaseResources = {
    if (allocator != null) {
      sendBuffers.foreach(allocator.recycle(_))
      allocator = null
      sendBuffers = null
    }
  }

  override def finalize {
    releaseResources
  }

  final def send(): Iterable[(ProcessID, ByteBuffer)] = {
    incrementRound
    currentRound.packSend(sendBuffers)
  }

  final def update(msgs: Iterable[Message]) {
    currentRound.unpackUpdate(msgs)
  }

  final def expectedNbrMessages: Int = currentRound.expectedNbrMessages

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
