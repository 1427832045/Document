package com.seer.srd.device.converter

import com.seer.srd.CONFIG
import com.seer.srd.device.charger.toPositive
import com.seer.srd.io.modbus.ModbusTcpMasterHelper
import com.seer.srd.scheduler.GlobalTimer
import io.netty.buffer.ByteBuf
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.pow

data class HttpToModbusTcpRequestBody(
    // 需要操作的modbus地址, 必须符合 Ipv4 的格式。
    val ip: String,

    // modbus 服务端口号, 默认值为 502
    val port: Int = 502,

    // modbus从站的ID，默认值为0
    val slaveId: Int = 0,

    // 功能码
    val functionNum: String,

    // 起始地址
    val startAddress: Int,

    // 功能码对应的地址长度
    val length: Int,

    // 需要发给设备的数据，如果是读取，为空数组即可。modbus只能传输数字。
    val writeData: List<Int> = emptyList()
)

data class HttpToModbusTcpResponseBody(
    // 响应结果字段success,0代表转接操作成功，1代表转接失败
    val success: Int,

    // 错误信息
    val message: String,

    // 注意只有当success字段为0成功时，才会有相应的数据反馈，且数据格式为int类型的数组结构
    val responseArray: List<Int>
)

class HttpToModbusTcpConverterManager(private val reqBody: HttpToModbusTcpRequestBody) {
    private val logger = LoggerFactory.getLogger(HttpToModbusTcpConverterManager::class.java)

    private val mark = "HttpToModbusTcp[${reqBody.ip}:${reqBody.port}]"

    private fun logInfo(message: String) {
        logger.info("$mark: $message")
    }

    private fun logDebug(message: String) {
        logger.debug("$mark: $message")
    }

    private var latestRequestOn: Instant? = null

    private fun updateLatestRequestOn() {
        latestRequestOn = Instant.now()
    }

    @Volatile
    private var reading = false

    @Volatile
    private var writing = false

    private var helper: ModbusTcpMasterHelper? = null

    private val period = CONFIG.httpToModbusTcpConverterConfig.pollingPeriod

    private val timer: ScheduledFuture<*>

    init {
        timer = GlobalTimer.executor.scheduleAtFixedRate(
            this::disconnectIfTimedOut,
            2000,
            period,
            TimeUnit.MILLISECONDS
        )
    }

    private fun disconnectIfTimedOut() {
        // 规定时间内，如果没有接收到当前 helper 的读写请求，就断开连接
        if (latestRequestOn == null) return
        if (toPositive(Duration.between(latestRequestOn, Instant.now()).toSeconds()) > CONFIG.httpToModbusTcpConverterConfig.delayForForceDisconnect) {
            logInfo("断开连接：长时间未收到新的读/写请求... ")
            helper?.disconnect()
            helper = null
            latestRequestOn = null
        }
    }

    private fun getHelper(): ModbusTcpMasterHelper {
        if (helper == null) {
            logDebug("build helper from null...")
            helper = ModbusTcpMasterHelper(reqBody.ip, reqBody.port)
            Thread.sleep(2000)
        }
        return helper!!
    }

    fun dispose() {
        helper?.disconnect()
        timer.cancel(true)
    }

    fun doRead(req: HttpToModbusTcpRequestBody): HttpToModbusTcpResponseBody {
        if (reading) return responseWithErrorMessage("读取失败：请求过于频繁，连续两次读操作的时间间隔需大于200ms")
        if (req.length < 1) return responseWithErrorMessage("读取失败：地址长度【${req.length}】必须大于等于1")
        updateLatestRequestOn()
        if (writing) {
            // 防止读\写操作的时间间隔太短导致的请求失败
            logDebug("正在执行写操作，等待200ms...")
            Thread.sleep(200)
        }
        return read(req)
    }

    private fun read(req: HttpToModbusTcpRequestBody): HttpToModbusTcpResponseBody {
        val remark = "HttpToModbusTcp"
        val funcNum = req.functionNum
        return try {
            reading = true
            val result = when (funcNum) {
                "01" -> getHelper().read01Coils(req.startAddress, req.length, req.slaveId, remark)
                "02" -> getHelper().read02DiscreteInputs(req.startAddress, req.length, req.slaveId, remark)
                "03" -> getHelper().read03HoldingRegisters(req.startAddress, req.length, req.slaveId, remark)
                "04" -> getHelper().read04InputRegisters(req.startAddress, req.length, req.slaveId, remark)
                else -> throw Error("无法识别的功能码【$funcNum】")
            } ?: throw Error("读取失败：读取到的数据为空！")

            responseForSuccess(parseData(listOf("01", "02").contains(funcNum), result, req.length))
        } catch (e: Exception) {
            responseWithErrorMessage(e.toString())
        } finally {
            reading = false
        }
    }

    private fun parseData(coils: Boolean, readData: ByteBuf, length: Int): List<Int> {
        val result = if (coils) parseCoilsData(readData, length) else parseRegisterData(readData, length)
        logDebug("$result")
        return result
    }

    private fun parseRegisterData(readData: ByteBuf, length: Int): List<Int> {
        val result = mutableListOf<Int>()
        // readData.size = length * 2
        for (index in (0 until length * 2)) {
            if (index % 2 == 1) continue
            result.add(readData.getShort(index).toInt())
        }
        return result
    }

    fun write(req: HttpToModbusTcpRequestBody): HttpToModbusTcpResponseBody {
        if (writing) return responseWithErrorMessage("写入失败：请求过于频繁，连续两次写操作的时间间隔需大于200ms")
        updateLatestRequestOn()
        if (reading) {
            // 防止读\写操作的时间间隔太短导致的请求失败
            logDebug("正在执行读操作，等待200ms...")
            Thread.sleep(200)
        }
        try {
            writing = true
            val data = checkWrittenData(req)
            when (val funcNum = req.functionNum) {
                "05" -> getHelper().write05SingleCoil(
                    req.startAddress, data.first() % 2 == 1, req.slaveId, "")
                "06" -> getHelper().write06SingleRegister(
                    req.startAddress, data.first(), req.slaveId, "")
                "15", "0F", "0f" -> getHelper().write0FMultipleCoils(
                    req.startAddress, req.length, formatWriteDataForCoils(data), req.slaveId, "")
                "16", "10" -> getHelper().write10MultipleRegisters(
                    req.startAddress, req.length, formatWriteDataForRegisters(data), req.slaveId, "")
                else -> throw Error("无法识别的功能码${funcNum}!")
            }
            return writeSuccess()
        } catch (e: Exception) {
            return responseWithErrorMessage(e.toString())
        } finally {
            writing = false
        }
    }

    private fun formatWriteDataForRegisters(rawData: List<Int>): ByteArray {
        val ba = ByteArray(rawData.size * 2)
        val bb = ByteBuffer.wrap(ba)
        rawData.forEach { bb.putShort(it.toShort()) }
        return ba
    }
}

fun parseCoilsData(readData: ByteBuf, length: Int): List<Int> {
    val result = mutableListOf<Int>()
    val size = length / 8
    loop1@ for (i in (0..size)) {
        var value = readData.getUnsignedByte(i).toInt()

        for (j in (0..7)) {
            result.add(value % 2)
            value /= 2
            // 展开写是为了方便理解
            val index = 8 * i + j
            if (index + 1 >= length) break@loop1
        }
    }
    return result
}

fun formatWriteDataForCoils(rawData: List<Int>): ByteArray {
    val tempData = mutableMapOf(0 to 0)
    rawData.forEachIndexed { index, i ->
        var tempValue = tempData[index / 8] ?: 0
        tempValue += (i * 2.toDouble().pow((index % 8).toDouble()).toInt())
        tempData[index / 8] = tempValue
    }

    val size = ceil(rawData.size / 8.0).toInt()
    val ba = ByteArray(size)
    val bb = ByteBuffer.wrap(ba)
    tempData.forEach { bb.put(it.value.toByte()) }
    return ba
}

fun checkWrittenData(req: HttpToModbusTcpRequestBody): List<Int> {
    val length = req.length
    val writeData = req.writeData
    val size = writeData.size
    if (length == 0) throw Error("地址长度不能为0！")
    if (size == 0) throw Error("缺少写入参数，期望写入的数据测长度不能为0！")
    if (length != size) throw Error("地址长度【$length】与期望写入的数据测长度【$size】不匹配！")
    return writeData
}

fun responseForSuccess(resArray: List<Int>): HttpToModbusTcpResponseBody {
    return HttpToModbusTcpResponseBody(0, "read success...", resArray)
}

fun writeSuccess(): HttpToModbusTcpResponseBody {
    return HttpToModbusTcpResponseBody(0, "write success...", emptyList())
}

fun unRecognizedFuncNo(funcNo: String): HttpToModbusTcpResponseBody {
    return HttpToModbusTcpResponseBody(1, "无法识别的功能码$funcNo!", emptyList())
}

fun responseWithErrorMessage(message: String): HttpToModbusTcpResponseBody {
    return HttpToModbusTcpResponseBody(1, message, emptyList())
}