package com.seer.srd.huicang

import com.seer.srd.robottask.component.TaskComponentDef
import com.seer.srd.robottask.component.TaskComponentParam
import com.seer.srd.robottask.component.parseComponentParamValue

object CustomComponent {
    val customComponent: List<TaskComponentDef> = listOf(
        TaskComponentDef(
            "extra", "BuildAndPersistReturnBody", "将需要回传的信息记录到任务中", "",
            false, listOf(
            TaskComponentParam("wallId", "wallID", "string"),
            TaskComponentParam("indexInWall", "indexInWall", "string"),
            TaskComponentParam("systemId", "systemId", "string")
        ), true
        ) { component, ctx ->
            val wallId = parseComponentParamValue("wallId", component, ctx) as String
            val indexInWall = parseComponentParamValue("indexInWall", component, ctx) as String
            val systemId = parseComponentParamValue("systemId", component, ctx) as String
            val returnBody = """{"wallID": "$wallId", "indexInWall": "$indexInWall", "systemId": "$systemId"}"""
            ctx.task.persistedVariables[component.returnName ?: "returnBody"] = returnBody
        }
    )
}