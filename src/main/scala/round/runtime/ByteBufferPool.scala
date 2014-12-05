package round.runtime

import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import dzufferey.utils.LogLevel._
import dzufferey.utils.Logger

class ByteBufferPool( bufferSize: Int,
                      direct: Boolean,
                      allocChunk: Int,
                      startPoolSize: Int,
                      maxPoolSize: Int)
{

  private val queue = new ArrayBlockingQueue[ByteBuffer](maxPoolSize)

  @inline private def allocSingleBuffer = 
    if (direct) ByteBuffer.allocateDirect(bufferSize)
    else ByteBuffer.allocate(bufferSize)

  for (_ <- 0 until startPoolSize) {
    queue.offer(allocSingleBuffer)
  }

  private def alloc {
    Logger("ByteBufferPool", Warning, "allocating " + allocChunk + " of " + bufferSize + " bytes buffers.")
    var i = 0
    while (i < allocChunk) {
      i += 1
      queue.offer(allocSingleBuffer)
    }
  }

  def get = {
    var buffer = queue.poll
    while (buffer == null) {
      alloc
      buffer = queue.poll
    }
    buffer
  }

  def recycle(buffer: ByteBuffer) {
    buffer.clear
    assert(buffer.capacity == bufferSize, "byte buffer has the wrong size")
    queue.offer(buffer)
  }

}

object ByteBufferPool {

  private val bufferSize: Int = 512

  private val direct: Boolean = true

  private val maxPoolSize: Int = 1024
  private val startPoolSize: Int = maxPoolSize / 2
  private val allocChunk: Int = startPoolSize / 2

  def apply(bufferSize: Int, direct: Boolean, allocChunk: Int, startPoolSize: Int, maxPoolSize: Int): ByteBufferPool =
    new ByteBufferPool(bufferSize, direct, allocChunk, startPoolSize, maxPoolSize)

  def apply(bufferSize: Int, direct: Boolean): ByteBufferPool = {
    apply(bufferSize, direct, allocChunk, startPoolSize, maxPoolSize)
  }

  def apply(): ByteBufferPool = {
    apply(bufferSize, direct)
  }

}
