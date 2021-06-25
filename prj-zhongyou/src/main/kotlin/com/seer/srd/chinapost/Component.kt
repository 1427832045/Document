package com.seer.srd.chinapost

import com.seer.srd.BusinessError
import com.seer.srd.chinapost.Services.getModBusSiteBySiteId
import com.seer.srd.chinapost.plc.CommandParams
import com.seer.srd.chinapost.plc.PlcService
import com.seer.srd.robottask.component.TaskComponentDef
import com.seer.srd.robottask.component.TaskComponentParam
import com.seer.srd.robottask.component.parseComponentParamValue
import com.seer.srd.storesite.StoreSiteService
import org.slf4j.LoggerFactory

object ExtraComponent {

  private val logger = LoggerFactory.getLogger(ExtraComponent::class.java)

//  private val cdMap: Map<String, Boolean> = mapOf("fullTray2Roller" to false)

  val extraComponents: List<TaskComponentDef> = listOf(
      TaskComponentDef(
          "extra", "chinaPost:find", "按库位类型找到一个空库位", "", false, listOf(
          TaskComponentParam("type", "类型", "string")
      ), true) { component, ctx ->
        val type = parseComponentParamValue("type", component, ctx) as String
        val sites = StoreSiteService.listStoreSites().filter { it.type == type && !it.filled && !it.locked}
        if (sites.isEmpty()) throw BusinessError("【$type】没有可用空库位")
        ctx.setRuntimeVariable(component.returnName, sites[0])
      },
      TaskComponentDef(
          "extra", "chinaPost:find2", "检查库位空未锁定", "", false, listOf(
          TaskComponentParam("siteId", "库位ID", "string")
      ), true) { component, ctx ->
        val siteId = parseComponentParamValue("siteId", component, ctx) as String
          val site = getModBusSiteBySiteId(siteId, CUSTOM_CONFIG.enabled)
//        val site = StoreSiteService.getExistedStoreSiteById(siteId)
        if (site.filled) throw BusinessError("${siteId}非空！！")
        if (site.locked) throw BusinessError("${siteId}已被任务锁定！！")
        ctx.setRuntimeVariable(component.returnName, site)
      },
      TaskComponentDef(
          "extra", "chinaPost:find3", "检查库位满未锁定", "", false, listOf(
          TaskComponentParam("siteId", "库位ID", "string")
      ), true) { component, ctx ->
        val siteId = parseComponentParamValue("siteId", component, ctx) as String
          val site = getModBusSiteBySiteId(siteId, CUSTOM_CONFIG.enabled)
//        val site = StoreSiteService.getExistedStoreSiteById(siteId)
        if (!site.filled) throw BusinessError("${siteId}空！！")
        if (site.locked) throw BusinessError("${siteId}已被任务锁定！！")
        ctx.setRuntimeVariable(component.returnName, site)
      },
      TaskComponentDef(
          "extra", "chinaPost:go", "检查放行", "", false, listOf(
      ), false) { _, _ ->
        if (CUSTOM_CONFIG.plcEnabled) {
          logger.debug("开始检查放行...")
          checkPass()
        } else {
          logger.debug("skip check pass ...")
        }
      },
      TaskComponentDef(
          "extra", "chinaPost:onPosition", "到位", "", false, listOf(
      ), false) { _, _ ->
        if (CUSTOM_CONFIG.plcEnabled) {
          logger.debug("start on position ...")
          checkResCode11001()
        } else {
          logger.debug("skip on position ...")
        }
      },
      TaskComponentDef(
          "extra", "chinaPost:cd", "CD", "", false, listOf(
      ), false) { _, _ ->
        logger.debug("start cd ...")

      },
      TaskComponentDef(
          "extra", "chinaPost:getOneCK", "获取一个终点库位", "", false, listOf(
          TaskComponentParam("siteId", "库位ID", "string")
      ), true) { component, ctx ->
        val siteId = parseComponentParamValue("siteId", component, ctx) as String
        val site = Services.getModBusSiteBySiteId(siteId, CUSTOM_CONFIG.enabled)
        if (site.type == "人工出库口") {
          if (site.filled || site.locked) {
            val another = StoreSiteService.listStoreSites().filter { it.type == site.type && it.id != siteId}
            another.forEach {
              val s = Services.getModBusSiteBySiteId(it.id, CUSTOM_CONFIG.enabled)
              if (!s.filled && !s.locked) {
                ctx.setRuntimeVariable(component.returnName, s)
                return@TaskComponentDef
              }
            }
          }
        }
        ctx.setRuntimeVariable(component.returnName, site)
      },
      TaskComponentDef(
          "extra", "chinaPost:checkSiteLocked", "锁定使用库位", "", false, listOf(
          TaskComponentParam("siteId", "库位ID", "string")
      ), false) { component, ctx ->
        val siteId = parseComponentParamValue("siteId", component, ctx) as String
        val site = Services.getModBusSiteBySiteId(siteId, CUSTOM_CONFIG.enabled)
        if (site.locked) throw BusinessError("库位【$siteId】已被任务锁定!!")
        StoreSiteService.lockSiteIfNotLock(siteId, ctx.task.id, "locked from ${ctx.task.def}")
      }
  )

  @Synchronized
  private fun checkPass() {
    val pd = PlcService.getPlcDeviceByName(CUSTOM_CONFIG.plcDeviceList[0].id)
    pd.sendCommand(CommandParams(1000))
    var count = 0

    val interval = CUSTOM_CONFIG.timeout11000 / 100
    while(count < interval){
      count ++
      Thread.sleep(100)

      val req = pd.getReqCmdAndFlowNo()
      val rep = pd.getRepCmdAndFlowNo()
      if (req[1000] == rep[11000]) {
        logger.info("checkPass 已响应。")
        break
      }
    }
    if (count >= interval) throw BusinessError("request timeout ${CUSTOM_CONFIG.timeout11000}ms")
    if(!pd.passed()) throw BusinessError("未放行")
    logger.info("已放行")
    pd.resetPass()
  }

  @Synchronized
  private fun checkResCode11001() {
    val pd = PlcService.getPlcDeviceByName(CUSTOM_CONFIG.plcDeviceList[0].id)
    pd.sendCommand(CommandParams(1001, true))
    var count = 0

    val interval = CUSTOM_CONFIG.timeout11001 / 100
    while(count < interval){
      count ++
      Thread.sleep(100)

      val req = pd.getReqCmdAndFlowNo()
      val res = pd.getRepCmdAndFlowNo()
      if (req[1001] == res[11001]) {
        logger.info("checkResCode11001 已响应。")
        break
      }
    }
    if (count >= interval) throw BusinessError("request timeout ${CUSTOM_CONFIG.timeout11001}ms")
    if(pd.getResCode11001() != 0) throw BusinessError("on position error !!")
    logger.info("on position success!!")
    pd.resetResCode11001()
  }
}
