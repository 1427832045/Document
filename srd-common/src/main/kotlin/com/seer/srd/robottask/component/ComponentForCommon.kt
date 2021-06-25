@file:Suppress("BooleanLiteralArgument")

package com.seer.srd.robottask.component

import com.seer.srd.BusinessError
import com.seer.srd.util.isFalseLike
import com.seer.srd.util.withMemoryLock

val commonComponents: List<TaskComponentDef> = listOf(
    TaskComponentDef(
        "common", "CodeBlock", "组合子组件", "组合一组子组件，用于只接受一个组件的地方，如if条件组件",
        true, emptyList(), false
    ) { component, ctx ->
        processComponents(component.components, ctx)
    },
    TaskComponentDef(
        "common", "WithMemoryLock", "内存锁", "",
        true, listOf(TaskComponentParam("lockName", "锁的名字", "string")), false
    ) { component, ctx ->
        val lockName = parseComponentParamValue("lockName", component, ctx) as String
        withMemoryLock(lockName, -1, 66L) { processComponents(component.components, ctx) }
    },
    TaskComponentDef(
        "common", "ThrowToWait", "继续等待", "",
        false, listOf(TaskComponentParam("reason", "原因", "string")), false
    ) { component, ctx ->
        val reason = parseComponentParamValue("reason", component, ctx) as? String?
        throw BusinessError(reason ?: "")
    },
    TaskComponentDef(
        "common", "SetRuntimeVariable", "设置运行时变量", "",
        false, listOf(
            TaskComponentParam("value", "取值", "string"),
            TaskComponentParam("name", "变量名", "string")
        ), false
    ) { component, ctx ->
        val value = parseComponentParamValue("value", component, ctx)
        val name = parseComponentParamValue("name", component, ctx) as String
        ctx.runtimeVariables[name] = value
    },
    TaskComponentDef(
        "common", "EnsureNotFalseValue", "检查值不为假值", "否则抛出异常",
        false, listOf(
            TaskComponentParam("value", "值", "string"),
            TaskComponentParam("errorMessage", "错误消息", "string")
        ), false
    ) { component, ctx ->
        val value = parseComponentParamValue("value", component, ctx)
        val errorMessage = parseComponentParamValue("errorMessage", component, ctx) as String?
        if (isFalseLike(value)) throw BusinessError(errorMessage)
    }
)