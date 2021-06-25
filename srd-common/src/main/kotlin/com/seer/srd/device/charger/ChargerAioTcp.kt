package com.seer.srd.device.charger

import com.seer.srd.device.lift.LiftManager.Companion.buildRequestBuffer
import com.seer.srd.device.lift.LiftManager.Companion.pkgLenWidth
import com.seer.srd.device.lift.LiftManager.Companion.pkgStartLiftWL
import com.seer.srd.device.charger.AbstractChargerManager.Companion.buildTotalBuffer
import com.seer.srd.device.charger.AbstractChargerManager.Companion.pkgStart
import com.seer.srd.device.charger.AbstractChargerManager.Companion.totalSize
import com.seer.srd.io.aioTcp.AioTcpHelper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.lang.RuntimeException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.AsynchronousSocketChannel
import java.util.*
import java.util.concurrent.TimeUnit

enum class DeviceType {
    CHARGER, LIFT
}

class ChargerAioTcp(
    private val name: String,
    private val host: String,
    private val port: Int,
    private val type: DeviceType = DeviceType.CHARGER
) {
    private val logger = LoggerFactory.getLogger(ChargerAioTcp::class.java)

    private val channel = AsynchronousSocketChannel.open()

    private val lock = Mutex()

    private val limit = when (type) {
        DeviceType.CHARGER -> 32767
        DeviceType.LIFT -> 127
    }

    // 锂平的充电机协议中没有流水号，之后也只会在充电机上报的状态中添加流水号。
    var flowNo = 0

    private fun getNextFlowNo(): Int {
        flowNo = (flowNo + 1) % limit
        return flowNo
    }

    init {
        runBlocking { connect() }
    }

    private suspend fun connect() {
        AioTcpHelper.connect(channel, host, port)
        logger.info("${type}[$name] connected, host: {}, port: {}", host, port)
    }

    // connect 之前，一定要先 close channel
    fun close() {
        logger.info("${type}[${name}] close channel, host: {}, port: {}", host, port)
        try {
            channel.close()
        } catch (e: Exception) {
            logger.error("close error: ", e)
        }
    }

    fun requestBlocking(voltage: Int, current: Int, turnOn: Boolean) = runBlocking {
        request(voltage, current, turnOn)
    }

    // 组包 + 发送； 发送指令和读取状态是独立的
    private suspend fun request(voltage: Int, current: Int, turnOn: Boolean) {
        lock.withLock {
            val data = buildTotalBuffer(voltage, current, turnOn, getNextFlowNo().toShort())
            logger.debug("req-data to charger[${name}]: ${convertByteArrayToHexString(data.array(), 20)}")
            AioTcpHelper.writeAll(channel, data)
        }
    }

    fun readResponseBlocking() = runBlocking {
        readResponse()
    }

    // 读取 + 拆包； 发送指令和读取状态是独立的
    private suspend fun readResponse(): ByteBuffer {
        // 获取报文起始位置
        val buffer1 = ByteBuffer.allocate(pkgStart.size)
        while (true) {
            buffer1.rewind()
            AioTcpHelper.readAll(channel, buffer1, 3, TimeUnit.SECONDS)
            if (Arrays.equals(buffer1.array(), pkgStart)) break
        }
        buffer1.flip()

        // 报文的总长度是固定的20个字节，直接读取除头部2个字节以外的所有报文
        val remainsBuf = ByteBuffer.allocate(totalSize - pkgStart.size)
        AioTcpHelper.readAll(channel, remainsBuf, 3, TimeUnit.SECONDS)
        remainsBuf.flip()

        // 返回完整的报文 数据解析交给 ChargerManager 处理
        val totalBuf = ByteBuffer.allocate(totalSize)
        totalBuf.put(buffer1)
        totalBuf.put(remainsBuf)
        totalBuf.flip()

        return totalBuf
    }

    fun writeAndReadBlocking(cmd1: Byte, cmd2: Byte, content: String, name: String) = runBlocking {
        writeAndRead(cmd1, cmd2, content, name)
    }

    private suspend fun writeAndRead(cmd1: Byte, cmd2: Byte, content: String, name: String): ByteBuffer {
        lock.withLock {
            val flowNo = getNextFlowNo()
            val totalBuffer = buildRequestBuffer(cmd1, cmd2, content, flowNo.toByte(), name)
            logger.debug("req-data to lift[${name}]: ${convertByteArrayToHexString(totalBuffer.array(), totalBuffer.array().size)}")
            AioTcpHelper.writeAll(channel, totalBuffer)
            return readResponse(pkgStartLiftWL, flowNo)
        }
    }

    private suspend fun readResponse(liftPkgStart: ByteArray, expectedFlowNo: Int): ByteBuffer {
        // 包头 - 3； 数据长度 - 2； 传输方向 - 1； 流水号 - 1； 命令字 - 2； 传输数据 - n； 校验字 - 1
        val timeLimit = 5L   // default = 3
        val start = ByteBuffer.allocate(liftPkgStart.size)
        while (true) {
            start.rewind()
            AioTcpHelper.readAll(channel, start, timeLimit, TimeUnit.SECONDS)
            if (Arrays.equals(start.array(), liftPkgStart)) break
        }
        start.flip()

        val dataLen = ByteBuffer.allocate(pkgLenWidth)
        AioTcpHelper.readAll(channel, dataLen, timeLimit, TimeUnit.SECONDS)
        dataLen.flip()
        val dataLenInt = dataLen.order(ByteOrder.BIG_ENDIAN).short.toInt()
        dataLen.rewind()

        val remainsBuf = ByteBuffer.allocate(dataLenInt)
        AioTcpHelper.readAll(channel, remainsBuf, timeLimit, TimeUnit.SECONDS)
        remainsBuf.flip()

        val actualFlowNo = remainsBuf.get(1).toInt() % 127
        if (actualFlowNo != expectedFlowNo) {
            throw RuntimeException("FlowNo mismatch, expected: $expectedFlowNo, got: $actualFlowNo")
        }

        val totalBuf = ByteBuffer.allocate(5 + dataLenInt)
        totalBuf.put(start)
        totalBuf.put(dataLen)
        totalBuf.put(remainsBuf)
        totalBuf.flip()

        return totalBuf
    }
}