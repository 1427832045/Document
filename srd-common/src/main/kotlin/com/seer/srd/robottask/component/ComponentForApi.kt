@file:Suppress("BooleanLiteralArgument")

package com.seer.srd.robottask.component

import com.seer.srd.BusinessError
import com.seer.srd.Error400
import com.seer.srd.util.isFalseLike
import java.util.regex.Pattern

val apiComponents: List<TaskComponentDef> = listOf(
    TaskComponentDef(
        "api", "TrimRequired", "请求参数不为空", "",
        false, listOf(
            TaskComponentParam("value", "值", "string"),
            TaskComponentParam("message", "错误消息", "string")
        ), true
    ) { component, ctx ->
        val value = parseComponentParamValue("value", component, ctx)
        val message = parseComponentParamValue("message", component, ctx) as String?
        if (isFalseLike(value)) throw BusinessError(message)
        ctx.setRuntimeVariable(component.returnName, value)
    },
    TaskComponentDef(
        "api", "MatchRegex", "匹配模式", "",
        false, listOf(
            TaskComponentParam("value", "值", "string"),
            TaskComponentParam("regex", "正则表达式", "string"),
            TaskComponentParam("message", "错误消息", "string")
        ), false
    ) { component, ctx ->
        val value = parseComponentParamValue("value", component, ctx) as String
        val regex = parseComponentParamValue("regex", component, ctx) as String
        val message = parseComponentParamValue("message", component, ctx) as String
        val pattern = Pattern.compile(regex)
        if (!pattern.matcher(value).matches()) throw Error400("NotMathPattern", "$message $value")
    }
)