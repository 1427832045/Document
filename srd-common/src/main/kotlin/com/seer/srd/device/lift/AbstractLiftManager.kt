package com.seer.srd.device.lift

import com.seer.srd.device.charger.ResultAndMsg
import com.seer.srd.eventbus.EventBus.onLiftChanged
import org.slf4j.LoggerFactory
import java.time.Instant

abstract class AbstractLiftManager(val liftConfig: LiftConfig) {
    val logger = LoggerFactory.getLogger(AbstractLiftManager::class.java)

    fun logInfo(message: String) {
        logger.info("Lift[${liftConfig.name}] $message")
    }

    fun logDebug(message: String) {
        logger.debug("Lift[${liftConfig.name}] $message")
    }

    fun logError(message: String, e: Exception? = null) {
        if (e == null) logger.error("Lift[${liftConfig.name}] $message")
        else logger.error("Lift[${liftConfig.name}] $message", e)
    }

    @Volatile
    var model: LiftModel = LiftModel(liftConfig.name, config = liftConfig)

    fun liftChanged(newModel: LiftModel) {
        model = newModel
        onLiftChanged(model)
    }

    fun getLiftModel(): LiftModel {
        return model
    }

    fun hasError(): ResultAndMsg {
        val message =
            if (model.isEmergency) "is emergency, " else "" +
            if (model.doorStatus == LiftDoorStatus.ERROR) "its door status is error, " else "" +
            // 电梯的上下行状态异常，其实对于AGV来说，并不重要
            if (model.moveStatus == LiftMoveStatus.Error) "its move status is error" else ""
        val msg = if (message.isNotBlank()) "charger cannot charge: $message" else null

        val result = model.isEmergency
            || model.doorStatus == LiftDoorStatus.ERROR
            // || model.moveStatus == LiftMoveStatus.Error

        return ResultAndMsg(result, msg)
    }

    abstract fun dispose()

    abstract fun setOccupied(occupied: Boolean, remark: String)

    abstract fun call(floor: String, remark: String)

    abstract fun go(floor: String, remark: String)

    abstract fun openDoor(remark: String)

    abstract fun closeDoor(remark: String)

    abstract fun checkOffline()

    abstract fun syncStatus()
}

class LiftConfig(
    var name: String = "",
    var host: String = "",
    var port: Int = 0,
    var mode: IOMode = IOMode.Tcp,
    // 弃用此配置(standAlone)
    var standAlone: Boolean = false,            // true:单机的，不需要连接指定的服务方； false: 需要连接指定的服务方
    var modbusConfig: Any? = null
)

data class LiftModel(
    val name: String,
    val currentFloor: String? = null,
    val destFloor: String? = null,
    val isOccupy: Boolean = false,
    val isEmergency: Boolean = false,
    val moveStatus: LiftMoveStatus? = null,
    // 5分钟没有获取到电梯反馈的状态，则判定电梯因为连接超时而掉线。
    val reconnectTimeOut: Int = 5,
    val online: Boolean = false,
    // 呼叫电梯之后，如果一段时间内（默认值为2分钟），电梯没有在目标楼层开门，则报“呼叫电梯超时”的错误。
    val timeoutForOpenOnDestFloor: Int = 1800,  // 单位：秒
    val commandTimeout: Boolean = false,        // 呼叫电梯超时
    val doorStatus: LiftDoorStatus? = null,
    val config: LiftConfig? = null
)

enum class LiftDoorStatus {
    OPEN, OPENING, CLOSE, CLOSING, ERROR
}

enum class LiftMoveStatus {
    Error, Hold, Down, Up
}

data class FirstCmd(
    private var first: Boolean = true,
    private var timestamp: Instant = Instant.now()
) {
    fun first(): Boolean {
        return first
    }

    fun timestamp(): Instant {
        return timestamp
    }

    fun notFirst() {
        if (first) return
        first = false
        timestamp = Instant.now()
    }

    fun resetFirst() {
        if (!first) return
        first = true
        timestamp = Instant.now()
    }
}

enum class IOMode {
    Tcp,
    AioTcp,
    ModbusTcp,
    ModbusTcpSiemensCd,
    ModbusTcpSiemensCdV2
}
