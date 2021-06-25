package com.seer.srd.robottask.component

import com.seer.srd.BusinessError
import com.seer.srd.SkipCurrentTransport
import com.seer.srd.robottask.RobotTaskService
import com.seer.srd.robottask.RobotTransport
import com.seer.srd.robottask.RobotTransportState
import java.time.Instant

val transportComponents: List<TaskComponentDef> = listOf(
    TaskComponentDef(
        "transport", "SetDeadline", "设置Deadline", "",
        false, listOf(
            TaskComponentParam("transport", "运单", "string"),
            TaskComponentParam("deadline", "deadline", "string")
        ), false
    ) { component, ctx ->
        val transport = parseComponentParamValue("transport", component, ctx) as RobotTransport
        val deadline = parseComponentParamValue("deadline", component, ctx) as String
        transport.deadline = Instant.parse(deadline) // todo
    },
    TaskComponentDef(
        "transport", "SetIntendedRobot", "设置指定机器人", "",
        false, listOf(
            TaskComponentParam("transport", "运单", "string"),
            TaskComponentParam("robot", "指定机器人", "string")
        ), false
    ) { component, ctx ->
        val transport = parseComponentParamValue("transport", component, ctx) as RobotTransport
        val robot = parseComponentParamValue("robot", component, ctx) as String
        transport.intendedRobot = robot
    },
    TaskComponentDef(
        "transport", "SetCategory", "设置Category", "",
        false, listOf(
        TaskComponentParam("transport", "运单", "string"),
        TaskComponentParam("category", "category", "string")
    ), false
    ) { component, ctx ->
        val transport = parseComponentParamValue("transport", component, ctx) as RobotTransport
        val category = parseComponentParamValue("category", component, ctx) as String
        transport.category = category
    },
    TaskComponentDef(
        "transport", "SkipTransport", "跳过指定运单", "",
        false, listOf(
            TaskComponentParam("transportIndex", "运单索引", "int"),
            TaskComponentParam("reason", "原因", "string")
        ), false
    ) { component, ctx ->
        val index = parseComponentParamValue("transportIndex", component, ctx) as Int
        val reason = parseComponentParamValue("reason", component, ctx) as String
        val transport = ctx.task.transports[index]
        RobotTaskService.updateTransportStateToFinal(RobotTransportState.Skipped, reason, transport, ctx.task)
    },
    TaskComponentDef(
        "transport", "SkipCurrentTransport", "跳过当前运单", "",
        false, listOf(
            TaskComponentParam("reason", "原因", "string")
        ), false
    ) { component, ctx ->
        val reason = parseComponentParamValue("reason", component, ctx) as String
        throw SkipCurrentTransport(reason)
    },
    TaskComponentDef(
        "transport", "SkipTransports", "跳过指定多个运单", "",
        false, listOf(
        TaskComponentParam("startIndex", "运单起始索引", "int"),
        TaskComponentParam("endIndex", "运单结束索引", "int"),
        TaskComponentParam("reason", "原因", "string")
    ), false
    ) { component, ctx ->
        val fromIndex = parseComponentParamValue("startIndex", component, ctx) as Int
        val toIndex = parseComponentParamValue("endIndex", component, ctx) as Int
        val reason = parseComponentParamValue("reason", component, ctx) as String
        if (fromIndex >= toIndex) throw BusinessError("起始索引必须小于结束索引")
        for (i in fromIndex..toIndex) RobotTaskService.updateTransportStateToFinal(RobotTransportState.Skipped, reason, ctx.task.transports[i], ctx.task)
    }
)
