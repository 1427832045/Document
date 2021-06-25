package com.seer.srd.huaxin

import com.seer.srd.BusinessError
import com.seer.srd.huaxin.Services.checkEndMap
import com.seer.srd.huaxin.Services.checkFromMap
import com.seer.srd.huaxin.Services.checkGoMap
import com.seer.srd.huaxin.Services.checkToMap
import com.seer.srd.robottask.component.TaskComponentDef
import com.seer.srd.robottask.component.TaskComponentParam
import com.seer.srd.robottask.component.parseComponentParamValue
import com.seer.srd.route.service.VehicleService
import org.slf4j.LoggerFactory

object CustomComponent {

  private val logger = LoggerFactory.getLogger(CustomComponent::class.java)

  val extraComponents: List<TaskComponentDef> = listOf(
    TaskComponentDef(
        "extra", "hefei:go", "放行", "", false, listOf(
    ), false) { _, ctx ->
      val vehicleName = ctx.task.persistedVariables["vehicleName"] as String?
      if (!vehicleName.isNullOrBlank()) {
        checkFromMap[vehicleName] = true
        if (checkGoMap[vehicleName] != true) throw BusinessError("等待放行！！")
        checkFromMap[vehicleName] = false
        checkGoMap[vehicleName] = false
        checkEndMap[vehicleName] = false
      }
    },
    TaskComponentDef(
        "extra", "hefei:end", "归位", "", false, listOf(
    ), false) { _, ctx ->
      val vehicleName = ctx.task.persistedVariables["vehicleName"] as String?
      if (!vehicleName.isNullOrBlank()) {
        checkToMap[vehicleName] = true
        if (checkEndMap[vehicleName] != true) throw BusinessError("等待归位！！")
        checkGoMap[vehicleName] = false
      }
    },
    TaskComponentDef(
        "extra", "hefei:check", "确认归位", "", false, listOf(
    ), false) { _, ctx ->
      val vehicleName = ctx.task.persistedVariables["vehicleName"] as String?
      if (!vehicleName.isNullOrBlank()) {
        if (checkEndMap[vehicleName] != true) {
          checkToMap[vehicleName] = true
          throw BusinessError("等待归位！！")
        } else {
          logger.debug("确认归位")
          checkToMap[vehicleName] = false
          checkEndMap[vehicleName] = false
        }
      }
    },
    TaskComponentDef(
        "extra", "hefei:getVehicle", "设置指定的AGV", "", false, listOf(
        TaskComponentParam("from", "起点", "string"),
        TaskComponentParam("to", "终点", "string")
    ), true) { component, ctx ->
      val from = parseComponentParamValue("from", component, ctx) as String
      val to = parseComponentParamValue("to", component, ctx) as String
      var vehicleName = ""
      (CUSTOM_CONFIG.taskDefToSiteVehicle.toMutableMap() + CUSTOM_CONFIG.extraTaskDefToSiteVehicle).forEach { taskDef, siteToVehicleList ->
        if (ctx.taskDef?.name == taskDef) {
          for(siteToVehicle in siteToVehicleList) {
            if (siteToVehicle.from.contains(from) && siteToVehicle.to.contains(to)) {
              vehicleName = siteToVehicle.vehicleName
              break
            }
          }
        }
        if (vehicleName.isNotBlank()) {
          ctx.task.transports[0].intendedRobot = vehicleName
          return@forEach
        }
      }
      val returnName = component.returnName
      if (!returnName.isNullOrBlank()) {
        ctx.setRuntimeVariable(returnName, vehicleName)
      }
    }
  )
}