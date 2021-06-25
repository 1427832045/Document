package com.seer.srd.festo.phase4

import com.seer.srd.BusinessError
import com.seer.srd.SkipCurrentTransport
import com.seer.srd.db.MongoDBManager
import com.seer.srd.db.MongoDBManager.collection
import com.seer.srd.festo.ComplexTaskPvs
import com.seer.srd.festo.festoConfig
import com.seer.srd.festo.phase4.ComplexTaskService.execEmptyTask
import com.seer.srd.festo.phase4.ComplexTaskService.execFilledTask
import com.seer.srd.festo.phase4.ComplexTaskService.getSentOfComplexTask
import com.seer.srd.festo.phase4.ComplexTaskService.getTypeOfComplexTask
import com.seer.srd.robottask.*
import com.seer.srd.robottask.component.TaskComponentDef
import com.seer.srd.robottask.component.TaskComponentParam
import com.seer.srd.robottask.component.parseComponentParamValue
import com.seer.srd.storesite.StoreSite
import com.seer.srd.storesite.StoreSiteService.changeSiteFilled
import com.seer.srd.storesite.StoreSiteService.changeSiteLocked
import com.seer.srd.storesite.StoreSiteService.getStoreSiteById
import com.seer.srd.storesite.StoreSiteService.listStoreSites
import com.seer.srd.storesite.StoreSiteService.lockSiteIfNotLock
import com.seer.srd.storesite.StoreSiteService.lockSiteIfNotLockAndEmpty
import org.litote.kmongo.*
import org.slf4j.LoggerFactory

const val VSO = "VSO"
const val SENT = "sent"
const val TYPE = "type"
const val BOTH = "Both"         // 顺风车任务： 创建新的满托盘任务，会将已创建的同组的空托盘任务升级成顺丰车任务，并放弃创建这条满托盘任务。
const val FILLED = "Filled"     // 满托盘任务
const val EMPTY = "Empty"       // 空托盘任务
const val TASK_DEF_COMPLEX = "TaskDefComplex"
const val TO_SITE_ID_EMPTY = "toSiteIdEmpty"
const val FROM_SITE_ID_EMPTY = "fromSiteIdEmpty"
const val FROM_SITE_ID_FILLED = "fromSiteIdFilled"
const val TO_SITE_ID_FILLED = "toSiteIdFilled"

object Component4 {
    private val logger = LoggerFactory.getLogger(Component4::class.java)

    val limit = festoConfig.limitOfSentTasks4

    // 可以从 E1 区域选择终点的 产线组 的集合
    private val e1List = listOf(
        "VSO1-5", "VSO1-6", "VSO1-7", "VSO1-8", "VSO1-9", "VSO1-M", "VSO2-6", "VSO2-7", "VSO2-M", "VSO5-M"
    )

    // 可以从 E2 区域选择终点的 产线组 的集合
    private val e2List = listOf(
        "VSO1-1", "VSO1-2", "VSO1-3", "VSO1-4",
        "VSO2-1", "VSO2-2", "VSO2-3", "VSO2-4", "VSO2-5",
        "VSO5-1", "VSO5-2", "VSO5-3", "VSO5-4", "VSO5-5", "VSO5-6"
    )

    // 需要进行库位管理的库位类型
    private val typesForBeManagedSites = listOf("F-0480", "F-0481", "E1", "E2", "M", "Other")

    private fun isManagedSite(siteType: String): Boolean {
        return typesForBeManagedSites.contains(siteType)
    }

    val components: List<TaskComponentDef> = listOf(
        TaskComponentDef(
            "extra", "getPersistedVariableFromDB", "从数据库中获取指定的任务变量", "",
            false, listOf(
            TaskComponentParam("fieldName", "变量名", "string")
        ), true) { component, ctx ->
            val fieldName = parseComponentParamValue("fieldName", component, ctx) as String
            val task = MongoDBManager.collection<RobotTask>().findOne(RobotTask::id eq ctx.task.id)
                ?: throw BusinessError("数据库中不存在当前任务！")
            val value = task.persistedVariables[fieldName]?.toString()
                ?: throw throw BusinessError("此任务未记录变量$fieldName .")
            ctx.setRuntimeVariable(component.returnName, value.toString())
        },

        TaskComponentDef(
            "extra", "MarkTaskSent", "标记已经被下发的任务", "",
            false, listOf(
        ), false) { _, ctx ->
            val task = ctx.task
            if (getSentOfComplexTask(task) == SENT) return@TaskComponentDef
            task.persistedVariables = persistPersistedVariables(task.id, ComplexTaskPvs(sent = SENT))
        },

        TaskComponentDef(
            "extra", "processComplexTasks", "根据条件下发Complex任务", "",
            false, listOf(
        ), false) { _, ctx ->
            try {
                ComplexTaskProcessor.process(ctx.task)
            } catch (e: Exception) {
                logger.error("$e")
                throw e
            }
        },

        TaskComponentDef(
            "extra", "FinishEmptyTrayTask", "结束空托盘任务", "",
            false, listOf(
        ), false) { _, _ ->
            // val pv = ctx.task.persistedVariables
            // val siteId = pv[TO_SITE_ID_EMPTY] ?: pv[FROM_SITE_ID_FILLED] ?: return@TaskComponentDef
            // val group = getGroupOfVSOBySiteId(siteId.toString())
            // if (creatingComplexTask.contains(group))
            // throw BusinessError("正在创建同组的满托盘任务，等待...")
        },

        TaskComponentDef(
            "extra", "ExecEmptyTrayTaskOrNot", "是否需要执行空托盘任务", "",
            false, listOf(
        ), false) { _, ctx ->
            val task = MongoDBManager.collection<RobotTask>().findOne(RobotTask::id eq ctx.task.id)
                ?: throw BusinessError("数据库中不存在当前任务！")
            val type = getTypeOfComplexTask(task)
            if (!type.isNullOrBlank()) ctx.task.persistedVariables[TYPE] = type
            if (!execEmptyTask(type)) {
                val reason = "不需要执行【空托盘】任务."
                val transport = ctx.task.transports[1]
                RobotTaskService.updateTransportStateToFinal(RobotTransportState.Skipped, reason, transport, ctx.task)
                throw SkipCurrentTransport(reason)
            }
        },

        TaskComponentDef(
            "extra", "ExecFilledTrayTaskOrNot", "是否需要执行满托盘任务", "",
            false, listOf(
        ), false) { _, ctx ->
            val task = MongoDBManager.collection<RobotTask>().findOne(RobotTask::id eq ctx.task.id)
                ?: throw BusinessError("数据库中不存在当前任务！")
            val type = getTypeOfComplexTask(task)
            if (!type.isNullOrBlank()) ctx.task.persistedVariables[TYPE] = type
            if (!execFilledTask(type)) {
                val reason = "不需要执行【满托盘】任务."
                val transport = ctx.task.transports[3]
                RobotTaskService.updateTransportStateToFinal(RobotTransportState.Skipped, reason, transport, ctx.task)
                throw SkipCurrentTransport(reason)
            }
        },

        TaskComponentDef(
            "extra", "MarkManagedSiteIdle", "将被管理的库位修改为空", "",
            false, listOf(
            TaskComponentParam("siteId", "库位ID", "string")
        ), false) { component, ctx ->
            val siteId = parseComponentParamValue("siteId", component, ctx) as String
            val site = getStoreSiteById(siteId)
                ?: throw BusinessError("不存在库位【$siteId】，请检查库位定义！")
            if (!isManagedSite(site.type)) return@TaskComponentDef
            changeSiteFilled(siteId, false, "From task=${ctx.task.id}")
        },

        TaskComponentDef(
            "extra", "MarkManagedSiteNotIdle", "将被管理的库位修改为非空", "",
            false, listOf(
            TaskComponentParam("siteId", "库位ID", "string")
        ), false) { component, ctx ->
            val siteId = parseComponentParamValue("siteId", component, ctx) as String
            val site = getStoreSiteById(siteId)
                ?: throw BusinessError("不存在库位【$siteId】，请检查库位定义！")
            if (!isManagedSite(site.type)) return@TaskComponentDef
            changeSiteFilled(siteId, true, "From task=${ctx.task.id}")
        },

        TaskComponentDef(
            "extra", "TryToLockUnlockedSite", "尝试锁定未锁定的库位", "",
            false, listOf(
            TaskComponentParam("siteId", "库位ID", "string"),
            TaskComponentParam("label", "库位描述", "string")
        ), false) { component, ctx ->
            val siteId = parseComponentParamValue("siteId", component, ctx) as String
            val label = parseComponentParamValue("label", component, ctx) as String
            val remark = "creating ${ctx.task.def}=${ctx.task.id}"
            // 锁定未锁定，且被占用的库位
            lockStoreSiteByCondition(siteId, ctx.task.id, label, true, remark)
        },

        TaskComponentDef( // 1
            "extra", "TryToLockToSiteFromAreaConcernedWithFromSite",
            "尝试从起点对应的收货区域中锁定未占用且未锁定的终点库位", "",
            false, listOf(
            TaskComponentParam("fromSiteId", "起点", "string")
        ), false) { component, ctx ->
            val fromSiteId = parseComponentParamValue("fromSiteId", component, ctx) as String
            val area = getToSiteAreaByFromSiteIdForReturnFilledTray(fromSiteId)
            val toSites = listStoreSites().filter { it.type == area && !it.locked && !it.filled }
            val areaId = area.split("-").last()
            if (toSites.isEmpty()) throw BusinessError("区域【${areaId}】中没有未占用，且未锁定的库位，请尽快处理！")
            var toSiteId = ""
            val task = ctx.task
            val taskId = task.id
            for (toSite in toSites) {
                toSiteId = toSite.id
                try {
                    lockSiteIfNotLockAndEmpty(toSiteId, ctx.task.id, "creating ${ctx.task.def}=${taskId}")
                    break
                } catch (e: Exception) {
                    // do nothing; 尝试锁定所有符合条件的库位
                }
            }
            if (toSiteId.isBlank()) throw BusinessError("正在锁定区域【${areaId}】中符合条件的库位，请等待...")
            // 持久化终点库位到数据中
            task.persistedVariables = persistPersistedVariables(taskId, ComplexTaskPvs(toSiteIdFilled = toSiteId))
        },

        TaskComponentDef( // 1
            "extra", "TryToLockFromSiteAndToSiteAndRecoverIfError",
            "尝试锁定起点和终点，出错时恢复库位状态", "",
            false, listOf(
            TaskComponentParam("fromSiteId", "起点", "string"),
            TaskComponentParam("toSiteId", "终点", "string")
        ), false) { component, ctx ->
            val fromSiteId = parseComponentParamValue("fromSiteId", component, ctx) as String
            val toSiteId = parseComponentParamValue("toSiteId", component, ctx) as String
            var fromSiteLocked = false
            val remark = "creating ${ctx.task.def}=${ctx.task.id}"
            try {
                // 锁定未锁定，且别占用的库位，作为起点库位。
                lockStoreSiteByCondition(fromSiteId, ctx.task.id, "起点", true, remark)
                fromSiteLocked = true
                //
                lockStoreSiteByCondition(toSiteId, ctx.task.id, "终点", false, remark)
            } catch (e: Exception) {
                // 解锁已经被锁定的起点库位
                if (fromSiteLocked) changeSiteLocked(fromSiteId, false, "", "$remark but failed")
                throw e
            }
        },

        TaskComponentDef( // 1
            "extra", "SelectFromSiteForEmptyTrayTaskByToSite",
            "根据空托盘任务的终点库位选择起点库位(不锁定)", "执行成功之后，数据会直接被持久化到数据库中",
            false, listOf(
            TaskComponentParam("toSiteId", "终点ID", "string")
        ), false) { component, ctx ->
            val toSiteId = parseComponentParamValue("toSiteId", component, ctx) as String
            val task = ctx.task
            val taskId = task.id
            val fromSiteId = selectFromSiteForEmptyTrayTaskByToSite(toSiteId, "component of $taskId").id
            // 需要手动将此数据持久化到数据库中
            task.persistedVariables = persistPersistedVariables(taskId, ComplexTaskPvs(fromSiteIdEmpty = fromSiteId))
        }

    )

    fun lockStoreSiteByCondition(siteId: String, taskId: String, label: String, filled: Boolean, remark: String) {
        // 库位是否存在
        val site = getStoreSiteById(siteId)
            ?: throw BusinessError("${label}库位【$siteId】不存在，请输入正确的库位信息！")
        // 被管理的库位一定要处于期望状态
        if (isManagedSite(site.type) && filled && !site.filled)
            throw BusinessError("${label}库位【$siteId】不符合要求(未被占用)，请尽快处理！")
        // 锁定库位
        try {
            lockSiteIfNotLock(siteId, taskId, remark)
        } catch (e: Exception) {
            throw BusinessError("${label}库位【$siteId】不符合要求(已被锁定)，请尽快处理！")
        }
    }

    private fun getToSiteAreaByFromSiteIdForReturnFilledTray(fromSiteId: String): String {
        val parts = fromSiteId.split("-")
        val lastPart = parts.last()
        if (lastPart.isBlank()) throw BusinessError("取货库位【$fromSiteId】不符合产线库位的命名规则！")
        return when (lastPart.first().toString().toInt()) {
            1 -> "F-0480"
            2 -> "F-0481"
            // 3 -> "???"          // 满托盘运输，没有类型为3的业务
            else -> {
                if (parts[1] == "M") return "M"
                throw BusinessError("无法识别库位【$fromSiteId】对应的满托盘运输的业务类型！")
            }
        }
    }

    @Synchronized
    // 调用此方法之后，需要注意内存数据和本地数据的同步问题。
    fun persistPersistedVariables(taskId: String, pvsObj: ComplexTaskPvs): MutableMap<String, Any?> {
        val pvsFromDB = collection<RobotTask>().findOne(RobotTask::id eq taskId)?.persistedVariables
            ?: throw BusinessError("系统中不存在任务【$taskId】")
        if (!pvsObj.sent.isNullOrBlank()) pvsFromDB[SENT] = pvsObj.sent
        if (!pvsObj.type.isNullOrBlank()) pvsFromDB[TYPE] = pvsObj.type
        if (!pvsObj.fromSiteIdEmpty.isNullOrBlank()) pvsFromDB[FROM_SITE_ID_EMPTY] = pvsObj.fromSiteIdEmpty
        if (!pvsObj.toSiteIdEmpty.isNullOrBlank()) pvsFromDB[TO_SITE_ID_EMPTY] = pvsObj.toSiteIdEmpty
        if (!pvsObj.fromSiteIdFilled.isNullOrBlank()) pvsFromDB[FROM_SITE_ID_FILLED] = pvsObj.fromSiteIdFilled
        if (!pvsObj.toSiteIdFilled.isNullOrBlank()) pvsFromDB[TO_SITE_ID_FILLED] = pvsObj.toSiteIdFilled

        collection<RobotTask>().updateOne(
            RobotTask::id eq taskId, set(RobotTask::persistedVariables setTo pvsFromDB)
        )
        return pvsFromDB
    }

    @Synchronized
    fun selectFromSiteForEmptyTrayTaskByToSite(toSiteId: String, remark: String): StoreSite {
        val group = getGroupOfVSOBySiteId(toSiteId)
        // 选择起点库区
        val type =
            if (e1List.contains(group)) "E1"
            else if (e2List.contains(group)) "E2"
            else throw BusinessError("没有与库位【$toSiteId】相匹配的发货区域！")

        // 选择终点库位
        val fromSites = listStoreSites().filter { it.type == type && !it.locked && it.filled }
        if (fromSites.isEmpty()) throw BusinessError("区域【$type】中没有被占用且未锁定的库位，请尽快处理！")
        return fromSites.first()
    }

    fun getGroupOfVSOBySiteId(siteId: String): String {
        if (siteId.isBlank()) throw BusinessError("siteId blank! siteId=$siteId .")
        if (siteId.substring(0, 3) != VSO) throw BusinessError("$siteId not belongs to VSO!")
        val parts = siteId.split("-")
        if (parts.size < 3) throw BusinessError("$siteId is bad formatted, expected is VSOa-b-c .")
        return "${parts[0]}-${parts[1]}"
    }

}