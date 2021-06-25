package com.seer.srd.device.lift

import com.seer.srd.*
import com.seer.srd.device.charger.ChargerAioTcp
import com.seer.srd.device.charger.DeviceType
import com.seer.srd.device.charger.isTimedOut
import com.seer.srd.device.charger.toPositive
import com.seer.srd.io.tcp.ByteArrayBuffer
import com.seer.srd.io.tcp.KeepReceivingTcpClient
import com.seer.srd.io.tcp.PkgExtractor
import com.seer.srd.scheduler.GlobalTimer
import com.seer.srd.scheduler.backgroundFixedExecutor
import com.seer.srd.util.mapper
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.experimental.xor

class LiftManager(val config: LiftConfig): AbstractLiftManager(config) {

    private val pkgExtractor = PkgExtractor(pkgStartLiftWL, this::parsePkgLen, this::onPkg)

    @Volatile
    private var tcpClient: KeepReceivingTcpClient? = null

    @Volatile
    private var aioTcpClient: ChargerAioTcp? = null

    @Volatile
    private var rebuilding = false

    private val firstCall = FirstCmd()

    private val firstGo = FirstCmd()

    private val repCount = RepCount()

    private var onPkgTimestamp: Instant? = null

    @Volatile
    private var flowNo = 0

    private lateinit var thread: Thread

    private var lastRequestOn: Instant? = null  // 在判读呼叫电梯超时之后修改

    private var currRequestOn: Instant? = null  // 在 call 和 go 时修改

    private var maxRequestCount = 3
    private fun maxRequestCountReducing(): Int {
        maxRequestCount--
        return maxRequestCount
    }

    private val timer: ScheduledFuture<*>

    init {
        if (liftConfig.mode == IOMode.Tcp) {
            tcpClient = KeepReceivingTcpClient(liftConfig.host, liftConfig.port, this.pkgExtractor)
        } else {
            rebuild("Lift[${liftConfig.name}] initialize...")
            // aioTcpClient = ChargerAioTcp(liftConfig.name, liftConfig.host, liftConfig.port)
        }

        timer = GlobalTimer.executor.scheduleAtFixedRate(
            this::syncStatus,
            2000,
            CONFIG.liftStatusPollingPeriod,
            TimeUnit.MILLISECONDS
        )

        checkLiftAvailable()
    }

    private fun getAioTcpClient(): ChargerAioTcp {
        val client = aioTcpClient
        if (client != null) return client
        rebuild("Lift[${liftConfig.name}] get client failed!")
        throw BusinessError("client is null")
    }

    private fun rebuild(reason: String) {
        if (rebuilding) {
            logger.debug("Lift[${liftConfig.name}] has been rebuilding, so return.")
            return
        }
        rebuilding = true

        backgroundFixedExecutor.submit {
            try {
                // 重连之前，一定要先 close
                closeAioTcpClient()
                runBlocking {
                    var rebuildCount = 0
                    while (true) {
                        logger.info("${liftConfig.name} rebuilding tcp clients, reason: $reason, rebuildCount=$rebuildCount, rebuilding=$rebuilding")
                        rebuildCount++
                        try {
                            aioTcpClient = ChargerAioTcp(liftConfig.name, liftConfig.host, liftConfig.port, DeviceType.LIFT)  // 如果连接失败就会一直连接
                            return@runBlocking
                        } catch (e: IOException) {
                            logger.error("IOException while rebuilding clients for ${liftConfig.name}", e)
                            closeAioTcpClient()
                            delay(1000)
                        } catch (e: Exception) {
                            logger.error("Error rebuilding", e)
                            closeAioTcpClient()
                            delay(1000)
                        }
                    }
                }
            } catch (e: InterruptedException) {
                logger.error("Rebuilding for ${liftConfig.name}, but interrupted")
            } finally {
                rebuilding = false
            }
        }
    }

     override fun dispose() {
        tcpClient?.close()
        closeAioTcpClient()
        timer.cancel(true)
    }

    private fun closeAioTcpClient() {
        aioTcpClient?.close()
    }

    private fun checkLiftAvailable() {
        thread = thread(name = "check-lift-available[${liftConfig.name}]") {
            while (true) {
                try {
                    checkOffline()
                    checkControlTimedOut()
                } catch (e: Exception) {
                    logger.error("check-lift-available occurred error: $e")
                    throw e
                } finally {
                    Thread.sleep(1000)
                }
            }
        }
    }

    override fun checkOffline() {
        try {
            if (!model.online) {
                if (!rebuilding) rebuild("Lift[${liftConfig.name}] is offline!")
                return
            }
            // 判断电梯是否超时掉线, 连续5秒内未获取到电梯上报额状态，则判定电梯离线
            if (repCount.newCount - repCount.historyCount < 3L) {
                repCount.update()
            } else {
                // 系统会定时获取电梯状态，如果长时间没有更新电梯状态，就得报错（电梯已经离线）
                val duration = toPositive(Duration.between(onPkgTimestamp, Instant.now()).toSeconds())
                if (duration > model.reconnectTimeOut) {
                    logger.error("电梯超时掉线！")
                    liftChanged(model.copy(online = false))
                }
            }
        } catch (e: Exception) {
            logger.error("Lift[${liftConfig.name}] check offline failed because $e ")
        }
    }

    private fun checkControlTimedOut() {
        try {
            if (currRequestOn == null) {
                if (model.commandTimeout) liftChanged(model.copy(commandTimeout = false))
                return
            }
            // 3秒之内一直接收到呼叫电梯的指令，才会判断呼叫电梯是否超时
            if (lastRequestOn == null) lastRequestOn = currRequestOn
            else {
                val last = lastRequestOn!!
                lastRequestOn = currRequestOn!!
                if (isTimedOut(currRequestOn!!, last, 3).result) {
                    if (model.commandTimeout) liftChanged(model.copy(commandTimeout = false))
                    return
                }

                // 呼叫电梯的流程已经结束, 不用再检测“呼叫电梯是否超时”，并清空上一次呼叫电梯的时间戳
                if (lastRequestOn == currRequestOn && maxRequestCountReducing() == 0) {
                    currRequestOn = null
                    lastRequestOn = null
                    maxRequestCount = 3
                }
            }

            // 判断是否呼叫电梯超时
            val doorStatus = model.doorStatus
            if (!(model.currentFloor == model.destFloor && model.doorStatus == LiftDoorStatus.OPEN)) {
                // 电梯没有在目标楼层开门
                if (doorStatus == null) logger.error("还未获取到电梯上报的状态！")
                val timestamp = if (model.isOccupy) firstCall.timestamp() else firstGo.timestamp()
                val elapsed = toPositive(Duration.between(timestamp, Instant.now()).toSeconds())
                val timeout = model.timeoutForOpenOnDestFloor
                if (elapsed > timeout) {
                    logger.error("呼叫电梯超时（${elapsed}/${timeout}）")
                    liftChanged(model.copy(commandTimeout = true))
                }
            } else {
                if (!model.commandTimeout) liftChanged(model.copy(commandTimeout = true))
            }
        } catch (e: Exception) {
            logger.error("Lift[${liftConfig.name}] check control-lift-timedOut failed because $e ")
        }
    }

    override fun setOccupied(occupied: Boolean, remark: String) {
        logger.info("setOccupied $occupied lift=${liftConfig.name}. $remark")

        if (occupied) firstCall.resetFirst() // 占用电梯时，呼叫电梯（call）结束，不会再发送call指令，下一条call指令就是第一条call指令。
        else firstGo.resetFirst()            // 解除占用电梯时，呼叫电梯（go）结束，同上。

        model = model.copy(isOccupy = occupied)
        // 当电梯 occupy 和 unoccupy 时，都要关闭电梯门
        closeDoor("setOccupied")
    }

    override fun call(floor: String, remark: String) {
        currRequestOn = Instant.now()
        if (model.isOccupy) {
            logger.error("call but occupied, lift=${liftConfig.name}")
            throw BusinessError("CallButOccupied ${liftConfig.name}")
        }

        if (firstCall.first()) firstCall.notFirst()

        logger.info("Call to floor $floor lift=${liftConfig.name}. $remark")
        model = model.copy(destFloor = floor)

        val param = ReqParams(inCall = floor)
        controlLift(param)
    }

    override fun go(floor: String, remark: String) {
        currRequestOn = Instant.now()
        if (!model.isOccupy) {
            logger.error("go but not occupied, lift=${liftConfig.name}")
            throw BusinessError("GoButNotOccupied ${liftConfig.name}")
        }

        if (firstGo.first()) firstGo.notFirst()

        logger.info("go to floor $floor lift=${liftConfig.name}. $remark")
        model = model.copy(destFloor = floor)

        val param = ReqParams(inCall = floor)
        controlLift(param)
    }

    override fun openDoor(remark: String) {
        logger.info("open door lift=${liftConfig.name} $remark")

        val param = ReqParams(fOpenCtrl = "30")
        controlLift(param)
    }

    override fun closeDoor(remark: String) {
        logger.info("close door lift=${liftConfig.name} $remark")
        // 推荐使用松开开门按钮，让电梯自动关门，防止主动关门夹到其他乘客
        val param = ReqParams(fOpenCtrl = "0")
        controlLift(param)
    }

    @Synchronized
    private fun sendCommand(cmd1: Byte, cmd2: Byte, content: String, name: String) {
        when (liftConfig.mode) {
            IOMode.Tcp -> tcpClientWrite(cmd1, cmd2, content, name)
            IOMode.AioTcp -> aioTcpClientWriteAndRead(cmd1, cmd2, content, name)
            else -> {
                //
            }
        }
    }

    private fun tcpClientWrite(cmd1: Byte, cmd2: Byte, content: String, name: String) {
        val client = tcpClient
        if (client == null) {
            logger.debug("Lift[${liftConfig.name}] write failed! because tcpClient is null...")
        } else {
            if (client.isError()) return
            flowNo = (flowNo + 1) % 255
            logger.info("[flowNo: $flowNo] controlLift=$content.")
            val totalBuffer = buildRequestBuffer(cmd1, cmd2, content, flowNo.toByte(), name)
            client.write(totalBuffer.array())
        }
    }

    private fun aioTcpClientWriteAndRead(cmd1: Byte, cmd2: Byte, content: String, name: String) {
        if (rebuilding) {
            logger.error("Lift[${liftConfig.name}] write and read failed because (online=${model.online}, rebuilding=$rebuilding)...")
            return
        }
        val result = getAioTcpClient().writeAndReadBlocking(cmd1, cmd2, content, name)
        parseInfo(result)
    }

    /** 控制电梯执行指定操作 */
    @Synchronized
    private fun controlLift(param: ReqParams) {
        try {
            val content = mapper.writeValueAsString(param)
            sendCommand(16, 3, content, liftConfig.name)
        } catch (e: Exception) {
            logger.error("Lift[${liftConfig.name}] control lift failed, req=$param, $e")
            rebuild("Lift[${liftConfig.name}] send command failed!")
        }
    }

    @Synchronized
    override fun syncStatus() {
        try {
            logger.debug("thread[check-lift-available=${liftConfig.name}] state=${thread.state}")
            sendCommand(16, 4, "", liftConfig.name)
        } catch (e: Exception) {
            logger.error("Lift[${liftConfig.name}] request status failed", e)
            rebuild("Lift[${liftConfig.name}] sync status failed!")
        }
    }

    private fun parsePkgLen(buffer: ByteArrayBuffer): Int {
        if (buffer.validEndIndex - buffer.validStartIndex < pkgStartLiftWL.size + pkgLenWidth) return -1
        val byteBuffer = ByteBuffer.wrap(buffer.buffer, buffer.validStartIndex + pkgStartLiftWL.size, pkgLenWidth)
        byteBuffer.order(ByteOrder.BIG_ENDIAN)
        val len = byteBuffer.short.toInt() // 从 “传输方向” 到 “校验字” 的字节总和
        return len + pkgStartLiftWL.size + pkgLenWidth // 要包含头
    }

    private fun onPkg(buffer: ByteArrayBuffer, len: Int) {
        parseInfo(ByteBuffer.wrap(buffer.buffer))
    }

    //
    private fun parseInfo(buffer: ByteBuffer) {
        // 包头 - 3； 数据长度 - 2； 传输方向 - 1； 流水号 - 1； 命令字 - 2； 传输数据 - n； 校验字 - 1
        if (!model.online) liftChanged(model.copy(online = true))
        // 更新解包的时间
        onPkgTimestamp = Instant.now()
        repCount.newCount()

        // dataLen = 传输方向.size + 流水号.size + 命令字.size + 传输数据.size + 校验字.size
        val dataLen = buffer.getShort(3).toInt()
        val contentLen = getContentLenIfParse(dataLen, liftConfig.name)
        val content = String(buffer.array(), 9, contentLen, StandardCharsets.UTF_8)
        parseDetails(content, buffer.get(6).toInt())
    }

    private fun parseDetails(content: String, flowNo: Int) {
        val json = mapper.readTree(content)
        val msgCode = json["msgCode"].asInt()
        if (msgCode != 0) {
            logger.error("[Lift][${liftConfig.name}] Bad msg code=$msgCode, flowNo=$flowNo")
            return
        }

        val liftStatus = json["status"]?.get(0)
        if (liftStatus == null) {
            logger.debug("[flowNo: $flowNo] [Lift][${liftConfig.name}] No status.")
            return
        }

        val currentFloor = liftStatus["floor"].asText() ?: null

        val doorStatus = when (liftStatus["frontDoor"].asInt()) {
            -1 -> LiftDoorStatus.ERROR
            0 -> LiftDoorStatus.CLOSE
            1 -> LiftDoorStatus.OPENING
            2 -> LiftDoorStatus.CLOSING
            3 -> LiftDoorStatus.OPENING // “3”代表电梯门正在动作，但设备无法区分电梯是在开门还是在关门 DOOR_MOVING => OPENING
            4 -> LiftDoorStatus.OPEN
            else -> null
        }

        val moveStatus = when (liftStatus["upDown"].asInt()) {
            -1 -> LiftMoveStatus.Error
            0 -> LiftMoveStatus.Hold
            1 -> LiftMoveStatus.Down
            2 -> LiftMoveStatus.Up
            else -> null
        }

        // 状态信息中不一定包含 "emergency"
        val isEmergency = if (liftStatus.toString().contains("emergency", ignoreCase = true)) {
            when (liftStatus["emergency"].asInt()) {
                0 -> true
                else -> false
            }
        } else false

        if (model.currentFloor != currentFloor
            || model.doorStatus != doorStatus
            || model.moveStatus != moveStatus
            || model.isEmergency != isEmergency
        ) {
            val newModel = model.copy(
                currentFloor = currentFloor,
                doorStatus = doorStatus,
                moveStatus = moveStatus,
                isEmergency = isEmergency
            )
            logger.info("[flowNo: $flowNo] Lift changed to $newModel")
            model = newModel
        } else logger.info("[flowNo: $flowNo] Lift not changed.")
    }

    companion object {
        val pkgStartLiftWL = byteArrayOf(0x49, 0x54, 0x4C)
        const val pkgLenWidth = 2               // 表示用于存储数据长度所需的字节数
        private const val direction = 0         // 固定值0; 0: 当前数据为请求； 1: 当前数据为响应; SRD一直是请求的发起方。

        fun getContentLenIfParse(dataLen: Int, name: String): Int {
            // 包头 - 3； 数据长度 - 2； 传输方向 - 1； 流水号 - 1； 命令字 - 2； 传输数据 - n； 校验字 - 1
            // dataLen = 传输方向.size + 流水号.size + 命令字.size + 传输数据.size(contentLen) + 校验字.size
            val contentLen = dataLen - 4
            return if (contentLen >= 0) contentLen else throw BusinessError(
                "Lift[$name] get real data length failed, (contentLen=$contentLen, dataLen=$dataLen ")
        }

        fun getDataLenIfBuild(contentLen: Int, name: String): Int {
            val dataLen = contentLen + 5
            return if (dataLen >= 4) dataLen else throw BusinessError(
                "Lift[$name] get data length failed, (contentLen=$contentLen, dataLen=$dataLen ")
        }

        fun buildRequestBuffer(cmd1: Byte, cmd2: Byte, content: String, serialNumber: Byte, name: String): ByteBuffer {
            if (cmd1 < 0 || cmd1 > 255) throw SystemError("Bad cmd1 $cmd1")
            if (cmd2 < 0 || cmd2 > 255) throw SystemError("Bad cmd2 $cmd2")
            // 整个包的长度为 3 + 6 + 内容长度 + 1
            val contentBuffer = content.toByteArray(StandardCharsets.UTF_8)
            val reqPkgDataLength = getDataLenIfBuild(contentBuffer.size, name)

            val req = ByteArray(contentBuffer.size + 10)
            val reqBuffer = ByteBuffer.wrap(req)
            reqBuffer.order(ByteOrder.BIG_ENDIAN)
            reqBuffer.put(pkgStartLiftWL)
            reqBuffer.putShort(reqPkgDataLength.toShort())  // 数据长度 2byte
            reqBuffer.put(direction.toByte())               // 传输方向 1byte
            reqBuffer.put(serialNumber)                     // 流水号 1byte
            reqBuffer.put(cmd1)
            reqBuffer.put(cmd2)
            reqBuffer.put(contentBuffer)

            // 校验字 1byte
            val checksum = calcChecksum(req)
            reqBuffer.put(checksum)
            reqBuffer.flip()

            return reqBuffer
        }
    }

}

private fun calcChecksum(req: ByteArray): Byte {
    // 对从【数据长度】到【报文结束】的所有字节进行异或
    // 排除头3位，排除最后的校验位
    var result = req[3]
    for (i in 4..req.size - 2) {
        result = result xor req[i]
    }
    return result
}

data class ReqParams(
    val inCall: String? = null,
    val fCloseCtrl: String? = null,
    val fOpenCtrl: String? = null
)

data class RepCount(
    var historyCount: Long = 0,
    var newCount: Long = 0
) {
    fun newCount() {
        newCount++
    }

    fun update() {
        historyCount = newCount
    }
}