package com.seer.srd.moshi

import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.RobotTransportState
import com.seer.srd.robottask.component.TaskComponentDef
import com.seer.srd.robottask.component.TaskComponentParam
import com.seer.srd.robottask.component.parseComponentParamValue
import com.seer.srd.storesite.StoreSite
import com.seer.srd.storesite.StoreSiteService
import org.litote.kmongo.`in`
import org.litote.kmongo.eq
import org.litote.kmongo.find

val extraComponents = listOf(
    TaskComponentDef(
        "extra", "lightYellow", "置黄灯常亮", "", false, listOf(
        TaskComponentParam("location", "工作站", "string")
    ), false) {component, ctx ->
      val location = parseComponentParamValue("location", component, ctx) as String
      if (!CUSTOM_CONFIG.others.contains(location)) {
        val area = SiteAreaService.getAreaModBusBySiteId(location)
            ?: throw BusinessError("lightYellow: no such modbus config $location")
        area.switchYellow(location)
      }
    },
    TaskComponentDef(
        "extra", "addVehicle", "添加车辆信息", "", false, listOf(
        TaskComponentParam("location", "工作站", "string")
    ), false) {_, ctx ->
      val vehicle = ctx.task.transports[ctx.transportIndex].processingRobot
      if (vehicle!= null) ctx.task.persistedVariables["vehicle"] = vehicle
    },
    TaskComponentDef(
        "extra", "lightYellowTwinkle", "置黄灯闪烁", "", false, listOf(
        TaskComponentParam("location", "工作站", "string")
    ), false) {component, ctx ->
      val location = parseComponentParamValue("location", component, ctx) as String
      if (!CUSTOM_CONFIG.others.contains(location)) {
        val area = SiteAreaService.getAreaModBusBySiteId(location)
            ?: throw BusinessError("lightYellow: no such modbus config $location")
        SiteAreaService.yellowTwinkle[location] = true
        area.switchYellow(location, true)
      }
    },
    TaskComponentDef(
        "extra", "lightRed", "置红灯常亮报警", "", false, listOf(
        TaskComponentParam("location", "工作站", "string")
    ), false) {component, ctx ->
      val location = parseComponentParamValue("location", component, ctx) as String
      if (!CUSTOM_CONFIG.others.contains(location)) {
        val area = SiteAreaService.getAreaModBusBySiteId(location)
            ?: throw BusinessError("lightRed: no such modbus config $location")
        area.switchRed(location)
      }
    },
    TaskComponentDef(
        "extra", "pass", "是否能通过前置点", "", false, listOf(
        TaskComponentParam("location", "能到达工作站", "string"),
        TaskComponentParam("type", "工作站类型(from/to)", "string")
    ), false) {component, ctx ->
      val location = parseComponentParamValue("location", component, ctx) as String
      val type = parseComponentParamValue("type", component, ctx) as String
      when (type) {
        "from" -> {
          val filled = if (!CUSTOM_CONFIG.others.contains(location))
            SiteAreaService.getAreaModBusBySiteId(location)?.getSiteInfo(location)?.filled ?: throw BusinessError("pass: no such modbus config $location")
          else StoreSiteService.getExistedStoreSiteById(location).filled
          if (!filled) {
            SiteAreaService.pass[location] = false
            ctx.task.persistedVariables["pass"] = "false"
          } else if (filled){
              if (SiteAreaService.pass[location] == true)
                ctx.task.persistedVariables["pass"] = "true"
              else  ctx.task.persistedVariables["pass"] = "false"
          }
        }
        "to" -> {
          val filled = if (!CUSTOM_CONFIG.others.contains(location))
            SiteAreaService.getAreaModBusBySiteId(location)?.getSiteInfo(location)?.filled ?: throw BusinessError("pass: no such modbus config $location")
          else StoreSiteService.getExistedStoreSiteById(location).filled
          if (filled) {
            SiteAreaService.pass[location] = false
            ctx.task.persistedVariables["pass"] = "false"
          } else if (!filled){
              if (SiteAreaService.pass[location] == true)
                ctx.task.persistedVariables["pass"] = "true"
              else  ctx.task.persistedVariables["pass"] = "false"
          }
        }
        else -> throw BusinessError("未指定工作站类型")
      }
    },
    TaskComponentDef(
        "extra", "updateErrorSite", "更新报错库位", "", false, listOf(
        TaskComponentParam("location", "工作站", "string")
    ), false) {component, ctx ->
      val location = parseComponentParamValue("location", component, ctx) as String
      SiteAreaService.pass[location] = true
    },
    TaskComponentDef(
        "extra", "moLex:getOneSite", "从终点库区选择一个空库位", "", false, listOf(
        TaskComponentParam("type", "库区名称", "string")
    ), true) {component, ctx ->
      val type = parseComponentParamValue("type", component, ctx) as String

      val from = ctx.httpCtx?.bodyAsClass(TaskReq::class.java)?.params?.get("from")
      val toSites = MongoDBManager.collection<StoreSite>()
//          .find(StoreSite::type eq type, StoreSite::filled eq false, StoreSite::locked eq false).toList()
          .find(StoreSite::type eq type, StoreSite::filled eq false).toList()
          .filter { if (!from.isNullOrBlank() && from.contains("T-")) it.id.contains("T-") else true }
      if (toSites.isEmpty()){
        if (!from.isNullOrBlank() && from.contains("T-")) throw BusinessError("终点库区没有可用空的宽库位!!")
        else throw BusinessError("终点库区没有可用空库位!!")
      }
      var toSite: StoreSite? = null
      for (site in toSites) {
        if (CUSTOM_CONFIG.others.contains(site.id)) {
          toSite = site
          break
        }
        else {
          toSite = SiteAreaService.getAreaModBusBySiteId(site.id)?.getSiteInfo(site.id)
          break
        }
      }
      if (toSite == null) throw BusinessError("获取终点库位失败，检查配置!!")
      ctx.setRuntimeVariable(component.returnName, toSite.id)
    },

    TaskComponentDef(
        "extra", "checkTransferSite", "检查起点库位","强制刷新库位信息", false, listOf(
        TaskComponentParam("location", "库位名称", "string")
    ), false) { component, ctx ->
      val location = parseComponentParamValue("location", component, ctx) as String
      val site =
          if (!CUSTOM_CONFIG.others.contains(location))
            SiteAreaService.getAreaModBusBySiteId(location)?.getSiteInfo(location) ?: throw BusinessError("checkSite: no such modbus config $location")
          else StoreSiteService.getExistedStoreSiteById(location)
      if (!site.filled) throw BusinessError("库位${location}空！！")
//      if (site.locked) throw BusinessError("起点库位有任务正在执行！！")
    },
    TaskComponentDef(
        "extra", "checkSite", "检查要更新的库位", "强制刷新库位信息", false, listOf(
        TaskComponentParam("location", "库位名称", "string"),
        TaskComponentParam("filled", "from/to", "string")
    ), false) {component, ctx ->
      val location = parseComponentParamValue("location", component, ctx) as String
      val type = parseComponentParamValue("filled", component, ctx) as String
      val site =
          if (!CUSTOM_CONFIG.others.contains(location))
            SiteAreaService.getAreaModBusBySiteId(location)?.getSiteInfo(location) ?: throw BusinessError("checkSite: no such modbus config $location")
          else StoreSiteService.getExistedStoreSiteById(location)
      val taskIds = SiteAreaService.fireTask[site.id]

      if (taskIds.isNullOrEmpty()) {
        if (type == "from") {
          if (!site.filled) throw BusinessError("库位${location}空！！")
        } else if (type == "to") {
          if (site.filled) throw BusinessError("库位${location}非空！！")
        } else throw BusinessError("未选择库位类型!!")
      } else {
        val tasks = MongoDBManager.collection<RobotTask>().find(RobotTask::id `in` taskIds).toList()
        for (task in tasks) {
          if (task.transports[1].state == RobotTransportState.Success) {
            if (type == "from") throw BusinessError("库位${site.id}类型选择错误!!")
            if (site.filled) throw BusinessError("库位${location}非空！！")
          } else if (task.transports[0].state == RobotTransportState.Success) {
            if (type == "to") throw BusinessError("库位${site.id}类型选择错误!!")
            if (!site.filled) throw BusinessError("库位${location}空！！")
          } else {
            if (type == "from") {
              if (!site.filled) throw BusinessError("库位${location}空！！")
            } else if (type == "to") {
              if (site.filled) throw BusinessError("库位${location}非空！！")
            } else throw BusinessError("未选择库位类型!!")
          }
        }
      }
    }
)