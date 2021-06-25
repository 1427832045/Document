package com.seer.srd.http

import com.seer.srd.*
import com.seer.srd.user.UserManager.validateUserSession
import com.seer.srd.user.UserWithRolesPermissions
import com.seer.srd.util.mapper
import com.seer.srd.util.searchDirectoryFromWorkDirUp
import io.javalin.Javalin
import io.javalin.http.Context
import io.javalin.http.Handler
import io.javalin.http.HandlerType
import io.javalin.http.staticfiles.Location
import io.javalin.plugin.json.JavalinJackson
import org.bson.types.ObjectId
import org.opentcs.access.KernelRuntimeException
import org.opentcs.data.ObjectConflictException
import org.opentcs.data.ObjectUnknownException
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException

object HttpServer {

    const val COOKIE_USER_ID = "srd_user_id"
    const val COOKIE_USER_TOKEN = "srd_user_token"

    const val SESSION_USER = "user"

    private val logger = LoggerFactory.getLogger(HttpServer::class.java)

    private val app: Javalin = Javalin.create { config ->
        JavalinJackson.configure(mapper)
        config.showJavalinBanner = false
        config.requestCacheSize = CONFIG.httpRequestCacheSize
        if (!CONFIG.uiPath.isBlank()) {
            config.addStaticFiles(CONFIG.uiPath, Location.EXTERNAL)
        } else {
            val uiDir = searchDirectoryFromWorkDirUp("ui")
            if (uiDir != null) {
                logger.info("Found UI directory $uiDir")
                config.addStaticFiles(uiDir.absolutePath, Location.EXTERNAL)
            }
        }
    }.ws("/socket", WebSocketManager::onWebSocket)

    val mappings: MutableList<HttpRequestMapping> = CopyOnWriteArrayList()

    fun startHttpServer(port: Int = 80) {
        logger.info("Start HTTP server at $port")
        app.start(port)
    }

    fun handle(vararg reqMappings: HttpRequestMapping) {
        mappings.addAll(reqMappings)
        for (h in reqMappings) {
            val path = CONFIG.apiPrefix + "/" + h.path
            app.addHandler(h.method, path, enhanceHandler(h))
        }
    }

    fun handleWithoutApiPrefix(vararg reqMappings: HttpRequestMapping) {
        mappings.addAll(reqMappings)
        for (h in reqMappings) {
            val path = "/" + h.path
            app.addHandler(h.method, path, enhanceHandler(h))
        }
    }

    private fun enhanceHandler(reqHandler: HttpRequestMapping): Handler {
        return Handler { ctx ->
            try {
                wrap(ctx, reqHandler)
                // ctx.res.characterEncoding = "utf-8"
            } catch (e: Error401) {
                ctx.status(401)
            } catch (e: Error403) {
                ctx.status(403)
                ctx.json(mapOf("permission" to e.message))
            } catch (e: BusinessError) {
                ctx.status(400)
                ctx.json(mapOf("code" to e.javaClass.name, "message" to e.message))
            } catch (e: Error400) {
                ctx.status(400)
                ctx.json(mapOf("code" to e.code, "message" to e.message))
            } catch (e: IllegalArgumentException) {
                ctx.status(400)
                ctx.json(mapOf("message" to e.message))
            } catch (e: ObjectUnknownException) {
                ctx.status(404)
                ctx.json(mapOf("message" to e.message))
            } catch (e: ObjectConflictException) {
                ctx.status(409)
                ctx.json(mapOf("message" to e.message))
            } catch (e: KernelRuntimeException) {
                ctx.status(500)
                ctx.json(mapOf("message" to e.message))
            } catch (e: IllegalStateException) {
                ctx.status(500)
                ctx.json(mapOf("message" to e.message))
            } catch (e: Throwable) {
                if (e is ExecutionException) {
                    when (val ex = e.cause) {
                        is Error401 -> {
                            ctx.status(401)
                        }
                        is Error403 -> {
                            ctx.status(403)
                            ctx.json(mapOf("permission" to ex.message))
                        }
                        is Error400 -> {
                            ctx.status(400)
                            ctx.json(mapOf("code" to ex.code, "message" to ex.message))
                        }
                        is BusinessError -> {
                            ctx.status(400)
                            ctx.json(mapOf("code" to ex.javaClass.name, "message" to ex.message))
                        }
                        is IllegalArgumentException -> {
                            ctx.status(400)
                            ctx.json(mapOf("message" to ex.message))
                        }
                        is ObjectUnknownException -> {
                            ctx.status(404)
                            ctx.json(mapOf("message" to ex.message))
                        }
                        is ObjectConflictException -> {
                            ctx.status(409)
                            ctx.json(mapOf("message" to ex.message))
                        }
                        is KernelRuntimeException -> {
                            ctx.status(500)
                            ctx.json(mapOf("message" to ex.message))
                        }
                        is IllegalStateException -> {
                            ctx.status(500)
                            ctx.json(mapOf("message" to ex.message))
                        }
                        else -> {
                            ctx.status(500)
                            ctx.json(mapOf("message" to ex?.message))
                            logger.error("catch all at http server", ex)
                        }
                    }
                } else {
                    ctx.status(500)
                    ctx.json(mapOf("message" to e.message))
                    logger.error("catch all at http server", e)
                }
            }
        }
    }

    private fun wrap(ctx: Context, mapping: HttpRequestMapping) {
        var urp: UserWithRolesPermissions? = null
        var userId = ctx.cookie(getPortedCookieName(COOKIE_USER_ID, ctx.port()))
        var userToken = ctx.cookie(getPortedCookieName(COOKIE_USER_TOKEN, ctx.port()))
        if (userId.isNullOrBlank() || userToken.isNullOrBlank()) {
            userId = ctx.header("x-pita-user-id")
            userToken = ctx.header("x-pita-user-token")
        }
        if (!(userId.isNullOrBlank() || userToken.isNullOrBlank())) {
            urp = validateUserSession(ObjectId(userId), userToken)
            if (urp != null) {
                ctx.sessionAttribute(SESSION_USER, urp)
                ctx.sessionAttribute("userId", urp.user.id)
            }
        }
        if (mapping.meta.auth) {
            if (urp == null) throw Error401()
            val p = mapping.meta.permission
            if (!urp.user.admin && !p.isNullOrBlank() && !urp.permissions.contains(p)) throw Error403(
                mapping.meta.permission
            )
        }

        // Log the request when method is POST or PUT
        if (ctx.method() == "POST" || ctx.method() == "PUT") {
            logger.info(
                "Receive HTTP ${ctx.method()}: \n" +
                    "$userId@${ctx.ip()} \n" +
                    "${ctx.fullUrl()} \n" +
                    "${ctx.headerMap()}"
            )
        }

        mapping.handler(ctx)
    }

    fun auth() = ReqMeta(true)

    fun noAuth() = ReqMeta(false)

    fun permit(permission: String) = ReqMeta(true, permission)

}

class HttpRequestMapping(
    var method: HandlerType,
    var path: String,
    var handler: (ctx: Context) -> Unit,
    var meta: ReqMeta = ReqMeta(),
    var withoutApiPrefix: Boolean = false
)

data class ReqMeta(
    var auth: Boolean = true,
    var permission: String? = null,
    var test: Boolean = false,
    var page: Boolean = false,
    var reqBodyDemo: List<String>? = null
)

class Handlers(private val pathPrefix: String = "") {
    fun get(path: String, handler: (ctx: Context) -> Unit, meta: ReqMeta = ReqMeta()) {
        HttpServer.handle(HttpRequestMapping(HandlerType.GET, "$pathPrefix/$path", handler, meta))
    }

    fun post(path: String, handler: (ctx: Context) -> Unit, meta: ReqMeta = ReqMeta()) {
        HttpServer.handle(HttpRequestMapping(HandlerType.POST, "$pathPrefix/$path", handler, meta))
    }

    fun put(path: String, handler: (ctx: Context) -> Unit, meta: ReqMeta = ReqMeta()) {
        HttpServer.handle(HttpRequestMapping(HandlerType.PUT, "$pathPrefix/$path", handler, meta))
    }

    fun delete(path: String, handler: (ctx: Context) -> Unit, meta: ReqMeta = ReqMeta()) {
        HttpServer.handle(HttpRequestMapping(HandlerType.DELETE, "$pathPrefix/$path", handler, meta))
    }
}


class HandlersWithoutApiPrefix(private val pathPrefix: String = "") {
    fun get(path: String, handler: (ctx: Context) -> Unit, meta: ReqMeta = ReqMeta()) {
        HttpServer.handleWithoutApiPrefix(HttpRequestMapping(HandlerType.GET, "$pathPrefix/$path", handler, meta, true))
    }

    fun post(path: String, handler: (ctx: Context) -> Unit, meta: ReqMeta = ReqMeta()) {
        HttpServer.handleWithoutApiPrefix(HttpRequestMapping(HandlerType.POST, "$pathPrefix/$path", handler, meta, true))
    }

    fun put(path: String, handler: (ctx: Context) -> Unit, meta: ReqMeta = ReqMeta()) {
        HttpServer.handleWithoutApiPrefix(HttpRequestMapping(HandlerType.PUT, "$pathPrefix/$path", handler, meta, true))
    }

    fun delete(path: String, handler: (ctx: Context) -> Unit, meta: ReqMeta = ReqMeta()) {
        HttpServer.handleWithoutApiPrefix(HttpRequestMapping(HandlerType.DELETE, "$pathPrefix/$path", handler, meta, true))
    }
}