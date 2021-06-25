package com.seer.srd.jinfeng

import com.seer.srd.Application
import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.eventbus.EventBus
import com.seer.srd.jinfeng.ExtraComponents.extraComponents
import com.seer.srd.jinfeng.Handle.unlockSite
import com.seer.srd.model.Link
import com.seer.srd.robottask.*
import com.seer.srd.robottask.RobotTaskService.saveNewRobotTask
import com.seer.srd.robottask.component.registerRobotTaskComponents
import com.seer.srd.route.service.PlantModelService
import com.seer.srd.route.service.VehicleService
import com.seer.srd.scheduler.GlobalTimer
import com.seer.srd.setVersion
import com.seer.srd.util.HttpClient
import com.seer.srd.util.loadConfig
import com.seer.srd.vehicle.Vehicle
import com.seer.srd.vehicle.VehicleManager.setVehicleIntegrationLevel
import com.seer.srd.vehicle.VehicleOutput
import org.litote.kmongo.*
import org.slf4j.LoggerFactory
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit


private val customConfig = loadConfig("srd-config.yaml", CustomConfig::class) ?: CustomConfig()

//private val selfClient = HttpClient.buildHttpClient(customConfig.localUrl, SelfClient::class.java)
object JinFengApp {

    private val logger = LoggerFactory.getLogger(JinFengApp::class.java)

    fun init() {
        setVersion("GINFON", "1.0.5")

        registerHttpResponseDecorator("custom", customHttpResDecorator)

        registerRobotTaskComponents(extraComponents)

        registerMockHandlers()

        Application.initialize()

        EventBus.robotTaskFinishedEventBus.add(JinFengApp::onRobotTaskFinished)

        logger.info("customConfig specialSiteToWaitSite is:${customConfig.specialSiteToWaitSite}")

        if (customConfig.exchangePowerEnable)
            GlobalTimer.executor.scheduleAtFixedRate(this::monitorExchangePower, 8, 5, TimeUnit.SECONDS)
    }

    private fun onRobotTaskFinished(robotTask: RobotTask) {
        if (RobotTaskState.Aborted == robotTask.state) {
            //通知上游系统手动终止
            val outOrderNo = robotTask.outOrderNo ?: throw BusinessError("robotTask don't have outOrder")
            val jinFengTask = MongoDBManager.collection<JinFengTask>().findOne(JinFengTask::taskID eq outOrderNo)
                ?: throw BusinessError("找不到jinFengTask, taskID:$outOrderNo")
            MongoDBManager.collection<JinFengTask>().updateOne(JinFengTask::taskID eq outOrderNo, set(JinFengTask::state setTo ErpBodyState.terminated))

            unlockSite(jinFengTask)

            val flag = ExtraComponents.tellErp(outOrderNo, ErpBodyState.terminated, robotTask.transports[0].processingRobot)
            if (!flag) logger.error("上报任务状态给GF失败,jinfengTaskId:$outOrderNo")
        }
        if (RobotTaskState.Failed == robotTask.state) {
            //通知上游系统失败
            val outOrderNo = robotTask.outOrderNo ?: throw BusinessError("robotTask don't have outOrder")
            val jinFengTask = MongoDBManager.collection<JinFengTask>().findOne(JinFengTask::taskID eq outOrderNo)
                ?: throw BusinessError("找不到jinFengTask, taskID:$outOrderNo")

            MongoDBManager.collection<JinFengTask>().updateOne(JinFengTask::taskID eq outOrderNo, set(JinFengTask::state setTo ErpBodyState.failed))

            unlockSite(jinFengTask)

//            val flag = ExtraComponents.tellErp(outOrderNo, ErpBodyState.failed, robotTask.transports[0].processingRobot)
//            if (!flag) logger.error("上报任务状态给GF失败,jinfengTaskId:$outOrderNo")
        }
    }

    private fun monitorExchangePower(){
        try {

            //获取当前的时间
            val localDateTime: LocalDateTime = Instant.now().atZone(ZoneId.systemDefault()).toLocalDateTime()
            val df = DateTimeFormatter.ofPattern("HH:mm")
            val nowString = localDateTime.format(df)
            val exchangePowerTimes = customConfig.exchangePowerTimes
            var isTime = false
            for (t in exchangePowerTimes) {
                val timeList = t.split("-")
                val timeStart = timeList[0]
                val timeEnd = timeList[1]
                if (nowString >= timeStart && nowString <= timeEnd) {
                    isTime = true
                    break
                }
            }


            val vehicles = VehicleService.listVehiclesOutputs()

            val thresholdA = customConfig.thresholdA
            val thresholdB = customConfig.thresholdB

            val thresholdM = customConfig.thresholdM
            val thresholdN = customConfig.thresholdN
            for (v in vehicles) {
                if (v.energyLevel == 0) continue
                if (v.state in listOf(Vehicle.State.UNKNOWN, Vehicle.State.UNAVAILABLE, Vehicle.State.ERROR)) continue
//                if (v.integrationLevel != Vehicle.IntegrationLevel.TO_BE_UTILIZED) continue

                //如果车的电量低于 thresholdA,thresholdB,且处于换电时段，触发柔性任务，并设为在线不接单
                if (v.energyLevel in thresholdB until thresholdA && isTime) {
                    createExchangeTaskAndSetToBeRespected(v)
                } else if (v.energyLevel < thresholdB) {
                    //如果车的电量低于 thresholdB,触发柔性任务，并设为在线不接单
                    createExchangeTaskAndSetToBeRespected(v)
                }

                //获取车所在的工作站名字
                val currentPosition = v.currentPosition
                val currentStationName = if (currentPosition != null) getSiteByPoint(currentPosition) else ""

                if (v.energyLevel > thresholdM && (stationPre + v.name) == currentStationName) {
                    //如果车的电量高于 thresholdM，顶升车状态置为在线接单
                    setVehicleToBeRespected(v, Vehicle.IntegrationLevel.TO_BE_UTILIZED.name)
                    logger.info("机器人已换过电,机器人：${v.name},电量：${v.energyLevel},置为在线接单")
                } else if (v.energyLevel in (thresholdN + 1)..thresholdM && !isTime && (stationPre + v.name) == currentStationName) {
                    //如果车的电量高于 thresholdN,并不在换电阶段，顶升车置为在线接单
                    setVehicleToBeRespected(v, Vehicle.IntegrationLevel.TO_BE_UTILIZED.name)
                    logger.info("机器人：${v.name},电量:${v.energyLevel},不在换电时段，置为在线接单")
                }
            }
        } catch (e: Exception) {
            logger.error("monitorExchangePower error, $e")
        }

    }

    private const val stationPre = "C-"
    @Synchronized
    private fun createExchangeTaskAndSetToBeRespected(v: VehicleOutput) {

        val currentPosition = v.currentPosition
        if (currentPosition != null) {
            val currentStationName = getSiteByPoint(currentPosition)
            //如果已经在换电点了，return
            if (currentStationName == (stationPre + v.name)) return
        }

        if (v.integrationLevel == Vehicle.IntegrationLevel.TO_BE_RESPECTED) return

        val robotTask = MongoDBManager.collection<RobotTask>().findOne(
            RobotTask::def eq "goExchangeStation",
            RobotTask::state eq RobotTaskState.Created, RobotTask::transports.elemMatch(RobotTransport::intendedRobot eq v.name)
        )

        if (robotTask != null) return

        //触发柔性任务，柔性任务（去换电点，并顶升，设置为在线不接单）
//        val res = selfClient.callSelfInterface(mapOf("exchangeStation" to "$stationPre${v.name}", "robotName" to v.name))
//            .execute().body()

        val stationName = stationPre + v.name
        if (!PlantModelService.getPlantModel().locations.containsKey(stationName)) {
            logger.error("没有站点，请检查站点名称：$stationName")
            return
        }

        val taskDef = getRobotTaskDef("goExchangeStation")
            ?: throw BusinessError("未找到名称为【goExchangeStation】的柔性任务！")

        val newTask = buildTaskInstanceByDef(taskDef)

        newTask.transports[0].stages[0].location = stationName

        newTask.transports[1].stages[0].location = stationName

        newTask.transports.forEach { it.intendedRobot = v.name }

        saveNewRobotTask(newTask)

        logger.info("创建机器人换电任务，机器人:${v.name},电量:${v.energyLevel},taskId:${newTask.id}")

    }

    private fun setVehicleToBeRespected(v: VehicleOutput, level: String) {
        try {
            setVehicleIntegrationLevel(v.name, level)
        } catch (ex: Exception) {
            logger.error("Change ${v.name} to $level failed, $ex", ex)
        }
    }

    //获取站点的名称
    private fun getSiteByPoint(point: String): String {
        for ((k, v) in PlantModelService.getPlantModel().locations) {
            if (v.attachedLinks.isNotEmpty() && v.attachedLinks.first().point == point) {
                return k
            }
        }
        return ""
    }
}



fun main() {
    JinFengApp.init()
    Application.start()
}
