package com.seer.srd.io.tcp

import com.seer.srd.scheduler.backgroundCacheExecutor
import org.slf4j.LoggerFactory
import java.io.InputStream

class InputStreamToPkg(
    private val inputStream: InputStream,
    private val readBufferSize: Int,
    private val pkgExtractor: PkgExtractor,
    private val onError: (e: Exception) -> Unit
) {
    private val logger = LoggerFactory.getLogger("com.seer.srd.io.tcp")

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
                if (readSize >= 0) {
                    // 这里是因为, 如果 readSize < 0, 那么会缩短 readBuffer.validEndIndex,
                    // 导致和 pkgLen 长度不匹配
                    readBuffer.validEndIndex += readSize
                }
                pkgExtractor.onData(readBuffer)
                // 先用包, 再 return, 否则可能导致失去最后一个包
                if (readSize < 0) {
                    logger.warn("read end")
                    return
                }
            } catch (e: Exception) {
                logger.error("read", e)
                onError(e)
                return
            }
        }
    }

}