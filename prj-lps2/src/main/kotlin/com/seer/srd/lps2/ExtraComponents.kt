package com.seer.srd.lps2

import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.http.WebSocketManager
import com.seer.srd.lps2.LPS2App.mockDetail
import com.seer.srd.lps2.Services.downSiteToAgvSiteMap
import com.seer.srd.lps2.Services.upSiteToAgvSiteMap
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.RobotTaskService
import com.seer.srd.robottask.RobotTransportState
import com.seer.srd.robottask.component.TaskComponentDef
import com.seer.srd.robottask.component.TaskComponentParam
import com.seer.srd.robottask.component.parseComponentParamValue
import com.seer.srd.route.getVehicleDetails
import com.seer.srd.route.service.VehicleService
import com.seer.srd.scheduler.ThreadFactoryHelper
import com.seer.srd.storesite.StoreSiteService
import org.bson.types.ObjectId
import org.litote.kmongo.*
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.Executors

object ExtraComponents {

  private val logger = LoggerFactory.getLogger(ExtraComponents::class.java)

  @Volatile
  private var isOpening = false
  
   val extraComponents: List<TaskComponentDef> = listOf(
      TaskComponentDef(
          "extra", "creteDownTask", "立即创建送料桶任务", "", false, listOf(
      ), false) { _, _ ->
        DownTask.createTask()
        logger.debug("立即创建送料任务")
      },
      TaskComponentDef(
          "extra", "canMove", "检查该取料位是否都取完(不用)", "", false, listOf(
      ), false) { _, ctx ->
        val location = ctx.task.transports[ctx.transportIndex - 1].stages[0].location
        // 当前取料位绑定的工位
        val workStations = Services.getStationsByDownSiteId(location)
        // 当前等待取料的舱位
        val agvSites = Services.getAgvSiteByState(2)
        if (!workStations.isNullOrEmpty() && !agvSites.isNullOrEmpty()) {
          for (site in agvSites) {
            if (site.workStation !in workStations) break
            else {
              logger.debug("等待${site.workStation}取料..")
              throw BusinessError("等待${site.workStation}取料..")
            }
          }
        }
      },
      TaskComponentDef(
          "extra", "canMove2", "检查所有装舱任务是否完成(不用)", "", false, listOf(
      ), false) { _, ctx ->
        val location = ctx.task.persistedVariables["toSite"] as String
//        val upSiteToAgvSite = MongoDBManager.collection<UpSiteToAgvSite>().findOne(UpSiteToAgvSite::upSiteId eq location)
//        if (upSiteToAgvSite != null && !upSiteToAgvSite.agvSiteIds.isNullOrEmpty()) {
//          for (agvSiteId in upSiteToAgvSite.agvSiteIds) {
//            val agvSite = Services.getAgvSiteById(agvSiteId)
//            if (agvSite?.state != 2) throw BusinessError("等待装舱完成...")
//          }
//        }

        val ids = upSiteToAgvSiteMap[location]
        if (!ids.isNullOrEmpty()) {
          for (agvSiteId in ids) {
            val agvSite = Services.getAgvSiteById(agvSiteId)
            if (agvSite?.state != 2) throw BusinessError("等待装舱完成...")
          }
        }
        MongoDBManager.collection<UpSiteToAgvSite>().updateOne(UpSiteToAgvSite::upSiteId eq location,
            set(UpSiteToAgvSite::agvSiteIds setTo emptyList()))
        upSiteToAgvSiteMap.clear()
      },
       TaskComponentDef(
           "extra", "setDO", "设置下一个开舱舱位（不用）", "", false, listOf(
       ), false) { _, ctx ->
         val location = ctx.task.persistedVariables["toSite"] as String
//         val upSiteToAgvSite = MongoDBManager.collection<UpSiteToAgvSite>().findOne(UpSiteToAgvSite::upSiteId eq location)
//         if (upSiteToAgvSite != null && !upSiteToAgvSite.agvSiteIds.isNullOrEmpty()) {
//           for (agvSiteId in upSiteToAgvSite.agvSiteIds) {
         val upSiteToAgvSite = upSiteToAgvSiteMap[location]
         if (!upSiteToAgvSite.isNullOrEmpty()) {
           for (agvSiteId in upSiteToAgvSite) {
             val agvSite = Services.getAgvSiteById(agvSiteId) ?: throw BusinessError("agvSiteInfo: ${agvSiteId}不存在")
             if (agvSite.state != 2) {
               val value = when (agvSiteId) {
                 "B-1" -> {
                   "[{\"key\": \"1\", \"value\": \"false\"}," +
                   "{\"key\": \"2\", \"value\": \"false\"}," +
                   "{\"key\": \"3\", \"value\": \"true\"}," +
                   "{\"key\": \"4\", \"value\": \"false\"}]"
                 }
                 "S-1" -> {
                   "[{\"key\": \"1\", \"value\": \"false\"}," +
                   "{\"key\": \"2\", \"value\": \"true\"}," +
                   "{\"key\": \"3\", \"value\": \"false\"}," +
                   "{\"key\": \"4\", \"value\": \"false\"}]"
                 }
                 "S-2" -> {
                   "[{\"key\": \"1\", \"value\": \"false\"}," +
                   "{\"key\": \"2\", \"value\": \"true\"}," +
                   "{\"key\": \"3\", \"value\": \"true\"}," +
                   "{\"key\": \"4\", \"value\": \"false\"}]"
                 }
                 "S-3" -> {
                   "[{\"key\": \"1\", \"value\": \"true\"}," +
                   "{\"key\": \"2\", \"value\": \"false\"}," +
                   "{\"key\": \"3\", \"value\": \"false\"}," +
                   "{\"key\": \"4\", \"value\": \"false\"}]"
                 }
                 "S-4" -> {
                   "[{\"key\": \"1\", \"value\": \"true\"}," +
                   "{\"key\": \"2\", \"value\": \"false\"}," +
                   "{\"key\": \"3\", \"value\": \"true\"}," +
                   "{\"key\": \"4\", \"value\": \"false\"}]"
                 }
                 else -> ""
               }
               if (value == "") throw BusinessError("打开舱门异常!!")

               ctx.task.transports[ctx.transportIndex + 1].stages[1].properties = value
//               when (ctx.transportIndex) {
//                 0 -> ctx.task.transports[ctx.transportIndex + 1].stages[3].properties = value
//                 1 -> ctx.task.transports[ctx.transportIndex + 1].stages[2].properties = value
//                 else -> ctx.task.transports[ctx.transportIndex + 1].stages[1].properties = value
//               }

               val openSite = MongoDBManager.collection<OpenSiteId>().findOne()
               if (openSite == null)
                 MongoDBManager.collection<OpenSiteId>().insertOne(OpenSiteId(ObjectId(), agvSiteId))
               else {
                 MongoDBManager.collection<OpenSiteId>().updateOne(OpenSiteId::openSiteId eq openSite.openSiteId, set(
                     OpenSiteId::openSiteId setTo agvSiteId))
               }
               val agvSiteInfo = Services.getAgvSiteById(agvSiteId) ?: throw BusinessError("no such agv site info: $agvSiteId")
               ctx.task.persistedVariables["agvSiteInfo"] = agvSiteInfo
               break
             }
           }
         }
       },
       TaskComponentDef(
           "extra", "setDO2", "设置下一个舱位信息", "", false, listOf(
       ), false) { _, ctx ->
         val stations = ctx.task.persistedVariables["stations"] as MutableList<String>
         for (agvSiteId in stations) {
           val value = when (agvSiteId) {
             "B-1" -> {
               "[{\"key\": \"1\", \"value\": \"false\"}," +
                   "{\"key\": \"2\", \"value\": \"false\"}," +
                   "{\"key\": \"3\", \"value\": \"true\"}," +
                   "{\"key\": \"4\", \"value\": \"false\"}]"
             }
             "S-1" -> {
               "[{\"key\": \"1\", \"value\": \"false\"}," +
                   "{\"key\": \"2\", \"value\": \"true\"}," +
                   "{\"key\": \"3\", \"value\": \"false\"}," +
                   "{\"key\": \"4\", \"value\": \"false\"}]"
             }
             "S-2" -> {
               "[{\"key\": \"1\", \"value\": \"false\"}," +
                   "{\"key\": \"2\", \"value\": \"true\"}," +
                   "{\"key\": \"3\", \"value\": \"true\"}," +
                   "{\"key\": \"4\", \"value\": \"false\"}]"
             }
             "S-3" -> {
               "[{\"key\": \"1\", \"value\": \"true\"}," +
                   "{\"key\": \"2\", \"value\": \"false\"}," +
                   "{\"key\": \"3\", \"value\": \"false\"}," +
                   "{\"key\": \"4\", \"value\": \"false\"}]"
             }
             "S-4" -> {
               "[{\"key\": \"1\", \"value\": \"true\"}," +
                   "{\"key\": \"2\", \"value\": \"false\"}," +
                   "{\"key\": \"3\", \"value\": \"true\"}," +
                   "{\"key\": \"4\", \"value\": \"false\"}]"
             }
             else -> ""
           }
           if (value == "") throw BusinessError("打开舱门异常!!")

           if (CUSTOM_CONFIG.load == "load6") ctx.task.transports[ctx.transportIndex + 1].stages[1].properties = value
           else ctx.task.transports[ctx.transportIndex + 1].stages[1].properties = value

           val openSite = MongoDBManager.collection<OpenSiteId>().findOne()
           if (openSite == null)
             MongoDBManager.collection<OpenSiteId>().insertOne(OpenSiteId(ObjectId(), agvSiteId))
           else {
             MongoDBManager.collection<OpenSiteId>().updateOne(OpenSiteId::openSiteId eq openSite.openSiteId, set(
                 OpenSiteId::openSiteId setTo agvSiteId))
           }
           var agvSiteInfo = Services.getAgvSiteById(agvSiteId) ?: throw BusinessError("no such agv site info: $agvSiteId")
           agvSiteInfo = agvSiteInfo.copy(taskId = ctx.task.id)
           ctx.task.persistedVariables["agvSiteInfo"] = agvSiteInfo
           val task = MongoDBManager.collection<RobotTask>().findOne(RobotTask::id eq ctx.task.id)
           if (task != null) {
             task.persistedVariables["agvSiteInfo"] = agvSiteInfo
             MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq task.id, RobotTask::persistedVariables setTo task.persistedVariables)
           }
//           ctx.task.persistedVariables["hasOpened"] = false
//           ctx.task.persistedVariables["hasOpenedError"] = true
           stations.remove(agvSiteId)
           if (task != null) {
             task.persistedVariables["stations"] = stations
             MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq task.id, RobotTask::persistedVariables setTo task.persistedVariables)
           }
           ctx.task.persistedVariables["stations"] = stations
           logger.debug("开舱：$agvSiteId 剩余：$stations")
           break
         }
       },
       TaskComponentDef(
           "extra", "websocket", "弹出开舱信息", "", false, listOf(
       ), false) { _, ctx ->
         val agvSiteInfo = ctx.task.persistedVariables["agvSiteInfo"]
//         executor.submit {
//           if (!isOpening) {
//             isOpening = true
//             while (true) {
//               try {
//                 val hasOpened = ctx.task.persistedVariables["hasOpened"] as Boolean
//                 var hasOpenedError = ctx.task.persistedVariables["hasOpenedError"] as Boolean
//                 if (!hasOpened) {
//                   WebSocketManager.broadcastByWebSocket("open", agvSiteInfo)
//                   ctx.task.persistedVariables["hasOpened"] = true
//                 }
//                 else if (hasOpenedError) {
//                   Thread.sleep(4000)
//                   hasOpenedError = ctx.task.persistedVariables["hasOpenedError"] as Boolean
//                   if (hasOpenedError) WebSocketManager.broadcastByWebSocket("open", agvSiteInfo)
//                 }
//                 else {
//                   if (!hasOpenedError)
//                   logger.warn("${agvSiteInfo?.siteId}, hasOpened=$hasOpened, haOpenedError=$hasOpenedError")
//                 }
//               }catch (e: Exception) {
//                 logger.error("broadcast open error", e)
//               } finally {
//                 isOpening = false
//               }
//             }
//           }
//         }
         WebSocketManager.broadcastByWebSocket("open", agvSiteInfo)
      },
      TaskComponentDef(
          "extra", "checkLoad", "检查所有装舱任务是否完成", "", false, listOf(
      ), false) { _, ctx ->
        val location = ctx.task.persistedVariables["toSite"] as String
//        val upSiteToAgvSite = MongoDBManager.collection<UpSiteToAgvSite>().findOne(UpSiteToAgvSite::upSiteId eq location)
//        var unCompleteNums = 0
//        if (upSiteToAgvSite != null && !upSiteToAgvSite.agvSiteIds.isNullOrEmpty()) {
//          for (agvSiteId in upSiteToAgvSite.agvSiteIds) {
//            val agvSite = Services.getAgvSiteById(agvSiteId)
//            if (agvSite?.state != 2) unCompleteNums++
//          }
//        }
        val ids = upSiteToAgvSiteMap[location]
        var unCompleteNums = 0
        if (!ids.isNullOrEmpty()) {
          for (agvSiteId in ids) {
            val agvSite = Services.getAgvSiteById(agvSiteId)
            if (agvSite?.state != 2) unCompleteNums++
          }
        }
        ctx.task.persistedVariables["unCompleteNums"] = unCompleteNums
      },
      TaskComponentDef(
          "extra", "checkTake", "检查所有取料任务是否完成", "", false, listOf(
          TaskComponentParam("toSite", "目的地", "string")
      ), false) { component, ctx ->
        val location = parseComponentParamValue("toSite", component, ctx) as String
        var unCompleteNums = 0
//        val downSiteToAgvSite = MongoDBManager.collection<DownSiteToAgvSite>().findOne(DownSiteToAgvSite::downSiteId eq location)
//        if (downSiteToAgvSite != null && !downSiteToAgvSite.agvSiteIds.isNullOrEmpty()) {
//          logger.debug("检查所有取料任务是否完成")
//          for (agvSiteId in downSiteToAgvSite.agvSiteIds) {
//            val agvSite = Services.getAgvSiteById(agvSiteId)
//            if (agvSite?.state != 0) unCompleteNums++
//          }
        val ids = downSiteToAgvSiteMap[location]
        if (!ids.isNullOrEmpty()) {
          logger.debug("检查所有取料任务是否完成")
          for (agvSiteId in ids) {
            val agvSite = Services.getAgvSiteById(agvSiteId)
            if (agvSite?.state != 0) unCompleteNums++
          }
        } else {
          logger.debug("跳过检查所有取料任务是否完成")
        }
        ctx.task.persistedVariables["unCompleteNums"] = unCompleteNums
        logger.debug("unCompleteNums: $unCompleteNums")
      },
      TaskComponentDef(
          "extra", "checkAgvLocation", "检查这个任务有几个货舱需要装舱", "", false, listOf(
      ), true) { _, ctx ->
        val location = ctx.task.persistedVariables["toSite"] as String
//        val upSiteToAgvSite = MongoDBManager.collection<UpSiteToAgvSite>().findOne(UpSiteToAgvSite::upSiteId eq location)
//        if (upSiteToAgvSite != null && !upSiteToAgvSite.agvSiteIds.isNullOrEmpty()) {
//          for (agvSiteId in upSiteToAgvSite.agvSiteIds) {
//            ctx.task.persistedVariables[agvSiteId] = agvSiteId
//          }
//        }
        val ids = upSiteToAgvSiteMap[location]
        if (!ids.isNullOrEmpty()) {
          for (agvSiteId in ids) {
            ctx.task.persistedVariables[agvSiteId] = agvSiteId
          }
        }
      },
      TaskComponentDef(
          "extra", "WaitUntilOpen", "等待货舱打开按钮触发,并打开舱门", "", false, listOf(
          TaskComponentParam("offset", "偏移", "int")
      ), false) { component, ctx ->
        val offset = parseComponentParamValue("offset", component, ctx) as Int? ?: 0

        var openSiteId = MongoDBManager.collection<OpenSiteId>().findOne()?.openSiteId

        val toSite = if (ctx.transportIndex < 32) "toSite1"
                          else if (ctx.transportIndex < 64) "toSite2"
                          else if (ctx.transportIndex < 96) "toSite3"
                          else if (ctx.transportIndex < 128) "toSite4"
                          else  "toSite5"

//        val location =
            if (ctx.taskDef?.name == CUSTOM_CONFIG.load) ctx.task.persistedVariables["toSite"] as String else
          ctx.task.persistedVariables[toSite] as String

        // 等待开舱
        logger.debug("等待开舱...")
        while (openSiteId == "" || openSiteId == null) {
          openSiteId = MongoDBManager.collection<OpenSiteId>().findOne()?.openSiteId
        }
        logger.debug("开舱中...")
        ctx.task.persistedVariables["curAgvSite"] = openSiteId
        val value = when (openSiteId) {
          "B-1" -> "[{\"key\": \"${3 + offset}\", \"value\": \"true\"}]"
          "S-1" -> "[{\"key\": \"${2 + offset}\", \"value\": \"true\"}]"
          "S-2" -> "[{\"key\": \"${2 + offset}\", \"value\": \"true\"},{\"key\": \"${3 + offset}\", \"value\": \"true\"}]"
          "S-3" -> "[{\"key\": \"${1 + offset}\", \"value\": \"true\"}]"
          "S-4" -> "[{\"key\": \"${1 + offset}\", \"value\": \"true\"},{\"key\": \"${3 + offset}\", \"value\": \"true\"}]"
          else -> ""
        }
        if (value == "") throw BusinessError("打开舱门异常!!")
//        val index = if (ctx.transportIndex == 2) 2 else 1
        ctx.task.transports[ctx.transportIndex + 1].stages[1].properties = value
      },
      TaskComponentDef(
          "extra", "opened", "开门到位", "", false, listOf(
      ), false) { _, _ ->
        // 读取DI到位信号
        var flag = true
        while (flag) {
          if (CUSTOM_CONFIG.mockDi) {
            if (mockDetail[0]) flag = false
          } else {
            val details = getVehicleDetails()
            for (d in details) {
              val detail = d.value
              val di = detail["DI"] as List<Boolean>
              if (di[0]) flag = false
            }
          }
        }
        logger.debug("开门到位")
      },
      TaskComponentDef(
          "extra", "preClose", "等待当前装舱/取料任务完成, 并关闭舱门", "", false, listOf(
          TaskComponentParam("offset", "偏移", "int"),
          TaskComponentParam("state", "装舱(2)/取料(0)", "int")
      ), false) { component, ctx ->
        val offset = parseComponentParamValue("offset", component, ctx) as Int? ?: 0
        val state = parseComponentParamValue("state", component, ctx) as Int? ?: -1
        if (state == -1) throw BusinessError("state异常, $state")

        val openSiteId = MongoDBManager.collection<OpenSiteId>().findOne()?.openSiteId
        val index = when {
          ctx.transportIndex < 32 -> "toSite1"
          ctx.transportIndex < 64 -> "toSite2"
          ctx.transportIndex < 96 -> "toSite3"
          ctx.transportIndex < 128 -> "toSite4"
          else -> "toSite5"
        }
        val location = if (ctx.taskDef?.name == CUSTOM_CONFIG.load) ctx.task.persistedVariables["toSite"] as String else
          ctx.task.persistedVariables[index] as String
        logger.debug("index: $index")

        while (true) {
          val c = MongoDBManager.collection<AgvSiteInfo>()
          val agvSite = c.findOne(AgvSiteInfo::siteId eq openSiteId)
          if (state == 2 && agvSite != null) {
            if (agvSite.state == state) {
              logger.debug("装舱完成: ${agvSite.siteId}")
              logger.debug("agv site info: ${c.find().toMutableList()}")
              break
            }
            if (agvSite.state == 0) {
              logger.debug("取消装舱: ${agvSite.siteId}")
              logger.debug("agv site info: ${c.find().toMutableList()}")
//              val upSiteToAgvSite = MongoDBManager.collection<UpSiteToAgvSite>().findOne(UpSiteToAgvSite::upSiteId eq location)
//              val ids = upSiteToAgvSite?.agvSiteIds?.filter { it != agvSite.siteId } ?: emptyList()
              val ids = upSiteToAgvSiteMap[location]?.filter { it != openSiteId } ?: emptyList()
              upSiteToAgvSiteMap[location] = ids

              MongoDBManager.collection<UpSiteToAgvSite>().updateOne(UpSiteToAgvSite::upSiteId eq location,
                  set(UpSiteToAgvSite::agvSiteIds setTo ids))
              break
            }
          }
          else if (state == 0 && agvSite != null && (agvSite.state == state || agvSite.state == 1)) {
            logger.debug("取料完成: ${agvSite.siteId}")
            logger.debug("agv site info: ${c.find().toMutableList()}")
            break
          }
        }

        // 取消关门到位，所以关门到位的逻辑放到这里
        if (ctx.taskDef?.name == CUSTOM_CONFIG.load) {
          // 把这个货舱从当前任务的待打开中移除
//          val upSiteToAgvSite = MongoDBManager.collection<UpSiteToAgvSite>().findOne(UpSiteToAgvSite::upSiteId eq location)
//          val ids = upSiteToAgvSite?.agvSiteIds?.filter { it != openSiteId } ?: emptyList()

          val ids = upSiteToAgvSiteMap[location]?.filter{ it != openSiteId } ?: emptyList()
          upSiteToAgvSiteMap[location] = ids
          logger.debug("还有${ids.size}个未完成装舱，last open=$openSiteId")
          MongoDBManager.collection<UpSiteToAgvSite>().updateOne(UpSiteToAgvSite::upSiteId eq location,
              set(UpSiteToAgvSite::agvSiteIds setTo ids))

          MongoDBManager.collection<OpenSiteId>().updateOne(OpenSiteId::openSiteId eq openSiteId, set(
              OpenSiteId::openSiteId setTo ""))
        }
        if (ctx.taskDef?.name == CUSTOM_CONFIG.take) {
          // 把这个货舱从当前任务的待打开中移除
//          val downSiteToAgvSite = MongoDBManager.collection<DownSiteToAgvSite>().findOne(DownSiteToAgvSite::downSiteId eq location)
//          val ids = downSiteToAgvSite?.agvSiteIds?.filter { it != openSiteId } ?: emptyList()
          val ids = downSiteToAgvSiteMap[location]?.filter{ it != openSiteId } ?: emptyList()
          downSiteToAgvSiteMap[location] = ids
          logger.debug("还有${ids.size}个未完成取料，last open=$openSiteId")

          if (!openSiteId.isNullOrBlank()) {
            val records = ctx.task.persistedVariables["toWorkStations"] as List<LinkedHashMap<String, Any>>
            records.forEach {
              if (it["siteId"] == openSiteId || it["_id"] == openSiteId) {
                MongoDBManager.collection<MatRecord>().insertOne(
                    MatRecord(station = it["workStation"] as String, matNum = (it["matNum"] as Int).toLong(), recordOn = Instant.now())
                )
              }
            }
            logger.debug("task records: $records")
          }

          MongoDBManager.collection<DownSiteToAgvSite>().updateOne(DownSiteToAgvSite::downSiteId eq location,
              set(DownSiteToAgvSite::agvSiteIds setTo ids))

          MongoDBManager.collection<OpenSiteId>().updateOne(OpenSiteId::openSiteId eq openSiteId, set(
              OpenSiteId::openSiteId setTo ""))
        }

//        if (ctx.taskDef?.name == CUSTOM_CONFIG.take) {
          val value = when (openSiteId) {
            "B-1" -> {
              "[{\"key\": \"${3 + offset}\", \"value\": \"true\"},{\"key\": \"${4 + offset}\", \"value\": \"true\"}]"
            }
            "S-1" -> {
              "[{\"key\": \"${2 + offset}\", \"value\": \"true\"},{\"key\": \"${4 + offset}\", \"value\": \"true\"}]"
            }
            "S-2" -> {
              "[{\"key\": \"${2 + offset}\", \"value\": \"true\"},{\"key\": \"${3 + offset}\", \"value\": \"true\"},{\"key\": \"${4 + offset}\", \"value\": \"true\"}]"
            }
            "S-3" -> {
              "[{\"key\": \"${1 + offset}\", \"value\": \"true\"},{\"key\": \"${4 + offset}\", \"value\": \"true\"}]"
            }
            "S-4" -> {
              "[{\"key\": \"${1 + offset}\", \"value\": \"true\"},{\"key\": \"${3 + offset}\", \"value\": \"true\"},{\"key\": \"${4 + offset}\", \"value\": \"true\"}]"
            }
            else -> ""
          }
        if (value == "") throw BusinessError("关闭舱门异常!!")
        if (ctx.taskDef?.name == CUSTOM_CONFIG.take)
          ctx.task.transports[ctx.transportIndex + 1].stages[0].properties = value

        if (ctx.taskDef?.name == CUSTOM_CONFIG.load) {
          var unCompleteNums = 0
//          val upSiteToAgvSite = MongoDBManager.collection<UpSiteToAgvSite>().findOne(UpSiteToAgvSite::upSiteId eq location)
//          if (upSiteToAgvSite != null && !upSiteToAgvSite.agvSiteIds.isNullOrEmpty()) {
//            for (agvSiteId in upSiteToAgvSite.agvSiteIds) {
//              val agvSite = Services.getAgvSiteById(agvSiteId)
//              if (agvSite?.state != 2 && (ctx.task.persistedVariables["stations"] as List<String>).contains(agvSiteId)) unCompleteNums++
//            }
//          }
          val ids = upSiteToAgvSiteMap[location]
          if (!ids.isNullOrEmpty()) {
            for (id in ids) {
              val agvSite = Services.getAgvSiteById(id)
              if (agvSite?.state != 2 && (ctx.task.persistedVariables["stations"] as List<String>).contains(id)) unCompleteNums++
            }
          }
          if (unCompleteNums == 0) {
            if (CUSTOM_CONFIG.load == "load5") ctx.task.transports[6].stages[0].properties = value
            else {
              ctx.task.transports[11].stages[0].properties = value
              logger.debug("完成本次任务，关舱：$value")
            }
          } else {
            logger.debug("尚未完成本次装舱任务")
          }
        }

//        }

      },
      TaskComponentDef(
          "extra", "closed", "关门到位", "", false, listOf(
          TaskComponentParam("type", "装舱(2)/取料(0)", "int")
      ), false) { component, ctx ->
        val type = parseComponentParamValue("type", component, ctx) as Int? ?: -1
        // 读取DI到位信号
        var flag = true
        val openSiteId = MongoDBManager.collection<OpenSiteId>().findOne()?.openSiteId
        while (flag) {
          if (CUSTOM_CONFIG.mockDi) {
            if (mockDetail[1]) flag = false
          } else {
            val details = getVehicleDetails()
            for (d in details) {
              val detail = d.value
              val di = detail["DI"] as List<Boolean>
//              logger.debug("DI: ${detail["DI"]}")
              if (di[1]) flag = false
            }
          }
        }
        logger.debug("关门到位")
        if (type == 2) {
          // 把这个货舱从当前任务的待打开中移除
          val location = ctx.task.persistedVariables["toSite"] as String
//          val upSiteToAgvSite = MongoDBManager.collection<UpSiteToAgvSite>().findOne(UpSiteToAgvSite::upSiteId eq location)
//          val ids = upSiteToAgvSite?.agvSiteIds?.filter { it != openSiteId } ?: emptyList()
          val ids = upSiteToAgvSiteMap[location]?.filter{ it != openSiteId } ?: emptyList()
          upSiteToAgvSiteMap[location] = ids
          logger.debug("还有${ids.size}个未完成装舱")
          MongoDBManager.collection<UpSiteToAgvSite>().updateOne(UpSiteToAgvSite::upSiteId eq location,
              set(UpSiteToAgvSite::agvSiteIds setTo ids))
        }
        if (type == 0) {
          // 把这个货舱从当前任务的待打开中移除
          val index = when {
            ctx.transportIndex < 32 -> "toSite1"
            ctx.transportIndex < 64 -> "toSite2"
            ctx.transportIndex < 96 -> "toSite3"
            ctx.transportIndex < 128 -> "toSite4"
            else -> "toSite5"
          }
          val location = if (ctx.taskDef?.name == CUSTOM_CONFIG.load) ctx.task.persistedVariables["toSite"] as String else
            ctx.task.persistedVariables[index] as String
//          val downSiteToAgvSite = MongoDBManager.collection<DownSiteToAgvSite>().findOne(DownSiteToAgvSite::downSiteId eq location)
//          val ids = downSiteToAgvSite?.agvSiteIds?.filter { it != openSiteId } ?: emptyList()
          val ids = downSiteToAgvSiteMap[location]?.filter { it != openSiteId } ?: emptyList()
          downSiteToAgvSiteMap[location] = ids
          logger.debug("还有${ids.size}个未完成取料")
          MongoDBManager.collection<DownSiteToAgvSite>().updateOne(DownSiteToAgvSite::downSiteId eq location,
              set(DownSiteToAgvSite::agvSiteIds setTo ids))
        }
        // 重置openSiteId，可以让下一个货舱打开
//        Services.openSiteId = ""
        MongoDBManager.collection<OpenSiteId>().updateOne(OpenSiteId::openSiteId eq openSiteId, set(
            OpenSiteId::openSiteId setTo ""
        ))
      },
       TaskComponentDef(
           "extra", "closedForLoad", "关门到位(装舱用)", "", false, listOf(
       ), false) { _, _ ->
         // 读取DI到位信号
         var flag = true
         while (flag) {
           if (CUSTOM_CONFIG.mockDi) {
             if (mockDetail[1]) flag = false
           } else {
             val details = getVehicleDetails()
             for (d in details) {
               val detail = d.value
               val di = detail["DI"] as List<Boolean>
               if (di[1]) flag = false
             }
           }
         }
         logger.debug("装舱关门到位")
       },
      TaskComponentDef(
          "extra", "skip", "跳过指定多个运单", "", false, listOf(
          TaskComponentParam("fromIndex", "startIndex", "int"),
          TaskComponentParam("toIndex", "endIndex", "int"),
          TaskComponentParam("reason", "原因", "string")
      ), false) { component, ctx ->
        val startIndex = parseComponentParamValue("fromIndex", component, ctx) as Int
        val endIndex = parseComponentParamValue("toIndex", component, ctx) as Int
        val reason = parseComponentParamValue("reason", component, ctx) as String
        for (index in startIndex..endIndex) {
          val transport = ctx.task.transports[index]
          RobotTaskService.updateTransportStateToFinal(RobotTransportState.Skipped, reason, transport, ctx.task)
        }
      },
       TaskComponentDef(
           "extra", "checkPwd1", "校验密码", "", false, listOf(
       ), false) { _, ctx ->
         val curAgvSiteId = ctx.task.persistedVariables["curAgvSite"] as String
         val agvSite = Services.getAgvSiteById(curAgvSiteId)
         if (agvSite != null) {
           MongoDBManager.collection<StationToPwd>().updateOne(StationToPwd::workStation eq agvSite.workStation, set(
               StationToPwd::fire setTo true
           ))
         }
       },
       TaskComponentDef(
           "extra", "checkPwd2", "校验密码复位", "", false, listOf(
       ), false) { _, ctx ->
         val curAgvSiteId = ctx.task.persistedVariables["curAgvSite"] as String
         val agvSite = Services.getAgvSiteById(curAgvSiteId)
         if (agvSite != null) {
           MongoDBManager.collection<StationToPwd>().updateOne(StationToPwd::workStation eq agvSite.workStation, set(
               StationToPwd::fire setTo false
           ))
         }
       }
  )
}