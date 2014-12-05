package round.runtime

import round._

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import dzufferey.utils.LogLevel._
import dzufferey.utils.Logger

import scala.pickling._
import binary._

class Message(val sender: InetSocketAddress,
              val payload: ByteBuffer,
              var dir: Group,
              pool: ByteBufferPool) {

  protected var released = false

  def receiverId: ProcessID = dir.self
  lazy val senderId: ProcessID = try { dir.inetToId(sender) }
                                 catch { case _: Exception => new ProcessID(-1) }
  lazy val tag: Tag = {
    assert(!released, "cannot access a released Message")
    new Tag(payload.getLong(0))
  }

  def flag = tag.flag
  def instance = tag.instanceNbr
  def round = tag.roundNbr
  
  def getContent[A: SPickler: Unpickler: FastTypeTag]: A = {
    val bytes = getPayLoad
    val converted = BinaryPickle(bytes).unpickle[A]
    converted
  }

  def getInt(idx: Int): Int = {
    assert(!released, "cannot access a released Message")
    payload.getInt(8+idx)
  }
  
  def getPayLoad: Array[Byte] = {
    assert(!released, "cannot access a released Message")
    payload.getLong() //skip the tag
    val length: Int = payload.remaining()
    val bytes = Array.ofDim[Byte](length)
    payload.get(bytes)
    payload.rewind
    bytes
  }

  def release = {
    if (!released) {
      released = true
      if (pool != null) {
        pool.recycle(payload)
      } else {
        payload.clear
      }
    }
  }

  override def finalize {
    if (!released) {
      Logger("Message", Warning, "collected by GC but not released. instance = " + instance +
                                                                    ", round = " + round +
                                                                   ", sender = " + senderId )
    }
  }

}


object Message {

  def getTag(buffer: ByteBuffer): Tag = {
    new Tag(buffer.getLong(0))
  }
  
}
