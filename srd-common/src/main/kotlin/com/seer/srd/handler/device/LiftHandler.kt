package com.seer.srd.handler.device

import com.seer.srd.Error400
import com.seer.srd.device.lift.*
import io.javalin.http.Context
import org.slf4j.LoggerFactory

object LiftHandler {

    private val logger = LoggerFactory.getLogger(LiftService::class.java)

    fun handleControlLift(ctx: Context) {
        val lift = ctx.pathParam("name")
        // go 的优先级高于 call。电梯在 isOccupy == true 时，将不响应 call 指令
        val manager = LiftService.managers[lift] ?: throw Error400("NoSuchLift", "No such lift $lift")
    
        when (val action = ctx.queryParam("action")) {
            "enable", "disable" ->
                logger.warn("No support to enable/disable lift")
            "call" ->
                manager.call(ctx.queryParam("floor") ?: throw Error400("MissingFloor", "Missing Floor"), "from api")
            "go" ->
                manager.go(ctx.queryParam("floor") ?: throw Error400("MissingFloor", "Missing Floor"), "from api")
            "occupy" ->
                // 设置电梯装维为占用，并且关上电梯门。
                manager.setOccupied(true, "from api")
            "unoccupy" ->
                // 解除电梯的占用状态，并且关上电梯门。
                manager.setOccupied(false, "from api")
            else ->
                throw Error400("UnsupportedAction", "Unsupported action: $action")

        }
        ctx.header("Access-Control-Allow-Origin", "*")
        ctx.status(200)
    }

    /** 获取所有电梯状态 */
    fun handleListLiftsModels(ctx: Context) {
        val models = LiftService.listLiftsModels()
        ctx.header("Access-Control-Allow-Origin", "*")
        ctx.json(models)
    }

    /** 获取指定的电梯状态 */
    fun handleGetLiftModel(ctx: Context) {
        val lift = ctx.pathParam("name")
        val manager = LiftService.managers[lift] ?: throw Error400("NoSuchLift", "No such lift $lift")
        val model = manager.getLiftModel()
        ctx.header("Access-Control-Allow-Origin", "*")

        // 成都西门子项目定制协议中，通过“机器人是否可以进入电梯”和“机器人是否可以离开电梯”两个字段，来控制机器人进出电梯；
        // 这跟原来的逻辑有冲突，原来的逻辑：
        //     - route 检测到电梯在目标楼层开门之后，就会自由进出电梯。
        var status = model.doorStatus
        if (manager.liftConfig.mode == IOMode.ModbusTcpSiemensCd) {
            val manager1 = manager as LiftManagerModbusTcpCustom
            val occupied = model.isOccupy
            val toOpen = (!occupied && manager1.enterPermitted) || (occupied && manager1.leavePermitted)
            if (!toOpen) status = LiftDoorStatus.CLOSE
        } else if (manager.liftConfig.mode == IOMode.ModbusTcp) {
            // 使用非定制的ModbusTcp梯控协议时，如果电梯状态异常，AGV就不能进、出电梯。
            val hasError = manager.hasError()
            if (hasError.result) {
                logger.error("Lift[$lift] has error: {}", hasError.msg)
                status = LiftDoorStatus.ERROR
            }
        } else if (manager.liftConfig.mode == IOMode.ModbusTcpSiemensCdV2) {
            val manager1 = manager as LiftManagerModbusTcpCustomV2
            val occupied = model.isOccupy
            val toOpen = (!occupied && manager1.enterPermitted) || (occupied && manager1.leavePermitted)
            if (!toOpen) status = LiftDoorStatus.CLOSE
        }

        ctx.json(
            mapOf(
                "name" to model.name,
                "currentFloor" to model.currentFloor,
                "isOccupy" to model.isOccupy,
                "status" to status // todo case changed
            )
        )
    }

}