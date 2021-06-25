package com.seer.srd.siemensCd_1

import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.component.TaskComponentDef
import com.seer.srd.robottask.component.TaskComponentParam
import com.seer.srd.robottask.component.parseComponentParamValue
import com.seer.srd.siemensCd_1.Services.emptyTrayVehicles
import com.seer.srd.storesite.StoreSiteService
import org.litote.kmongo.eq
import org.litote.kmongo.set
import org.litote.kmongo.setTo
import org.litote.kmongo.setValue
import org.slf4j.LoggerFactory

object ExtraComponent {

  private val logger = LoggerFactory.getLogger(ExtraComponent::class.java)

  val extraComponents: List<TaskComponentDef> = listOf(
      TaskComponentDef(
          "extra", "siemensCd1:setCategory", "根据起点库位设置category", "", false, listOf(
          TaskComponentParam("from", "库位ID", "string")
      ), false) { component, ctx ->
        val from = parseComponentParamValue("from", component, ctx) as String
        ctx.task.transports.forEach { if (from.contains("EMPTY")) it.category = "EmptyTray1" else it.category = "EmptyTray2" }
        logger.debug("from=$from 设置运单类别: ${ctx.task.transports[0].category}")
      },
      TaskComponentDef(
          "extra", "siemensCd1:checkFromSite", "检查起点库位返回库位内容", "", false, listOf(
          TaskComponentParam("from", "库位ID", "string")
      ), true) { component, ctx ->
        val from = parseComponentParamValue("from", component, ctx) as String
        val site = StoreSiteService.getExistedStoreSiteById(from)
        if (!site.filled) throw BusinessError("[$from]是空的！！")
        val content = site.content
        if (site.type.toUpperCase().contains("EMPTY")) {
          if (content.isNotBlank()) throw BusinessError("[$from]不是空托盘！！")
        }
        else if (site.type.toUpperCase().contains("MAT")) {
          if (content.isBlank()) throw BusinessError("[$from]原料物料码缺失！！")
        }
        else if (site.type.toUpperCase().contains("FG")) {
          if (content.isBlank()) throw BusinessError("[$from]成品物料码缺失！！")
        }
        ctx.setRuntimeVariable(component.returnName, content)
      },
      TaskComponentDef(
          "extra", "siemensCd1:checkToSite", "检查终点是否有可用库位", "", false, listOf(
          TaskComponentParam("type", "类型", "string")
      ), false) { component, ctx ->
        val type = parseComponentParamValue("type", component, ctx) as String
        val sites = StoreSiteService.listStoreSites().filter { it.type == type && !it.filled }
        if (sites.isEmpty()) throw BusinessError("[$type]没有可用位置!!")
      },
      TaskComponentDef(
          "extra", "siemensCd1:checkToSite2", "选择一个可用终点库位", "", false, listOf(
      ), true) { component, ctx ->
        val toType = ctx.task.persistedVariables["toType"] as String
        val sites = StoreSiteService.listStoreSites().filter { it.type == toType && !it.filled }
        if (sites.isEmpty()) ctx.setRuntimeVariable(component.returnName, "")
        else ctx.setRuntimeVariable(component.returnName, sites[0].id)
      },
      TaskComponentDef(
          "extra", "siemensCd1:sendToVehicle", "下发空托盘任务", "", false, listOf(
      ), false) { _, ctx ->
        if (CUSTOM_CONFIG.emptyTrayTaskTrigger) {
          val v = ctx.task.transports[0].processingRobot
          if (!v.isNullOrBlank()) emptyTrayVehicles.add(v)
        }
      },
      TaskComponentDef(
          "extra", "siemensCd1:checkSend", "检查是否满足下发条件", "", false, listOf(
      ), false) { _, ctx ->
        if (CUSTOM_CONFIG.emptyTrayTaskTrigger) {
//          Thread.sleep(1500)
          val intendedVehicle = if (emptyTrayVehicles.isNotEmpty()) emptyTrayVehicles.poll() else ""
          if (intendedVehicle.isBlank()) throw BusinessError("等待可用车辆")
          ctx.task.transports.forEach { it.intendedRobot = intendedVehicle }
          val transports = ctx.task.transports
          MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq ctx.task.id, set(RobotTask::transports setTo transports))
        }
      },
      TaskComponentDef(
          "extra", "siemensCd1:updateSite", "更新库位状态", "", false, listOf(
          TaskComponentParam("id", "库位ID", "string"),
          TaskComponentParam("code", "物料码", "string"),
          TaskComponentParam("type", "变更类型", "string")
      ), true) { component, ctx ->
        val siteId = parseComponentParamValue("id", component, ctx) as String
        val code = parseComponentParamValue("code", component, ctx) as String? ?: ""
        val type = parseComponentParamValue("type", component, ctx) as String
        val site = StoreSiteService.getExistedStoreSiteById(siteId)
        if (site.locked) throw BusinessError("库位[$siteId]已被锁定!!")
        if (type == "clear") StoreSiteService.changeSiteFilled(siteId, false, "update from task")
        if (type == "update") {
          StoreSiteService.changeSiteFilled(siteId, true, "update from task")
          StoreSiteService.setSiteContent(siteId, if (siteId.toUpperCase().contains("EMPTY")) "" else code, "update from task")
        }
      }
  )
}