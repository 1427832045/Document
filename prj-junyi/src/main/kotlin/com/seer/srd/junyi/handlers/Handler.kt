package com.seer.srd.junyi.handlers

import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.junyi.CommonUtil.getPassword
import com.seer.srd.junyi.CommonUtil.processFinished
import com.seer.srd.junyi.CommonUtil.submit
import org.slf4j.LoggerFactory
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.RobotTaskState
import com.seer.srd.storesite.StoreSiteService
import io.javalin.http.Context
import org.litote.kmongo.*

object ExtHandlers {
    private val logger = LoggerFactory.getLogger(ExtHandlers::class.java)

    fun checkPassword(ctx: Context) {
        val remoteAddr = ctx.req.remoteAddr
        val oldWt = ctx.queryParam("oldWt")
        val oldWs = ctx.queryParam("oldWs")
        val newWt = ctx.queryParam("newWt")
        val newWs = ctx.queryParam("newWs")
        val change = "from (wt=$oldWt, ws=$oldWs) to (wt=$newWt, ws=$newWs)"

        when (ctx.queryParam("pwd")?.trim()) {
            getPassword() -> logger.info("[$remoteAddr]切换岗位和工位[成功]，$change.")
            null -> {
                logger.error("[$remoteAddr]切换岗位和工位[失败]，$change，确认密码不能为空，请重新输入！！！")
                throw BusinessError("确认密码不能为空，请重新输入！！！")
            }
            else -> {
                logger.error("[$remoteAddr]切换岗位和工位[失败]，$change，密码错误，请确认之后再输入！！！")
                throw BusinessError("密码错误，请确认之后再输入！！！")
            }
        }
        ctx.status(201)
    }

    // 跟“下壳体上线 - 放行”的逻辑一样，需要修改个别参数
    fun p030Finished(ctx: Context) {
        logger.info("P030 - 放行")
        try {
            // 呼叫任务未完成前，报错
            val task = MongoDBManager.collection<RobotTask>()
                .findOne(
                    RobotTask::def `in` listOf("TaskDefP020ToP030", "TaskDefP030ToTP030", "TaskDefTP030ToP030"),
                    RobotTask::state eq RobotTaskState.Created
                )

            if (task != null) {
                throw BusinessError("【P030】任务还未完成【${task.id}】is state=[${task.state}]].")
            }

            val signalId = "VIR-P030"
            val site = StoreSiteService.getExistedStoreSiteById(signalId)
            if (site.content.isBlank())
                throw BusinessError("没有未放行的任务，请在【呼叫】之后再【放行】！")

            // 调用接口，告知 MES 【下壳体上线】已经放行
            submit(SignalTypes.P030, "【P030放行】")

            ctx.status(201)

        } catch (e: Exception) {
            throw BusinessError("【P030放行】操作失败 - $e")
        }
    }

    fun p010Finished(ctx: Context) {
        ctx.status(processFinished("P010"))
    }

    fun p020Finished(ctx: Context) {
        ctx.status(processFinished("P020"))
    }

    fun p090Finished(ctx: Context) {
        ctx.status(processFinished("P090"))
    }

    fun p100Finished(ctx: Context) {
        ctx.status(processFinished("P100"))
    }

    fun p130Finished(ctx: Context) {
        ctx.status(processFinished("P130"))
    }

    // 模组入壳 - 放行
    fun loadModuleFinished(ctx: Context) {
        ctx.status(processFinished("模组入壳"))
    }

    // 模组固定 - 放行
    fun fixModuleFinished(ctx: Context) {
        ctx.status(processFinished("模组固定"))
    }

    // 高压铜线安装 - 放行
    fun loadHighVolCableFinished(ctx: Context) {
        ctx.status(processFinished("高压铜线安装"))
    }

    // 水冷板检测 - 放行
    fun boardTestFinished(ctx: Context) {
        ctx.status(processFinished("水冷板检测"))
    }

    // 线束安装 - 放行
    fun loadWireFinished(ctx: Context) {
        ctx.status(processFinished("线束安装"))
    }

    // 上壳体安装 - 放行 PAD -> SRD-K -> MES
    fun loadTopShellFinished(ctx: Context) {
        logger.info("上壳体安装 - 放行.")
        try {
            // 呼叫任务未完成前，报错
            val task = MongoDBManager.collection<RobotTask>()
                .findOne(RobotTask::def eq "TaskDefLoadTopShell", RobotTask::state eq RobotTaskState.Created)

            if (task != null) {
                throw BusinessError("【上壳体安装】任务未完成【${task.id}】is state=[${task.state}]].")
            }

            val signalId = "VIR-LoadTopShell"
            val site = StoreSiteService.getExistedStoreSiteById(signalId)
            if (site.content.isBlank())
                throw BusinessError("没有未放行的任务，请在【呼叫】之后再【放行】！")

            // 调用接口，告知 MES 【上壳体安装】已经放行
            submit(SignalTypes.LoadTopShell, "【上壳体安装放行】")

            ctx.status(201)

        } catch (e: Exception) {
            throw BusinessError("【放行】操作失败 - $e")
        }
    }

    // 螺丝紧固 - 放行
    fun fixScrewFinished(ctx: Context) {
        ctx.status(processFinished("螺丝紧固"))
    }

    // 气密性检测 - 放行
    fun airTightnessTestFinished(ctx: Context) {
        ctx.status(processFinished("气密性检测"))
    }

    fun agvDoneCharge(ctx: Context) {
        logger.info("检测是否已经生成【AGV充电】任务。")
        val site = StoreSiteService.getExistedStoreSiteById("VIR-ToCharge")
        ctx.json("${!site.locked}")
    }

    // MES mock
    fun loadBottomShellFinishedInnerCall(ctx: Context) {
        logger.info("[mock-api] - load bottom shell finished.")
        ctx.status(201)
    }

    fun vehicleAtLoadModulePosInnerCall(ctx: Context) {
        logger.info("[mock-api] - vehicle at load module pos.")
        ctx.status(201)
    }

    fun fixModuleAlreadyCalledInnerCall(ctx: Context) {
        logger.info("[mock-api] - fix module already called.")
        ctx.status(201)
    }

    fun vehicleAtLoadModuleNextPosInnerCall(ctx: Context) {
        logger.info("[mock-api] - vehicle at fix module pos.")
        ctx.status(201)
    }

    fun loadTopShellFinishedInnerCall(ctx: Context) {
        logger.info("[mock-api] - load top shell finished.")
        ctx.status(201)
    }

    fun vehicleAtFixScrewPosInnerCall(ctx: Context) {
        logger.info("[mock-api] - vehicle at fix screw pos.")
        ctx.status(201)
    }

    fun airTightnessTestAlreadyCalledInnerCall(ctx: Context) {
        logger.info("[mock-api] - air tightness test already called.")
        ctx.status(201)
    }

    fun vehicleAtFixScrewNextPosInnerCall(ctx: Context) {
        logger.info("[mock-api] - vehicle at air tightness test pos.")
        ctx.status(201)
    }
}

data class OperatorParams(
    val menuId: String = "",
    val workType: String = "",
    val workStation: String = "",
    val params: Params = Params()
)

data class Params(
    val enable: Boolean? = null
)

enum class SignalTypes {
    P030,
    // LoadBottomShell,
    LoadTopShell,
    VehicleAtLoadModulePos,
    VehicleAtLoadModuleNextPos,
    VehicleAtFixScrewPos,
    VehicleAtFixScrewNextPos,
    FixModuleAlreadyCalled,
    AirTightnessAlreadyCalled
}