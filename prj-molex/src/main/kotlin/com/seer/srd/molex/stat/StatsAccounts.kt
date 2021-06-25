package com.seer.srd.molex.stat

import com.mongodb.client.model.Accumulators.sum
import com.mongodb.client.model.BsonField
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.ne
import com.seer.srd.robottask.RobotTaskState
import org.bson.conversions.Bson
import java.util.*

/** 一个统计科目 */
data class StatAccount(
    val type: String,
    val table: String, // 要统计的数据所在的表
    val timeColumn: String, // 利用这一列的时间筛选,
    val vehicleColumn: String?, // 指定车
    val fieldAccumulators: List<BsonField>,
    val filters: Bson? = null,
    val group: Any? = null,
    val valueScale: Int = 1
)

val statAccounts: MutableList<StatAccount> = Collections.synchronizedList(
    arrayListOf(
        StatAccount("RobotTaskCreated", "RobotTask", "createdOn", "", listOf(sum("value", 1))),
        StatAccount("RobotTaskFinished", "RobotTask", "finishedOn", "", listOf(sum("value", 1))),
        StatAccount(
            "RobotTaskSuccess", "RobotTask", "finishedOn", "", listOf(sum("value", 1)),
            eq("state", RobotTaskState.Success)
        ),
        StatAccount(
            "RobotTaskFailed", "RobotTask", "finishedOn", "", listOf(sum("value", 1)),
            ne("state", RobotTaskState.Success)
        ),
        StatAccount(
            "RobotTaskDurationSum", "RobotTask", "finishedOn", "", listOf(sum("value", "\$duration")),
            eq("state", RobotTaskState.Success), valueScale = 1000
        ),
//        StatAccount(
//            "RobotTaskDurationAvg", "RobotTask", "finishedOn", "", listOf(avg("value", "\$duration")),
//            eq("state", RobotTaskState.Success), valueScale = 1000
//        ),
        StatAccount(
            "VehicleExecutingNum", "VehicleStateTrace", "startOn", "vehicle", listOf(sum("value", 1)),
            eq("fromState", "EXECUTING")
        ),
        StatAccount(
            "VehicleExecutingTime", "VehicleStateTrace", "startOn", "vehicle", listOf(sum("value", "\$duration")),
            eq("fromState", "EXECUTING"), valueScale = 1000
        ),
        StatAccount(
            "VehicleIdleNum", "VehicleStateTrace", "startOn", "vehicle", listOf(sum("value", 1)),
            eq("fromState", "IDLE")
        ),
        StatAccount(
            "VehicleIdleTime", "VehicleStateTrace", "startOn", "vehicle", listOf(sum("value", "\$duration")),
            eq("fromState", "IDLE"), valueScale = 1000
        ),
        StatAccount(
            "VehicleChargingNum", "VehicleStateTrace", "startOn", "vehicle", listOf(sum("value", 1)),
            eq("fromState", "CHARGING")
        ),
        StatAccount(
            "VehicleChargingTime", "VehicleStateTrace", "startOn", "vehicle", listOf(sum("value", "\$duration")),
            eq("fromState", "CHARGING"), valueScale = 1000
        ),
        StatAccount(
            "VehicleErrorNum", "VehicleStateTrace", "startOn", "vehicle", listOf(sum("value", 1)),
            eq("fromState", "ERROR")
        ),
        StatAccount(
            "VehicleErrorTime", "VehicleStateTrace", "startOn", "vehicle", listOf(sum("value", "\$duration")),
            eq("fromState", "ERROR"), valueScale = 1000
        )
    )
)