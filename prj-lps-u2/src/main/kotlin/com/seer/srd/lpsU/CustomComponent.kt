package com.seer.srd.lpsU

import com.seer.srd.BusinessError
import com.seer.srd.SkipCurrentTransport
import com.seer.srd.db.MongoDBManager
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.RobotTaskService
import com.seer.srd.robottask.RobotTaskState
import com.seer.srd.robottask.RobotTransportState
import com.seer.srd.robottask.component.TaskComponentDef
import com.seer.srd.robottask.component.TaskComponentParam
import com.seer.srd.robottask.component.parseComponentParamValue
import com.seer.srd.storesite.StoreSiteService
import org.litote.kmongo.eq
import org.litote.kmongo.setValue
import org.slf4j.LoggerFactory

object ExtraComponent {

  private val logger = LoggerFactory.getLogger(ExtraComponent::class.java)

  val extraComponents = listOf(
      TaskComponentDef(
          "extra", "updateSites", "更新库位", "", false, listOf(
      ), false) {_, ctx ->

        //        todo 使用OCR识别就务必删掉下面这行，这里是因为现在相机不能用，所以要暂时把识别的数据都删掉
//        UrTcpServer.siteToMagMap.clear()

        val carNum = ctx.task.persistedVariables["carNum"] as String
        val location = ctx.transport?.stages!![1].location
        val curColumn = location[location.length - 1].toString()

//        Services.saveOcrDataAndSetError(carNum, curColumn)
      },
      TaskComponentDef(
          "extra", "urge-reset", "一键复位", "", false, listOf(
      ), false) { _, _ ->
        Services.resetAll()
      },
      TaskComponentDef(
          "extra", "path", "到料车的路线和位置", "根据列推断路线", false, listOf(
          TaskComponentParam("column", "列", "string")
      ), false) { component, ctx ->
        val carNum = ctx.task.persistedVariables["carNum"] as String? ?: throw IllegalArgumentException("No such param carNum")
        val column = parseComponentParamValue("column", component, ctx) as String? ?: ""
        Services.setPathToCar(carNum, column)
      },
      TaskComponentDef(
          "extra", "path2", "到机台的路线", "", false, listOf(
          TaskComponentParam("value", "上料(1)/下料(2)", "int")
      ), false) { component, ctx ->
        val lineId = ctx.task.persistedVariables["lineId"] as String
        val upOrDown = parseComponentParamValue("value", component, ctx) as Int
        Services.setPathToLine(lineId, upOrDown)
      },

      TaskComponentDef(
          "extra", "checkNeededSites", "二次检查物料", "", false, listOf(
      ), false) { _, ctx ->
        var sites = ctx.task.persistedVariables["neededSites"] as List<String>
        val column = ctx.task.persistedVariables["column"] as String
        val carNum = ctx.task.persistedVariables["carNum"] as String
        val lineId = ctx.task.persistedVariables["lineId"] as String
        Services.operating = true
        if (Services.checking) throw BusinessError("等待最新一次Vision数据更新")
        val availableSites = StoreSiteService.listStoreSites().filter { it.id.contains("CART-UP-$carNum-$column") && it.filled }.map { it.id }.sorted()

        if (availableSites.isEmpty()) {
          logger.warn("上料车${carNum}的${column}列二次确认数据为空，终止任务")
          val transport = if (column == "A") ctx.task.transports[2] else ctx.task.transports[4]
          RobotTaskService.updateTransportStateToFinal(RobotTransportState.Skipped, "二次确认为空", transport, ctx.task)
          val agvSiteIds = StoreSiteService.listStoreSites().filter{ it.id.contains("AGV") && it.locked }.map{ it.id }
          StoreSiteService.changeSitesLockedByIds(agvSiteIds, false, ctx.task.id, "二次确认为空")
          throw SkipCurrentTransport("二次确认为空")
//          RobotTaskService.abortTask(ctx.task.id, immediate = true, disableVehicle = false)
        } else {
          if (availableSites.intersect(sites).size != sites.size) {
            sites = availableSites
            ctx.task.persistedVariables["neededSites"] = sites
            ctx.task.persistedVariables["upMatNum"] = sites.size
            MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq ctx.task.id, setValue(RobotTask::persistedVariables, ctx.task.persistedVariables))
          }
          MongoDBManager.collection<TaskExtraParam>().insertOne(
              TaskExtraParam(taskId = ctx.task.id, lineId = lineId, type = Services.getProductTypeOfLine(lineId) ?: "", matNum = sites.size.toString())
          )
        }
      },

      TaskComponentDef(
          "extra", "takeFromUpCar", "从上料车取料", "", false, listOf(
      ), false) { _, ctx ->
        val sites = ctx.task.persistedVariables["neededSites"] as List<String>
        val carNum = ctx.task.persistedVariables["carNum"] as String
        Services.takeFromCar2(sites, carNum, ctx.task)
        Services.operating = false
      },
      TaskComponentDef(
          "extra", "putOnline", "往机台放料", "", false, listOf(
      ), false) { _, ctx ->
        val lineId = ctx.task.workStations[0]
        Services.putOnLine2(lineId, ctx.task)
      },

      TaskComponentDef(
          "extra", "takeFromLine", "从机台取料", "", false, listOf(
          TaskComponentParam("num", "数量", "int")
      ), false) { component, ctx ->
        val lineId = ctx.task.persistedVariables["lineId"] as String
        val num = if (CUSTOM_CONFIG.downSize != 0) CUSTOM_CONFIG.downSize
        else parseComponentParamValue("num", component, ctx) as Int
        Services.takeFromLine2(lineId, num, ctx.task)
      },

      TaskComponentDef(
          "extra", "lp1:takeFromLine", "变更:从机台取料", "", false, listOf(
      ), false) { _, ctx ->
        val lineId = ctx.task.persistedVariables["lineId"] as String
        val task = ctx.task
        Services.takeFromLine(task, lineId)
      },

      TaskComponentDef(
          "extra", "putOnCar", "往料车放料", "", false, listOf(
      ), false) { _, ctx ->
        val lineId = ctx.task.persistedVariables["lineId"] as String
        val column = ctx.task.persistedVariables["column"] as String
        val carNum = ctx.task.persistedVariables["carNum"] as String
        val neededSites = (ctx.task.persistedVariables["neededSites"] as List<String>).sortedByDescending { it }
        Services.putOnCar2(lineId, carNum, column, neededSites, ctx.task)
      },
      TaskComponentDef(
          "extra", "canPutOnMachine", "检查是否可以往机台放料", "", false, listOf(
      ), false) { _, ctx ->
        Services.checkLineUp(ctx)
      },

      TaskComponentDef(
          "extra", "canTakeFromLine", "检查是否可以从机台取料", "", false, listOf(
      ), false) { _, ctx ->
        Services.checkLineDown(ctx)
      },

      TaskComponentDef(
          "extra", "canPutOnCar", "检查是否可以往空料车放料", "", false, listOf(
      ), false) { _, ctx ->
        //        Services.checkDownCar(ctx)
        if (CUSTOM_CONFIG.multiCar) Services.checkDownCar2(ctx)
        else Services.checkDownCar(ctx)
      },

      TaskComponentDef(
          "extra", "getData", "OCR获取数据", "", false, listOf(
      ), false) { _, _ ->
        Services.getOcrData()
      }
  )
}