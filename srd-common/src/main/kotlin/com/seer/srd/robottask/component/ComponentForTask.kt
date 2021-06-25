package com.seer.srd.robottask.component

import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.device.pager.FIELD_CANNOT_ABORT_BY_PAGER
import com.seer.srd.robottask.RobotTask
import org.litote.kmongo.eq
import org.litote.kmongo.findOne
import org.litote.kmongo.set
import org.litote.kmongo.setTo

val taskComponents: List<TaskComponentDef> = listOf(
    TaskComponentDef(
        "task", "SetOutOrderNo", "设置外部单号", "",
        false, listOf(
            TaskComponentParam("outOrderNo", "单号", "string")
        ), false
    ) { component, ctx ->
        val outOrderNo = parseComponentParamValue("outOrderNo", component, ctx) as String
        ctx.task.outOrderNo = outOrderNo
    },
    TaskComponentDef(
        "task", "SetTaskExtraVariables", "设置任务变量", "",
        false, listOf(
            TaskComponentParam("name", "变量名", "string"),
            TaskComponentParam("value", "值", "string")
        ), false
    ) { component, ctx ->
        val name = parseComponentParamValue("name", component, ctx) as String
        val value = parseComponentParamValue("value", component, ctx)
        ctx.task.persistedVariables[name] = value
        // 这里不调用持久化，而是等这一阶段结束再持久化
    },
    TaskComponentDef(
        "task", "MarkTaskCannotAbortByPager", "将任务标记为无法被呼叫器撤销的状态", "",
        false, listOf(
        ), false
    ) { _, ctx ->
        ctx.task.persistedVariables[FIELD_CANNOT_ABORT_BY_PAGER] = true
        val c = MongoDBManager.collection<RobotTask>()
        val taskFromDB = c.findOne(RobotTask::id eq ctx.task.id)
            ?: throw BusinessError("Cannot find task=${ctx.task.id} from DB")
        taskFromDB.persistedVariables.putAll(ctx.task.persistedVariables)
        ctx.task.persistedVariables = taskFromDB.persistedVariables
        c.updateOne(RobotTask::id  eq taskFromDB.id, set(
            RobotTask::persistedVariables setTo taskFromDB.persistedVariables
        ))
    },
    TaskComponentDef(
        "task", "AppendTaskWorkType", "追加关联岗位", "",
        false, listOf(
            TaskComponentParam("workType", "岗位", "string")
        ), false
    ) { component, ctx ->
        val workType = parseComponentParamValue("workType", component, ctx) as String
        ctx.task.workTypes.add(workType)
    },
    TaskComponentDef(
        "task", "AppendTaskWorkStation", "追加关联工位", "",
        false, listOf(
            TaskComponentParam("workStation", "工位", "string")
        ), false
    ) { component, ctx ->
        val workStation = parseComponentParamValue("workStation", component, ctx) as String
        ctx.task.workStations.add(workStation)
    }
)