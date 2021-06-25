package com.seer.srd.vehicle.driver.tcp2

import com.seer.srd.io.aioTcp.AioTcpHelper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.lang.Exception
import java.lang.RuntimeException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.AsynchronousSocketChannel
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.concurrent.TimeUnit
import kotlin.math.log

class AioTcpClient(private val agvId: String,
                   private val host: String,
                   private val port: Int) {
    private val logger = LoggerFactory.getLogger(AioTcpClient::class.java)
    private val channel = AsynchronousSocketChannel.open()
    private val lock = Mutex()
    
    var flowNo = 0
    
    /**
     * 用同一把锁约束流水号生成和请求发送这两个过程。
     * 避免在A线程请求发送之后，回复收到之前，另一个线程B进行下一次组包，
     * 导致共享的客户端对象流水号变动，A收到回复时，发现流水号不符的问题。
     */
    private suspend fun getNextFlowNo(): Int {
        lock.withLock {
            flowNo = (flowNo + 1) % 32767
            return flowNo
        }
    }
    
    init {
        runBlocking { connect() }
    }
    
    private suspend fun connect() {
        AioTcpHelper.connect(channel, host, port)
        logger.info("connected, host: {}, port: {}", host, port)
    }
    
    fun close() {
        logger.info("close, host: {}, port: {}", host, port)
        try {
            channel.close()
        } catch (e: Exception) {
            logger.error("close error: {}", e)
        }
    }
    
    fun requestBlocking(msgNo: Int, msg: String) = runBlocking {
//        logger.debug("req: [$flowNo][$port] $msg")
        val str = request(msgNo, msg)
//        logger.info("res: $str")
        str
    }
    
    suspend fun request(msgNo: Int, msg: String): String {
        val msgBytes = msg.toByteArray(StandardCharsets.UTF_8)
        val reqLength = msgBytes.size + rbkHeaderSize
        val reqBuffer = ByteBuffer.allocate(reqLength)
        // fill buffer
        reqBuffer.order(ByteOrder.BIG_ENDIAN)
        // header
        reqBuffer.put(rbkPkgStart)
        reqBuffer.put(rbkPkgVersion)
        reqBuffer.putShort(getNextFlowNo().toShort())
        reqBuffer.putInt(msgBytes.size)
        reqBuffer.putShort(msgNo.toShort())
        reqBuffer.put(rbkHeaderReserved)
        // body
        reqBuffer.put(msgBytes)
        // prepare to send
        reqBuffer.flip()
        
        // send and read response
        return lock.withLock {
            AioTcpHelper.writeAll(channel, reqBuffer)
            readResponse(flowNo)
        }
    }
    
    suspend fun readResponse(expectedFlowNo: Int?): String {
        // find package start
        val buffer1 = ByteBuffer.allocate(rbkPkgStart.size)
        while (true) {
            buffer1.rewind()
            AioTcpHelper.readAll(channel, buffer1, 3, TimeUnit.SECONDS)
            if (Arrays.equals(buffer1.array(), rbkPkgStart)) break
        }
        
        // find package header
        val headerBuffer = ByteBuffer.allocate(rbkHeaderSize - rbkPkgStart.size)
        headerBuffer.order(ByteOrder.BIG_ENDIAN)
        AioTcpHelper.readAll(channel, headerBuffer, 3, TimeUnit.SECONDS)
        
        // decode package header
        headerBuffer.position(1) // version is ignored
        val actualFlowNo = headerBuffer.short.toInt()
        val bodySize = headerBuffer.int
        
        if (actualFlowNo != expectedFlowNo && expectedFlowNo != null) {
            throw RuntimeException("FlowNo mismatch, expected: $expectedFlowNo, got: $actualFlowNo")
        }
        
        val bodyBuffer = ByteBuffer.allocate(bodySize)
        AioTcpHelper.readAll(channel, bodyBuffer, 5, TimeUnit.SECONDS)
        return String(bodyBuffer.array(), StandardCharsets.UTF_8)
    }
    
    companion object {
        const val rbkHeaderSize = 16
        val rbkPkgStart = byteArrayOf(0x5A)
        const val rbkPkgVersion: Byte = 1
        val rbkHeaderReserved = byteArrayOf(0, 0, 0, 0, 0, -35)
    }
}