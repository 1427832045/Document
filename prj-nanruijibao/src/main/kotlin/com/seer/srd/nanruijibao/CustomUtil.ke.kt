package com.seer.srd.nanruijibao

import com.seer.srd.BusinessError
import com.seer.srd.CONFIG
import com.seer.srd.db.MongoDBManager
import com.seer.srd.eventlog.UserOperationLog
import com.seer.srd.eventlog.recordUserOperationLog
import com.seer.srd.nanruijibao.handlers.ABORT_TASK
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.RobotTaskState
import com.seer.srd.route.service.VehicleService
import com.seer.srd.util.loadConfig
import com.seer.srd.util.mapper
import kotlinx.coroutines.channels.consumesAll
import org.bson.types.ObjectId
import org.litote.kmongo.`in`
import org.litote.kmongo.eq
import org.litote.kmongo.find
import org.slf4j.LoggerFactory

private val customConfig = loadConfig("srd-config.yaml", CustomConfig::class) ?: CustomConfig()
val workTypeMaps: MutableMap<String, String> = mutableMapOf()

object CustomUtil {
    private val logger = LoggerFactory.getLogger(CustomUtil::class.java)

    fun stringToBoolean(str: String): Boolean {
        return when (str) {
            "true" -> true
            "false" -> false
            else -> throw BusinessError("字符串【$str】无法转换为布尔值！")
        }
    }

    fun getPassword(): String {
        return customConfig.password
    }

    fun checkWorkTypesUnique() {
        val workTypes = CONFIG.operator?.workTypes
            ?: throw BusinessError("请完善手持端（PAD）的配置： 未配置账号对应的工位和岗位信息！")

        val ids: MutableList<String> = mutableListOf()
        workTypes.map {
            val id = it.id
            if (ids.contains(id)) throw BusinessError("存在重复的 工位Id=$id ！")
            else {
                ids.add(id)
                workTypeMaps[id] = it.label
            }
        }
    }

    fun recordOperatorOperationLog(workType: String, remoteAddr: String, details: String) {
        val wtLabel = workTypeMaps[workType]
            ?: throw BusinessError("无法识别当前账号对应的工位，请重启手持端APP，并绑定工位之后再操作！ 若还是失败，请检查服务端配置！")
        recordUserOperationLog(UserOperationLog(
            userId = ObjectId(workType.toByteArray()),
            operation = ABORT_TASK,
            details = "[PAD=$remoteAddr WT=$wtLabel] | $details"
        ))
    }

    fun existOperateExtraDeviceTasks() {
        val tasks = MongoDBManager.collection<RobotTask>()
            .find(
                RobotTask::state eq RobotTaskState.Created,
                RobotTask::def `in` listOf("TaskDefLoadExtraDevice", "TaskDefUnloadExtraDevice")
            ).toList()
        val size = tasks.size
        val vehicleNumber = VehicleService.listVehicles().size
        val taskIds = tasks.map { it.id }
        if (size >= vehicleNumber) throw BusinessError("所有机器人都要去执行的操作工装任务 ${taskIds}，等待...")
        if (size < vehicleNumber) throw BusinessError("存在待执行的操作工装任务 ${taskIds}，等待...")
    }
}

class CustomConfig {
    var password: String = "123456"
}