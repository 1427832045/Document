package com.seer.srd.huaxin.hb

import com.seer.srd.BusinessError
import com.seer.srd.huaxin.hb.Services.checkEndMap
import com.seer.srd.huaxin.hb.Services.checkFromMap
import com.seer.srd.huaxin.hb.Services.checkGoMap
import com.seer.srd.huaxin.hb.Services.checkToMap
import com.seer.srd.huaxin.hb.Services.getStationIdByLocation
import com.seer.srd.huaxin.hb.Services.mesHttpClient
import com.seer.srd.huaxin.hb.Services.passLocations
import com.seer.srd.huaxin.hb.Services.rolledLocations
import com.seer.srd.huaxin.hb.Services.waitingPassMap
import com.seer.srd.huaxin.hb.Services.waitingRolledMap
import com.seer.srd.robottask.component.TaskComponentDef
import com.seer.srd.robottask.component.TaskComponentParam
import com.seer.srd.robottask.component.parseComponentParamValue
import com.seer.srd.storesite.StoreSite
import com.seer.srd.storesite.StoreSiteService
import com.seer.srd.util.mapper
import org.slf4j.LoggerFactory

object CustomComponent {

  private val logger = LoggerFactory.getLogger(CustomComponent::class.java)

  val extraComponents: List<TaskComponentDef> = listOf(
    TaskComponentDef(
        "extra", "xian:outReady", "出库:通知上位机AGV到位", "", false, listOf(
        TaskComponentParam("location", "位置", "string")
    ), false) { component, ctx ->
      logger.debug("srd send ready signal...")
      val location = parseComponentParamValue("location", component, ctx) as String
      val station = getStationIdByLocation(location)
      val res = mesHttpClient.outReady(station).execute()
      val body = mapper.readValue(res.body(), MesResponse::class.java)
      val code = res.code()
      if (code != 200 || body.code != 200) {
        Thread.sleep(2000)
        logger.warn("response $code for body [$body] url: ${CUSTOM_CONFIG.mesUrl}seer/outstockbegin?station=$station")
        if (code != 200) throw BusinessError("response $code")
        if (body.code != 200) throw BusinessError(body.message)
      }
      logger.debug("mes has received out ready signal, $body")
    },
    TaskComponentDef(
        "extra", "xian:outLoaded", "出库:通知上位机AGV装载完成", "", false, listOf(
        TaskComponentParam("location", "位置", "string")
    ), false) { component, ctx ->
      logger.debug("srd send loaded signal...")
      val location = parseComponentParamValue("location", component, ctx) as String
      val station = getStationIdByLocation(location)
      if (station.isBlank()) throw BusinessError("接驳台${location}编号获取失败")
      val res = mesHttpClient.outLoaded(station).execute()
      val body = mapper.readValue(res.body(), MesResponse::class.java)
      val code = res.code()
      if (code != 200 || body.code != 200) {
        Thread.sleep(2000)
        logger.warn("response $code for body [$body] url: ${CUSTOM_CONFIG.mesUrl}seer/outstockend")
        if (code != 200) throw BusinessError("response $code")
        if (body.code != 200) throw BusinessError(body.message)
      }
      waitingPassMap[location] = true
      logger.debug("mes has received loaded signal, AS can stop rolled, $body")
    },
    TaskComponentDef(
        "extra", "xian:checkLSFrom", "检查流水线起点并返回", "", false, listOf(
    ), true) { component, ctx ->
      val from = ctx.task.persistedVariables["from"] as String
      val v = Services.getValByLSFrom(from) ?: throw BusinessError("未配置的起点$from")
      ctx.setRuntimeVariable(component.returnName, v)
    },
    TaskComponentDef(
        "extra", "xian:setOperation", "设置操作和属性", "", false, listOf(
        TaskComponentParam("from", "from", "string"),
        TaskComponentParam("to", "to", "string")
    ), false) { component, ctx ->
      val from = parseComponentParamValue("from", component, ctx) as String
      val to = parseComponentParamValue("to", component, ctx) as String
      if (from in listOf("")) {
        ctx.task.transports[0].stages[3].operation = "Actions"
        ctx.task.transports[0].stages[3].properties = """[{"key":"body","value":"{\"navigation\":{\"module_script_args\":{\"operation\":\"RollerPreLoad\"},\"module_script_name\":\"huaxin(do)(1).py\",\"script_stage\":2}}"}]"""
        ctx.task.transports[0].stages[2].operation = "WaitKey"
        ctx.task.transports[0].stages[2].properties = """[{"key": "${CUSTOM_CONFIG.waitKeyLoad}", "value": "true"}]"""
        ctx.task.transports[0].stages[4].operation = "Actions"
        ctx.task.transports[0].stages[4].properties = """[{"key":"body","value":"{\"navigation\":{\"module_script_args\":{\"operation\":\"RollerLoad\"},\"module_script_name\":\"huaxin(do)(1).py\",\"script_stage\":2}}"}]"""
      }
      if (to in listOf("")) {
        ctx.task.transports[1].stages[2].operation = "WaitKey"
        ctx.task.transports[1].stages[2].properties = """[{"key": "${CUSTOM_CONFIG.waitKeyUnLoad}", "value": "true"}]"""
        ctx.task.transports[1].stages[3].operation = "Actions"
        ctx.task.transports[1].stages[3].properties = """[{"key":"body","value":"{\"navigation\":{\"module_script_args\":{\"operation\":\"RollerUnLoad\"},\"module_script_name\":\"huaxin(do)(1).py\",\"script_stage\":2}}"}]"""
      }
    },
    TaskComponentDef(
        "extra", "xian:outPass", "出库:等待上位机放行信号", "", false, listOf(
        TaskComponentParam("location", "位置", "string")
    ), false) { component, ctx ->
      val location = parseComponentParamValue("location", component, ctx) as String
      if (passLocations.contains(location)) {
        logger.debug("${ctx.task.def}-${ctx.task.id}: get pass signal succeed")
        val signal = passLocations.remove(location)
        if (signal) {
          logger.debug("remove $location from pass list")
        } else logger.warn("already removed $location from pass list")
      } else throw BusinessError("等待放行")
    },
    TaskComponentDef(
        "extra", "xian:inReady", "入库:通知上位机AGV到位", "", false, listOf(
        TaskComponentParam("location", "位置", "string")
    ), false) { component, ctx ->
      logger.debug("srd send ready signal...")
      val location = parseComponentParamValue("location", component, ctx) as String
      val station = getStationIdByLocation(location)
      val res = mesHttpClient.inReady(station).execute()
      val body = mapper.readValue(res.body(), MesResponse::class.java)
      val code = res.code()
      if (code != 200 || body.code != 200) {
        Thread.sleep(2000)
        logger.warn("response $code for body [$body] url: ${CUSTOM_CONFIG.mesUrl}seer/instockbegin")
        if (code != 200) throw BusinessError("response $code")
        if (body.code != 200) throw BusinessError(body.message)
      }
      waitingRolledMap[location] = true
      logger.debug("received in ready signal, $body")
    },
    TaskComponentDef(
        "extra", "xian:inRolled", "入库:等待辊筒转动", "", false, listOf(
        TaskComponentParam("location", "位置", "string")
    ), false) { component, ctx ->
      logger.debug("wait rolled signal...")
      val location = parseComponentParamValue("location", component, ctx) as String
//      waitingRolledMap[location] = true
      if (!rolledLocations.contains(location)) throw BusinessError("等待上位机信号")
      logger.debug("received rolled signal of $location")
      val signal = rolledLocations.remove(location)
      waitingRolledMap[location] = false
      if (signal) {
        logger.debug("remove $location from rolled list")
      } else {
        logger.warn("already removed $location from rolled list")
      }
    },
    TaskComponentDef(
        "extra", "xian:inUnloaded", "入库:通知上位机AGV卸载完成", "", false, listOf(
        TaskComponentParam("location", "位置", "string")
    ), false) { component, ctx ->
      logger.debug("srd send loaded signal...")
      val location = parseComponentParamValue("location", component, ctx) as String
      val station = getStationIdByLocation(location)
      val code = mesHttpClient.inUnloaded(station).execute().code()
      if (code != 200) {
        logger.warn("response $code for ${CUSTOM_CONFIG.mesUrl}seer/1F/in/agv-unloaded")
        throw BusinessError("response $code")
      }
      waitingPassMap[location] = true
      logger.debug("mes has received unloaded signal, AS can stop roller")
    },
    TaskComponentDef(
        "extra", "xian:inPass", "入库:等待上位机放行信号", "", false, listOf(
        TaskComponentParam("location", "位置", "string")
    ), false) { component, ctx ->
      val location = parseComponentParamValue("location", component, ctx) as String
//      waitingPassMap[location] = true
      if (passLocations.contains(location)) {
        logger.debug("${ctx.task.def}-${ctx.task.id}: get pass signal succeed")
        val signal = passLocations.remove(location)
        waitingPassMap[location] = false
        if (signal) {
          logger.debug("remove $location from pass list")
        } else {
          logger.warn("already removed $location from pass list")
        }
      } else throw BusinessError("等待放行")
    },
    TaskComponentDef(
        "extra", "xian:go", "放行", "", false, listOf(
    ), false) { _, ctx ->
//      val vehicleName = ctx.task.transports[ctx.transportIndex - 1].processingRobot
      val vehicleName =
          if (ctx.task.def.contains("AS")) ctx.task.transports[ctx.transportIndex - 1].processingRobot
      else ctx.task.transports[0].processingRobot

      if (!vehicleName.isNullOrBlank()) {
        checkFromMap[vehicleName] = true
        if (checkGoMap[vehicleName] != true) throw BusinessError("等待放行！！")
        checkFromMap[vehicleName] = false
        checkGoMap[vehicleName] = false
        checkEndMap[vehicleName] = false
      } else {
        throw BusinessError("获取AGV失败，等待放行！！")
      }
    },
    TaskComponentDef(
        "extra", "xian:end", "归位", "", false, listOf(
    ), false) { _, ctx ->
      val vehicleName = ctx.transport?.processingRobot
      if (!vehicleName.isNullOrBlank()) {
        checkToMap[vehicleName] = true
        if (checkEndMap[vehicleName] != true) throw BusinessError("等待归位！！")
//        checkToMap[vehicleName] = false
//        checkEndMap[vehicleName] = false
        checkGoMap[vehicleName] = false
      } else {
        throw BusinessError("获取AGV失败，等待归位！！")
      }
    },
    TaskComponentDef(
        "extra", "xian:check", "确认归位", "", false, listOf(
    ), false) { _, ctx ->
      val vehicleName = ctx.task.transports[ctx.transportIndex - 1].processingRobot
      if (!vehicleName.isNullOrBlank()) {
        if (checkEndMap[vehicleName] != true) {
          checkToMap[vehicleName] = true
          throw BusinessError("等待归位！！")
        } else {
          logger.debug("确认归位")
          checkToMap[vehicleName] = false
          checkEndMap[vehicleName] = false
        }
      } else {
        throw BusinessError("获取AGV失败，等待归位！！")
      }
    },
    TaskComponentDef(
        "extra", "xian:checkIds", "检查多个库位ID", "", false, listOf(
        TaskComponentParam("ids", "ids", "string")
    ), true) { component, ctx ->
      val ids = parseComponentParamValue("ids", component, ctx) as List<String>
      if (ids.isEmpty()) throw BusinessError("起点/终点不能为空!!")
      val ll = mutableListOf<StoreSite>()
      ids.forEach { id ->
        ll.add(StoreSiteService.getExistedStoreSiteById(id))

      }
      ctx.setRuntimeVariable(component.returnName, ll)
    }
  )
}