package com.seer.srd.mingzhi

import com.seer.srd.Application
import com.seer.srd.RetryMaxError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.http.Handlers
import com.seer.srd.http.HttpRequestMapping
import com.seer.srd.http.HttpServer.handle
import com.seer.srd.http.HttpServer.noAuth
import com.seer.srd.http.ReqMeta
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.RobotTaskState
import com.seer.srd.robottask.component.TaskComponentDef
import com.seer.srd.robottask.component.TaskComponentParam
import com.seer.srd.robottask.component.parseComponentParamValue
import com.seer.srd.robottask.component.registerRobotTaskComponents
import com.seer.srd.setVersion
import com.seer.srd.util.HttpClient.buildHttpClient
import com.seer.srd.util.loadConfig
import io.javalin.http.Context
import io.javalin.http.HandlerType
import org.litote.kmongo.eq
import org.litote.kmongo.findOne
import org.slf4j.LoggerFactory
import retrofit2.Call
import retrofit2.http.POST
import retrofit2.http.Path
import kotlin.random.Random

object MzApp {
    
    private val logger = LoggerFactory.getLogger(MzApp::class.java)
    
    private val customConfig = loadConfig("srd-config.yaml", CustomConfig::class) ?: CustomConfig()
    
    private val upHttpClient = buildHttpClient(customConfig.upUrl, UpHttpClient::class.java)
    
    private val extraComponents: List<TaskComponentDef> = listOf(
        TaskComponentDef(
            "extra", "CanPick", "是否能取货", "", false, listOf(
            TaskComponentParam("site", "目标库位", "string")
        ), false) { component, ctx ->
            val site = parseComponentParamValue("site", component, ctx) as String
            val pass = canPick(site)
            if (!pass) throw RetryMaxError("查询是否能取货失败")
        },
        TaskComponentDef(
            "extra", "AfterI", "离开I", "", false, listOf(
        ), false) { _, ctx ->
            afterI(ctx.task.id)
        }
    )
    
    fun init() {
        setVersion("l2", "3.0.0")
        
        registerRobotTaskComponents(extraComponents)
        
        handle(HttpRequestMapping(HandlerType.GET, "task/:id", ::queryTask, ReqMeta(auth = false, test = true)))
        
        Application.initialize()
        
        val mock = Handlers("mock")
        mock.post("pick/:site", ::mockCanPick, noAuth())
        mock.post("IsRelease/:taskId", ::mockAfterI, noAuth())
    }
    
    private fun canPick(site: String): Boolean {
        for (i in 0..10) {
            try {
                val res = upHttpClient.canPick(site).execute().body() ?: continue
                if (res.pass) return true
            } catch (e: Exception) {
                // ignore
                Thread.sleep(3000)
            }
        }
        return false
    }
    
    private fun afterI(taskId: String) {
        try {
            upHttpClient.afterI(taskId).execute()
        } catch (e: Exception) {
            logger.error("after I callback", e)
        }
    }
    
    private fun mockCanPick(ctx: Context) {
        val site = ctx.pathParam("site")
        val pass = Random.nextBoolean()
        logger.info("Mock up: $site $pass")
        ctx.json(mapOf("pass" to pass))
    }
    
    private fun mockAfterI(ctx: Context) {
        logger.info("Mock up: after I ${ctx.pathParam("taskId")}")
        ctx.status(204)
    }
    
    private fun queryTask(ctx: Context) {
        val id = ctx.pathParam("id")
        val task = MongoDBManager.collection<RobotTask>().findOne(RobotTask::id eq id)
        val state = when {
            task == null -> "NOT_FOUND"
            task.state == RobotTaskState.Created -> "EXECUTING"
            else -> "FINISH"
        }
        val r = mapOf("id" to id, "state" to state)
        ctx.json(r)
    }
    
}

class CustomConfig {
    var upUrl: String = "http://192.168.13.140:8080/WJMZKJWMS/a/webcall/"
}

class PickResult(
    var pass: Boolean = false
)

@JvmSuppressWildcards
interface UpHttpClient {
    
    @POST("pick/{site}")
    fun canPick(@Path("site") site: String): Call<PickResult>
    
    @POST("IsRelease/{taskId}")
    fun afterI(@Path("taskId") taskId: String): Call<Void>
    
}

fun main() {
    MzApp.init()
    Application.start()
}