package com.seer.srd.molex

import com.fasterxml.jackson.module.kotlin.jacksonTypeRef
import com.mongodb.client.model.UpdateOptions
import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.domain.Property
import com.seer.srd.molex.Services.existedNonLastPalletTask
import com.seer.srd.molex.Services.sentTaskList
import com.seer.srd.molex.Services.unSentPalletMap
import com.seer.srd.molex.SiteAreaService.greenTwinkle
import com.seer.srd.robottask.*
import com.seer.srd.robottask.component.TaskComponentDef
import com.seer.srd.robottask.component.TaskComponentParam
import com.seer.srd.robottask.component.parseComponentParamValue
import com.seer.srd.route.service.VehicleService
import com.seer.srd.storesite.StoreSite
import com.seer.srd.storesite.StoreSiteService
import com.seer.srd.util.mapper
import com.seer.srd.vehicle.VehicleManager
import org.litote.kmongo.*
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.collections.HashMap

object CustomComponent {
  private val logger = LoggerFactory.getLogger(CustomComponent::class.java)

  val extraComponents = listOf(
      TaskComponentDef(
          "extra", "lightYellow", "置黄灯常亮", "", false, listOf(
          TaskComponentParam("location", "工作站", "string")
      ), false) {component, ctx ->
        val location = parseComponentParamValue("location", component, ctx) as String
        if (!CUSTOM_CONFIG.noSensorList.contains(location)) {
          val area = SiteAreaService.getAreaModBusBySiteId(location)
              ?: throw BusinessError("lightYellow: no such modbus config $location")
          area.switchYellow(location)
        }
      },
      TaskComponentDef(
          "extra", "pallet:chose", "根据类型返回一个未锁定已占用库位ID", "", false, listOf(
          TaskComponentParam("type", "起点类型", "string")
      ), true) {component, ctx ->
        var from = ""
        val fromType = ctx.task.persistedVariables["fromType"] as String?
        val type = parseComponentParamValue("type", component, ctx) as String
        val sites =
            when {
              ctx.task.def.contains("0128") && !fromType.isNullOrBlank() && fromType.contains("ST") -> {
                val all = mutableListOf<StoreSite>()
                StoreSiteService.listStoreSites().filter { it.type == type }.sortedBy { it.id }.forEach {
//                  val site = SiteAreaService.getAreaModBusBySiteId(it.id)?.getSiteInfo(it.id) ?: throw BusinessError("读取${it.id}状态失败")
                  val site = StoreSiteService.getExistedStoreSiteById(it.id)
                  if (site.filled && !site.locked) all.add(site)
                }
                all
              }
              else -> StoreSiteService.listStoreSites().filter { it.type == type && it.filled && !it.locked }.sortedBy { it.id }
            }
        if(sites.isEmpty()) ctx.setRuntimeVariable(component.returnName, "")
        else {
          from = sites[0].id

          ctx.setRuntimeVariable(component.returnName, from)
          ctx.task.persistedVariables["STFromSiteId"] = from
          MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq ctx.task.id, setValue(RobotTask::persistedVariables, ctx.task.persistedVariables))

          if (ctx.task.def.contains("0128") && !fromType.isNullOrBlank() && fromType.contains("ST")) {
            MongoDBManager.collection<SiteFireTask>().updateOne(SiteFireTask::siteId eq from, addToSet(
                SiteFireTask::fireTask, ctx.task.id
            ), UpdateOptions().upsert(true))
            if (!SiteAreaService.fireTask[from].isNullOrEmpty() && !SiteAreaService.fireTask[from]!!.contains(ctx.task.id)) SiteAreaService.fireTask[from]?.add(ctx.task.id)
            logger.debug("fire task ${ctx.task.id} ST from=$from")
          }
        }
        ctx.task.persistedVariables[component.returnName!!] = from
        MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq ctx.task.id, setValue(RobotTask::persistedVariables, ctx.task.persistedVariables))
        logger.debug("task ${ctx.task.id} choose site $from")

      },
      TaskComponentDef(
          "extra", "markComplete", "标记结束", "", false, listOf(
      ), false) {_, ctx ->
        try {
          if (sentTaskList.contains(ctx.task.id)) sentTaskList.remove(ctx.task.id)
          ctx.task.persistedVariables["willComplete"] = true
          logger.debug("mark ${ctx.task.id} complete")
          val pv = ctx.task.persistedVariables
          MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq ctx.task.id, RobotTask::persistedVariables setTo pv)
        } catch (e: Exception) {
          logger.error("mark task end error", e)
        }
      },
      TaskComponentDef(
          "extra", "kuerle:addToSentList", "加入已下发列表", "", false, listOf(
      ), false) {_, ctx ->
        val tmpList = sentTaskList
        tmpList.forEach {
          val task = MongoDBManager.collection<RobotTask>().findOne(RobotTask::id eq it)
          if (task == null || task.state >= RobotTaskState.Success) sentTaskList.remove(it)
        }
//        if (!sentTaskList.contains(ctx.task.id) && sentTaskList.size == CUSTOM_CONFIG.taskSentListCapacity - 1) {
        if (!sentTaskList.contains(ctx.task.id)) {
          val unfinishedTasks = MongoDBManager.collection<RobotTask>().find(RobotTask::state lt RobotStageState.Success).toList()
          val task102: MutableMap<String, Boolean> = mutableMapOf()
          val canSendTask102: MutableMap<String, Boolean> = mutableMapOf()
          val taskUnSentAutoPallet: MutableMap<String, Boolean> = mutableMapOf()
          for (task in unfinishedTasks) {
            // 存在没下发的102平台的任务
            if (task.def == CUSTOM_CONFIG.transferDef && task.transports[0].state < RobotTransportState.Sent) {
              val fromId = task.transports[0].stages[2].location
              val fromType = StoreSiteService.getExistedStoreSiteById(fromId).type
              if (fromType.contains("102")) {
                //task.persistedVariables["immediate"]在第一个栈板列任务完成后设置,见onRobotTaskFinished
                task102[task.id] = task.persistedVariables["immediate"] != null
                canSendTask102[task.id] = task.persistedVariables["canSend"] == true  // false：不可下发 和 null：还没判断
              }
            }
            // 检查非第一个栈类任务
            if (task.def == CUSTOM_CONFIG.palletDef && task.transports[0].state < RobotTransportState.Sent) {
              taskUnSentAutoPallet[task.id] = task.persistedVariables["auto"] == true
            }
          }
          // immediate为true的任务必须先执行
          val immediateTasks = task102.filter { it.value }
          if (immediateTasks.isNotEmpty()) {
            // 当前任务不是immediate = true
            logger.debug("immediateTasks: $immediateTasks")
            if (!immediateTasks.containsKey(ctx.task.id)) {
              // pallet终点为102的非第一阶段任务等待分配了AGV的transfer102任务后再执行
              if (ctx.task.def == CUSTOM_CONFIG.palletDef) {
                val toType = ctx.task.persistedVariables["toType"] as String
                val index = ctx.task.persistedVariables["num"] is Int
                if (toType.contains("102") && index) {
                  logger.warn("wait for transfer task to area 102 sending...")
                  throw BusinessError("等待分配了AGV的102平台任务下发")
                }
              }
              // 如果immediate = true的任务能下发
              val canSend = canSendTask102.values.toList()
              if (canSend.contains(true)) throw BusinessError("等待分配了AGV的102平台任务下发")
            }
          } else {
            logger.debug("task102: $task102")
          }

          //除102立即任务之外，pallet的后续自动任务优先执行
//          if (immediateTasks.containsKey(ctx.task.id)) logger.debug("102 priority higher, id=${ctx.task.id}")
//          else {
//            val type =
//            if (unSentPalletMap.any { it.key != ctx.task.id }) throw BusinessError("等待栈板列${unSentPalletMap.filter{it.key != ctx.task.id}}完成")
//          }
//
//          if (ctx.task.def == CUSTOM_CONFIG.palletDef && ctx.task.persistedVariables["num"] == ctx.task.persistedVariables["index"]) {
//            while (true) {
//              logger.debug("${ctx.task.id} get")
//              Thread.sleep(1000)
//            }
//          }

          val nonLastPalletTaskFlag = existedNonLastPalletTask(unfinishedTasks.filter { it.def == CUSTOM_CONFIG.palletDef && it.id != ctx.task.id })
          if (taskUnSentAutoPallet.filter { it.value }.isNotEmpty()) {  // 存在没下发的后续栈板列任务
            logger.debug("current auto task: $taskUnSentAutoPallet")
            if (immediateTasks.containsKey(ctx.task.id)) logger.debug("102 priority higher")
//            else if (taskUnSentAutoPallet[ctx.task.id] != true && nonLastPalletTaskFlag) throw BusinessError("等待栈板列任务执行..")
            else if (taskUnSentAutoPallet[ctx.task.id] != true) throw BusinessError("等待栈板列任务执行..")
          } else {    // 不存在未下发的auto栈板任务：1.auto都下发了 2.栈板列只有1个非auto任务 3.没有任务了。这三种情况都行继续下发当前ctx.task
//            val sentPalletTasks = unfinishedTasks.filter { it.def == CUSTOM_CONFIG.palletDef && sentTaskList.contains(it.id) }
//            if (sentPalletTasks.isNotEmpty()) {
//              val fromType = sentPalletTasks[0].persistedVariables["fromType"] as String? ?: ""
//              if (ctx.task.def == CUSTOM_CONFIG.palletDef && ctx.task.persistedVariables["auto"] != true && nonLastPalletTaskFlag) throw BusinessError("等待${fromType}全部栈板运输完成..")
//            }
          }
        }
        if (!sentTaskList.contains(ctx.task.id)) {
          if (ctx.task.def == CUSTOM_CONFIG.palletDef && ctx.task.persistedVariables["auto"] == true) {
            val preTaskId = ctx.task.persistedVariables["preTaskId"]
            if (preTaskId is String) {
              val preTask = MongoDBManager.collection<RobotTask>().findOne(RobotTask::id eq preTaskId)
              if (preTask != null) {
                if (preTask.transports[0].state < RobotTransportState.Sent) throw BusinessError("前置任务未下发")
              }
            } else {
              logger.error("check: ${ctx.task.id} get pre task error, pre=$preTaskId")
            }
          }
          ctx.task.persistedVariables["canSend"] = true
          ctx.task.persistedVariables["fireWait"] = CUSTOM_CONFIG.fireWaitTrigger
          val pv = ctx.task.persistedVariables
          MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq ctx.task.id, RobotTask::persistedVariables setTo pv)

          // 按创建时间执行任务，先创建的先执行
          // 先检索出来未下发的任务，栈板和第一个栈板列任务
          val unsentTasks = MongoDBManager.collection<RobotTask>()
              .find(RobotTask::state lt RobotTaskState.Success).toMutableList()
              .filter { it.transports[0].state < RobotTransportState.Sent && it.persistedVariables["canSend"] == true && it.persistedVariables["auto"] != true }
              .map { it.id }.sortedBy { it }

          logger.debug("current unsent tasks sorted by id: $unsentTasks")
          // 过滤已分配AGV的102任务
          if (ctx.task.persistedVariables["immediate"] != true && ctx.task.id in unsentTasks && unsentTasks[0] != ctx.task.id) throw BusinessError("等待${unsentTasks[0]}下发")

          // 加入下发列表
          logger.debug("sentTasks: $sentTaskList")
          if (!sentTaskList.contains(ctx.task.id) && sentTaskList.size >= CUSTOM_CONFIG.taskSentListCapacity) {
            ctx.task.persistedVariables["canSend"] = false
            throw BusinessError("下发任务已到达临界值：$sentTaskList")
          }

          sentTaskList.add(ctx.task.id)

          // 从未下发栈板列表中删除
          if (unSentPalletMap.containsKey(ctx.task.id)) {
            unSentPalletMap.remove(ctx.task.id)
            logger.debug("remove ${ctx.task.id} from unsent pallet map")
          }

          // 检查当前下发的任务是不是栈板类任务，且栈板列，只剩下一个，如果是，把当前任务置为最后一个，允许其他栈板列下发(同时只能执行一列栈板)
//          if (ctx.task.def == CUSTOM_CONFIG.palletDef) {
//            val fromType = ctx.task.persistedVariables["fromType"] as String
//            val sites = StoreSiteService.listStoreSites().filter{ it.type == fromType && it.filled }
//            if (sites.size == 1) {
//
//            }
//          }
        }
      },
      TaskComponentDef(
          "extra", "kuerle:checkSameType", "检查同区域任务", "", false, listOf(
      ), false) {_, ctx ->

        // 栈板列后续任务,分配了AGV的102不检查
        if (ctx.task.persistedVariables["auto"] == true || ctx.task.persistedVariables["immediate"] == true) return@TaskComponentDef

        val fromType = ctx.task.persistedVariables["fromType"] as String
        val toType = ctx.task.persistedVariables["toType"] as String

        // 新增:同区域同时只能下发一个任务的运单
        val unfinishedTasks = MongoDBManager.collection<RobotTask>().find(RobotTask::state lt RobotTaskState.Success)
            .toList().filter { it.persistedVariables["willComplete"] != true }
        val unfinishedSentTasks = unfinishedTasks.filter { it.transports[0].state > RobotTransportState.Created }
        var unSentTasks = unfinishedTasks - unfinishedSentTasks

        // 当未完成的任务中存在栈板列任务①，未下发的任务中也有栈板列任务②且这个未下发的任务是第一个栈板时，在检查区域时过滤这个任务②
        if (unSentTasks.size > 1 && unfinishedTasks.any { it.def == CUSTOM_CONFIG.palletDef } && unSentTasks.any { it.persistedVariables["auto"] == false })
          unSentTasks = unSentTasks.filter { it.persistedVariables["auto"] != true }

        if (unSentTasks.isNotEmpty()) {
          val flag = Services.checkOtherArea(fromType, toType, unSentTasks)
          logger.debug("check taskId=${ctx.task.id} with other area, flag=$flag")
          if (flag) return@TaskComponentDef
        }
        unfinishedSentTasks.forEach { task ->
          if (task.def == CUSTOM_CONFIG.palletDef) {
            val fromSiteId = task.transports[CUSTOM_CONFIG.fromIndex].stages[0].location
            val fromSite = StoreSiteService.getExistedStoreSiteById(fromSiteId)
            if (fromSite.type == fromType || fromSite.type == toType) {
              if (task.transports[0].state > RobotTransportState.Created && task.transports[0].state < RobotTransportState.Success
                  || task.transports[CUSTOM_CONFIG.preFromIndex].state > RobotTransportState.Created && task.transports[CUSTOM_CONFIG.preFromIndex].state < RobotTransportState.Success
                  || task.transports[CUSTOM_CONFIG.fromIndex].state > RobotTransportState.Created && task.transports[CUSTOM_CONFIG.fromIndex].state < RobotTransportState.Success) {
                // 置为不可下发
                ctx.task.persistedVariables["canSend"] = false
                MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq ctx.task.id, setValue(RobotTask::persistedVariables, ctx.task.persistedVariables))
                logger.warn("${ctx.task.def} task [${ctx.task.id}] area [$fromType] has been occupied by [${task.id}] 1")
//                throw BusinessError("区域[$fromType]已被任务[${task.id}]占用1")
                throw BusinessError("和[${task.id}]区域冲突")
              }
            }

            val curToType = task.persistedVariables["toType"] as String
//            val toSiteId = task.transports[2].stages[3].location
//            val toSite = StoreSiteService.getExistedStoreSiteById(toSiteId)
            if (curToType == toType || curToType == fromType){
              if (task.transports[0].state > RobotTransportState.Created && task.transports[0].state < RobotTransportState.Success){
                ctx.task.persistedVariables["canSend"] = false
                MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq ctx.task.id, setValue(RobotTask::persistedVariables, ctx.task.persistedVariables))
                logger.warn("${ctx.task.def} task [${ctx.task.id}] area [$curToType] has been occupied by [${task.id}] 2")
//                throw BusinessError("区域[$curToType]已被任务[${task.id}]占用2")
                throw BusinessError("和[${task.id}]区域冲突")
              }

              else if (task.transports[CUSTOM_CONFIG.preFromIndex].state > RobotTransportState.Created && task.transports[CUSTOM_CONFIG.preFromIndex].state < RobotTransportState.Success){
                ctx.task.persistedVariables["canSend"] = false
                MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq ctx.task.id, setValue(RobotTask::persistedVariables, ctx.task.persistedVariables))
                logger.warn("${ctx.task.def} task [${ctx.task.id}] area [$curToType] has been occupied by [${task.id}] 3")
//                throw BusinessError("区域[$curToType]已被任务[${task.id}]占用3")
                throw BusinessError("和[${task.id}]区域冲突")
              }

              else if (task.transports[CUSTOM_CONFIG.fromIndex].state > RobotTransportState.Created && task.transports[CUSTOM_CONFIG.fromIndex].state < RobotTransportState.Success){
                ctx.task.persistedVariables["canSend"] = false
                MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq ctx.task.id, setValue(RobotTask::persistedVariables, ctx.task.persistedVariables))
                logger.warn("${ctx.task.def} task [${ctx.task.id}] area [$curToType] has been occupied by [${task.id}] 4")
//                throw BusinessError("区域[$curToType]已被任务[${task.id}]占用4")
                throw BusinessError("和[${task.id}]区域冲突")
              }

              else if (task.transports[CUSTOM_CONFIG.preToIndex].state > RobotTransportState.Created && task.transports[CUSTOM_CONFIG.preToIndex].state < RobotTransportState.Success){
                ctx.task.persistedVariables["canSend"] = false
                MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq ctx.task.id, setValue(RobotTask::persistedVariables, ctx.task.persistedVariables))
                logger.warn("${ctx.task.def} task [${ctx.task.id}] area [$curToType] has been occupied by [${task.id}] 5")
//                throw BusinessError("区域[$curToType]已被任务[${task.id}]占用5")
                throw BusinessError("和[${task.id}]区域冲突")
              }

              else if (task.transports[CUSTOM_CONFIG.toIndex].state > RobotTransportState.Created && task.transports[CUSTOM_CONFIG.toIndex].state < RobotTransportState.Success) {
                ctx.task.persistedVariables["canSend"] = false
                MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq ctx.task.id, setValue(RobotTask::persistedVariables, ctx.task.persistedVariables))
                logger.warn("${ctx.task.def} task [${ctx.task.id}] area [$curToType] has been occupied by [${task.id}] 6")
//                throw BusinessError("区域[$curToType]已被任务[${task.id}]占用6")
                throw BusinessError("和[${task.id}]区域冲突")
              }
            }
          }
          else if (task.def == CUSTOM_CONFIG.transferDef) {
            val fromSiteId = task.transports[0].stages[2].location
            val fromSite = StoreSiteService.getExistedStoreSiteById(fromSiteId)
            if (fromSite.type == fromType || fromSite.type == toType) {
              // 还没通过前置点：1.还没到前置点 2.到了前置点但是不满足通过条件
              if (task.transports[0].state > RobotTransportState.Created && task.transports[0].state < RobotTransportState.Success) {
                ctx.task.persistedVariables["canSend"] = false
                MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq ctx.task.id, setValue(RobotTask::persistedVariables, ctx.task.persistedVariables))
                logger.warn("${ctx.task.def} task [${ctx.task.id}] area [${fromSite.type}] has been occupied by [${task.id}] 7")
//                throw BusinessError("区域[${fromSite.type}]已被任务[${task.id}]占用7")
                throw BusinessError("和[${task.id}]区域冲突")
              }

//              else if (task.transports[1].state > RobotTransportState.Created && task.transports[1].state < RobotTransportState.Success) {
//                ctx.task.persistedVariables["canSend"] = false
//                logger.warn("${ctx.task.def} task [${ctx.task.id}] area [${fromSite.type}] has been occupied by [${task.id}] 8")
////                throw BusinessError("区域[${fromSite.type}]已被任务[${task.id}]占用8")
//                throw BusinessError("和[${task.id}]区域冲突")
//              }
            }

            val curToType = task.persistedVariables["toType"] as String
            if (curToType == toType || curToType == fromType){
              // 还没通过前置点：1.还没到前置点 2.到了前置点但是不满足通过条件
              if (task.transports[0].state > RobotTransportState.Created && task.transports[0].state < RobotTransportState.Success) {
                ctx.task.persistedVariables["canSend"] = false
                MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq ctx.task.id, setValue(RobotTask::persistedVariables, ctx.task.persistedVariables))
                logger.warn("${ctx.task.def} task [${ctx.task.id}] area [$curToType] has been occupied by [${task.id}] 9")
//                throw BusinessError("区域[$curToType]已被任务[${task.id}]占用9")
                throw BusinessError("和[${task.id}]区域冲突")
              }

              else if (task.transports[1].state > RobotTransportState.Created && task.transports[1].state < RobotTransportState.Success) {
                ctx.task.persistedVariables["canSend"] = false
                MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq ctx.task.id, setValue(RobotTask::persistedVariables, ctx.task.persistedVariables))
                logger.warn("${ctx.task.def} task [${ctx.task.id}] area [$curToType] has been occupied by [${task.id}] 10")
//                throw BusinessError("区域[$curToType]已被任务[${task.id}]占用10")
                throw BusinessError("和[${task.id}]区域冲突")
              }
              else if (task.transports[2].state > RobotTransportState.Created && task.transports[2].state < RobotTransportState.Success) {
                ctx.task.persistedVariables["canSend"] = false
                MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq ctx.task.id, setValue(RobotTask::persistedVariables, ctx.task.persistedVariables))
                logger.warn("${ctx.task.def} task [${ctx.task.id}] area [$curToType] has been occupied by [${task.id}] 11")
//                throw BusinessError("区域[$curToType]已被任务[${task.id}]占用11")
                throw BusinessError("和[${task.id}]区域冲突")
              }

//              else if (task.transports[3].state > RobotTransportState.Created && task.transports[3].state < RobotTransportState.Success) {
//                ctx.task.persistedVariables["canSend"] = false
//                logger.warn("${ctx.task.def} task [${ctx.task.id}] area [$curToType] has been occupied by [${task.id}] 12")
////                throw BusinessError("区域[$curToType]已被任务[${task.id}]占用12")
//                throw BusinessError("和[${task.id}]区域冲突")
//              }
            }
          }
        }
      },
      TaskComponentDef(
          "extra", "kuerle:setIndex", "标记第一个栈板列任务并置为满库位", "", false, listOf(
      ), false) {_, ctx ->
        ctx.task.persistedVariables["index"] = 1
        ctx.task.persistedVariables["auto"] = false
        val num = ctx.task.persistedVariables["num"] as String

        ctx.task.persistedVariables["lastPalletTask"] = num == "1"  // 标记最后一个栈板任务

        val type = ctx.runtimeVariables["from"] as String
        val ids: MutableList<String> = mutableListOf()
        for (index in 1..num.toInt()) {
          ids.add("$type-0$index")
        }

        if (type in CUSTOM_CONFIG.recognizeTasks) {
          val def = getRobotTaskDef(ctx.task.def)
          if (def != null) {
            val oldProperties = def.transports[CUSTOM_CONFIG.fromIndex].stages[0].properties
            val properties =
                mapper.readValue(oldProperties, jacksonTypeRef<List<Property>>())
                    .filter { it.key != "recognize" }.toMutableList().apply { add(Property("recognize", "true")) }
            ctx.task.transports[CUSTOM_CONFIG.fromIndex].stages[0].properties =  mapper.writeValueAsString(properties)
          }
        }
        val fromType = ctx.task.persistedVariables["fromType"] as String?
        if (!fromType.isNullOrBlank() && CUSTOM_CONFIG.palletDef.contains("0128") && fromType.contains("ST")){
          ids.forEach {
//            val site = SiteAreaService.getAreaModBusBySiteId(it)?.getSiteInfo(it) ?: throw BusinessError("no config: $it")
            val site = StoreSiteService.getExistedStoreSiteById(it)
            if (!site.filled) throw BusinessError("${it}位置空")
            if (site.locked) throw BusinessError("${it}已被锁定")
          }
        }
        else StoreSiteService.changeSitesFilledByIds(ids, true, "from task def ${ctx.task.def}")
//        if (!unSentPalletMap.containsKey(ctx.task.id)) {
//          unSentPalletMap[ctx.task.id] = ctx.task.persistedVariables["fromType"] as String
//          logger.debug("add ${ctx.task.id} type=${ctx.task.persistedVariables["fromType"]} to unSentPalletMap")
//        }
        logger.debug("$type pallet task initial...")
      },
      TaskComponentDef(
          "extra", "sendAllTasks", "下发栈板列剩余任务", "", false, listOf(
      ), false) {_, ctx ->
        val num =
            if (ctx.task.persistedVariables["num"] is String) (ctx.task.persistedVariables["num"] as String).toInt()
            else ctx.task.persistedVariables["num"] as Int
        if (num < 2 || ctx.task.persistedVariables["sendNext"] == true) return@TaskComponentDef
        val fromType = ctx.task.persistedVariables["fromType"] as String
        val toType = ctx.task.persistedVariables["toType"] as String
        var preTaskId = ctx.task.id

        ctx.task.persistedVariables["sendNext"] = true
        val pv = ctx.task.persistedVariables
        MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq ctx.task.id, RobotTask::persistedVariables setTo pv)

        for (index in 2..num) {
          val taskDef = getRobotTaskDef(CUSTOM_CONFIG.palletDef) ?: throw BusinessError("no such task def [${CUSTOM_CONFIG.palletDef}]")
          val newTask = buildTaskInstanceByDef(taskDef)

          // 标记上一个栈板任务
          newTask.persistedVariables["preTaskId"] = preTaskId

          // 标记自动创建
          newTask.persistedVariables["auto"] = true
          // 标记当前任务的索引号
          newTask.persistedVariables["index"] = index
          // 标记共有几个任务需要生成
          newTask.persistedVariables["num"] = num

          // 标记最后一个栈板任务
          newTask.persistedVariables["lastPalletTask"] = index == num

          // 设置起点工作站
          newTask.persistedVariables["fromSite"] = "$fromType-0$index"
          // 设置起点终点库区类型
          newTask.persistedVariables["fromType"] = fromType
          newTask.persistedVariables["toType"] = toType

          if (fromType in CUSTOM_CONFIG.recognizeTasks) {
            val def = getRobotTaskDef(newTask.def)
            if (def != null) {
              val oldProperties = def.transports[CUSTOM_CONFIG.fromIndex].stages[0].properties
              val properties =
                  mapper.readValue(oldProperties, jacksonTypeRef<List<Property>>())
                      .filter { it.key != "recognize" }.toMutableList().apply { add(Property("recognize", "true")) }
              newTask.transports[CUSTOM_CONFIG.fromIndex].stages[0].properties =  mapper.writeValueAsString(properties)
            }
          }
          newTask.persistedVariables["sendNext"] = true
          RobotTaskService.saveNewRobotTask(newTask)
//          if (!unSentPalletMap.containsKey(newTask.id)) {
//            unSentPalletMap[newTask.id] = fromType
//            logger.debug("add ${newTask.id} to unSentPalletMap")
//          }
          preTaskId = newTask.id
        }
      },
      TaskComponentDef(
          "extra", "skipWait", "检查前序任务状态", "一个任务结束后续全结束", false, listOf(
      ), false) {_, ctx ->
        val preTaskId = ctx.task.persistedVariables["preTaskId"]
        if (preTaskId is String) {
          val task = MongoDBManager.collection<RobotTask>().findOne(RobotTask::id eq preTaskId)
          if (task != null) {
            if (task.state > RobotTaskState.Success) RobotTaskService.abortTask(ctx.task.id)
            else ctx.task.persistedVariables["skipWait"] = task.transports[3].state > RobotTransportState.Created
          }
        } else {
          logger.error("${ctx.task.id} get pre task error, pre=$preTaskId")
        }
      },
      TaskComponentDef(
          "extra", "checkOtherPallet", "检查前序列任务是否下发", "", false, listOf(
      ), false) {_, ctx ->
        if (ctx.task.def == CUSTOM_CONFIG.palletDef) {
          val preTaskId = ctx.task.persistedVariables["preTaskId"] as String?
          if (preTaskId != null) {
            val task = MongoDBManager.collection<RobotTask>().findOne(RobotTask::id eq preTaskId)
            if (task != null) {
              if (task.transports[0].state < RobotTransportState.Sent) throw BusinessError("前序任务${preTaskId}未下发")
            } else {
              logger.error("get pre task error 1, curTaskId=${ctx.task.id}")
            }
          } else {
            logger.error("get pre task error 2, curTaskId=${ctx.task.id}")
          }
        }
//        if (preTaskId is String) {
//          val task = MongoDBManager.collection<RobotTask>().findOne(RobotTask::id eq preTaskId)
//          if (task != null) {
//            if (task.state > RobotTaskState.Success) RobotTaskService.abortTask(ctx.task.id)
//            else ctx.task.persistedVariables["skipWait"] = task.transports[3].state > RobotTransportState.Created
//          }
//        } else {
//          logger.error("${ctx.task.id} get pre task error, pre=$preTaskId")
//        }
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
        if (!CUSTOM_CONFIG.noSensorList.contains(location)) {
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
        if (!CUSTOM_CONFIG.noSensorList.contains(location)) {
          val area = SiteAreaService.getAreaModBusBySiteId(location)
              ?: throw BusinessError("lightRed: no such modbus config $location")
          area.switchRed(location)
        }
      },
      TaskComponentDef(
          "extra", "twinkleTypeRed", "置区域红灯闪烁报警", "", false, listOf(
          TaskComponentParam("type", "区域", "string")
      ), false) {component, ctx ->
        val type = parseComponentParamValue("type", component, ctx) as String
        val allSites = StoreSiteService.listStoreSites().filter { it.type == type }
        val notLockedSites = allSites.filter { !it.locked }

        // 可用的库位数量
        val greenTwinkleSize = greenTwinkle
            .filter { notLockedSites.map { s -> s.id }.contains(it.key) && it.value }
            .size

        if (notLockedSites.isEmpty()) logger.debug("red twinkle: no available sites of $type")
        else {
          notLockedSites.forEach {
            if (!CUSTOM_CONFIG.noSensorList.contains(it.id)) {
              // 有可用空位置 && 当前检查的位置是空的
              if (notLockedSites.size != greenTwinkleSize && greenTwinkle[it.id] == true) {
                logger.debug("skip red twinkle: ${it.id} green twinkle")
                logger.debug("green twinkle sites size: $greenTwinkleSize")
              } else {
                // 没有可用位置 || 当前位置不可用
                logger.debug("green twinkle sites size: $greenTwinkleSize")
                val area = SiteAreaService.getAreaModBusBySiteId(it.id)
                    ?: throw BusinessError("lightRed: no such modbus config ${it.id}")
                SiteAreaService.redTwinkle[it.id] = true
                area.switchRed(it.id, true, "red twinkle")
              }
            }
          }
        }
      },
      TaskComponentDef(
          "extra", "cancelTwinkleTypeRed", "取消区域红灯报警", "", false, listOf(
          TaskComponentParam("type", "区域", "string")
      ), false) { component, ctx ->
        val type = parseComponentParamValue("type", component, ctx) as String
        val sites = StoreSiteService.listStoreSites().filter { it.type == type }
        if (sites.isEmpty()) logger.debug("cancel red twinkle: no available sites of $type")
        else {
          logger.debug("cancel red twinkle ${sites.map { it.id }}")
          sites.forEach { SiteAreaService.redTwinkle[it.id] = false }
        }
      },
      TaskComponentDef(
          "extra", "pass", "是否能通过前置点", "", false, listOf(
          TaskComponentParam("location", "能到达工作站", "string"),
          TaskComponentParam("type", "工作站类型(from/to)", "string")
      ), false) {component, ctx ->
        val location = parseComponentParamValue("location", component, ctx) as String
        val type = parseComponentParamValue("type", component, ctx) as String

        val area = SiteAreaService.getAreaModBusBySiteId(location) ?: throw BusinessError("pass: no such modbus config $location")
        if (CUSTOM_CONFIG.checkBeforeFinal && area.error) {
          ctx.task.persistedVariables["checkError"] = true
          MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq ctx.task.id, setValue(RobotTask::persistedVariables, ctx.task.persistedVariables))
          throw BusinessError("库位请求出错")
        }
        when (type) {
          "from" -> {
//            val filled = if (!CUSTOM_CONFIG.noSensorList.contains(location))
//              SiteAreaService.getAreaModBusBySiteId(location)?.getSiteInfo(location)?.filled ?: throw BusinessError("pass: no such modbus config $location")
//            else StoreSiteService.getExistedStoreSiteById(location).filled
            val filled = StoreSiteService.getExistedStoreSiteById(location).filled
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
//            val filled = if (!CUSTOM_CONFIG.noSensorList.contains(location))
//              SiteAreaService.getAreaModBusBySiteId(location)?.getSiteInfo(location)?.filled ?: throw BusinessError("pass: no such modbus config $location")
//            else StoreSiteService.getExistedStoreSiteById(location).filled
            val filled = StoreSiteService.getExistedStoreSiteById(location).filled
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
        if (ctx.task.persistedVariables["pass"] == true) {
          //把终点加到fireTask
          val to = ctx.task.persistedVariables["toSiteId"] as String
          MongoDBManager.collection<SiteFireTask>().updateOne(SiteFireTask::siteId eq to, addToSet(
              SiteFireTask::fireTask, ctx.task.id
          ), UpdateOptions().upsert(true))
          if (!SiteAreaService.fireTask[to].isNullOrEmpty() && !SiteAreaService.fireTask[to]!!.contains(ctx.task.id)) SiteAreaService.fireTask[to]?.add(ctx.task.id)
          logger.debug("fire task:${ctx.task.id} get final empty site $to")
        }
      },
      TaskComponentDef(
          "extra", "passArea", "AGV在终点区域前选择一个可用位置", "", false, listOf(
//          TaskComponentParam("area", "区域", "string")
      ), true) {component, ctx ->
//        val area = parseComponentParamValue("area", component, ctx) as String
        val firstToSite = ctx.task.persistedVariables["toSite"] as String
        val toSite = StoreSiteService.getExistedStoreSiteById(firstToSite)
        val allSites = StoreSiteService.listStoreSites().filter { it.type == toSite.type }
        val id: String
        if (!toSite.locked && !toSite.filled) {
          id = toSite.id
          ctx.setRuntimeVariable(component.returnName, id)
        } else {
          logger.debug("firstToSite ${toSite.id} unavailable, filled=${toSite.filled}, locked=${toSite.locked}")
          val list = allSites
              .filter { !it.filled && !it.locked && !it.id.contains("Pre-") }
              .sortedBy { it.id }
          if (list.isEmpty()) {
            // 如果没有可用位置就返回空字符串，等待一个随机可用位置
            if (CUSTOM_CONFIG.twinkleAllRed || ctx.task.def.contains("1225")) {
              id = ""
              ctx.setRuntimeVariable(component.returnName, "")

              // 改成不需要下发"更新库位任务"就能继续执行
//              allSites.forEach { site ->
//                SiteAreaService.pass[site.id] = false
//              }

            } else {
              // 如果没有可用位置就选择第一次选择的终点位置
              id = toSite.id
              ctx.setRuntimeVariable(component.returnName, id)
              logger.debug("no available sites of Type ${toSite.type}")
            }
          }
          else {
            logger.debug("available sites ${list.map { it.id }}")
            id = list[0].id
            ctx.setRuntimeVariable(component.returnName, id)
          }
        }
        ctx.task.persistedVariables["toSiteId"] = id
        if (id.isNotBlank()) {
//          allSites.forEach { if (it.id != id)  SiteAreaService.pass[it.id] = true }
          MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq ctx.task.id, RobotTask::persistedVariables setTo ctx.task.persistedVariables)
//          //把终点加到fireTask
//          val to = ctx.task.persistedVariables["toSiteId"] as String
//          MongoDBManager.collection<SiteFireTask>().updateOne(SiteFireTask::siteId eq to, addToSet(
//              SiteFireTask::fireTask, ctx.task.id
//          ), UpdateOptions().upsert(true))
//          if (!SiteAreaService.fireTask[to].isNullOrEmpty() && !SiteAreaService.fireTask[to]!!.contains(ctx.task.id)) SiteAreaService.fireTask[to]?.add(ctx.task.id)
//          logger.debug("fire task:${ctx.task.id} get final empty site $id")
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
          "extra", "molex:setV", "设置AGV", "", false, listOf(
      ), false) {_, ctx ->
        val fromType = ctx.task.persistedVariables["fromType"] as String
        if (fromType.contains("102") && ctx.task.def == CUSTOM_CONFIG.transferDef) {

          val unfinishedAllTasks = MongoDBManager.collection<RobotTask>().find(
              and(
                  RobotTask::state lt RobotTaskState.Success
//                  RobotTask::def eq CUSTOM_CONFIG.palletDef
              )
          ).toMutableList()

          // 检索未完成的immediate102的栈板任务分配的AGV
          val immediate102TaskAgv = unfinishedAllTasks.filter {
                it.def == CUSTOM_CONFIG.transferDef &&
                it.persistedVariables["fromType"] == "102" &&
                it.persistedVariables["immediate"] == true
          }.map { it.transports[0].intendedRobot }

          // 检索未完成已下发的栈板列任务
          val unfinishedPalletTasks = unfinishedAllTasks.filter {
              it.def == CUSTOM_CONFIG.palletDef &&
              it.transports[CUSTOM_CONFIG.preFromIndex].state > RobotTransportState.Created
          }

          if (unfinishedPalletTasks.isNotEmpty()) {

            val oneTask = unfinishedPalletTasks.findLast { it.transports[CUSTOM_CONFIG.fromIndex].state == RobotTransportState.Success && !immediate102TaskAgv.contains(it.transports[0].processingRobot) }
            val vehicleName =
                if (oneTask != null) oneTask.transports[CUSTOM_CONFIG.preFromIndex].processingRobot
//                else unfinishedTasks[0].transports[CUSTOM_CONFIG.preFromIndex].processingRobot
                else null
            if (vehicleName.isNullOrBlank()) return@TaskComponentDef
            ctx.task.transports[0].intendedRobot = vehicleName

            ctx.task.persistedVariables["immediate"] = true
            logger.debug("set intended vehicle $vehicleName to task [${ctx.task.id}]")
          }
        }
      },
      TaskComponentDef(
          "extra", "molex:setVAndCategory", "设置AGV和类别", "", false, listOf(
      ), false) {_, ctx ->
        val fromType = ctx.task.persistedVariables["fromType"] as String
        if (fromType.contains("102") && ctx.task.def == CUSTOM_CONFIG.transferDef) {
          val unfinishedTasks = MongoDBManager.collection<RobotTask>().find(
              and(
                  RobotTask::state lt RobotTaskState.Success,
                  RobotTask::def eq CUSTOM_CONFIG.palletDef
              )
          ).toMutableList().filter { it.transports[0].state > RobotTransportState.Created }
          if (unfinishedTasks.isNotEmpty()) {
            ctx.task.transports[0].intendedRobot = unfinishedTasks[0].transports[0].processingRobot
            val vehicleName = unfinishedTasks[0].transports[0].processingRobot

            // -----设置category--------
            if (!vehicleName.isNullOrBlank()) {
              ctx.task.transports[0].category = ctx.task.id
              val v = VehicleService.listVehicles().filter {it.name == vehicleName}[0]
              val categories = v.processableCategories.toMutableSet()
              categories.add(ctx.task.id)
              VehicleManager.setVehicleProcessableCategories(vehicleName, categories.toList())
              logger.debug("add category ${ctx.task.id} to $vehicleName")
            }
            //-------------------------
            ctx.task.persistedVariables["immediate"] = true
            logger.debug("set vehicle ${unfinishedTasks[0].transports[0].processingRobot} to task [${ctx.task.id}]")
          }
        }
      },
      TaskComponentDef(
          "extra", "moLex:getOneSite", "从终点库区选择一个空库位", "", false, listOf(
          TaskComponentParam("type", "库区名称", "string")
      ), true) {component, ctx ->
        val toType = parseComponentParamValue("type", component, ctx) as String

        val from = ctx.task.persistedVariables["fromSite"] as String
        val fromSite = StoreSiteService.getExistedStoreSiteById(from)
        if (fromSite.type == toType) throw BusinessError("起点和终点库位不能同区域！！")

        val toSites = MongoDBManager.collection<StoreSite>()
//            .find(StoreSite::type eq toType, StoreSite::filled eq false, StoreSite::locked eq false).toList()
          .find(StoreSite::type eq toType, StoreSite::locked eq false).toList()
            .filter { !it.id.contains("Pre-") }
            .filter { if (from.contains("T-")) it.id.contains("T-") else true }
        if (toSites.isEmpty()){
          if (from.contains("T-")) throw BusinessError("终点库区【${toType}】没有可用空的宽库位!!")
          else throw BusinessError("终点库区【${toType}】没有可用空库位!!")
        }
        var toSite: StoreSite? = null
        for (site in toSites) {
          if (CUSTOM_CONFIG.noSensorList.contains(site.id)) {
            toSite = site
            break
          }
          else {
//            toSite = SiteAreaService.getAreaModBusBySiteId(site.id)?.getSiteInfo(site.id)
            toSite = StoreSiteService.getStoreSiteById(site.id)
            if (toSite != null) break
          }
        }
        if (toSite == null) throw BusinessError("获取终点库位失败，检查配置!!")
        ctx.setRuntimeVariable(component.returnName, toSite.id)
        ctx.task.persistedVariables["toSite"] = toSite.id
        MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq ctx.task.id, RobotTask::persistedVariables setTo ctx.task.persistedVariables)
        logger.debug("task:${ctx.task.id} get one first site ${toSite.id}")
      },
      TaskComponentDef(
          "extra", "moLex:getOneSitePallet", "从终点库区选择一个空库位(列任务运单阶段使用)", "", false, listOf(
          TaskComponentParam("type", "库区名称", "string")
      ), true) {component, ctx ->
        val toType = parseComponentParamValue("type", component, ctx) as String

        val toSites = MongoDBManager.collection<StoreSite>()
            .find(StoreSite::type eq toType).toList()
            .filter { !it.id.contains("Pre-") }.sortedBy { it.id }
        if (toSites.isEmpty()) throw BusinessError("终点库区【${toType}】没有可用库位!!")
        val availableSites = toSites.filter { !it.locked && !it.filled }
        val toSite = if (availableSites.isEmpty()) {
          logger.debug("no available sites, get one to another site ${toSites[0].id} from $toSites")
          toSites[0]
        } else {
          logger.debug("get one available sites ${availableSites[0].id} from $availableSites")
          availableSites[0]
        }
        ctx.setRuntimeVariable(component.returnName, toSite.id)
        ctx.task.persistedVariables["toSite"] = toSite.id
        MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq ctx.task.id, RobotTask::persistedVariables setTo ctx.task.persistedVariables)
        logger.debug("task:${ctx.task.id} get on empty site ${toSite.id}")
      },
      TaskComponentDef(
          "extra", "checkTransferSite", "检查起点库位","强制刷新库位信息", false, listOf(
          TaskComponentParam("location", "库位名称", "string")
      ), false) { component, ctx ->
        val location = parseComponentParamValue("location", component, ctx) as String
        if (location in CUSTOM_CONFIG.palletTaskType) {
          val typeSites = StoreSiteService.listStoreSites().filter { it.type == location }
          val sites = typeSites.filter { it.filled || it.locked }.map { it.id }.sortedBy { it }
          val params = mapper.readValue(ctx.httpCtx?.body(), HashMap::class.java)["params"] as HashMap<String, String>?
          val num = if (params != null && params["num"] is String) params["num"]!!.toInt() else 0
          if (location.contains("ST") && CUSTOM_CONFIG.palletDef.contains("0128")){
            logger.debug("create $location task with sensor")
          } else {
            if (sites.isNotEmpty()) throw BusinessError("${location}区域上次任务未执行完成:$sites")
            val availableSites = typeSites.filter { !it.filled && !it.locked }.map { it.id }.sortedBy { it }
            if (num != 0 && availableSites.size < num) throw BusinessError("${location}区域没有足够的库位:$num")
          }
        } else {
          val site =
              if (!CUSTOM_CONFIG.noSensorList.contains(location))
                SiteAreaService.getAreaModBusBySiteId(location)?.getSiteInfo(location) ?: throw BusinessError("checkSite: no such modbus config $location")
              else StoreSiteService.getExistedStoreSiteById(location)
          if (!site.filled) throw BusinessError("库位${location}空！！")
          if (CUSTOM_CONFIG.lockFrom && site.locked) throw BusinessError("起点库位有任务正在执行！！")
        }
      },

//      TaskComponentDef(
//          "extra", "checkPalletTransferSite", "检查栈板列起点库区","", false, listOf(
//          TaskComponentParam("type", "库区名称", "string")
//      ), false) { component, ctx ->
//        val type = parseComponentParamValue("type", component, ctx) as String
//      val site =
//          if (!CUSTOM_CONFIG.noSensorList.contains(location))
//            SiteAreaService.getAreaModBusBySiteId(location)?.getSiteInfo(location) ?: throw BusinessError("checkSite: no such modbus config $location")
//          else StoreSiteService.getExistedStoreSiteById(location)
//      if (!site.filled) throw BusinessError("库位${location}空！！")
//      if (CUSTOM_CONFIG.lockFrom && site.locked) throw BusinessError("起点库位有任务正在执行！！")
//      val sites = StoreSiteService.listStoreSites().filter { it.type == site.type && it.locked}
//      if (sites.isNotEmpty()) throw BusinessError("起点区域已有任务！！")
//      },

      TaskComponentDef(
          "extra", "checkSite", "检查要更新的库位", "强制刷新库位信息", false, listOf(
          TaskComponentParam("location", "库位名称", "string"),
          TaskComponentParam("filled", "from/to", "string")
      ), false) {component, ctx ->
        val location = parseComponentParamValue("location", component, ctx) as String
        val type = parseComponentParamValue("filled", component, ctx) as String
        val site =
            if (!CUSTOM_CONFIG.noSensorList.contains(location))
              SiteAreaService.getAreaModBusBySiteId(location)?.getSiteInfo(location) ?: throw BusinessError("checkSite: no such modbus config $location")
            else StoreSiteService.getExistedStoreSiteById(location)
//        val taskIds = SiteAreaService.fireTask[site.id]

        if (type == "from") {
          if (!site.filled) throw BusinessError("库位${location}空！！")
        } else if (type == "to") {
          if (site.filled) throw BusinessError("库位${location}非空！！")
        } else throw BusinessError("未选择库位类型!!")

//        if (taskIds.isNullOrEmpty()) {
//          if (type == "from") {
//            if (!site.filled) throw BusinessError("库位${location}空！！")
//          } else if (type == "to") {
//            if (site.filled) throw BusinessError("库位${location}非空！！")
//          } else throw BusinessError("未选择库位类型!!")
//        } else {
//          val tasks = MongoDBManager.collection<RobotTask>().find(RobotTask::id `in` taskIds).toList()
//          for (task in tasks) {
//            if (task.def == CUSTOM_CONFIG.transferDef) {
//              if (task.transports[1].state == RobotTransportState.Success) {
//                if (type == "from") throw BusinessError("库位${site.id}类型选择错误!!")
//                if (site.filled) throw BusinessError("库位${location}非空！！")
//              } else if (task.transports[0].state == RobotTransportState.Success) {
//                if (type == "to") throw BusinessError("库位${site.id}类型选择错误!!")
//                if (!site.filled) throw BusinessError("库位${location}空！！")
//              }
//            } else {
//              if (type == "from") {
//                if (!site.filled) throw BusinessError("库位${location}空！！")
//              } else if (type == "to") {
//                if (site.filled) throw BusinessError("库位${location}非空！！")
//              } else throw BusinessError("未选择库位类型!!")
//            }
//          }
//        }
      }
  )
}