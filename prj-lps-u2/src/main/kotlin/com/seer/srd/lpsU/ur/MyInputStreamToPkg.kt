package com.seer.srd.lpsU.ur

import com.seer.srd.io.tcp.ByteArrayBuffer
import com.seer.srd.scheduler.backgroundCacheExecutor
import org.slf4j.LoggerFactory
import java.io.InputStream

class MyInputStreamToPkg(
    private val inputStream: InputStream,
    private val readBufferSize: Int,
    private val pkgExtractor: MyPkgExtractor,
    private val onError: (e: Exception) -> Unit
) {
  private val logger = LoggerFactory.getLogger(MyInputStreamToPkg::class.java)

  @Volatile
  var stop = false

  private var readBuffer = ByteArrayBuffer(ByteArray(readBufferSize), 0, 0)

  fun start() {
    stop = false
    backgroundCacheExecutor.submit(this::loop)
  }

  fun stop() {
    stop = true
  }

  private fun loop() {
    while (!stop) {
      try {
        val readSize = inputStream.read(
            readBuffer.buffer,
            readBuffer.validEndIndex, readBufferSize - readBuffer.validEndIndex
        )
        if (readSize < 0) {
          logger.warn("read end")
          return
        }
        readBuffer.validEndIndex += readSize
        pkgExtractor.onData(readBuffer)
      } catch (e: Exception) {
        logger.error("read", e)
        onError(e)
        return
      }
    }
  }

}