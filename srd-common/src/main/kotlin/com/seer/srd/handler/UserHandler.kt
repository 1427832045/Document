package com.seer.srd.handler

import com.seer.srd.Error400
import com.seer.srd.Error401
import com.seer.srd.Error404
import com.seer.srd.I18N.locale
import com.seer.srd.db.MongoDBManager.collection
import com.seer.srd.eventlog.EventLogLevel
import com.seer.srd.eventlog.SystemEvent
import com.seer.srd.eventlog.recordSystemEventLog
import com.seer.srd.http.*
import com.seer.srd.http.HttpServer.COOKIE_USER_ID
import com.seer.srd.http.HttpServer.COOKIE_USER_TOKEN
import com.seer.srd.user.HumanUser
import com.seer.srd.user.UserManager.createRole
import com.seer.srd.user.UserManager.createUser
import com.seer.srd.user.UserManager.getAllRoles
import com.seer.srd.user.UserManager.getUserForEditById
import com.seer.srd.user.UserManager.getUserRoleById
import com.seer.srd.user.UserManager.hashPassword
import com.seer.srd.user.UserManager.listRoles
import com.seer.srd.user.UserManager.listUser
import com.seer.srd.user.UserManager.signIn
import com.seer.srd.user.UserManager.signOut
import com.seer.srd.user.UserManager.updateRole
import com.seer.srd.user.UserManager.updateUser
import com.seer.srd.user.UserRole
import com.seer.srd.user.permissions
import io.javalin.http.Context
import org.bson.types.ObjectId
import org.litote.kmongo.eq
import org.litote.kmongo.findOne

fun handlePing(ctx: Context) {
    val urp = getRequestUserRolePermission(ctx)
    if (urp != null) {
        ctx.json(
            mapOf(
                "id" to urp.user.id.toString(),
                "username" to urp.user.username,
                "admin" to urp.user.admin,
                "roles" to urp.roles,
                "permissions" to urp.permissions
            )
        )
    } else {
        throw Error401()
    }
}

data class SignInReq(
    var username: String = "",
    var password: String = ""
)

// 用户登录接口
fun handleSignIn(ctx: Context) {
    val req = ctx.bodyAsClass(SignInReq::class.java)
    if (req.username.isBlank() || req.password.isBlank())
        throw Error400(
            "MissingFields",
            locale("MissingUsernamePassword", getReqLang(ctx))
        )
    
    val session = signIn(req.username, req.password)
    ctx.json(mapOf("userId" to session.userId, "userToken" to session.userToken))
    
    recordSystemEventLog("User", EventLogLevel.Info, SystemEvent.UserSignIn, req.username)
    
    setUserSessionCookies(ctx, session.userId.toString(), session.userToken)
}

// 登出接口
fun handleSignOut(ctx: Context) {
    val urp = ensureRequestUserRolePermission(ctx)
    signOut(urp.user.id)
    setUserSessionCookies(ctx, "", "") // 清 cookies
    ctx.status(204)
}

private fun setUserSessionCookies(ctx: Context, id: String, token: String) {
    ctx.cookie(buildSessionCookie(getPortedCookieName(COOKIE_USER_ID, ctx.port()), id))
    ctx.cookie(buildSessionCookie(getPortedCookieName(COOKIE_USER_TOKEN, ctx.port()), token))
}

fun handleListUser(ctx: Context) {
    val pageNo = getPageNo(ctx)
    val pageSize = getPageSize(ctx)
    val r = listUser(pageNo, pageSize)
    r.page = r.page.map { it.copy(password = "") }
    ctx.json(r)
}

fun handleGetUserForEdit(ctx: Context) {
    val id = ctx.pathParam("id")
    val user = getUserForEditById(ObjectId(id))
    if (user == null)  ctx.json(mapOf("code" to "NoSuchUser", "message" to id))
    else ctx.json(user)
}

data class CreateUserReq(
    var username: String = "",
    var password: String = "",
    val disabled: Boolean = false,
    var rolesStr: String = ""
)

fun handleCreateUser(ctx: Context) {
    val req = ctx.bodyAsClass(CreateUserReq::class.java)
    if (req.username.isBlank()) throw Error400("UsernameNeeded", locale("UsernameNeeded", getReqLang(ctx)))
    checkUsernameDuplicated(null, req.username, getReqLang(ctx))
    val roleIds = req.rolesStr.split(",").filter { it.isNotBlank() }.map { ObjectId(it.trim()) }
    req.password = hashPassword(req.password)
    createUser(HumanUser(ObjectId(), req.username, req.password, "", false, req.disabled, roleIds))
    ctx.status(201)
}

data class UpdateUserReq(
    var username: String = "",
    var password: String = "",
    val disabled: Boolean = false,
    var rolesStr: String = ""
)

fun handleUpdateUser(ctx: Context) {
    val id = ctx.pathParam("id")
    val req = ctx.bodyAsClass(UpdateUserReq::class.java)
    checkAdmin(ObjectId(id), req.username, getReqLang(ctx))
    checkUsernameDuplicated(ObjectId(id), req.username, getReqLang(ctx))
    val roleIds = req.rolesStr.split(",").filter { it.isNotBlank() }.map { ObjectId(it.trim()) }
    if (req.password.isNotBlank()) req.password = hashPassword(req.password)
    updateUser(
        ObjectId(id),
        HumanUser(ObjectId(id), req.username, req.password, "", false, req.disabled, roleIds)
    )
    ctx.status(204)
}

private fun checkUsernameDuplicated(id: ObjectId?, username: String, lang: String) {
    val c = collection<HumanUser>()
    val user = c.findOne(HumanUser::username eq username)
    if (user != null && (id == null || user.id != id))
        throw Error400("DuplicatedUsername", locale("DuplicatedUsername", lang))
}

private fun checkAdmin(id: ObjectId, username: String, lang: String) {
    val c = collection<HumanUser>()
    val user = c.findOne(HumanUser::id eq id)
    if (user != null && user.admin && user.username != username) throw Error400("UpdateAdminError", locale("UpdateAdminError", lang))
}

fun handleListRoles(ctx: Context) {
    val pageNo = getPageNo(ctx)
    val pageSize = getPageSize(ctx)
    ctx.json(listRoles(pageNo, pageSize))
}

fun handleGetAllRoles(ctx: Context) {
    ctx.json(getAllRoles())
}

fun handleFindRole(ctx: Context) {
    val id = ctx.pathParam("id")
    val role = getUserRoleById(ObjectId(id))
    if (role == null)  ctx.json(mapOf("code" to "NoSuchRole", "message" to id))
    else ctx.json(role)
}

class CreateOrUpdateRoleReq(
    var name: String = "",
    var permissionsStr: String = ""
)

fun handleCreateRole(ctx: Context) {
    val req = ctx.bodyAsClass(CreateOrUpdateRoleReq::class.java)
    if (req.name.isBlank()) throw Error400("RoleNameNeeded", locale("RoleNameNeeded", getReqLang(ctx)))
    checkRoleDuplicated(null, req.name, getReqLang(ctx))
    val permissions = req.permissionsStr.split(",").filter { it.isNotBlank() }.map { it.trim() }
    val id = createRole(UserRole(ObjectId(), req.name, permissions))
    ctx.status(201)
    ctx.json(mapOf("id" to id))
}

fun handleUpdateRole(ctx: Context) {
    val id = ctx.pathParam("id")
    val req = ctx.bodyAsClass(CreateOrUpdateRoleReq::class.java)
    checkRoleDuplicated(ObjectId(id), req.name, getReqLang(ctx))
    val permissions = req.permissionsStr.split(",").filter { it.isNotBlank() }.map { it.trim() }
    updateRole(UserRole(ObjectId(id), req.name, permissions))
    ctx.status(204)
}

private fun checkRoleDuplicated(id: ObjectId?, name: String, lang: String) {
    val c = collection<UserRole>()
    val role = c.findOne(UserRole::name eq name)
    if (role != null && (id == null || role.id != id))
        throw Error400("DuplicatedRoleName", locale("DuplicatedRoleName", lang))
}

fun handleListPermissionDefs(ctx: Context) {
    val lang = getReqLang(ctx)
    val defs = permissions.map { p ->
        mapOf("name" to p, "label" to locale("P_$p", lang))
    }
    ctx.json(defs)
}
