package com.seer.srd.robottask.component

import com.seer.srd.ComponentDefNotFound
import com.seer.srd.NoTaskComponentParamDef
import com.seer.srd.robottask.*
import com.seer.srd.util.mapper
import org.mvel2.MVEL
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.ArrayList

private val LOG = LoggerFactory.getLogger("com.seer.srd.robottask")

//任务组件
class TaskComponent(
    var def: String = "",
    var params: Map<String, String> = HashMap(),
    var returnName: String? = null
) : HasTaskComponents {
    override var components: List<TaskComponent> = ArrayList()
}

interface HasTaskComponents {
    var components: List<TaskComponent>
}

typealias TaskComponentFunc = (component: TaskComponent, ctx: ProcessingContext) -> Unit

/*
任务组件详细信息
 */
data class TaskComponentDef(
    var group: String,
    var name: String,
    var label: String,
    var description: String,
    var wrap: Boolean,
    var params: List<TaskComponentParam>,
    var returnResult: Boolean,
    var func: TaskComponentFunc
)

//任务组件参数
data class TaskComponentParam(
    var name: String,
    var label: String,
    var type: String,
    var inputWidth: String? = null,
    var defaultValue: String? = null
)

//将任务组件详情保存在map里面
private val componentDefs: MutableMap<String, TaskComponentDef> = ConcurrentHashMap()

val ctxVariables: MutableMap<String, Any?> = ConcurrentHashMap()

//取出任务组件信息map中其中某一组件的详细信息
fun getComponentDef(name: String): TaskComponentDef {
    return componentDefs[name] ?: throw ComponentDefNotFound(name)
}

//列出所有组件的详细信息并转化成list
fun listComponentDefs(): List<TaskComponentDef> {
    return componentDefs.values.toList()
}

//注册任务组件们
fun registerRobotTaskComponents() {
    registerRobotTaskComponents(commonComponents)
    registerRobotTaskComponents(apiComponents)
    registerRobotTaskComponents(logicComponents)
    registerRobotTaskComponents(operatorComponents)
    registerRobotTaskComponents(storeSiteComponents)
    registerRobotTaskComponents(stageComponents)
    registerRobotTaskComponents(taskComponents)
    registerRobotTaskComponents(transportComponents)
    registerRobotTaskComponents(httpRequestComponents)
    registerRobotTaskComponents(modbusTcpMasterComponents)
    registerRobotTaskComponents(vehicleComponents)
    registerRobotTaskComponents(locationPointComponents)
}

//将每个组件的def保存在组件信息map中
fun registerRobotTaskComponents(components: List<TaskComponentDef>) {
    for (c in components) componentDefs[c.name] = c
}

fun processComponents(components: List<TaskComponent>, ctx: ProcessingContext) {
    if (components.isEmpty()) return
    for (component in components) {
        // getSystemLogger().debug(`process component name=${component.def}, params=${JSON.stringify(component.params)}`)

        RobotTaskService.throwIfTaskAborted(ctx.task.id)

        val func = getComponentDef(component.def).func
        func(component, ctx)
    }
}

fun parseComponentParamValue(nameInput: String, component: TaskComponent, ctx: ProcessingContext): Any? {
    if (component.params.isEmpty()) return null
    val name = nameInput.trim()
    val comDef = getComponentDef(component.def)
    val paramDef = comDef.params.find { pd -> pd.name == name } ?: throw NoTaskComponentParamDef(name)

    var strValue = component.params[name] ?: ""
    strValue = strValue.trim()
    return when {
        strValue == "=[now]" -> {
            System.currentTimeMillis()
        }
        strValue.isBlank() -> ""
        strValue[0] == '=' -> {
            val value = parseExpression(strValue.substring(1), ctx)
            LOG.debug("component param expression, ${name}=${value}")
            value
        }
        else -> {
            when (paramDef.type) {
                "int" -> strValue.toInt()
                "float" -> strValue.toDouble()
                else -> strValue
            }
        }
    }
}

fun parseExpression(expression: String, ctx: ProcessingContext): Any? {
    val context: MutableMap<String, Any?> = HashMap()

    context["task"] = ctx.task
    context["taskDef"] = ctx.taskDef
    context["transportIndex"] = ctx.transportIndex
    context["transport"] = ctx.transport
    context["transportDef"] = ctx.transportDef
    context["stageIndex"] = ctx.stageIndex
    context["stage"] = ctx.stage
    context["stageDef"] = ctx.stageDef
    context["httpCtx"] = ctx.httpCtx

    val bodyStr = ctx.httpCtx?.body()
    if (!bodyStr.isNullOrBlank()) {
        context["httpBody"] = mapper.readValue(bodyStr, HashMap::class.java)
    } else {
        context["httpBody"] = emptyMap<String, String>()
    }

    context.putAll(ctxVariables)
    context.putAll(ctx.task.persistedVariables)
    context.putAll(ctx.runtimeVariables)

    ctx.taskDef?.transports?.forEachIndexed { i, transportDef ->
        if (!transportDef.refName.isBlank()) {
            context[transportDef.refName] = ctx.task.transports[i]
        }
        transportDef.stages.forEachIndexed { j, stageDef ->
            if (!stageDef.refName.isBlank()) {
                context[stageDef.refName] = ctx.task.transports[i].stages[j]
            }
        }
    }

    return MVEL.eval(expression, context)
}

