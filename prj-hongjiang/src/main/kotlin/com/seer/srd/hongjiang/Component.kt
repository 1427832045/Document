package com.seer.srd.hongjiang

import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.hongjiang.HongJiangApp.cutterBackMap
import com.seer.srd.robottask.*
import com.seer.srd.robottask.component.TaskComponentDef
import com.seer.srd.robottask.component.TaskComponentParam
import com.seer.srd.robottask.component.parseComponentParamValue
import com.seer.srd.storesite.StoreSite
import com.seer.srd.storesite.StoreSiteService
import org.litote.kmongo.eq
import org.litote.kmongo.findOne
import org.litote.kmongo.setValue
import org.slf4j.LoggerFactory

object ExtraComponent {

  private val logger = LoggerFactory.getLogger(ExtraComponent::class.java)

  val extraComponents: List<TaskComponentDef> = listOf(
      TaskComponentDef(
          "extra", "hongJiang:updateSiteRandom", "更新库位", "", false, listOf(
          TaskComponentParam("site", "库位ID", "string"),
          TaskComponentParam("type", "类型", "string")
      ), false) {component, ctx ->
        val siteId = parseComponentParamValue("site", component, ctx) as String
        val type = parseComponentParamValue("type", component, ctx) as String
        val site = StoreSiteService.getExistedStoreSiteById(siteId)
        if (site.locked) throw BusinessError("库位[$siteId]已被锁定")
        if (type == "clear") StoreSiteService.changeSiteFilled(siteId, false, "change from task")
        else StoreSiteService.changeSiteFilled(siteId, true, "change from task")
      },
      TaskComponentDef(
          "extra", "hongJiang:find", "按库位类型找到一个空/满库位", "", false, listOf(
          TaskComponentParam("type", "类型", "string"),
          TaskComponentParam("filled", "空/满", "string")
      ), true) { component, ctx ->
        val type = parseComponentParamValue("type", component, ctx) as String
        val filled = parseComponentParamValue("filled", component, ctx) as String
        var sites = listOf<StoreSite>()
        if (filled == "空") sites = StoreSiteService.listStoreSites().filter { it.type == type && !it.filled && !it.locked}.sortedBy { it.id }
        else if (filled == "满") sites = StoreSiteService.listStoreSites().filter { it.type == type && it.filled && !it.locked }.sortedBy { it.id }
        if (sites.isEmpty()) throw BusinessError("【$type】没有可用${filled}库位")
        ctx.setRuntimeVariable(component.returnName, sites[0])
      },
      TaskComponentDef(
          "extra", "hongJiang:find2", "按库位类型返回一个空/满库位ID(不抛出异常)", "", false, listOf(
          TaskComponentParam("type", "类型", "string"),
          TaskComponentParam("filled", "空/满", "string")
      ), true) { component, ctx ->
        val type = parseComponentParamValue("type", component, ctx) as String
        val filled = parseComponentParamValue("filled", component, ctx) as String
        var sites = listOf<StoreSite>()
        if (filled == "空") sites = StoreSiteService.listStoreSites().filter { it.type == type && !it.filled && !it.locked }.sortedBy { it.id }
        else if (filled == "满") sites = StoreSiteService.listStoreSites().filter { it.type == type && it.filled && !it.locked }.sortedBy { it.id }
        if (sites.isEmpty()) {
          ctx.setRuntimeVariable(component.returnName, "")
          ctx.task.persistedVariables["msg"] = "${type}没有可用${filled}库位,任务生成并挂起"
        }
        else ctx.setRuntimeVariable(component.returnName, sites[0].id)
      },
      TaskComponentDef(
          "extra", "hongJiang:sendNext", "创建刀具返回任务", "用于刀具请求任务", false, listOf(
          TaskComponentParam("from", "起点", "string"),
          TaskComponentParam("to", "终点", "string"),
          TaskComponentParam("creator", "创建者", "string")  //下发任务的工位
      ), false) { component, ctx ->

        // from为前置任务的终点，to为前置任务的起点
        val from = parseComponentParamValue("from", component, ctx) as String? ?: ""
        val to = parseComponentParamValue("to", component, ctx) as String? ?: ""
        val creator = parseComponentParamValue("creator", component, ctx) as String? ?: ""

        // 生成请求任务的同时生成返回任务，并把请求任务信息保存到返回任务中
        val def = getRobotTaskDef("cutterBack") ?: throw BusinessError("cutterBack任务不存在")
        val backTask = buildTaskInstanceByDef(def)
        backTask.persistedVariables["from"] = from
        backTask.persistedVariables["to"] = to
        backTask.persistedVariables["preTaskId"] = ctx.task.id
        backTask.persistedVariables["canSend"] = false
        backTask.workStations.addAll(listOf(to, creator))
        RobotTaskService.saveNewRobotTask(backTask)

//        ctx.task.persistedVariables["AfterTaskId"] = backTask.id
        // 返回任务保存到内存
        cutterBackMap[backTask.id] = CutterBackReq(from, to)
      },
      TaskComponentDef(
          "extra", "hongJiang:canExec", "检查是否可以下发", "用于刀具返回任务", false, listOf(
      ), false) { _, ctx ->
        val preTaskId = ctx.task.persistedVariables["preTaskId"] as String
        val preTask = MongoDBManager.collection<RobotTask>().findOne(RobotTask::id eq preTaskId)
        if (preTask == null) {
          logger.debug("id=${ctx.task.id}, pre task id=$preTaskId not exists!!")
          throw BusinessError("前序任务id=${preTaskId}不存在")
        }
        else {
          if (!cutterBackMap.containsKey(ctx.task.id)) throw BusinessError("缓存中未找到${ctx.task.id}")
          val canSend =
              ctx.task.persistedVariables["canSend"] as Boolean || cutterBackMap.filter { it.key == ctx.task.id }.map { it.value.canSend }[0]
          if (!canSend) throw BusinessError("人工未下发")
          ctx.task.persistedVariables["canSend"] = true
          val from = preTask.persistedVariables["to"] as String
          val to = preTask.persistedVariables["from"] as String
          val fromSite = StoreSiteService.getExistedStoreSiteById(from)
          val toSite = StoreSiteService.getExistedStoreSiteById(to)
          if (!fromSite.filled) throw BusinessError("${from}是空的")
          if (toSite.filled) throw BusinessError("${to}不是空的")
          if (fromSite.locked) throw BusinessError("${from}被锁定")
          if (toSite.locked) throw BusinessError("${to}被锁定")
          ctx.task.persistedVariables["from"] = from
          ctx.task.persistedVariables["to"] = to
          MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq ctx.task.id, setValue(RobotTask::persistedVariables, ctx.task.persistedVariables))
        }
      },
      TaskComponentDef(
          "extra", "hongJiang:resetToSite", "重新设置终点并持久化", "", false, listOf(
          TaskComponentParam("to", "终点", "string")
      ), false) { component, ctx ->
        val to = parseComponentParamValue("to", component, ctx) as String? ?: ""
        ctx.task.persistedVariables["to"] = to
        MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq ctx.task.id, setValue(RobotTask::persistedVariables, ctx.task.persistedVariables))
      },
      TaskComponentDef(
          "extra", "hongJiang:resetFromSite", "重新设置起点并持久化", "", false, listOf(
          TaskComponentParam("from", "起点", "string")
      ), false) { component, ctx ->
        val from = parseComponentParamValue("from", component, ctx) as String? ?: ""
        ctx.task.persistedVariables["from"] = from
        MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq ctx.task.id, setValue(RobotTask::persistedVariables, ctx.task.persistedVariables))
      },
      TaskComponentDef(
          "extra", "hongJiang:trigger", "触发刀具返回任务", "", false, listOf(
          TaskComponentParam("to", "终点", "string")
      ), false) { component, ctx ->
        // to为前置任务的起点
        val to = parseComponentParamValue("to", component, ctx) as String? ?: ""
        if (to.isNotBlank()) {
          // 任务逻辑为工位A请求仓库B物料，装满后再送回来，一个工位同时只能下发一个资源请求任务
          // 未完成的返回任务,如任务A->B(表示前置任务),B->A(表示后续返回任务，随前置任务生成而自动生成的)
          val taskIds = cutterBackMap.filter { it.value.to == to }.map { it.key }
          // 按照正常逻辑，taskIds数量为1或0，一个库位对应一个料车，但不排除人为把别的料架运到同一个库位上来导致taskIds大于1
          // 这种人为因素系统无法处理，只能取taskIds的第一个参数作为目标id
          if (taskIds.size > 1) throw BusinessError("异常:刀具工位【${to}】存在多个任务")
          if (taskIds.isNotEmpty()) {
            val taskId = taskIds[0]
            val task = MongoDBManager.collection<RobotTask>().findOne(RobotTask::id eq taskId)
            if (task == null) {     // 该返回任务内存中存在，但DB中不存在：人为删除导致
              cutterBackMap.remove(taskId)
              logger.error("Back task id=$taskId exists in cache, but can not find in DB.")
              throw BusinessError("任务${taskId}不存在")
            } else {      // 从DB中找到对应的返回任务
              if (task.persistedVariables["canSend"] == true) throw BusinessError("任务已下发")
              val preTaskId = task.persistedVariables["preTaskId"] as String?
              if (preTaskId.isNullOrBlank()) {
                // 返回任务中没有保存前置任务信息，除非手动改数据库，否则不可能出现
                logger.error("Can not find pre task for task $taskId, but task $taskId exists in SRD system")
                throw BusinessError("前序任务不存在,本次任务无法完成，请撤销")
              } else {
                val preTask = MongoDBManager.collection<RobotTask>().findOne(RobotTask::id eq preTaskId)
                if (preTask == null) {
                  // 前置任务不存在，被删除
                  logger.warn("pre task id=$preTaskId not exists in DB")
                  throw BusinessError("前置任务${preTaskId}未找到")
                } else {    // 找到前置任务
                  // 检查前置任务状态
                  if (preTask.state < RobotTaskState.Success) throw BusinessError("前置任务${preTaskId}未完成")
//                  if (preTask.state > RobotTaskState.Success) ctx.task.persistedVariables["msg"] = "前序任务异常终止,确定下发吗?"
                  // 检查起点库位
                  val fromId = task.persistedVariables["from"] as String
                  val fromSite = StoreSiteService.getExistedStoreSiteById(fromId)
                  if (!fromSite.filled) throw BusinessError("刀具库位置[$fromId]为空！")
                  if (fromSite.locked) throw BusinessError("刀具库位置[$fromId]已被锁定！")
                  // 检查终点库位
                  val toSite = StoreSiteService.getExistedStoreSiteById(to)
                  if (toSite.locked) throw BusinessError("刀具请求库位[$to]已被锁定！")
                  if (toSite.filled) ctx.task.persistedVariables["msg"] = "刀具请求库位[$to]被非法占用，任务将被挂起"
                  // 前置任务已经结束
                  task.persistedVariables["canSend"] = true
                  if (cutterBackMap[taskId] != null) cutterBackMap[taskId] = cutterBackMap[taskId]!!.copy(canSend = true)
                  MongoDBManager.collection<RobotTask>().updateOne(RobotTask::id eq task.id, setValue(RobotTask::persistedVariables, task.persistedVariables))
                }
              }
            }
          } else throw BusinessError("刀具工位【${to}】没有运输请求任务")
        }

//        if (to.isNotBlank()) {
//          val taskIds = cutterMap.filter { it.value.from == to }.map { it.key }
//          if (taskIds.isNotEmpty()) {
//            logger.debug("find task id = ${taskIds[0]} from cache, which mean pre task is not finished ...")
//            val task = MongoDBManager.collection<RobotTask>().findOne(RobotTask::id eq taskIds[0])
//            if (task == null) {
//              logger.warn("can't find task ${taskIds[0]} from DB, return")
//            } else {
//              if (task.state < RobotTaskState.Success) {
//                logger.debug("pre task ${task.id} is unfinished, return")
//                throw BusinessError("前置任务${task.id}未完成")
//              } else {
//
//              }
//            }
//          } else throw BusinessError("起点为${to}的前置任务不存在")
//        } else throw BusinessError("终点未指定")
      }
  )
}
