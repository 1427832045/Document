package com.seer.srd.siemensSH.common

import com.seer.srd.BusinessError
import com.seer.srd.robottask.*
import com.seer.srd.robottask.component.TaskComponentDef
import com.seer.srd.robottask.component.TaskComponentParam
import com.seer.srd.robottask.component.parseComponentParamValue
import com.seer.srd.scheduler.backgroundCacheExecutor
import com.seer.srd.siemensSH.common.ComUtils.checkSiteFilled
import com.seer.srd.siemensSH.common.ComUtils.checkSiteFilledWithContent
import com.seer.srd.siemensSH.common.ComUtils.existReturnEmptyTrayToStorageTaskOfSameFromSite
import com.seer.srd.siemensSH.common.ComUtils.getUnfinishedTasksOrNullByTaskDef
import com.seer.srd.siemensSH.common.ComUtils.processTasksByCreatedOn
import com.seer.srd.siemensSH.common.ComUtils.tryToLockEmptyAndUnlockedSiteOfType
import com.seer.srd.siemensSH.common.ComUtils.tryToLockFilledAndUnlockedSiteOfType
import com.seer.srd.siemensSH.common.ComUtils.tryToUnLockSite
import com.seer.srd.siemensSH.common.ComUtils.updatePersistedVariablesIntoDB
import com.seer.srd.storesite.StoreSiteService
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
            "extra", "TryToUnLockSite", "尝试释放已锁定的库位", "",
            false, listOf(
            TaskComponentParam("siteId", "库位ID", "string")
        ), false) { component, ctx ->
            val siteId = parseComponentParamValue("siteId", component, ctx) as String
            logger.info("try to unlock site=$siteId")
            tryToUnLockSite(siteId)
        },

        TaskComponentDef(
            "extra", "checkSiteFilled", "检查库位被占用", "",
            false, listOf(
            TaskComponentParam("siteId", "库位ID", "string")
        ), true) { component, ctx ->
            val siteId = parseComponentParamValue("siteId", component, ctx) as String
            logger.info("check site=$siteId filled.")
            val filled = checkSiteFilled(siteId)
            ctx.setRuntimeVariable(component.returnName, filled)
        },

        TaskComponentDef(
            "extra", "checkSiteFilledAndThrowErrorIfFilled", "检查库位被占用，被占用时报错", "",
            false, listOf(
            TaskComponentParam("siteId", "库位ID", "string"),
            TaskComponentParam("errMsg", "警告提示(选填)", "string")
        ), false) { component, ctx ->
            val siteId = parseComponentParamValue("siteId", component, ctx) as String
            val errMsg = parseComponentParamValue("errMsg", component, ctx) as String
            logger.info("check site=$siteId filled.")
            if (checkSiteFilled(siteId))
                throw BusinessError(if (errMsg.isBlank()) "库位【$siteId】上有货物！" else errMsg)
        },

        TaskComponentDef(
            "extra", "TryToLockEmptyAndUnlockedSiteOfType", "尝试锁定指定类型的未被占用的库位", "",
            false, listOf(
            TaskComponentParam("type", "库位类型", "string")
        ), true) { component, ctx ->
            val type = parseComponentParamValue("type", component, ctx) as String
            logger.info("try to lock empty and unlocked site=$type")
            val site = tryToLockEmptyAndUnlockedSiteOfType(ctx.task.id, type)

            val returnName = component.returnName ?: "site"
            val task = ctx.task
            val pv = task.persistedVariables
            pv[returnName] = site

            updatePersistedVariablesIntoDB(task.id, pv)
        },

        TaskComponentDef(
            "extra", "TryToLockFilledAndUnlockedSiteOfType", "尝试锁定指定类型的被占用的库位", "",
            false, listOf(
            TaskComponentParam("type", "库位类型", "string")
        ), true) { component, ctx ->
            val type = parseComponentParamValue("type", component, ctx) as String
            logger.info("try to lock empty and unlocked site=$type")
            val site = tryToLockFilledAndUnlockedSiteOfType(ctx.task.id, type)

            val returnName = component.returnName ?: "site"
            ctx.task.persistedVariables[returnName] = site
        },

        TaskComponentDef(
            "extra", "PersistCode", "持久化货物编码", "",
            false, listOf(
            TaskComponentParam("code", "货物编码", "string")
        ), true) { component, ctx ->
            val code = parseComponentParamValue("code", component, ctx) as String
            logger.info("persist code=$code")

            val returnName = component.returnName ?: "code"
            ctx.task.persistedVariables[returnName] = code
        },

        TaskComponentDef(
            "extra", "ExistTasksOfSameDef", "是否存在未完成的指定类型的任务",
            "在设置任务变量 fromSiteId 之后再调用！",
            false, listOf(
            TaskComponentParam("def", "柔性任务标识", "string")
        ), false) { component, ctx ->
            val def = parseComponentParamValue("def", component, ctx) as String

            val taskDef = getRobotTaskDef(def)
                ?: throw BusinessError("不存在柔性任务【$def】,请联系开发人员！")

            val fromSiteId = (ctx.task.persistedVariables["fromSiteId"]
                ?: throw BusinessError("此任务还未记录起点信息，请检查柔性任务！")) as String

            if (def == DEF_RETURN_EMPTY_TRAY_BACK_TO_STORAGE)
                existReturnEmptyTrayToStorageTaskOfSameFromSite(fromSiteId)
            else {
                if (getUnfinishedTasksOrNullByTaskDef(def) != null) {
                    val prefix = if (fromSiteId.isNotEmpty()) "起点是【$fromSiteId】的" else ""
                    throw BusinessError("${prefix}任务【${taskDef.description}】还未结束，请勿重复下单!")
                }
            }
        },

        TaskComponentDef(
            "extra", "ProcessTasksByCreatedOn", "按照创建时间执行任务", "",
            false, listOf(
        ), false) { _, ctx ->
            processTasksByCreatedOn(ctx.task)
        },

        TaskComponentDef(
            "extra", "SkipTransportsIfFromSiteNotFilledWithContent", "起点库位未被货物占用时跳过指定运单1", "",
            false, listOf(
            TaskComponentParam("fromSiteId", "起点库位ID", "string"),
            TaskComponentParam("toSiteId", "终点库位ID", "string"),
            TaskComponentParam("transportIndexes", "要被撤销的运单索引", "string"),
            TaskComponentParam("markFailed", "标记任务为失败", "string"),
            TaskComponentParam("content", "货物（选填）", "string")
        ), false)
        { component, ctx ->
            val fromSiteId = parseComponentParamValue("fromSiteId", component, ctx) as String
            val toSiteId = parseComponentParamValue("toSiteId", component, ctx) as String
            val content = parseComponentParamValue("content", component, ctx) as String
            val transportIndexes = parseComponentParamValue("transportIndexes", component, ctx) as String
            val markFailed = (parseComponentParamValue("markFailed", component, ctx) as String) == "true"

            try {
                checkSiteFilledWithContent(fromSiteId, content)
            } catch (e: Exception) {
                val task = ctx.task
                val taskId = task.id

                transportIndexes.split(",").map { it.trim().toInt() }.forEach {
                    val reason = e.message ?: e.toString()
                    val transport = task.transports[it]
                    RobotTaskService.updateTransportStateToFinal(RobotTransportState.Skipped, reason, transport, task)
                }

                // 解锁被锁定的库位
                try {
                    StoreSiteService.unlockSiteIfLocked(fromSiteId, "skip task=${taskId} by component")
                } catch (e: Exception) {
                    logger.error(e.toString())
                }
                try {
                    StoreSiteService.unlockSiteIfLocked(toSiteId, "skip task=${taskId} by component")
                } catch (e: Exception) {
                    logger.error(e.toString())
                }

                if (markFailed) backgroundCacheExecutor.submit {
                    Thread.sleep(3000)
                    RobotTaskService.markTaskFailed(task)
                }
            }
        }
    )
}