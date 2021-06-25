package com.seer.srd.siemensSH.phase2

import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.robottask.*
import com.seer.srd.robottask.component.TaskComponentDef
import com.seer.srd.robottask.component.TaskComponentParam
import com.seer.srd.robottask.component.parseComponentParamValue
import com.seer.srd.siemensSH.common.ComUtils.getSiteTypeByMenuId
import com.seer.srd.siemensSH.common.TRY_NEXT_MARK
import com.seer.srd.siemensSH.phase2.Phase2Utils.appendRecognizeProperty
import com.seer.srd.siemensSH.phase2.Phase2Utils.createTaskOfReturnEmptyTrayToStorage
import com.seer.srd.siemensSH.phase2.Phase2Utils.forkLoadRecognizeOrNot
import com.seer.srd.siemensSH.phase2.Phase2Utils.tryToExecuteReturnEmptyTrayToStorage
import com.seer.srd.siemensSH.phase2.Phase2Utils.tryToLockSiteIfNotLocked
import com.seer.srd.storesite.StoreSiteService
import com.seer.srd.util.mapper
import org.litote.kmongo.eq
import org.litote.kmongo.findOne
import org.litote.kmongo.set
import org.litote.kmongo.setTo
import org.slf4j.LoggerFactory
import java.lang.Error

object Phase2Component {
    private val logger = LoggerFactory.getLogger(Phase2Component::class.java)

    val phase2Components: List<TaskComponentDef> = listOf(
        TaskComponentDef(
            "extra", "TryToLockSiteIfNotLocked", "尝试锁定未锁定的库位", "",
            false, listOf(
            TaskComponentParam("siteId", "库位ID", "string"),
            TaskComponentParam("extMsg", "附加提示", "string")
        ), false) { component, ctx ->
            val siteId = parseComponentParamValue("siteId", component, ctx) as String
            val extMsg = parseComponentParamValue("extMsg", component, ctx) as String
            logger.info("try to lock site=$siteId if unlocked")
            try {
                tryToLockSiteIfNotLocked(ctx.task.id, siteId)
            }catch (e: Exception) {
                throw BusinessError("${TRY_NEXT_MARK}${e.message}，$extMsg")
            }
        },

        TaskComponentDef(
            "extra", "GetSiteTypeByMenuId", "根据 MenuId 获取库位信息", "",
            false, listOf(
            TaskComponentParam("menuId", "menuId", "string")
        ), true) { component, ctx ->
            val menuId = parseComponentParamValue("menuId", component, ctx) as String
            logger.info("get site type by menuId=$menuId")
            val type = getSiteTypeByMenuId(menuId)
            val returnName = component.returnName ?: "type"
            ctx.task.persistedVariables[returnName] = type
        },

        TaskComponentDef(
            "extra", "GetSiteTypeByPS", "根据二期产线名称获取库位类型", "",
            false, listOf(
            TaskComponentParam("ps", "产线名称", "string")
        ), true) { component, ctx ->
            val ps = parseComponentParamValue("ps", component, ctx) as String
            logger.info("get site by PS=$ps")
            val type = if (ps == "PS1") "M4-A" else "M4-B"

            val returnName = component.returnName ?: "type"
            ctx.task.persistedVariables[returnName] = type
        },

        TaskComponentDef(
            "extra", "persistSiteContent", "持久化库位上的货物信息", "",
            false, listOf(
            TaskComponentParam("siteId", "库位ID", "string")
        ), true) { component, ctx ->
            val siteId = parseComponentParamValue("siteId", component, ctx) as String
            logger.info("persist site content, siteId=$siteId")

            val site = StoreSiteService.getStoreSiteById(siteId)
                ?: throw BusinessError("不存在库位【$siteId】！")

            val returnName = component.returnName ?: "content"
            ctx.task.persistedVariables[returnName] = site.content
            MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq ctx.task.id, set(
                RobotTask::persistedVariables setTo ctx.task.persistedVariables
            ))
        },

        TaskComponentDef(
            "extra", "forkLoadRecognizeOrNot", "根据库位ID判断在此库位叉货是否需要识别(下发运单前调用)", "",
            false, listOf(
            TaskComponentParam("siteId", "库位ID", "string"),
            TaskComponentParam("transportIndex", "目标运单索引", "int"),
            TaskComponentParam("stageIndex", "目标stage在其运单中的索引", "int")
        ), false) { component, ctx ->
            val siteId = parseComponentParamValue("siteId", component, ctx) as String
            val stageIndex = parseComponentParamValue("stageIndex", component, ctx) as Int
            val transportIndex = parseComponentParamValue("transportIndex", component, ctx) as Int
            logger.info("persist site content, siteId=$siteId")

            if (!forkLoadRecognizeOrNot(siteId)) {
                logger.debug("机器人在[$siteId]上执行ForkLoad时，不需要进行识别.")
                return@TaskComponentDef
            }
            logger.debug("机器人在[$siteId]上执行ForkLoad时，需要进行识别.")

            val transports = ctx.task.transports
            if (transports.size < transportIndex) throw BusinessError("运单的索引越界！")

            val stages = ctx.task.transports[transportIndex].stages
            if (stages.size < stageIndex) throw BusinessError("阶段的索引越界！")

            val stage = stages[stageIndex]

            // [{"key":"end_height","value":"0.15"}]
            val info = "task=${ctx.task.id} transportIndex=$transportIndex stageIndex=$stageIndex"

            val taskDef = getRobotTaskDef(ctx.task.def)
            val oldProperties = taskDef?.transports?.get(transportIndex)?.stages?.get(stageIndex)?.properties ?: ""
            logger.debug("$info old properties is : $oldProperties .")

            val newProperties = appendRecognizeProperty(oldProperties)
            logger.debug("$info new properties is : $newProperties .")

            stage.properties = newProperties
        },

        TaskComponentDef(
            "extra", "CreateTaskOfReturnEmptyTrayToStorage", "创建【空托盘运至仓库】的任务", "",
            false, listOf(
        ), false) { _, ctx ->
            var success = false
            var reason = ""
            try {
                createTaskOfReturnEmptyTrayToStorage()
                success = true
            } catch (e: Exception) {
                logger.error("$e")
                reason = e.message ?: e.toString()
            } finally {
                val pv = ctx.task.persistedVariables
                pv["createSuccess"] = success
                pv["reason"] = reason
                MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq ctx.task.id,
                    set(RobotTask::persistedVariables setTo pv))
            }
        },

        TaskComponentDef(
            "extra", "tryToExecuteReturnEmptyTrayToStorage", "尝试执行【顺风车】任务", "",
            false, listOf(
        ), false) { _, ctx ->
            val task = ctx.task
            val pv = task.persistedVariables
            try {
                val result = tryToExecuteReturnEmptyTrayToStorage()
                pv["fromSiteIdBack"] = result.first()
                pv["toSiteIdBack"] = result.last()
            } catch (e: Exception) {
                logger.error("$e")
                val reason = e.message ?: e.toString()

                MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq task.id,
                    set(RobotTask::persistedVariables setTo pv))
                val transports = task.transports
                val size = transports.size
                RobotTaskService.updateTransportState(RobotTransportState.Success, transports[size - 3], task)
                RobotTaskService.updateTransportStateToFinal(RobotTransportState.Skipped, reason, transports[size - 2], task)
                RobotTaskService.updateTransportStateToFinal(RobotTransportState.Skipped, reason, transports[size - 1], task)
//                RobotTaskService.markTaskFailed(task)
            }
        }
    )
}