package com.seer.srd.mfld

import com.seer.srd.Application
import com.seer.srd.Error400
import com.seer.srd.db.MongoDBManager.collection
import com.seer.srd.http.HttpRequestMapping
import com.seer.srd.http.HttpServer.handle
import com.seer.srd.http.ReqMeta
import com.seer.srd.robottask.*
import com.seer.srd.robottask.RobotTaskService.saveNewRobotTask
import com.seer.srd.robottask.component.TaskComponent
import com.seer.srd.robottask.component.TaskComponentDef
import com.seer.srd.robottask.component.registerRobotTaskComponents
import com.seer.srd.scheduler.GlobalTimer.executor
import com.seer.srd.scheduler.backgroundCacheExecutor
import com.seer.srd.setVersion
import com.seer.srd.storesite.StoreSiteService
import com.seer.srd.util.HttpClient.buildHttpClient
import com.seer.srd.util.loadConfig
import io.javalin.http.Context
import io.javalin.http.HandlerType
import okhttp3.logging.HttpLoggingInterceptor.Level
import org.apache.commons.lang3.StringUtils
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.lt
import org.slf4j.LoggerFactory
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST
import java.time.Instant
import java.util.concurrent.TimeUnit

fun main() {
    App.init()
}

object App {
    
    private val logger = LoggerFactory.getLogger(App::class.java)
    
    private val line = listOf(
        "BUF-1", "PS-10", "PS-09", "PS-08", "PS-07", "PS-06",
        "BUF-2", "PS-05", "PS-04", "PS-03", "PS-02", "PS-01"
    )
    
    private val robotTaskDefUp = mainRobotTaskDef(1, "MainUp", "上半圈开始的循环")
    private val robotTaskDefDown = mainRobotTaskDef(7, "MainDown", "下半圈开始的循环")
    
    private val customConfig = loadConfig("srd-config.yaml", CustomConfig::class) ?: CustomConfig()
    
    private val mesHttpClient = buildHttpClient(customConfig.mesUrl, MesHttpClient::class.java, Level.BODY)
    
    private val extraComponents: List<TaskComponentDef> = listOf(
        TaskComponentDef("extra", "SendStageDoneToMes", "告知MES阶段完成", "", false, listOf(), false) { _, ctx ->
            val transport = ctx.transport!!
            val transportDef = ctx.transportDef!!
            val info = StageDoneInfo(
                StringUtils.firstNonBlank(transportDef.stages[2].location, transport.stages[2].location),
                StringUtils.firstNonBlank(transportDef.stages[3].location, transport.stages[3].location),
                ctx.transportIndex,
                ctx.task.id
            )
            backgroundCacheExecutor.submit {
                try {
                    mesHttpClient.sendStageDoneToMes(info).execute()
                } catch (e: Exception) {
                    logger.error("sendStageDoneToMes", e)
                }
            }
        }
    )
    
    fun init() {
        setVersion("manfred-auto", "1.0.2.1")
        
        registerRobotTaskComponents(extraComponents)
        
        handle(
            HttpRequestMapping(
                HandlerType.POST, "mes/call-from-site", this::handleMesSiteSign,
                ReqMeta(auth = false, test = true, reqBodyDemo = listOf("""{"callBySite": "PS-09"}"""))
            )
        )
        
        handle(
            HttpRequestMapping(
                HandlerType.POST, "mes", this::handleMockOnStageDone,
                ReqMeta(auth = false, test = true)
            )
        )
        
        Application.initialize()
        
        registerRobotTaskDefs()
        
        Application.start()
        
        executor.scheduleAtFixedRate(this::tryStart, 3, 1, TimeUnit.SECONDS)
        
        // robotTaskFinishedEventBus.add(this::onRobotTaskFinished)
    }
    
    private fun handleMesSiteSign(ctx: Context) {
        val req = ctx.bodyAsClass(MesSiteSign::class.java)
        if (req.callBySite.isBlank()) throw Error400("MissingCallBySite", "callBySite cannot be blank")
        markSiteCall(req.callBySite)
        ctx.status(204)
    }
    
    private fun handleMockOnStageDone(ctx: Context) {
        logger.info("Mock Mes receiving stage done ${ctx.body()}")
        ctx.status(204)
    }
    
    @Synchronized
    private fun markSiteCall(callBySite: String) {
        val site = StoreSiteService.getExistedStoreSiteById(callBySite)
        val record = ExtMesSiteSignRecord(callBySite, site.filled, site.locked, site.content)
        
        if (site.locked) {
            logger.warn("Mes sign on locked site $callBySite")
        } else {
            StoreSiteService.changeSiteFilled(callBySite, true, "Mes site call")
        }
        
        val c = collection<ExtMesSiteSignRecord>()
        c.insertOne(record)
    }
    
    @Synchronized
    private fun tryStart() {
        try {// 有未完成的任务则不创建新任务
            val cTask = collection<RobotTask>()
            if (cTask.countDocuments(RobotTask::state lt RobotTaskState.Success) > 0) return
            
            // 创建新任务时，最开始的两个库位至少有一个就位
            val site10 = StoreSiteService.getExistedStoreSiteById("PS-10")
            val site05 = StoreSiteService.getExistedStoreSiteById("PS-05")
            if (!(site10.filled && !site10.locked || site05.filled && !site05.locked)) return
            
            val taskUp = buildTaskInstanceByDef(robotTaskDefUp)
            saveNewRobotTask(taskUp)
            
            val taskDown = buildTaskInstanceByDef(robotTaskDefDown)
            saveNewRobotTask(taskDown)
        } catch (e: Exception) {
            logger.error("main loop", e)
        }
    }
    
    private fun registerRobotTaskDefs() {
        registerStaticRobotTaskDef(robotTaskDefUp)
        registerStaticRobotTaskDef(robotTaskDefDown)
    }
    
    private fun mainRobotTaskDef(startIndexInLine: Int, name: String, label: String): RobotTaskDef {
        val transportNum = 12 // 每个循环12次运输
        val transportDefs = arrayListOf<RobotTransportDef>()
        repeat(transportNum) { i ->
            val startSiteId = line[(startIndexInLine + i) % transportNum]
            val toSiteId = line[(startIndexInLine + i - 1 + transportNum) % transportNum]
            if (toSiteId == "PS-02") {
                transportDefs += RobotTransportDef(
                    seqGroup = name,
                    stages = arrayListOf(
                        RobotStageDef(
                            description = "等待起点就绪",
                            components = arrayListOf(
                                TaskComponent("CheckStoreSiteFilled", mapOf("siteId" to startSiteId)),
                                TaskComponent("LockSiteOnlyIfNotLock", mapOf("siteId" to startSiteId))
                            )
                        ),
                        RobotStageDef(
                            description = "等待终点就绪",
                            components = arrayListOf(
                                TaskComponent("CheckStoreSiteEmpty", mapOf("siteId" to toSiteId)),
                                TaskComponent("LockSiteOnlyIfNotLock", mapOf("siteId" to toSiteId))
                            )
                        ),
                        RobotStageDef(
                            description = "去起点", forRoute = true, location = startSiteId, operation = "JackLoad"
                        ),
                        // 去线外工站 TODO
                        // 这里拆分成两个 Transport，第二个 Transport 先等待线外按按钮
//                        RobotStageDef(
//                            description = "去线外", forRoute = true, location = "TuJiao", operation = "Wait"
//                        ),
                        RobotStageDef(
                            description = "去终点", forRoute = true, location = toSiteId, operation = "JackUnload"
                        ),
                        RobotStageDef(
                            description = "回起点", forRoute = true, location = startSiteId, operation = "Wait"
                        ),
                        RobotStageDef(
                            description = "标记完成",
                            components = arrayListOf(
                                TaskComponent("UnlockSiteIfLocked", mapOf("siteId" to startSiteId)),
                                TaskComponent("UnlockSiteIfLocked", mapOf("siteId" to toSiteId)),
                                TaskComponent("MarkSiteIdle", mapOf("siteId" to startSiteId))
                            )
                        ),
                        RobotStageDef(
                            description = "告知MES",
                            components = arrayListOf(
                                TaskComponent("SendStageDoneToMes", mapOf())
                            )
                        )
                    )
                )
            } else {
                transportDefs += RobotTransportDef(
                    seqGroup = name,
                    stages = arrayListOf(
                        RobotStageDef(
                            description = "等待起点就绪",
                            components = arrayListOf(
                                TaskComponent("CheckStoreSiteFilled", mapOf("siteId" to startSiteId)),
                                TaskComponent("LockSiteOnlyIfNotLock", mapOf("siteId" to startSiteId))
                            )
                        ),
                        RobotStageDef(
                            description = "等待终点就绪",
                            components = arrayListOf(
                                TaskComponent("CheckStoreSiteEmpty", mapOf("siteId" to toSiteId)),
                                TaskComponent("LockSiteOnlyIfNotLock", mapOf("siteId" to toSiteId))
                            )
                        ),
                        RobotStageDef(
                            description = "去起点", forRoute = true, location = startSiteId, operation = "JackLoad"
                        ),
                        RobotStageDef(
                            description = "去终点", forRoute = true, location = toSiteId, operation = "JackUnload"
                        ),
                        RobotStageDef(
                            description = "回起点", forRoute = true, location = startSiteId, operation = "Wait"
                        ),
                        RobotStageDef(
                            description = "标记完成",
                            components = arrayListOf(
                                TaskComponent("UnlockSiteIfLocked", mapOf("siteId" to startSiteId)),
                                TaskComponent("UnlockSiteIfLocked", mapOf("siteId" to toSiteId)),
                                TaskComponent("MarkSiteIdle", mapOf("siteId" to startSiteId))
                            )
                        ),
                        RobotStageDef(
                            description = "告知MES",
                            components = arrayListOf(
                                TaskComponent("SendStageDoneToMes", mapOf())
                            )
                        )
                    )
                )
            }
        }
        return RobotTaskDef(null, name, label, arrayListOf(), transportDefs, static = true)
    }
    
}

class CustomConfig {
    var mesUrl: String = "http://localhost:7100/api/"
}

@JvmSuppressWildcards
interface MesHttpClient {
    
    @POST("mes")
    fun sendStageDoneToMes(@Body info: StageDoneInfo): Call<Void>
    
}

data class StageDoneInfo(
    val fromSite: String,
    val toSite: String,
    val transportIndex: Int,
    val taskId: String
)

data class MesSiteSign(
    val callBySite: String
)

data class ExtMesSiteSignRecord(
    val callBySite: String,
    val filled: Boolean,
    val locked: Boolean,
    val content: String?,
    @BsonId val id: ObjectId = ObjectId(),
    val createdOn: Instant = Instant.now()
)