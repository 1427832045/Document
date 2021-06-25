@file:Suppress("BooleanLiteralArgument")

package com.seer.srd.robottask.component

import com.seer.srd.BusinessError
import com.seer.srd.util.isFalseLike
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory

private val LOG = LoggerFactory.getLogger("com.seer.srd.robottask")

val logicComponents: List<TaskComponentDef> = listOf(
    TaskComponentDef(
        "logic", "If", "if", "如果值为真，执行第一个组件，否则执行第二个。第二个组件可以为空",
        true, listOf(
            TaskComponentParam("value", "值", "string")
        ), false
    ) { component, ctx ->
        val value = parseComponentParamValue("value", component, ctx)
        val componentNum = component.components.size
        if (!(componentNum == 1 || componentNum == 2)) throw BusinessError("if应有一个或两个子组件")
        if (!isFalseLike(value)) {
            processComponents(listOf(component.components[0]), ctx)
        } else {
            if (componentNum == 2) processComponents(listOf(component.components[1]), ctx)
        }
    },
    TaskComponentDef(
        "logic", "IfNot", "if not", "如果值为假，执行第一个组件，否则执行第二个。第二个组件可以为空",
        true, listOf(
            TaskComponentParam("value", "值", "string")
        ), false
    ) { component, ctx ->
        val value = parseComponentParamValue("value", component, ctx)
        val componentNum = component.components.size
        if (!(componentNum == 1 || componentNum == 2)) throw BusinessError("if应有一个或两个子组件")
        if (isFalseLike(value)) {
            processComponents(listOf(component.components[0]), ctx)
        } else {
            if (componentNum == 2) processComponents(listOf(component.components[1]), ctx)
        }
    },
    TaskComponentDef(
        "logic", "CompareMatch", "比较两个值匹配", "按指定方法比较两个值，如果不符合，抛出异常",
        false, listOf(
            TaskComponentParam("v1", "值1", "string"),
            TaskComponentParam("v2", "值2", "string"),
            TaskComponentParam("operator", "比较方法", "string"),
            TaskComponentParam("message", "错误消息", "string")
        ), false
    ) { component, ctx ->
        val v1 = parseComponentParamValue("v1", component, ctx)
        val v2 = parseComponentParamValue("v2", component, ctx)
        val operator = parseComponentParamValue("operator", component, ctx) as String
        val message = parseComponentParamValue("message", component, ctx) as String
        when (operator) {
            "=", "==" ->
                if (v1 != v2) throw BusinessError(StringUtils.firstNonBlank(message, "不相等"))
            "<>", "!=" ->
                if (v1 == v2) throw BusinessError(StringUtils.firstNonBlank(message, "不不等"))
            ">" ->
                if ((v1 as Int) <= (v2 as Int)) throw BusinessError(StringUtils.firstNonBlank(message, "不大于"))
            "<" ->
                if ((v1 as Int) >= (v2 as Int)) throw BusinessError(StringUtils.firstNonBlank(message, "不小于"))
            ">=" ->
                if ((v1 as Int) < (v2 as Int)) throw BusinessError(StringUtils.firstNonBlank(message, "不大于等于"))
            "<=" ->
                if ((v1 as Int) > (v2 as Int)) throw BusinessError(StringUtils.firstNonBlank(message, "不小于等于"))
        }
    },
    TaskComponentDef(
        "logic", "TryCatch", "异常处理", "执行除最后一个组件之外的所有组件，当有异常抛出时，停止执行，执行最后一个组件",
        true, emptyList(), false
    ) { component, ctx ->
        val components = component.components
        if (components.isNotEmpty()) {
            try {
                processComponents(components.subList(0, components.size - 1), ctx)
            } catch (err: Exception) {
                LOG.error("Component TryCatch", err)
                processComponents(components.subList(components.size - 1, components.size), ctx)
            }
        }
    },
    TaskComponentDef(
        "logic", "EmptyToRun", "当变量为空时执行", "",
        true, listOf(TaskComponentParam("value", "值", "string")), false
    ) { component, ctx ->
        val value = parseComponentParamValue("value", component, ctx)
        if (isFalseLike(value)) processComponents(component.components, ctx)
    }
)