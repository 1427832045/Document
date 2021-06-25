package com.seer.srd.chinapost.plc

import com.seer.srd.BusinessError
import com.seer.srd.chinapost.CUSTOM_CONFIG
import com.seer.srd.io.tcp.ByteArrayBuffer
import com.seer.srd.io.tcp.KeepReceivingTcpClient
import com.seer.srd.io.tcp.PkgExtractor
import com.seer.srd.util.mapper
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class PlcManager(val config: PlcConfig) {

    private val logger = LoggerFactory.getLogger(PlcManager::class.java)

    private val reqCmdAndFlowNo: MutableMap<Int, Int> = mutableMapOf(1000 to -1, 1001 to -1)

    private val repCmdAndFlowNo: MutableMap<Int, Int> = mutableMapOf(11000 to -1, 11001 to -1)

    @Volatile
    private var pass: Boolean = false

    @Volatile
    private var resCode11001 = -1

    val passBodyMap: MutableMap<Int, ReplyBody> = ConcurrentHashMap()

    val onPositionBodyMap: MutableMap<Int, ReplyBody> = ConcurrentHashMap()

    // errorMap<code: Int, message: String>
    private val errorMap: Map<Int, String> = CUSTOM_CONFIG.errorMap
        ?: mapOf(0 to "no error.", 4000 to "request failed.")

    // 报文同步头:1 + 流水号:1 + 指令编号:2 + 数据区长度:4 + 数据区:n
    private val headerLen = 8

    @Volatile
    private var flowNo = 0

    private val pkgHead = byteArrayOf(0x5a.toByte()) // 0x5a

    private val pkgExtractor = PkgExtractor(pkgHead, this::parsePkgLen, this::onPkg)

    private val tcpClient = KeepReceivingTcpClient(config.ip, config.port, pkgExtractor)

    init {
        logger.info("init plc manager ${config.id}")
    }

    fun dispose() {
        tcpClient.close()
    }

    /** 获取完整的数据包长度 */
    private fun parsePkgLen(buffer: ByteArrayBuffer): Int {
        // 缓存的数据长度小于报头长度时，直接返回 -1
        if (buffer.validEndIndex - buffer.validStartIndex < headerLen) return -1

        val dataLen = ByteBuffer.wrap(buffer.buffer).getInt(4)
        return headerLen + dataLen
    }

    private fun onPkg(buffer: ByteArrayBuffer, len: Int) {
        // 报文同步头:1 + 流水号:1 + 指令编号:2 + 数据区长度:4 + 数据区:n
        val totalBa = ByteArray(len)
        System.arraycopy(buffer.buffer, 0, totalBa, 0, len)

        val headerBb = ByteBuffer.wrap(totalBa, 0, headerLen)
        val flowNo = headerBb[1].toInt()

        val commandCode = headerBb.getShort(2).toInt()
        if (commandCode !in listOf(11000, 11001)) {
            logger.error("PLC rep[$flowNo]: Bad reply cause undefined commandCode=$commandCode!")
        }

        val dataLen = headerBb.getInt(4)
        if (dataLen == 0) {
            logger.info("PLC rep[$flowNo]: Bad reply cause no body!")
            return
        }

        val bodyBa = ByteArray(dataLen)
        System.arraycopy(totalBa, headerLen, bodyBa, 0, len - headerLen)
        val bodyString = String(bodyBa, 0, dataLen, StandardCharsets.UTF_8)
        logger.debug("PLC rep[$flowNo]: $bodyString")

        val replyBody: ReplyBody
        try {
            replyBody = mapper.readValue(bodyString, ReplyBody::class.java)
        } catch (e: Exception) {
            logger.error("PLC rep[$flowNo]: Bad reply body!")
            return
        }

        if (replyBody.code != 0) {
            logger.error("PLC rep[$flowNo]: request failed cause [${replyBody.code}]message=${replyBody.message}!")
            return
        }

        // 根据指令编码解析数据
        when (commandCode) {
            11000 -> {
                if (replyBody.pass == null) logger.error("code: 11000, but no pass param!!!")
                else {
                    this.pass = replyBody.pass
                    logger.info("PLC rep[$flowNo]: pass=${replyBody.pass}")
                }
                repCmdAndFlowNo[11000] = flowNo
            }
            11001 -> {
                logger.info("PLC rep[$flowNo]: info place item on-position success.")
                repCmdAndFlowNo[11001] = flowNo
                resCode11001 = 0
            }
            else -> logger.error("PLC rep[$flowNo]: undefined commandCode=$commandCode")
        }
    }

    fun resetPass(){
        pass = false
    }

    fun passed(): Boolean {
        return pass
    }

    fun resetResCode11001(){
        resCode11001 = -1
    }

    fun getResCode11001(): Int {
        return resCode11001
    }

    fun getReqCmdAndFlowNo(): Map<Int, Int> {
        return reqCmdAndFlowNo
    }

    fun getRepCmdAndFlowNo(): Map<Int, Int> {
        return repCmdAndFlowNo
    }

    @Synchronized
    fun sendCommand(commandParams: CommandParams) {
        var no = flowNo
        logger.debug("************************* $commandParams $no")
        val req = buildRequestBuffer(commandParams, no)
        reqCmdAndFlowNo[commandParams.code] = no
        flowNo = (++no) % 256
        logger.debug("------------------------ $commandParams $flowNo")
        tcpClient.write(req, true)
    }

    private fun buildRequestBuffer(commandParams: CommandParams, flowNo: Int): ByteArray {
        val code = commandParams.code
        val dataString =
            if (code == 1000) "" else """{"onPosition":${commandParams.onPosition}}"""

        val dataBa = dataString.toByteArray()
        val dataLen = dataBa.size
        val totalLen = headerLen + dataLen

        val req = ByteArray(totalLen)
        val reqBuffer = ByteBuffer.wrap(req)
        reqBuffer.order(ByteOrder.BIG_ENDIAN)
        reqBuffer.put(pkgHead)                  // head:            1 byte
        reqBuffer.put(flowNo.toByte())          // flowNo:          1 byte
        reqBuffer.putShort(code.toShort())      // command code:    2 bytes
        reqBuffer.putInt(dataLen)               // data length:     4 bytes
        reqBuffer.put(dataBa)                   // data             n bytes

        return req
    }
}

data class PlcConfig(
    var id: String = "",
    var ip: String = "",
    var port: Int = 4001
)

data class ReplyBody(
    val code: Int = 0,
    val message: String = "",
    val pass: Boolean? = null
)

data class CommandParams(
    val code: Int = 0,
    val onPosition: Boolean? = null
)