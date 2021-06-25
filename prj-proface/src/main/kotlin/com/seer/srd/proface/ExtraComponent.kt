package com.seer.srd.proface

import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.operator.AlertInOperator
import com.seer.srd.proface.ProFaceApp.transferTaskMap
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.component.TaskComponentDef
import com.seer.srd.robottask.component.TaskComponentParam
import com.seer.srd.robottask.component.parseComponentParamValue
import com.seer.srd.storesite.StoreSite
import com.seer.srd.storesite.StoreSiteService
import org.litote.kmongo.eq
import org.litote.kmongo.setValue
import org.slf4j.LoggerFactory
import java.time.Instant
import javax.mail.Store

object ExtraComponent {

  private val logger = LoggerFactory.getLogger(ExtraComponent::class.java)

  val extraComponent = listOf(
      TaskComponentDef(
          "extra", "SchneiderWuXi:updateSite", "更新库位", "", false, listOf(
          TaskComponentParam("site", "库位ID", "string"),
          TaskComponentParam("type", "类型", "string"),
          TaskComponentParam("ps", "产线", "string")
      ), false) { component, ctx ->
        val siteId = parseComponentParamValue("site", component, ctx) as String
        val type = parseComponentParamValue("type", component, ctx) as String
        val ps = parseComponentParamValue("ps", component, ctx) as String
        val site = StoreSiteService.getExistedStoreSiteById(siteId)
        if (site.locked) throw BusinessError("库位[$siteId]正在执行任务")
        val filled = type != "clear"
        val label = if (filled && ps.isNotBlank()) Instant.now().toEpochMilli().toString() else ""
        val newSite = site.copy(filled = filled, content = ps, label = label)
//            if (ps.isNotBlank() && ps != site.content) site.copy(filled = filled, content = ps, label = label)
//            else  site.copy(filled = filled, label = label)

        if (ps.isNotBlank() && ps != site.content) logger.debug("绑定库位${site.id}到产线$ps")
        StoreSiteService.replaceSitesAndRetainOthers(listOf(newSite), "更新库位")

//        if (type == "clear") StoreSiteService.changeSiteEmptyAndRetainContent(siteId, "change from task")
//        else {
//          val newSite =
//              if (ps.isNotBlank() && site.content != ps) {
//                logger.debug("准备绑定库位${site.id}到产线$ps")
//                site.copy(filled = true, content = ps, label = Instant.now().toEpochMilli().toString())
//              }
//              else site.copy(filled = true, label = Instant.now().toEpochMilli().toString())
//          StoreSiteService.replaceSitesAndRetainOthers(listOf(newSite), "更新库位")
//        }
      },
      TaskComponentDef(
          "extra", "SchneiderWuXi:findOneSite", "找到一个类型的库位", "", false, listOf(
          TaskComponentParam("filled", "满(1)/空(0)", "int"),
          TaskComponentParam("type", "类型", "string")
      ), true) { component, ctx ->
        val filled = parseComponentParamValue("filled", component, ctx) as Int == 1
        val type = parseComponentParamValue("type", component, ctx) as String
        val sites = StoreSiteService.listStoreSites().filter { it.type == type && it.filled == filled && !it.locked }
        if (sites.isEmpty()) throw BusinessError("类型[$type]没有可用库位")
        ctx.setRuntimeVariable(component.returnName, sites[0])
      },
      TaskComponentDef(
          "extra", "SchneiderWuXi:checkCreate", "检查是否能生成", "", false, listOf(
      ), false) { _, ctx ->
        val psId = ctx.task.persistedVariables["psId"] as String
        if (transferTaskMap.containsKey(psId)) throw BusinessError("产线【${psId}】有任务未完成")
        transferTaskMap[psId] = ctx.task.id
      },
      TaskComponentDef(
          "extra", "SchneiderWuXi:checkSubmit", "检查是否可下发", "", false, listOf(
      ), false) { _, ctx ->
        if (ctx.task.persistedVariables["canSend"] != true) {
          // 检查绑定这条产线的仓库库位
          val ws = ctx.task.persistedVariables["psId"] as String
          var msg = ""

          // 检索绑定了下单产线的库位
          val psSites = StoreSiteService.listStoreSites().filter { it.content == ws }
          if (psSites.isEmpty()) {
            msg = "产线【${ws}】叫料失败,绑定关系未完成!!"
            if (ctx.task.persistedVariables[msg] != true) {
              AlertInOperator.alertInOperator(AlertInOperator(message = msg, toWorkStations = listOf(ws, "三楼仓库", "四楼仓库", "五楼仓库"), toWorkTypes = emptyList(), toAll = false))
              ctx.task.persistedVariables[msg] = true
            }
            throw BusinessError(msg)
          }

          // 查找所有绑定该产线的仓库
          val types = psSites.map { it.type }.distinct().toMutableList()

          val availableSites = mutableListOf<StoreSite>()
          var sites: List<StoreSite>

          // 检查每一层的绑定产线ws的库位，如果绑定该产线的库位全部为空或锁定则提示手持端
          for (type in types) {
            sites = psSites
                .filter { it.type == type && !it.locked && it.filled }
            if (sites.isEmpty()) {
              msg += "【${type}匹配产线${ws}的物料已用完】"
              if (ctx.task.persistedVariables["${type}匹配产线${ws}的物料已用完"] != true) {
                AlertInOperator.alertInOperator(AlertInOperator(message = "${type}匹配产线${ws}的物料已用完", toWorkStations = listOf(type), toWorkTypes = emptyList(), toAll = false))
                ctx.task.persistedVariables["${type}匹配产线${ws}的物料已用完"] = true
              }
            }
            else availableSites.addAll(sites)
          }
          if (availableSites.isEmpty()) throw BusinessError(msg)

          // 以时间戳排序
          availableSites.sortBy { it.label }

          // 检索一个空库位(放空托盘的库位1-6)
          // 检查可用满物料所在仓库是否有能放空托盘的库位,type为仓库
          var emptySites: List<StoreSite>
          for (type in types) {
            emptySites = Services.listSiteByType(type, false).filter { !it.locked && !it.filled }
            if (emptySites.isEmpty()) {
              msg += "【${type}无可用空车位】"
              if (ctx.task.persistedVariables["${type}无可用空托盘库位"] != true) {
                AlertInOperator.alertInOperator(AlertInOperator("${type}无可用空托盘库位", listOf(type), emptyList(), false))
                ctx.task.persistedVariables["${type}无可用空托盘库位"] = true
              }
              // 将没有空托盘库位的仓库剔除
              availableSites.removeIf{ it.type == type }
            }
          }
          // 先删除没有满物料的库位，在删除没有空托盘位置的库位，得到现在的availableSites
          if (availableSites.isEmpty()) throw BusinessError(msg)
          // 根据label(物料补充的时间戳)排序
          availableSites.sortBy { it.label }
          // 检索一个满物料库位和对应的放空托盘的库位
          emptySites = Services.listSiteByType(availableSites[0].type, false).filter { !it.locked && !it.filled }

          ctx.task.persistedVariables["filledId"] = availableSites[0].id
          ctx.task.persistedVariables["emptyId"] = emptySites[0].id
          ctx.task.persistedVariables["storeId"] = availableSites[0].type
          MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq ctx.task.id, setValue(RobotTask::persistedVariables, ctx.task.persistedVariables))
//          ctx.setRuntimeVariable(component.returnName, mapOf("filledId" to availableSites[0].id, "emptyId" to emptySites[0].id, "storeId" to availableSites[0].type))

          ctx.task.persistedVariables["canSend"] = true
        }
      },
      TaskComponentDef(
          "extra", "SchneiderWuXi:findOneSiteNew", "返回一个仓库的满料库位ID、一个空库位ID和仓库区域类型", "filledId、emptyId和storeId", false, listOf(
          TaskComponentParam("ws", "产线库位Id", "string")
      ), true) { component, ctx ->
        // 检查绑定这条产线的仓库库位
        val ws = parseComponentParamValue("ws", component, ctx) as String
        var msg = ""

        // 检索绑定了下单产线的库位
        val psSites = StoreSiteService.listStoreSites().filter { it.content == ws }
        if (psSites.isEmpty()) {
          if (ctx.task.persistedVariables["warning"] != true) {
            ctx.task.persistedVariables["warning"] = true
//            msg = "产线【${ws}】绑定关系未完成无法叫料!!"
            msg = "产线【${ws}】叫料失败,绑定关系未完成!!"
            ctx.task.persistedVariables[msg] = true
            AlertInOperator.alertInOperator(AlertInOperator(message = msg, toWorkStations = listOf(ws, "三楼仓库", "四楼仓库", "五楼仓库"), toWorkTypes = emptyList(), toAll = false))

            ctx.setRuntimeVariable(component.returnName, mapOf("filledId" to "", "emptyId" to "", "storeId" to ""))

//          throw BusinessError(msg)
          }

        // 分支2：psSites不是空的
        } else {
          // 查找所有绑定该产线的仓库
          val types = psSites.map { it.type }.distinct().toMutableList()

          val availableSites = mutableListOf<StoreSite>()
          var sites: List<StoreSite>

          // 检查每一层的绑定产线ws的库位，如果绑定该产线的库位全部为空或锁定则提示手持端
          for (type in types) {
            sites = psSites
                .filter { it.type == type && !it.locked && it.filled }
            if (sites.isEmpty()) {
              msg += "【${type}匹配产线${ws}的物料已用完】"
              AlertInOperator.alertInOperator(AlertInOperator(message = "${type}匹配产线${ws}的物料已用完", toWorkStations = listOf(type), toWorkTypes = emptyList(), toAll = false))
              ctx.task.persistedVariables["${type}匹配产线${ws}的物料已用完"] = true
            }
            else availableSites.addAll(sites)
          }
          if (availableSites.isEmpty()) {
//            throw BusinessError(msg)
            AlertInOperator.alertInOperator(AlertInOperator(message = msg, toWorkStations = listOf(ws), toWorkTypes = emptyList(), toAll = false))
            ctx.setRuntimeVariable(component.returnName, mapOf("filledId" to "", "emptyId" to "", "storeId" to ""))

          // 分支3：availableSites不是空的
          } else {
            // 以时间戳排序
            availableSites.sortBy { it.label }

            // 检索一个空库位(放空托盘的库位1-6)
            // 检查可用满物料所在仓库是否有能放空托盘的库位,type为仓库
            var emptySites: List<StoreSite>
            for (type in types) {
              emptySites = Services.listSiteByType(type, false).filter { !it.locked && !it.filled }
              if (emptySites.isEmpty()) {
                msg += "【${type}无可用空车位】"
                AlertInOperator.alertInOperator(AlertInOperator("${type}无可用空托盘库位", listOf(type), emptyList(), false))
                ctx.task.persistedVariables["${type}无可用空托盘库位"] = true
                // 将没有空托盘库位的仓库剔除
                availableSites.removeIf{ it.type == type }
              }
            }
            // 先删除没有满物料的库位，在删除没有空托盘位置的库位，得到现在的availableSites
            if (availableSites.isEmpty()) {

              AlertInOperator.alertInOperator(AlertInOperator(msg, listOf(ws), emptyList(), false))
              ctx.setRuntimeVariable(component.returnName, mapOf("filledId" to "", "emptyId" to "", "storeId" to ""))
//              throw BusinessError(msg)

            // 分支4：最终availableSites不是空的
            } else {
              // 根据label(物料补充的时间戳)排序
              availableSites.sortBy { it.label }
              // 检索一个满物料库位和对应的放空托盘的库位
              emptySites = Services.listSiteByType(availableSites[0].type, false).filter { !it.locked && !it.filled }

              ctx.setRuntimeVariable(component.returnName, mapOf("filledId" to availableSites[0].id, "emptyId" to emptySites[0].id, "storeId" to availableSites[0].type))
              ctx.task.persistedVariables["canSend"] = true
            }
          }
        }
      },
      TaskComponentDef(
          "extra", "SchneiderWuXi:findOneSiteNew2", "返回一个仓库的满料库位ID、一个空库位ID和仓库区域类型2", "filledId、emptyId和storeId", false, listOf(
          TaskComponentParam("ws", "产线库位Id", "string")
      ), true) { component, ctx ->
        // 检查绑定这条产线的仓库库位
        val ws = parseComponentParamValue("ws", component, ctx) as String
        var msg = ""

        // 检索绑定了下单产线的库位
        val psSites = StoreSiteService.listStoreSites().filter { it.content == ws }
        if (psSites.isEmpty()) {
          msg = "产线绑定关系未完成无法叫料"
          throw BusinessError(msg)
        }

        // 查找所有绑定该产线的仓库
        val types = psSites.map { it.type }.distinct().toMutableList()

        val availableSites = mutableListOf<StoreSite>()
        var sites: List<StoreSite>

        // 检查每一层的绑定产线ws的库位，如果绑定该产线的库位全部为空或锁定则提示手持端
        for (type in types) {
          sites = psSites
              .filter { it.type == type && !it.locked && it.filled }
          if (sites.isEmpty()) {
            msg += "【${type}匹配产线${ws}的物料已用完】"
            AlertInOperator.alertInOperator(AlertInOperator(message = "${type}匹配产线${ws}的物料已用完", toWorkStations = listOf(type), toWorkTypes = emptyList(), toAll = false))
          }
          else availableSites.addAll(sites)
        }
        if (availableSites.isEmpty()) throw BusinessError(msg)

        // 以时间戳排序
        availableSites.sortBy { it.label }

        // 检索一个空库位(放空托盘的库位1-6)
        // 检查可用满物料所在仓库是否有能放空托盘的库位,type为仓库
        var emptySites: List<StoreSite>
        for (type in types) {
          emptySites = Services.listSiteByType(type, false).filter { !it.locked && !it.filled }
          if (emptySites.isEmpty()) {
            msg += "【${type}无可用空车位】"
            AlertInOperator.alertInOperator(AlertInOperator("${type}无可用空托盘库位", listOf(type), emptyList(), false))
            // 将没有空托盘库位的仓库剔除
            availableSites.removeIf{ it.type == type }
          }
        }
        // 先删除没有满物料的库位，在删除没有空托盘位置的库位，得到现在的availableSites
        if (availableSites.isEmpty()) throw BusinessError(msg)
        // 根据label(物料补充的时间戳)排序
        availableSites.sortBy { it.label }
        // 检索一个满物料库位和对应的放空托盘的库位
        emptySites = Services.listSiteByType(availableSites[0].type, false).filter { !it.locked && !it.filled }

        ctx.setRuntimeVariable(component.returnName, mapOf("filledId" to availableSites[0].id, "emptyId" to emptySites[0].id, "storeId" to availableSites[0].type))

      },
      // 设置库位为空并且设置content为非空
      TaskComponentDef(
          "extra", "SchneiderWuXi:setSiteEmptyWithContent", "保留并设置库位为空", "", false, listOf(
          TaskComponentParam("siteId", "库位Id", "string")
      ), false) { component, ctx ->
        val siteId = parseComponentParamValue("siteId", component, ctx) as String
        StoreSiteService.changeSiteEmptyAndRetainContent(siteId, "取走物料")
      }
//      // 设置库位为满并且设置content非空
//      TaskComponentDef(
//          "extra", "SchneiderWuXi:setSiteFilledWithContent", "保留绑定的产线并设置库位为占用", "把物料运输到产线", false, listOf(
//          TaskComponentParam("siteId", "库位Id", "string")
//      ), false) { component, ctx ->
//        val siteId = parseComponentParamValue("siteId", component, ctx) as String
//        StoreSiteService.changeSiteEmptyAndRetainContent(siteId, "取走物料")
//      }
  )
}