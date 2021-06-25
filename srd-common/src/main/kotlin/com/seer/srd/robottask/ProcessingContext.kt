package com.seer.srd.robottask

import io.javalin.http.Context

class ProcessingContext(
    val task: RobotTask, // 任务
    val transportIndex: Int, // 当前处理的运单索引，注意在处理运单前，如处理 HTTP 接口时，该值无意义
    val stageIndex: Int // 当前处理的阶段的索引
) {
    val runtimeVariables: MutableMap<String, Any?> = HashMap() // 内存中的变量池，宕机后丢失，因此只能存储临时变量
    val taskDef: RobotTaskDef? = getRobotTaskDef(task.def)
    val transportDef: RobotTransportDef?
    val stageDef: RobotStageDef?
    val transport: RobotTransport? = if (transportIndex >= 0) task.transports[transportIndex] else null
    val stage: RobotStage?

    var httpCtx: Context? = null// HTTP 请求上下文

    init {
        transportDef = if (taskDef != null && transportIndex >= 0) taskDef.transports[transportIndex] else null
        stage = if (transport != null && stageIndex >= 0) transport.stages[stageIndex] else null
        stageDef = if (transportDef != null && stageIndex >= 0) transportDef.stages[stageIndex] else null
    }

    fun setRuntimeVariable(name: String?, value: Any?) {
        if (name.isNullOrBlank()) return
        runtimeVariables[name] = value
    }

    fun stageIndexToDestinationIndex(): Int {
        val i = transportDef?.stageIndexOfFirstDestination()
        return if (i == null) stageIndex else stageIndex - i
    }
}