package com.seer.srd.device.converter

import com.seer.srd.CONFIG
import com.seer.srd.device.charger.convertByteArrayToHexString
import com.seer.srd.io.aioTcp.AioTcpHelper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class ConverterAioTcpClient(
    val host: String,
    val port: Int
) {

    private val logger = LoggerFactory.getLogger(ConverterAioTcpClient::class.java)

    private val channel = AsynchronousSocketChannel.open()

    private val lock = Mutex()

    private val name = "${host}:${port}"

    private val asciiReturn: Byte = 0x0d

    private val asciiNewLine: Byte = 0x0a

    init {
        runBlocking { connect() }
    }

    private suspend fun connect() {
        AioTcpHelper.connect(channel, host, port)
        logger.info("Converter[{}] connected...", name)
    }

    // connect 之前，一定要先 close channel
    fun close() {
        logger.info("Converter[{}] close channel...", name)
        try {
            channel.close()
        } catch (e: Exception) {
            logger.error("close error: ", e)
        }
    }

    fun requestBlocking(socketWord: String, timeLimit: Long) = runBlocking {
        request(socketWord, timeLimit)
    }

    private suspend fun request(socketWord: String, timeLimit: Long): String {
        lock.withLock {
            val ba = socketWord.toByteArray(StandardCharsets.UTF_8)
            val data = ByteBuffer.wrap(ba)
            logger.debug("request to remote[${name}]: $socketWord")
            logger.debug("request to remote[${name}]: ${convertByteArrayToHexString(ba, ba.size)}")
            AioTcpHelper.writeAll(channel, data)
            val resultBa = readResponse(timeLimit)
            val result = String(resultBa, 0, resultBa.size, StandardCharsets.UTF_8)
            logger.debug("response: $result")
            return result
        }
    }

    private suspend fun readResponse(timeLimit: Long): ByteArray {
        val buffer = ByteBuffer.allocate(CONFIG.httpToTcpConverterConfig.bufferSize)
        val tempBuffer = ByteBuffer.allocate(1)
        while (true) {
            tempBuffer.rewind()
            AioTcpHelper.readAll(channel, tempBuffer, timeLimit, TimeUnit.SECONDS)
            // /r/n
            buffer.put(tempBuffer.array())
            val arr = buffer.array()
            if (arr.contains(asciiReturn)) {
                val index = arr.indexOf(asciiReturn)
                val nextIndex = index + 1
                if (arr.size >= nextIndex && arr[nextIndex] == asciiNewLine) {
                    // 已经匹配到连续的/r/n，匹配结束
                    val result = ByteArray(nextIndex + 1)
                    // 获取完整的数据包，并返回给调用者
                    System.arraycopy(arr, 0, result, 0, result.size)
                    return result
                }
            }
        }
    }
}