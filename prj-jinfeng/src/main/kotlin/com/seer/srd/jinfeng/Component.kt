package com.seer.srd.jinfeng

import com.seer.srd.BusinessError
import com.seer.srd.FailedLockStoreSite
import com.seer.srd.RetryMaxError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.robottask.*
import com.seer.srd.robottask.component.TaskComponentDef
import com.seer.srd.robottask.component.TaskComponentParam
import com.seer.srd.robottask.component.parseComponentParamValue
import com.seer.srd.route.service.PlantModelService
import com.seer.srd.route.service.VehicleService.getVehicle
import com.seer.srd.scheduler.backgroundFixedExecutor
import com.seer.srd.storesite.StoreSiteService
import com.seer.srd.util.HttpClient
import com.seer.srd.util.loadConfig
import com.seer.srd.util.mapper
import com.seer.srd.vehicle.Vehicle
import com.seer.srd.vehicle.VehicleManager
import com.seer.srd.vehicle.VehicleOutput
import io.javalin.http.Context
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId

import org.litote.kmongo.*
import org.opentcs.data.order.TransportOrder
import org.slf4j.LoggerFactory
import java.lang.Exception
import java.time.Instant
import kotlin.random.Random


private val customConfig = loadConfig("srd-config.yaml", CustomConfig::class) ?: CustomConfig()

private val erpHttpClient = HttpClient.buildHttpClient(customConfig.upUrl, ErpHttpClient::class.java)

class CustomConfig {
    var upUrl: String = ""
    var specialSiteToWaitSite: Map<String, List<String>> = mapOf()
    var taskTypeList: List<TaskType> = listOf()
    var exchangePowerTimes: List<String> = emptyList()
    var thresholdA: Int = 0
    var thresholdB: Int = 0
    var thresholdM: Int = 0
    var thresholdN: Int = 0
    var localUrl: String = ""
    var exchangePowerEnable: Boolean = false
    var fromProperties: List<StationProperty> = listOf()
    var toProperties: List<StationProperty> = listOf()
    var jackProperties: List<StationProperty> = listOf()
    var excludeSpecialForRecognize: Boolean = true

    var taskTypeConst: Map<String, String> = mapOf()
    var sbFull: List<String> = listOf()
    var jcFull: List<String> = listOf()
    var sbEmpty: List<String> = listOf()
    var jcEmpty: List<String> = listOf()
}


data class JinFengTask(
    @BsonId var id: String = ObjectId().toHexString(),
    var taskID: String,
    var taskType: String,
    var fromSite: String,
    var toSite: String,
    var robotType: String,
    var state: String = ErpBodyState.beforeCreated,
    var createdOn: Instant = Instant.now(),
    var modifiedOn: Instant = Instant.now(),
    val processingRobot: String? = null,
    var waitToSite: String = ""
)

private fun mockCanPick(ctx: Context) {
//    val site = ctx.pathParam("site")
    val pass = Random.nextBoolean()
//    logger.info("Mock up: $site $pass")
    ctx.json(mapOf("pass" to pass))
}

class GFParam(
    var TaskID: String, // 任务唯一标识
    var TaskType: String, // 任务类型
    var FromSite: String, // 起点库位名称
    var ToSite: String, //终点库位名称
    var RobotType: String //fork 或 jack
)

object GFParamInstance {
    const val taskID = "TaskID"
    const val taskType = "TaskType"
    const val fromSite = "FromSite"
    const val toSite = "ToSite"
    const val robotType = "RobotType"
}

class JinFengRequestRecord(
    @BsonId var id: String = ObjectId().toHexString(),
    var reqBody: ErpBody,
    var reason: String?,
    var createdOn: Instant = Instant.now()
)

object ExtraComponents {
    private val logger = LoggerFactory.getLogger(ExtraComponents::class.java)

    val extraComponents: List<TaskComponentDef> = listOf(
        TaskComponentDef(
            "extra", "checkTask", "检查任务是否重复发送", "",
            false, listOf(
        ), false) { _, ctx ->
            val bodyString = ctx.httpCtx?.body()
            logger.info("ManualTask body: $bodyString")
            val req = mapper.readValue(bodyString, GFParam::class.java)
            val taskID = req.TaskID
            val taskType = req.TaskType
            val fromSite = req.FromSite
            val toSite = req.ToSite
            val robotType = req.RobotType

            if (taskID.isBlank()) throw IllegalArgumentException("TaskID不能为空")
            if (taskType.isBlank()) throw IllegalArgumentException("TaskType不能为空")
            if (fromSite.isBlank()) throw IllegalArgumentException("FromSite不能为空")
            if (toSite.isBlank()) throw IllegalArgumentException("ToSite不能为空")
            if (robotType.isBlank()) throw IllegalArgumentException("RobotType不能为空")
            synchronized(this) {
                val c = MongoDBManager.collection<JinFengTask>()
                if (c.findOne(JinFengTask::taskID eq taskID) != null)
                    throw BusinessError("task id conflict,taskID=$taskID")
                if (c.findOne(JinFengTask::fromSite eq fromSite,
                        JinFengTask::toSite eq toSite,
                        JinFengTask::state `in` listOf(ErpBodyState.beforeCreated, ErpBodyState.created, ErpBodyState.waitPre, ErpBodyState.started)) != null)
                    throw BusinessError("The same task is executing,fromSite=$fromSite,toSite=$toSite")
                c.insertOne(JinFengTask(taskID = taskID, taskType = taskType, fromSite = fromSite, toSite = toSite, robotType = robotType))
            }

        },

        TaskComponentDef(
            "extra", "tellGF", "主动上报任务状态给GF", "",
            false, listOf(
            TaskComponentParam("state", "任务状态", "string")
        ), false) { component, ctx ->
            val state = parseComponentParamValue("state", component, ctx) as String
            if (!ErpBodyState.stateList.contains(state)) throw BusinessError("state is wrong,state must in (1, 2, 3, 4, 5, 6),state=$state")
            val taskID = ctx.task.outOrderNo ?: throw BusinessError("outOrderNo is null")
            val processingRobot = ctx.task.transports[0].processingRobot
            MongoDBManager.collection<JinFengTask>().updateOne(JinFengTask::taskID eq taskID,
                set(JinFengTask::state setTo state, JinFengTask::processingRobot setTo processingRobot, JinFengTask::modifiedOn setTo Instant.now()))
            val flag = tellErp(taskID, state, processingRobot)

            if (!flag) {
                logger.error("上报任务状态给GF失败,jinfengTaskId:$taskID")//throw RetryMaxError("上报任务状态给GF失败")
            }
        },

        TaskComponentDef(
            "extra", "SetOperation", "设置工作站的操作", "",
            false, listOf(
            TaskComponentParam("destination", "阶段", "string"),
            TaskComponentParam("value", "操作", "string")
        ), false
        ) { component, ctx ->
            val destination = parseComponentParamValue("destination", component, ctx) as RobotStage
            val value = parseComponentParamValue("value", component, ctx) as String
            var strValue = component.params["destination"] ?: ""
            strValue = strValue.trim()
            if (strValue[0] == '=') strValue = strValue.substring(1)

            val param = ctx.task.persistedVariables["param"] as HashMap<*, *>
            val taskType = param[GFParamInstance.taskType] as String
//            val properties = customConfig.taskTypeList.find { taskType == it.taskType }?.stationList?.find { destination.location == it.name }?.properties

//            val properties = customConfig.taskTypeList.map { it.stationList }.flatten().find { destination.location == it.name }?.properties

            val fromProperties = customConfig.fromProperties
            val toProperties = customConfig.toProperties
            var jackProperties = customConfig.jackProperties
            if ("Fork" == value) {
                if ("from" == strValue && fromProperties.isNotEmpty()) {
                    destination.properties = mapper.writeValueAsString(fromProperties)
                }else if ("to" == strValue && toProperties.isNotEmpty()) {
                    destination.properties = mapper.writeValueAsString(toProperties)
                }
            } else if("Jack" == value){
                val param = ctx.task.persistedVariables["param"] as HashMap<*, *>
                val from = param[GFParamInstance.fromSite] as String
                val b: Boolean = customConfig.specialSiteToWaitSite.containsKey(from)//这个点是不是特殊库位
                if ("from" == strValue && jackProperties.isNotEmpty()) {
                    //如果起点在特殊库位的配置列表中，并且开启了排除特殊库位，不设置recognize为true
                    if (customConfig.excludeSpecialForRecognize && b) {
                        //nothing
                    }else {
                        destination.properties = mapper.writeValueAsString(jackProperties)
                    }



                }
            }


//            if ("Fork" == value) destination.properties = if (properties == null) "" else mapper.writeValueAsString(properties)

            val operation = getOperation(value, strValue)
            destination.operation = operation

            ctx.task.transports.forEach { it.category = value }
        },

        TaskComponentDef(
            "extra", "SetTransportDeadline", "设置Deadline", "",
            false, listOf(
            TaskComponentParam("transport", "运单", "string")
        ), false
        ) { component, ctx ->
            val transport = parseComponentParamValue("transport", component, ctx) as RobotTransport
            transport.deadline = Instant.now()
        },

        TaskComponentDef(
            "extra", "isSpecial", "终点是否是特殊库位", "",
            false, listOf(
        ), false
        ) { _, ctx ->
            val param = ctx.task.persistedVariables["param"] as HashMap<*, *>
            val to = param[GFParamInstance.toSite] as String
            val b: Boolean = customConfig.specialSiteToWaitSite.containsKey(to)
            ctx.task.persistedVariables["toSiteIsSpecial"] = b
        },
        TaskComponentDef(
            "extra", "dealRelativeTask", "若存在关联任务，提高其优先级", "",
            false, listOf(
        ), false
        ) { _, ctx ->
            val param = ctx.task.persistedVariables["param"] as HashMap<*, *>
            val to = param[GFParamInstance.toSite] as String

            val c = MongoDBManager.collection<JinFengTask>()
            val task = c.findOne(JinFengTask::fromSite eq to, JinFengTask::state `in` listOf<String>(ErpBodyState.beforeCreated, ErpBodyState.created))
            if (task != null) {
                //提高优先级
                val robotTask = MongoDBManager.collection<RobotTask>().findOne(RobotTask::outOrderNo eq task.taskID)
                for (i in robotTask?.transports?.indices!!) {
                    val order = MongoDBManager.collection<TransportOrder>().findOne(TransportOrder::name eq robotTask.transports[i].routeOrderName)
                    if (order != null) {
                        val advanceDeadline = Instant.ofEpochMilli(order.deadline.toEpochMilli() - 1000 * 3600 * 24)
                        MongoDBManager.collection<TransportOrder>().updateOne(TransportOrder::name eq robotTask.transports[i].routeOrderName, set(TransportOrder::deadline setTo advanceDeadline))
                    }
                }
            }
        },
        TaskComponentDef(
            "extra", "waitRelativeTaskFinish", "等待关联任务执行完成", "",
            false, listOf(
        ), false
        ) { _, ctx ->
            val param = ctx.task.persistedVariables["param"] as HashMap<*, *>
            val to = param[GFParamInstance.toSite] as String

            val c = MongoDBManager.collection<JinFengTask>()
            val task = c.findOne(JinFengTask::fromSite eq to, JinFengTask::state `in` listOf<String>(ErpBodyState.beforeCreated, ErpBodyState.created))
            if (task != null) throw BusinessError("关联任务的机器人还没有完成接取，继续等待")
        },
/*        TaskComponentDef(
            "extra", "SetWaitLocationNameForSpecial", "设置终点为特殊库位的等待工作站", "",
            false, listOf(
            TaskComponentParam("destination", "阶段", "string"),
            TaskComponentParam("value", "工作站", "string")
        ), false
        ) { component, ctx ->
            val destination = parseComponentParamValue("destination", component, ctx) as RobotStage
            val value = parseComponentParamValue("value", component, ctx) as String?
            if (value.isNullOrBlank()) throw IllegalArgumentException("工作站不能为空")
            if (customConfig.specialSiteToWaitSite.containsKey(value)) {
                //是特殊工作站
                val waitSite = customConfig.specialSiteToWaitSite[value]
                if (waitSite.isNullOrEmpty()) throw IllegalArgumentException("${value}点的等待库位必须配置")
//                destination.location = waitSite
            }
        },*/
        TaskComponentDef(
            "extra", "LockWaitSiteAndSetLocation", "锁定终点库位的等待库位，并设置其阶段工作站", "",
            false, listOf(
            TaskComponentParam("destination", "阶段", "string"),
            TaskComponentParam("siteId", "终点库位ID", "string")
        ), false
        ) { component, ctx ->
            val siteId = parseComponentParamValue("siteId", component, ctx) as String
            val destination = parseComponentParamValue("destination", component, ctx) as RobotStage
            if (customConfig.specialSiteToWaitSite.containsKey(siteId)) {
                //是特殊工作站
                val waitSiteList = customConfig.specialSiteToWaitSite[siteId]
                if (waitSiteList.isNullOrEmpty()) throw IllegalArgumentException("${siteId}点的等待库位必须配置")
                var waitSite = ""
                for (i in waitSiteList.indices) {
                    try {
                        StoreSiteService.lockSiteIfNotLock(waitSiteList[i], ctx.task.id, "From task ${ctx.taskDef?.name}")
                        waitSite = waitSiteList[i]
                        break
                    } catch (e: FailedLockStoreSite) {
                        continue
                    }
                }
                if (waitSite.isBlank()) throw FailedLockStoreSite("${siteId}点的等待库位锁定失败")
                destination.location = waitSite

                val c = MongoDBManager.collection<JinFengTask>()
                c.updateOne(
                    JinFengTask::taskID eq ctx.task.outOrderNo,
                    set(JinFengTask::waitToSite setTo waitSite)
                )
            }
        },
        TaskComponentDef(
            "extra", "checkStation", "检查站点是否存在", "",
            false, listOf(
                TaskComponentParam("value1", "工作站1", "string"),
                TaskComponentParam("value2", "工作站2", "string"),
                TaskComponentParam("value3", "工作站3", "string")
            ), false
        ) { component, ctx ->
            val value1 = parseComponentParamValue("value1", component, ctx) as String?
            val value2 = parseComponentParamValue("value2", component, ctx) as String?
            val value3 = parseComponentParamValue("value3", component, ctx) as String?

            if (value1.isNullOrBlank()) throw IllegalArgumentException("工作站1不能为空")
            if (value2.isNullOrBlank()) throw IllegalArgumentException("工作站2不能为空")
            if (value3.isNullOrBlank()) throw IllegalArgumentException("工作站3不能为空")

            if (!PlantModelService.getPlantModel().locations.containsKey(value1)) {
                logger.error("没有站点，请检查站点名称：$value1")
                throw IllegalArgumentException("没有站点，请检查站点名称：$value1")
            }

            if (!PlantModelService.getPlantModel().locations.containsKey(value2)) {
                logger.error("没有站点，请检查站点名称：$value2")
                throw IllegalArgumentException("没有站点，请检查站点名称：$value2")
            }
            if (!PlantModelService.getPlantModel().locations.containsKey(value3)) {
                logger.error("没有站点，请检查站点名称：$value3")
                throw IllegalArgumentException("没有站点，请检查站点名称：$value3")
            }

        },
        TaskComponentDef(
            "extra", "setRobotToBeRespected", "设置机器人在线不接单", "",
            false, listOf(
            ), false
        ) { _, ctx ->

            val processingRobot = ctx.task.transports[0].processingRobot ?:throw IllegalArgumentException("运单的机器人(processingRobot)为空")

            setVehicleToBeRespected(processingRobot, Vehicle.IntegrationLevel.TO_BE_RESPECTED.name)


        }

    )

    fun tellErp(id: String, state: String, processingRobot: String?): Boolean {
        val erpBody = ErpBody(id, state, processingRobot)
        val req = mapOf("TaskId" to id, "TaskStatus" to state, "AgvNo" to processingRobot)
        for (i in 1..5) {
            try {
                val resStr = erpHttpClient.tellErp(req).execute().body() ?: continue
                logger.info("tellErp resInfo from GF is: $resStr")
                val json = mapper.readTree(resStr)
                val jsonNode = json.get("Status")
                if (jsonNode.asInt() == 0 ) return true
                MongoDBManager.collection<JinFengRequestRecord>().insertOne(JinFengRequestRecord(reqBody = erpBody, reason = json.get("ErrorInfo").asText()))
            } catch (e: Exception) {
                // ignore
                MongoDBManager.collection<JinFengRequestRecord>().insertOne(JinFengRequestRecord(reqBody = erpBody, reason = e.message))
                Thread.sleep(5000)//
            }
        }
        return false
    }

    private fun getOperation(robotType: String, location: String): String {
        if (robotType.isBlank()) throw IllegalArgumentException("robotType不能为空")
        if (location.isBlank()) throw IllegalArgumentException("location不能为空")
        if ("Jack" == robotType) {
            if ("from" == location) return "JackLoad"
            if ("to" == location) return "JackUnload"
        } else if ("Fork" == robotType) {
            if ("from" == location) return "ForkLoad"
            if ("to" == location) return "ForkUnload"
        }
        throw BusinessError("RobotType is error:RobotType is $robotType")
    }

    private fun setVehicleToBeRespected(robotName: String, level: String) {
        try {
            VehicleManager.setVehicleIntegrationLevel(robotName, level)
        } catch (ex: Exception) {
            logger.error("Change $robotName to $level failed, $ex", ex)
        }
    }
}