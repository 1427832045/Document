package com.seer.srd.robottask.component

import com.seer.srd.operator.AlertInOperator
import com.seer.srd.operator.AlertInOperator.Companion.alertInOperator
import com.seer.srd.util.splitTrim

val operatorComponents: List<TaskComponentDef> = listOf(
    TaskComponentDef(
        "operator", "AlertOperator", "通知手持端", "",
        false, listOf(
            TaskComponentParam("message", "消息", "string"),
            TaskComponentParam("workStations", "通知工位", "string"),
            TaskComponentParam("workTypes", "通知岗位", "string"),
            TaskComponentParam("all", "通知所有(true/false)", "string")
        ), false
    ) { component, ctx ->
        val message = parseComponentParamValue("message", component, ctx) as String
        val all = parseComponentParamValue("all", component, ctx) as String?
        val toAll = all == "true"
        val workStationsStr = parseComponentParamValue("workStations", component, ctx) as String?
        val toWorkStations = splitTrim(workStationsStr, ",")
        val workTypesStr = parseComponentParamValue("workTypes", component, ctx) as String?
        val toWorkTypes = splitTrim(workTypesStr, ",")
        alertInOperator(AlertInOperator(message, toWorkStations, toWorkTypes, toAll))
    }
)