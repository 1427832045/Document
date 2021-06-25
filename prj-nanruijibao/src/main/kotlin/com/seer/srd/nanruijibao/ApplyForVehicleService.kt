package com.seer.srd.nanruijibao

import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.nanruijibao.CustomUtil.stringToBoolean
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.RobotTaskState
import com.seer.srd.route.service.PlantModelService
import com.seer.srd.route.service.VehicleService
import com.seer.srd.storesite.StoreSite
import com.seer.srd.storesite.StoreSiteService
import com.seer.srd.vehicle.Vehicle
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.*
import org.opentcs.data.order.TransportOrder
import org.opentcs.data.order.TransportOrderState
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.collections.toList
import kotlin.concurrent.thread

data class AssignedVehicle(
    @BsonId val id: ObjectId = ObjectId(),
    val createdOn: Instant = Instant.now(),
    val vehicleName: String = "",
    val taskId: String = "",
    var taskFinished: Boolean = false,
    var finishedOn: Instant? = null
)

data class VehicleAndTag(
    val name: String = "",
    val loading: Boolean = false
)

data class VehicleAndTaskType(
    val name: String = "",
    val type: TaskType = TaskType.Unknown
)

enum class TaskType {
    Load, Unload, Unknown
}

object ApplyForVehicleService {

    private val logger = LoggerFactory.getLogger(ApplyForVehicleService::class.java)

    private val finalStatus = listOf(TransportOrderState.FAILED, TransportOrderState.FINISHED, TransportOrderState.UNROUTABLE)

    private val c = MongoDBManager.collection<AssignedVehicle>()

    private val taskIds: MutableList<String> = mutableListOf()

    init {
        updateAssignedVehicles()
    }

    @Synchronized
    fun removeTaskIdFromMemory(taskId: String) {
        if (taskIds.contains(taskId)) {
            logger.debug("remove task=$taskId from memory.")
            taskIds.remove(taskId)
        }
    }

    @Synchronized
    fun taskApplyForVehicle(task: RobotTask): VehicleAndTag {
        val taskId = task.id
        val load = stringToBoolean(task.persistedVariables["loadDevice"] as String)
        if (!taskIds.contains(taskId))
            logger.info("task=$taskId apply for vehicle with${if (load) "" else "out"} extra-device.")

        taskIds.add(taskId)

        val vehicleAndTag = getAvailableVehicle(load)
            ?: throw BusinessError("没有可用的机器人，等待...")
        val vehicleName = vehicleAndTag.name
        insertOneAssignVehicle(AssignedVehicle(vehicleName = vehicleName, taskId = taskId))

        return vehicleAndTag
    }

    @Synchronized
    fun updateAssignedVehiclesIfTaskFinished(taskId: String) {
        removeTaskIdFromMemory(taskId)
        c.updateOne(AssignedVehicle::taskId eq taskId, set(
            AssignedVehicle::taskFinished setTo true,
            AssignedVehicle::finishedOn setTo Instant.now()
        ))
    }

    // 尝试获取正在执行PP任务或者CP任务的机器人
    private fun tryToGetVehicleProcessingDispensableTransports(): List<Vehicle> {
        val unfinishedDispensableTransports = MongoDBManager.collection<TransportOrder>()
            .find(TransportOrder::state nin finalStatus, TransportOrder::isDispensable eq true).toList()

        val procVehicles = unfinishedDispensableTransports.mapNotNull { it.processingVehicle }.filter { it.isNotBlank() }
        logger.debug("vehicles=$procVehicles are processing dispensable transport orders.")

        return procVehicles.map { VehicleService.getVehicle(it) }
    }

    // 获取指定标签的 在线接单的空闲机器人
    private fun getAvailableVehicle(forLoadExtDevice: Boolean): VehicleAndTag? {
        val vehicleNames: MutableList<String> = mutableListOf()
        // 筛选在线接单的空闲机器人
        VehicleService.listVehicles().forEach {
            val vehicleName = it.name
            if (it.integrationLevel == Vehicle.IntegrationLevel.TO_BE_UTILIZED
                // 正在充电的AGV也是可用的AGV，强制充电的情况交给Route判断。
                && (it.state == Vehicle.State.CHARGING || it.state == Vehicle.State.IDLE)
                && !vehicleNames.contains(vehicleName)) {
                vehicleNames.add(vehicleName)
            }
        }
        // 没有在线接单的空闲机器人，尝试获取正在执行非必要运单的机器人
        tryToGetVehicleProcessingDispensableTransports().map { it.name }.forEach {
            if (!vehicleNames.contains(it)) vehicleNames.add(it)
        }
        if (vehicleNames.isEmpty()) throw BusinessError("没有在线接单的空闲机器人，没有正在执行非必要运单的机器人！")

        val vehiclesAndItsSite: MutableMap<String, String> = mutableMapOf() // <storeSite-agv-tag, vehicleName>
        vehicleNames.forEach { vehiclesAndItsSite[getTagIdByVehicleName(it)] = it }

        // 获获取正在执行，或者将要执行【操作工装】任务的机器人的信息，这些机器人是不能执行任务的,需要过滤这些机器人
        val assignedVehicles = vehiclesAssignedForOperateExtraDevice().map { it.name }

        // 筛选指定标签的机器人
        val loads: MutableList<String> = mutableListOf()
        val unloads: MutableList<String> = mutableListOf()
        vehiclesAndItsSite.keys.forEach {
            val site = MongoDBManager.collection<StoreSite>().findOne(StoreSite::id eq it)
                ?: throw BusinessError("请添加库位【$it】，以记录AGV是否已经安装工装！")
            val vehicleName = vehiclesAndItsSite[it]
            if (vehicleName != null && !assignedVehicles.contains(vehicleName)) {
                if (site.filled) loads.add(vehicleName) else unloads.add(vehicleName)
            }
        }

        if (forLoadExtDevice) {
            // 需要已安装工装的AGV
            if (loads.isNotEmpty()) return VehicleAndTag(loads.first(), true)
            if (unloads.isNotEmpty()) return VehicleAndTag(unloads.first(), false)
        } else {
            // 需要未安装工装的AGV
            if (unloads.isNotEmpty()) return VehicleAndTag(unloads.first(), false)
            if (loads.isNotEmpty()) return VehicleAndTag(loads.first(), true)
        }

        return null
    }

    fun getTagIdByVehicleName(vehicleName: String): String {
        return "AGV-TAG-0${vehicleName.last()}"
    }

    // 获取正在执行，或者将要执行【操作工装】任务的机器人的信息
    private fun vehiclesAssignedForOperateExtraDevice(): List<VehicleAndTaskType> {
        val intendedVehicles: MutableList<VehicleAndTaskType> = mutableListOf()
        MongoDBManager.collection<RobotTask>().find(
            RobotTask::state eq RobotTaskState.Created,
            RobotTask::def `in` listOf("TaskDefLoadExtraDevice", "TaskDefUnloadExtraDevice")
        ).toList().forEach { task ->
            task.transports.forEach {
                val vehicle = it.intendedRobot
                if (!vehicle.isNullOrBlank()) {
                    val type = if (task.def.contains("Unload")) TaskType.Unload else TaskType.Load
                    intendedVehicles.add(VehicleAndTaskType(vehicle, type))
                }
            }
        }
        val vehicleNumber = VehicleService.listVehicles().size
        if (intendedVehicles.size >= vehicleNumber)
            throw BusinessError("所有机器人都有未完成的【操作工装】任务，请等待...")

        return intendedVehicles
    }

    private fun insertOneAssignVehicle(assignedVehicle: AssignedVehicle) {
        val vehicleName = assignedVehicle.vehicleName
        val record = c.find(AssignedVehicle::vehicleName eq vehicleName, AssignedVehicle::taskFinished eq false).toList()
        if (record.isNotEmpty()) throw BusinessError("AGV【$vehicleName】还未完成当前任务【${record.map { it.taskId }}】！")

        c.insertOne(assignedVehicle)
    }

    // 更新被撤销或者失败的任务的相关记录
    private fun updateAssignedVehicles() {
        thread(name = "update-assigned-vehicles") {
            while (true) {
                try {
                    val assignedVehicles: MutableMap<String, AssignedVehicle> = mutableMapOf()
                    c.find(AssignedVehicle::taskFinished eq false).toList().forEach {
                        assignedVehicles[it.taskId] = it
                    }
                    if (assignedVehicles.isEmpty()) {
                        logger.debug("no unfinished task in assigned-vehicles...")
                        continue
                    }

                    val taskIds = assignedVehicles.keys.map { it }.filter { it.isNotBlank() }
                    val tasks = MongoDBManager.collection<RobotTask>()
                        .find(RobotTask::id `in` taskIds).toList()
                    if (tasks.isNotEmpty()) {
                        // 同步未被删除的任务的状态
                        for (task in tasks) {
                            if (task.state > 0) updateAssignedVehiclesIfTaskFinished(task.id)
                        }

                        // 将 AssignedVehicles 中记录的，但是 RobotTask 中已经被删除的任务标记为 finished
                        val taskIdsFromRobotTask = tasks.map { it.id }
                        taskIds.forEach {
                            if (!taskIdsFromRobotTask.contains(it)) updateAssignedVehiclesIfTaskFinished(it)
                        }
                    }
                } catch (e: Exception) {
                    throw e
                } finally {
                    Thread.sleep(1000L)
                }
            }
        }
    }

    fun checkVehicleLoadExtDevice(vehicleName: String): Boolean {
        val tagId = getTagIdByVehicleName(vehicleName)
        val site = StoreSiteService.getStoreSiteById(tagId)
            ?: throw BusinessError("请添加库位【$tagId】，以记录AGV是否已经安装工装！")
        return site.filled
    }

    // 判断机器人是否在点位上
    fun vehicleOnSiteAndAvailable(siteId: String): String {
        val vehicles = numberOfIdleVehiclesOnSite(siteId)

        if (vehicles.isEmpty())
            throw BusinessError("工位【$siteId】上没有在线接单的空闲AGV！")

        if (vehicles.size > 1)
            throw BusinessError("有多个机器人【$vehicles】在【${siteId}】，请确认机器人位置！")

        return vehicles.first()
    }

    // 获取目标库位/工作站上的机器人列表
    private fun numberOfIdleVehiclesOnSite(siteId: String): List<String> {
        val point = getPointOfSite(siteId)
        // 记录停靠在此库位的机器人
        val vehicleNames: MutableList<String> = mutableListOf()
        for (vehicle in VehicleService.listVehicles()) {
            // 获取 在点位上 && 在线接单 && 空闲 的机器人
            if (vehicle.currentPosition == point
                && vehicle.state == Vehicle.State.IDLE
                && vehicle.integrationLevel == Vehicle.IntegrationLevel.TO_BE_UTILIZED) {
                vehicleNames.add(vehicle.name)
            }
        }
        return vehicleNames
    }

    // 获取库位/工作站的站点名称
    private fun getPointOfSite(siteId: String): String {
        val loc = PlantModelService.getPlantModel().locations[siteId]
            ?: throw BusinessError("不存在库位【$siteId】")
        return loc.attachedLinks.first().point
    }
}