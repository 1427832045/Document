package com.seer.srd.nanruijibao

import com.seer.srd.BusinessError
import com.seer.srd.FailedLockStoreSite
import com.seer.srd.NoSuchStoreSite
import com.seer.srd.db.MongoDBManager
import com.seer.srd.nanruijibao.CustomUtil.stringToBoolean
import com.seer.srd.robottask.*
import com.seer.srd.robottask.component.TaskComponentDef
import com.seer.srd.robottask.component.TaskComponentParam
import com.seer.srd.robottask.component.parseComponentParamValue
import com.seer.srd.storesite.StoreSiteService
import org.litote.kmongo.eq
import org.litote.kmongo.find
import org.litote.kmongo.set
import org.litote.kmongo.setTo
import org.slf4j.LoggerFactory

object ComComponent {
    private val logger = LoggerFactory.getLogger(ComComponent::class.java)

    val comComponents: List<TaskComponentDef> = listOf(
        TaskComponentDef(
            "extra", "RecordMessageIntoLog", "将信息记录到日志中（仅适用于接口调用）", "",
            false, listOf(
            TaskComponentParam("message", "信息", "string")
        ), false) { component, ctx ->
            val message = parseComponentParamValue("message", component, ctx) as String

            var remoteAddr = "empty"
            try {
                remoteAddr = ctx.httpCtx!!.req.remoteAddr
            } catch (e: Exception) {
                // 记录IP不是业务流程的必须向，出错时不用抛异常。
                logger.error("get remote address occurred error: ${e.message}")
            }
            logger.info("record message from[$remoteAddr] into log, message=$message.")
        },

        TaskComponentDef(
            "extra", "Note", "备注", "",
            false, listOf(
            TaskComponentParam("message", "信息", "string")
        ), false) { _, _ ->
            // 此组件只是为了在柔性任务上显示必要的备注信息
        },

        TaskComponentDef(
            "extra", "ReplaceTaskWithAssignedTaskDef", "加载指定柔性任务的业务逻辑", "",
            false, listOf(
            TaskComponentParam("taskDefId", "目标柔性任务名称", "string")
        ), false) { component, ctx ->
            val taskDefId = parseComponentParamValue("taskDefId", component, ctx) as String
            val taskDef = getRobotTaskDef(taskDefId) ?: throw BusinessError("不存在任务定义【$taskDefId】!")
            val newTask = buildTaskInstanceByDef(taskDef)

            // buildTaskInstanceByDef() 只是构建了task.transports，因此只需要替换transports即可。
            ctx.task.transports = newTask.transports
        },

        TaskComponentDef(
            "extra", "TryToLockFilledSite", "锁定已占用的库位", "",
            false, listOf(
            TaskComponentParam("siteId", "库位ID", "string")
        ), false) { component, ctx ->
            val siteId = parseComponentParamValue("siteId", component, ctx) as String
            val site = StoreSiteService.getStoreSiteById(siteId) ?: throw BusinessError("不存在库位【$siteId】！")
            if (!site.filled) throw BusinessError("库位【$siteId】未被占用，请放置货物后操作相关按钮！")
            try {
                StoreSiteService.lockSiteIfNotLock(siteId, ctx.task.id, ctx.task.id)
            } catch (e: Exception) {
                if (e is NoSuchStoreSite) throw BusinessError("不存在库位【$siteId】！")
                if (e is FailedLockStoreSite) throw BusinessError("锁定库位【$siteId】失败！")
            }
        },

        TaskComponentDef(
            "extra", "TryToLockEmptySite", "锁定未占用的库位", "",
            false, listOf(
            TaskComponentParam("siteId", "库位ID", "string")
        ), false) { component, ctx ->
            val siteId = parseComponentParamValue("siteId", component, ctx) as String
            val site = StoreSiteService.getStoreSiteById(siteId) ?: throw BusinessError("不存在库位【$siteId】！")
            if (site.filled) throw BusinessError("库位【$siteId】已被占用，请领走货物后操作相关按钮！")
            try {
                StoreSiteService.lockSiteIfNotLock(siteId, ctx.task.id, ctx.task.id)
            } catch (e: Exception) {
                if (e is NoSuchStoreSite) throw BusinessError("不存在库位【$siteId】！")
                if (e is FailedLockStoreSite) throw BusinessError("锁定库位【$siteId】失败！")
            }
        },

        TaskComponentDef(
            "extra", "ApplyForVehicle", "申请可用的AGV", "",
            false, listOf(
        ), false) { _, ctx ->
            val task = ctx.task
            val vehicleName = ApplyForVehicleService.taskApplyForVehicle(ctx.task)
            task.transports.forEach { it.intendedRobot = vehicleName.name }

            ctx.task.persistedVariables["loading"] = vehicleName.loading
            logger.debug("pvs=${ctx.task.persistedVariables}")
            // 记录到数据库中，防止重启之后数据丢失
            MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq task.id, set(
                RobotTask::persistedVariables setTo ctx.task.persistedVariables
            ))
        },

        TaskComponentDef(
            "extra", "CustomSkipTransport", "跳过不必要的运单", "",
            false, listOf(
        ), false) { _, ctx ->
            val task = ctx.task
            val pvs = task.persistedVariables
            val loadDevice = stringToBoolean(pvs["loadDevice"].toString())
            val loading = stringToBoolean(pvs["loading"].toString())
            val returnName = "skipCheck"
            if ((loadDevice && loading) || (!loadDevice && !loading)) { // 需要工装的任务分配到了有工装的车
                RobotTaskService.updateTransportStateToFinal(RobotTransportState.Skipped, "获取到符合要求的AGV",
                    task.transports[1], task)
                RobotTaskService.updateTransportStateToFinal(RobotTransportState.Skipped, "获取到符合要求的AGV",
                    task.transports[2], task)
                ctx.task.persistedVariables[returnName] = true
            } else if (loadDevice && !loading) {
                RobotTaskService.updateTransportStateToFinal(RobotTransportState.Skipped, "AGV需要去安装工装",
                    task.transports[2], task)
                ctx.task.persistedVariables[returnName] = false
            } else { //(loadDevice && !loading)
                RobotTaskService.updateTransportStateToFinal(RobotTransportState.Skipped, "AGV需要去拆卸工装",
                    task.transports[1], task)
                ctx.task.persistedVariables[returnName] = false
            }

            logger.debug("pvs=${ctx.task.persistedVariables}")
            // 记录到数据库中，防止重启之后数据丢失
            MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq task.id, set(
                RobotTask::persistedVariables setTo ctx.task.persistedVariables
            ))
        },

        TaskComponentDef(
            "extra", "CheckLoadOrUnloadDeviceFinished", "检查安装/拆卸工装是否结束", "",
            false, listOf(
            TaskComponentParam("load", "true:检查安装；false:检查拆卸", "string")
        ), false) { component, ctx ->
            if (stringToBoolean(ctx.task.persistedVariables["skipCheck"].toString())) {
                logger.debug("task=${ctx.task.id}已获取到期望的机器人，不需要再检查工装操作是否完成。")
                return@TaskComponentDef
            }

            val loadStr = parseComponentParamValue("load", component, ctx) as String
            val load = when (loadStr) {
                "true" -> true
                "false" -> false
                else -> throw BusinessError("无效参数【$loadStr】，无法确定检查安装完成或者拆卸完成！")
            }

            val siteId = if (load) "Load-Finished" else "Unload-Finished"
            val txt = if (load) "安装" else "拆卸"
            val site = StoreSiteService.getStoreSiteById(siteId)
                ?: throw BusinessError("请添加库位【$siteId】，以记录${txt}工装是否完成！")
            if (!site.filled) throw BusinessError("${txt}工装中，请等待。。。（操作完成之后请点击【安装\\拆卸工装完成】）")

        },

        TaskComponentDef(
            "extra", "SetVehicleFreeIfTaskFinished", "所有调度运单结束后，释放被指派给当前任务的AGV", "",
            false, listOf(
        ), false) { _, ctx ->
            ApplyForVehicleService.updateAssignedVehiclesIfTaskFinished(ctx.task.id)
        },

        TaskComponentDef(
            "extra", "CheckVehicleNotLoadExtDevice", "检查AGV未安装工装", "",
            false, listOf(
            TaskComponentParam("vehicleName", "AGV名称", "string")
        ), false) { component, ctx ->
            val vehicleName = parseComponentParamValue("vehicleName", component, ctx) as String

            if (ApplyForVehicleService.checkVehicleLoadExtDevice(vehicleName))
                throw BusinessError("AGV【$vehicleName】已安装工装！")
        },

        TaskComponentDef(
            "extra", "CheckVehicleLoadExtDevice", "检查AGV已安装工装", "",
            false, listOf(
            TaskComponentParam("vehicleName", "AGV名称", "string")
        ), false) { component, ctx ->
            val vehicleName = parseComponentParamValue("vehicleName", component, ctx) as String

            if (!ApplyForVehicleService.checkVehicleLoadExtDevice(vehicleName))
                throw BusinessError("AGV【$vehicleName】未安装工装！")
        },

        TaskComponentDef(
            "extra", "TryToLockSiteOnlyIfNotLock", "尝试锁定未锁定的库位", "",
            false, listOf(
            TaskComponentParam("siteId", "库位ID", "string")
        ), false) { component, ctx ->
            val siteId = parseComponentParamValue("siteId", component, ctx) as String
            try {
                StoreSiteService.lockSiteIfNotLock(siteId, ctx.task.id, "From task ${ctx.taskDef?.name}")
            } catch (e: Exception) {
                if (e is NoSuchStoreSite) throw BusinessError("不存在库位【$siteId】!")
                throw BusinessError("锁定库位【$siteId】失败!")
            }
        },

        TaskComponentDef(
            "extra", "TryToUnlockSiteIfLocked", "尝试释放已锁定的库位", "",
            false, listOf(
            TaskComponentParam("siteId", "库位ID", "string")
        ), false) { component, ctx ->
            val siteId = parseComponentParamValue("siteId", component, ctx) as String
            try {
                StoreSiteService.unlockSiteIfLocked(siteId, "From task ${ctx.taskDef?.name}")
            } catch (e: Exception) {
                if (e is NoSuchStoreSite) throw BusinessError("不存在库位【$siteId】!")
                throw BusinessError("解锁库位【$siteId】失败!")
            }
        },

        TaskComponentDef(
            "extra", "ExistSameTaskOfOperateExtraDevice", "检测是否存在相同的操作工装的任务", "",
            false, listOf(
        ), false) { _, ctx ->
            val def = ctx.taskDef!!.name
            val vehicle = ctx.task.transports[0].intendedRobot
            val tasks = MongoDBManager.collection<RobotTask>()
                .find(RobotTask::state eq RobotTaskState.Created, RobotTask::def eq def).toList()
            tasks.forEach { task ->
                task.transports.forEach {
                    if (it.intendedRobot == vehicle)
                        throw BusinessError("机器人【$vehicle】有未完成的【${ctx.taskDef!!.description}】任务=${task.id}，请勿重复下发！")
                }
            }
        }
    )
}