package com.seer.srd.siemensSH.phase2

import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.robottask.*
import com.seer.srd.siemensSH.common.ComUtils.existReturnEmptyTrayToStorageTaskOfSameFromSite
import com.seer.srd.siemensSH.common.DEF_MAT_TO_PS
import com.seer.srd.siemensSH.common.DEF_RETURN_EMPTY_TRAY_BACK_TO_STORAGE
import com.seer.srd.siemensSH.common.TRY_NEXT_MARK
import com.seer.srd.storesite.StoreSite
import com.seer.srd.storesite.StoreSiteService
import com.seer.srd.util.mapper
import org.litote.kmongo.eq
import org.litote.kmongo.find
import org.slf4j.LoggerFactory

object Phase2Utils {
    private val logger = LoggerFactory.getLogger(Phase2Utils::class.java)

    @Synchronized
    fun tryToLockSiteIfNotLocked(taskId: String, siteId: String) {
        val site = StoreSiteService.getStoreSiteById(siteId)
            ?: throw BusinessError("${TRY_NEXT_MARK}不存在库位【$siteId】，请确认之后再重新输入！")

        if (site.locked) throw BusinessError("${TRY_NEXT_MARK}无法锁定已经被锁定的库位【$siteId】！")

        try {
            StoreSiteService.lockSiteIfNotLock(siteId, taskId, "try to lock unlocked site")
        } catch (e: Exception) {
            logger.error("try to lock site if not locked failed!")
        }
    }

    @Synchronized
    fun createTakeMatToPsTasksByCodeAndPsName(code: String, ps: String, wt: String) {
        // 根据 code 获取起点 -> 从仓库A和B的原料满料车发货库位查询匹配的库位
        val sites = getUnlockedSitesFilledWithContentByCode(code)
        if (sites.isEmpty()) throw BusinessError("仓库A中没有跟【$code】匹配的物料！")

        sites.forEach {
            val createdTasks = getCreatedTaskByConditions(DEF_MAT_TO_PS, it.id, code)
            if (createdTasks.isNotEmpty()) throw BusinessError("物料【$code】的运输任务已创建，请勿重复下发！")
        }

        val newTasks: MutableList<RobotTask> = mutableListOf()
        for (site in sites) {
            val taskDef = getRobotTaskDef(DEF_MAT_TO_PS)
                ?: throw BusinessError("不存在任务定义【${DEF_MAT_TO_PS}】，请联系项开发人员")
            val task = buildTaskInstanceByDef(taskDef)

            // 将发起请求的产线信息记录在任务的持久化参数中，在任务的资源分配阶段再尝试获取终点
            task.persistedVariables["fromSiteId"] = site.id
            task.persistedVariables["content"] = site.content
            task.persistedVariables["ps"] = ps

            // 追加关联工位
            task.workTypes.add(wt)

            newTasks.add(task)
        }

        // 当所有任务有创建好了之后再保存
        logger.info("save all new tasks")
        newTasks.forEach { RobotTaskService.saveNewRobotTask(it) }
    }

    private fun getUnlockedSitesFilledWithContentByCode(code: String): List<StoreSite> {
        val types = listOf("CA-1")
        val sites: MutableList<StoreSite> = mutableListOf()
        for (site in StoreSiteService.listStoreSites()) {
            if (types.contains(site.type) && site.content == code && site.filled && !site.locked) sites.add(site)
        }
        return sites
    }

    fun getCreatedTaskByConditions(taskDef: String, fromSiteId: String, content: String): List<RobotTask> {
        val tasks = MongoDBManager.collection<RobotTask>()
            .find(RobotTask::def eq taskDef, RobotTask::state eq RobotTaskState.Created).toList()
        if (tasks.isEmpty()) return tasks
        return tasks.filter {
            it.persistedVariables["fromSiteId"] == fromSiteId
                && it.persistedVariables["content"] == content
        }
    }

    fun forkLoadRecognizeOrNot(siteId: String): Boolean {
        return try {
            // 判断库位类型
            val type = siteId.substring(0, 5) // CA-1-
            if (type != "CA-1-") {
                logger.error("只有【CA-1】类型的库位需要判断是否需要识别。")
                return false
            }

            val tail = siteId.last().toString().toInt()
            logger.debug("siteId=$siteId, tail=$tail ...")
            if (tail % 2 == 0) { //
                logger.debug("库位编号位数为偶数 - 识别。")
                true
            } else {
                logger.debug("库位编号位数为奇数 - 不识别。")
                false
            }
        } catch (e: Exception) {
            logger.error("根据库位编号判断叉货是否需要识别时出错：$e")
            throw e
        }
    }

    fun appendRecognizeProperty(srcProperties: String): String {
        // srcProperties like [{"key":"end_height","value":"0.15"}]
        var newProperty = """{"key":"recognize","value":"true"}"""
        if (srcProperties.isBlank()) return "[$newProperty]"

        val properties = mapper.readTree(srcProperties)
        properties.forEach { newProperty = "$newProperty, $it" }

        return "[$newProperty]"
    }

    @Synchronized
    fun createTaskOfReturnEmptyTrayToStorage() {
        val taskDef = getRobotTaskDef(DEF_RETURN_EMPTY_TRAY_BACK_TO_STORAGE)
            ?: throw BusinessError(("无柔性任务【${DEF_RETURN_EMPTY_TRAY_BACK_TO_STORAGE}】，自动创建任务失败！"))

        // 判断起点和终点库位是否符合条件: 起点被占用且未被锁定，终点未被占用且未被锁定
        val fromSites = StoreSiteService.listStoreSites().filter { it.id.contains("M4-2-") }
        val msg = "auto create task of $DEF_RETURN_EMPTY_TRAY_BACK_TO_STORAGE:"
        if (fromSites.isEmpty())
            throw BusinessError("未找到类型是【M4-2】的库位！")
        logger.debug("$msg fromSites=$fromSites!")
        val fromSitesAvailable = fromSites.filter { it.filled && !it.locked }
        if (fromSitesAvailable.isEmpty()) throw BusinessError("起点未被占用，或已锁定！")
        val fromSite = fromSitesAvailable.first()

        val toSiteId = "CB-3-1"
        val toSite = StoreSiteService.getStoreSiteById(toSiteId)
            ?: throw BusinessError("不存在终点库位【$toSiteId】，请检查库位信息！")
        logger.debug("$msg toSite=$toSite!")
        if (toSite.filled || toSite.locked)
            throw BusinessError("终点已被占用，或已锁定！")

        existReturnEmptyTrayToStorageTaskOfSameFromSite(fromSite.id) // will throw error

        val newTask = buildTaskInstanceByDef(taskDef)
        newTask.persistedVariables["fromSiteId"] = fromSite.id
        newTask.persistedVariables["toSiteId"] = toSite.id
        RobotTaskService.saveNewRobotTask(newTask)
    }

    @Synchronized
    fun tryToExecuteReturnEmptyTrayToStorage(): List<String> {
        // 判断起点和终点库位是否符合条件: 起点被占用且未被锁定，终点未被占用且未被锁定
        val fromSites = StoreSiteService.listStoreSites().filter { listOf("M4-2", "M4-3").contains(it.type) }
        if (fromSites.isEmpty())
            throw BusinessError("未找到类型是【M4-2】或【M4-3】的库位！")
        logger.debug("fromSites=$fromSites for free-ride-task.")
        val fromSitesAvailable = fromSites.filter { it.filled && !it.locked }
        // 分开处理 M4-2 >> CB-3-1 和 M4-3 >> CB-1-1 的顺风车任务。
        val firstSiteM42 = fromSitesAvailable.firstOrNull { it.type == "M4-2" }
        val firstSiteM43 = fromSitesAvailable.firstOrNull { it.type == "M4-3" }
        val fromSite = firstSiteM42 ?: firstSiteM43 ?: throw BusinessError("库区【M4-2】和【M4-3】的库位未被占用，或已锁定，没有符合条件的起点库位！")
        val toSiteId = if (fromSite.type == "M4-2") "CB-3-1" else "CB-1-1"
        val toSite = StoreSiteService.getStoreSiteById(toSiteId)
            ?: throw BusinessError("不存在终点库位【$toSiteId】，请检查库位信息！")
        logger.debug("$toSite=$toSite for free-ride-task.")
        if (toSite.filled || toSite.locked)
            throw BusinessError("终点【$toSiteId】已被占用，或已锁定！")

        return listOf(fromSite.id, toSiteId)
    }
}
