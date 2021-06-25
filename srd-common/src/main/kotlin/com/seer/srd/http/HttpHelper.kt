package com.seer.srd.http

import com.seer.srd.Error400
import com.seer.srd.Error401
import com.seer.srd.http.HttpServer.SESSION_USER
import com.seer.srd.user.UserWithRolesPermissions
import io.javalin.http.Context
import javax.servlet.http.Cookie

fun getPortedCookieName(name: String, port: Int): String {
    return "$name-$port"
}

fun buildSessionCookie(name: String, value: String): Cookie {
    val c = Cookie(name, value)
    c.isHttpOnly = true
    c.path = "/"
    return c
}

fun getRequestUserRolePermission(ctx: Context): UserWithRolesPermissions? {
    return ctx.sessionAttribute<UserWithRolesPermissions>(SESSION_USER)
}

fun ensureRequestUserRolePermission(ctx: Context): UserWithRolesPermissions {
    return ctx.sessionAttribute<UserWithRolesPermissions>(SESSION_USER) ?: throw Error401()
}

fun getPageNo(ctx: Context): Int {
    val pageNo = ctx.queryParam("pageNo")?.toInt() ?: 1
    if (pageNo <= 0) throw Error400("BadPageNo", "BadPageNo")
    return pageNo
}

fun getPageSize(ctx: Context): Int {
    val pageSize = ctx.queryParam("pageSize")?.toInt() ?: 20
    if (pageSize <= 0) throw Error400("BadPageSize", "BadPageSize")
    return pageSize
}

fun queryParamsMapToSimpleMap(map: Map<String, List<String>>): MutableMap<String, String> {
    val result: MutableMap<String, String> = HashMap()
    for (key in map.keys) {
        val list = map[key] ?: continue
        result[key] = if (list.isNotEmpty()) list.first() else ""
    }
    return result
}

fun getReqLang(ctx: Context): String {
    val lang = ctx.header("X-Req-Lang")
    return if (lang.isNullOrBlank()) "zh" else lang
}