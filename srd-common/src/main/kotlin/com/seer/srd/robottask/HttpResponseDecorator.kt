package com.seer.srd.robottask

import io.javalin.http.Context
import java.util.concurrent.ConcurrentHashMap

typealias HttpResponseDecorator = (task: RobotTask, ctx: Context, error: Exception?) -> Unit

val decorators: MutableMap<String, HttpResponseDecorator> = ConcurrentHashMap()

fun registerHttpResponseDecorator(name: String, decorator: HttpResponseDecorator) {
    decorators[name] = decorator
}

fun getHttpResponseDecorator(name: String?): HttpResponseDecorator? {
    if (name.isNullOrBlank()) return null
    return decorators[name]
}