package com.seer.srd.siemensSH.phase1

import com.seer.srd.BusinessError
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.RobotTaskService
import com.seer.srd.robottask.buildTaskInstanceByDef
import com.seer.srd.robottask.getRobotTaskDef
import com.seer.srd.siemensSH.MaterialProductMappingService
import com.seer.srd.siemensSH.MaterialProductMappingService.markRecordsUsedByMaterials
import com.seer.srd.siemensSH.common.CUSTOM_CONFIG
import com.seer.srd.siemensSH.common.DEF_E_TO_STATION
import com.seer.srd.storesite.StoreSite
import com.seer.srd.storesite.StoreSiteService
import org.slf4j.LoggerFactory

object Phase1Utils {
    private val logger = LoggerFactory.getLogger(Phase1Utils::class.java)

    val plcSites: MutableList<String> = mutableListOf()

    init {
        CUSTOM_CONFIG.plcDevices.values.forEach { pd -> pd.siteAddrMapping.values.forEach { plcSites.add(it.siteId) } }
        logger.debug("sites with sensor: $plcSites")
    }

    @Synchronized
    fun clearSiteContentIfUnlocked(siteId: String) {
        val site = StoreSiteService.getStoreSiteById(siteId)
            ?: throw BusinessError("无此库位【$siteId】，请重新输入正确的库位名称！")

        if (site.locked) throw BusinessError("无法操作被锁定的库位【$siteId】！")

        val content = site.content
        if (!content.isBlank()) {
            // 清空有光电的库位的货物信息时，如果此库位不是未占用状态，则清空信息失败。
            if (plcSites.contains(site.id) && !site.filled) throw BusinessError("无法操作未被占用的库位【$siteId】!")

            try {
                val remark = "PDA[库位取货]"
                StoreSiteService.setSiteContent(siteId, "", remark)
                // 如果此库位有光电来检测状态，则不需要再手动占用/解除占用库位
                if (site.filled) StoreSiteService.changeSiteFilled(siteId, false, remark)
            } catch (e: Exception) {
                logger.error("${e.message}")
            }
        }
    }

    @Synchronized
    fun lockOneSiteFilledWithEmptyTrayByType(taskId: String, type: String): StoreSite {
        val allSites = StoreSiteService.listStoreSites()

        val siteIds: MutableList<String> = mutableListOf()
        for (site in allSites) {
            if (site.type == type && !site.locked && site.content == "EmptyTray") {
                siteIds.add(site.id)
            }
        }

        if (siteIds.isEmpty()) throw BusinessError("区域【$type】中无带有空托盘的库位")

        try {
            return StoreSiteService.lockFirstSiteWithEmptyTray(siteIds, taskId, "空料车呼叫")
        } catch (e: Exception) {
            throw BusinessError("区域【$type】中无带有空托盘的库位")
        }
    }

    @Synchronized
    fun createEToStationTasksByWorkStationAndCode(ws: String, productCode: String) {
        // 此类任务是通过 Station 的 PDA 通过扫描 整机码productCode 发起的

        // 根据 productCode 获取对应的 materialCode .
        val mappings = MaterialProductMappingService.getMappingsByProduct(productCode)
        if (mappings.isEmpty()) throw BusinessError("未找到与整机码[$productCode]匹配的单元码！")

        val mappingsUnprocessed = mappings.filter { !it.processed }
        if (mappingsUnprocessed.isEmpty()) throw BusinessError("没有未处理的与整机码[$productCode]匹配单元码，请勿重复下单！")

        val materials = mappingsUnprocessed.mapNotNull { it.material }
        logger.info("与整机码[$productCode]匹配的单元码包括 $materials！")

        var mappingMarked = false
        val materialsOnSite: MutableList<String> = mutableListOf()
        try {
            // 根据 code 获取起点 -> 从仓库A和B的原料满料车发货库位查询匹配的库位
            val sites = getUnlockedSitesFilledWithContentByMatCodes(materials, "AE-1")
            if (sites.isEmpty()) throw BusinessError("单元房的单元缓存库位中没有跟【$productCode】匹配的单元码！")

            val msg = sites.map {
                val matCode = it.content
                materialsOnSite.add(matCode)
                "siteId=$matCode"
            }
            logger.info("存放原料的库位有(siteId=matCode)：${msg}")

            val siteType = getSiteTypeForMatByWorkStation(ws) // 终点库位类型

            val newTasks: MutableList<RobotTask> = mutableListOf()
            val newTaskIds: MutableList<String> = mutableListOf() // 用于分批处理任务
            for (site in sites) {
                val taskDef = getRobotTaskDef(DEF_E_TO_STATION)
                    ?: throw BusinessError("不存在任务定义【$DEF_E_TO_STATION】，请联系项开发人员")
                val task = buildTaskInstanceByDef(taskDef)
                newTaskIds.add(task.id)

                // 将发起请求的产线信息记录在任务的持久化参数中，在任务的资源分配阶段再尝试获取终点
                task.persistedVariables["fromSiteId"] = site.id
                task.persistedVariables["content"] = site.content
                task.persistedVariables["siteType"] = siteType

                newTasks.add(task)
            }

            newTaskIds.add("")

            // 当所有任务有创建好了之后再保存
            logger.info("save all new tasks of $DEF_E_TO_STATION")
            newTasks.forEach {
                val a = newTaskIds[newTaskIds.indexOf(it.id) + 1]
//                it.persistedVariables["nextE2StationTaskId"] = a
                RobotTaskService.saveNewRobotTask(it)
            }

            mappingMarked = true
            markRecordsUsedByMaterials(materialsOnSite, true, "create tasks success and mark them processed.")

            // 此次调用之后1秒，才能再次调用此方法，防止连续两次创建的任务的createdOn在秒级相等，从而导致执行E2Station任务时，未按批次执行任务
            Thread.sleep(1000)
        } catch (e: Exception) {
            logger.info("create tasks of TaskDefEToStation failed, because ${e.message}")
            if (mappingMarked) markRecordsUsedByMaterials(materialsOnSite, false, "create tasks failed and clear marks.")
            throw e
        }
    }

    @Synchronized
    fun createTakeMatFromAE1ToAE3TasksByCode(code: String) {
        // 根据 code 获取起点 -> 从仓库 AE-1 区域查询匹配的库位
        val sites = getUnlockedSitesFilledWithContentByCode(code, "AE-1")
        if (sites.isEmpty()) throw BusinessError("单元房的单元缓存库位中没有跟【$code】匹配的且未被占用的物料！")

        val newTasks: MutableList<RobotTask> = mutableListOf()
        for (site in sites) {
            val taskDef = getRobotTaskDef("TaskDefTakeMatFromAE1ToAE3")
                ?: throw BusinessError("不存在任务定义【TaskDefTakeMatFromAE1ToAE3】，请联系项开发人员")
            val task = buildTaskInstanceByDef(taskDef)

            // 将 起点 和 终点 信息持久化到任务信息中，在任务执行阶段再尝试锁定库位，否则任务无法生成
            task.persistedVariables["fromSiteId"] = site.id
            task.persistedVariables["toSiteId"] = "AE-3-1"    // 任务的终点都是固定的
            task.persistedVariables["code"] = code

            newTasks.add(task)
        }

        // 当所有任务有创建好了之后再保存
        logger.info("save all new tasks of TaskDefTakeMatFromAE1ToAE3")
        newTasks.forEach { RobotTaskService.saveNewRobotTask(it) }
    }

    fun getSiteTypeForMatByWorkStation(ws: String): String {
        return when (ws) {
            "station1" -> "M0-1"
            "station2" -> "M1-1"
            "station3" -> "M2-1"
            "station4" -> "M3-1"
            else -> throw BusinessError("undefined station=${ws}!")
        }
    }

    private fun getUnlockedSitesFilledWithContentByCode(code: String, siteType: String): List<StoreSite> {
        val sites: MutableList<StoreSite> = mutableListOf()
        for (site in StoreSiteService.listStoreSites()) {
            if (site.type == siteType && site.content == code && site.filled && !site.locked) sites.add(site)
        }
        return sites
    }

    private fun getUnlockedSitesFilledWithContentByMatCodes(matCodes: List<String>, siteType: String): List<StoreSite> {
        val sites: MutableList<StoreSite> = mutableListOf()
        for (site in StoreSiteService.listStoreSites()) {
            if (site.type == siteType && site.content in matCodes && site.filled && !site.locked) sites.add(site)
        }
        return sites
    }
}