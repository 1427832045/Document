package com.seer.srd.zhenghai

import com.seer.srd.BusinessError
import com.seer.srd.CONFIG
import com.seer.srd.db.MongoDBManager.collection
import com.seer.srd.operator.OperatorOrder
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.component.TaskComponentDef
import com.seer.srd.robottask.component.TaskComponentParam
import com.seer.srd.robottask.component.parseComponentParamValue
import com.seer.srd.storesite.StoreSiteService
import com.seer.srd.util.loadConfig
import org.litote.kmongo.eq
import org.litote.kmongo.findOne
import org.litote.kmongo.set
import org.litote.kmongo.setTo
import org.slf4j.LoggerFactory

val zhengHaiConfig = loadConfig("srd-config.yaml", CustomConfig::class) ?: CustomConfig()

object CustomComponent {
    private val logger = LoggerFactory.getLogger(CustomComponent::class.java)

    private val jackHeightOfSites = zhengHaiConfig.jackHeightOfSites

    private val operatorOrderMap = mutableMapOf<String, OperatorOrder>()

    init {
        CONFIG.operator?.orders?.forEach { operatorOrderMap[it.menuId] = it }
        if (operatorOrderMap.isEmpty()) throw BusinessError("请在配置文件中添加下单相关的配置之后再重启SRD！")
    }

    val customComponents: List<TaskComponentDef> = listOf(
        TaskComponentDef(
            "extra", "CheckTaskFromAToBNotFinished", "锁定库位1，如果失败，解锁库位2", "",
            false, listOf(
            TaskComponentParam("siteId1", "库位Id1", "string"),
            TaskComponentParam("siteId2", "库位Id2", "string"),
            TaskComponentParam("message", "错误提示", "string")
        ), false) { component, ctx ->
            val siteId1 = parseComponentParamValue("siteId1", component, ctx) as String
            val siteId2 = parseComponentParamValue("siteId2", component, ctx) as String
            val message = parseComponentParamValue("message", component, ctx) as String
            try {
                StoreSiteService.lockSiteIfNotLockAndEmpty(siteId1, ctx.task.id, "task=${ctx.task.id} lock unlocked&empty")
            } catch (e: Exception) {
                StoreSiteService.changeSiteLocked(siteId2, false, "", "task=${ctx.task.id} lock $siteId1 failed")
                throw BusinessError(if (message.isNotBlank()) message else "锁定库位【$siteId1】失败！")
            }
        },

        TaskComponentDef(
            "extra", "SetCategoryByMenuId", "根据menuId设置运单的category，并记录手持端上的任务描述",
            "只能在处理Http请求时调用。",
            false, listOf(
            TaskComponentParam("menuId", "menuId", "string")
        ), false) { component, ctx ->
            val menuId = parseComponentParamValue("menuId", component, ctx) as String
            val operatorOrder = getOperatorOrderByMenuId(menuId)
            ctx.task.persistedVariables["operatorOrderLabel"] = operatorOrder.label
            val category = getCategoryByMenuId(operatorOrder)
            ctx.task.transports.forEach { it.category = category }

            // 这个组件是在保存任务之前被调用的，不用强制更新数据库数据
        },

        TaskComponentDef(
            "extra", "GetHeightOfJackLoadBySiteId", "根据库位名称获取顶升高度", "",
            false, listOf(
            TaskComponentParam("siteId", "库位ID", "string")
        ), true) { component, ctx ->
            val siteId = parseComponentParamValue("siteId", component, ctx) as String
            val heightStr = getHeightOfJackLoadBySiteId(siteId)
            ctx.setRuntimeVariable(component.returnName, heightStr)
        },

        TaskComponentDef(
            "extra", "CheckSiteOfMatInPermitted", "判断进料工位是否放行，并获取终点库位", "",
            false, listOf(
            TaskComponentParam("siteId", "库位ID", "string")
        ), false) { component, ctx ->
            val siteId = parseComponentParamValue("siteId", component, ctx) as String
            val site = StoreSiteService.getStoreSiteById(siteId)
                ?: throw BusinessError("不存在库位【$siteId】！")
            val destStr = site.content
            if (destStr.isBlank()) throw BusinessError("库位【$siteId】还未放行")
            val dest = StoreSiteService.getStoreSiteById(destStr) ?: return@TaskComponentDef
            val taskId = ctx.task.id
            val c = collection<RobotTask>()
            val taskFromDB = c.findOne(RobotTask::id eq taskId)
                ?: throw BusinessError("数据库中不存在任务【$taskId】")
            taskFromDB.persistedVariables["dest"] = dest.id
            ctx.task.persistedVariables = taskFromDB.persistedVariables
            collection<RobotTask>().updateOne(RobotTask::id eq taskId, set(
                RobotTask::persistedVariables setTo taskFromDB.persistedVariables
            ))
            StoreSiteService.setSiteContent(siteId, "", "task=$taskId continue")
        }
    )

    private fun getCategoryByMenuId(operatorOrder: OperatorOrder): String {
        val menuId = operatorOrder.menuId
        return if (zhengHaiConfig.menuIdForFork.contains(menuId)) "Fork"
        else if (zhengHaiConfig.menuIdForJack.contains(menuId)) "Jack"
        else throw BusinessError("无法识别下单操作【${operatorOrder.label}】对应的业务类型，请检查配置文件！")
    }

    private fun getOperatorOrderByMenuId(menuId: String): OperatorOrder {
        return operatorOrderMap[menuId]
            ?: throw BusinessError("找不到相关配置，请重启应用再尝试下单。（若多次操作任提示此错误请联系相关人员检查配置文件）")
    }

    private fun getHeightOfJackLoadBySiteId(siteId: String): String {
        return jackHeightOfSites[siteId] ?: throw BusinessError("库位【$siteId】未配置顶升高度，请检查配置文件！")
    }
}
