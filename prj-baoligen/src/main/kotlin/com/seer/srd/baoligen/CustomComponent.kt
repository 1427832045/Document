package com.seer.srd.baoligen

import com.seer.srd.baoligen.CustomHttpClient.takeMatFinished
import com.seer.srd.baoligen.CustomHttpClient.taskFinished
import com.seer.srd.robottask.component.TaskComponentDef
import com.seer.srd.robottask.component.TaskComponentParam
import com.seer.srd.robottask.component.parseComponentParamValue

object CustomComponent {
    val customComponent: List<TaskComponentDef> = listOf(
        TaskComponentDef(
            "extra", "InfoRemoteTakeMatFinished", "告知对方上位机，AGV在起点已完成取货", "",
            false, listOf(
            TaskComponentParam("currentTransportIndex", "当前运单索引", "int"),
            TaskComponentParam("siteId", "当前站点ID", "string")
        ), false
        ) { component, ctx ->
            val index = parseComponentParamValue("currentTransportIndex", component, ctx) as Int
            val siteId = parseComponentParamValue("siteId", component, ctx) as String
            val task = ctx.task
            val transport = task.transports[index]
            val body = TakeMatFinished(task.id, transport.processingRobot ?: "-", siteId)
            takeMatFinished(body)
        },

        TaskComponentDef(
            "extra", "InfoRemoteTaskFinished", "告知对方上位机，AGV已完成运输任务", "",
            false, listOf(
            TaskComponentParam("fromSiteId", "起点ID", "string"),
            TaskComponentParam("toSiteId", "终点ID", "string")
        ), false
        ) { component, ctx ->
            val fromSiteId = parseComponentParamValue("fromSiteId", component, ctx) as String
            val toSiteId = parseComponentParamValue("toSiteId", component, ctx) as String
            val task = ctx.task
            val body = TaskFinished(task.id, fromSiteId, toSiteId)
            taskFinished(body)
        }
    )
}