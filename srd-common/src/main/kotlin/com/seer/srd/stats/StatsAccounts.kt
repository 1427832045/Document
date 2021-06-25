package com.seer.srd.stats

import com.mongodb.client.model.Accumulators.*
import com.mongodb.client.model.BsonField
import com.mongodb.client.model.Filters.*
import com.seer.srd.robottask.RobotTaskState
import org.bson.conversions.Bson
import org.litote.kmongo.and
import org.litote.kmongo.or
import java.util.*

/** 一个统计科目 */
data class StatAccount(
    val type: String,
    val table: String, // 要统计的数据所在的表
    val timeColumn: String, // 利用这一列的时间筛选,
    val fieldAccumulators: List<BsonField>,
    val filters: Bson? = null,
    val group: Any? = null,
    val valueScale: Int = 1
)

val statAccounts: MutableList<StatAccount> = Collections.synchronizedList(
    arrayListOf(
        StatAccount("RobotTaskCreated", "RobotTask", "createdOn", listOf(sum("value", 1))),
        StatAccount("RobotTaskFinished", "RobotTask", "finishedOn", listOf(sum("value", 1))),
        StatAccount(
            "RobotTaskSuccess", "RobotTask", "finishedOn", listOf(sum("value", 1)),
            eq("state", RobotTaskState.Success)
        ),
        StatAccount(
            "RobotTaskFailed", "RobotTask", "finishedOn", listOf(sum("value", 1)),
            eq("state", RobotTaskState.Failed)
        ),
        StatAccount(
            "RobotTaskAborted", "RobotTask", "finishedOn", listOf(sum("value", 1)),
            eq("state", RobotTaskState.Aborted)
        ),
        StatAccount(
            "RobotTaskDurationSum", "VehicleStateTrace", "startOn", listOf(sum("value", "\$duration")),
            and(exists("robotTask", true), ne("robotTask", "")), valueScale = 1000
        ),
        StatAccount(
            "RobotTaskDurationAvg", "VehicleStateTrace", "startOn", listOf(sum("sum", "\$duration")),
            ne("robotTask", ""), "\$robotTask", valueScale = 1000
        ),
        StatAccount(
            "VehicleExecutingTime", "VehicleStateTrace", "startOn", listOf(sum("value", "\$duration")),
            or(
                eq("fromState", "EXECUTING"),
                and(
                    eq("fromState", "IDLE"),
                    or(
                        ne("transportOrder", ""),
                        ne("robotTask", ""),
                        and(exists("orderSequence", true), ne("orderSequence", ""))
                    )
                )
            ), valueScale = 1000
        ),
        StatAccount(
            "VehicleIdleTime", "VehicleStateTrace", "startOn", listOf(sum("value", "\$duration")),
            and(
                eq("fromState", "IDLE"),
                eq("transportOrder", ""),
                eq("robotTask", ""),
                or(exists("orderSequence", false), eq("orderSequence", ""))
            ), valueScale = 1000
        ),
        StatAccount(
            "VehicleChargingNum", "VehicleStateTrace", "startOn", listOf(sum("value", 1)),
            eq("fromState", "CHARGING")
        ),
        StatAccount(
            "VehicleChargingTime", "VehicleStateTrace", "startOn", listOf(sum("value", "\$duration")),
            eq("fromState", "CHARGING"), valueScale = 1000
        ),
        StatAccount(
            "VehicleErrorNum", "VehicleStateTrace", "startOn", listOf(sum("value", 1)),
            eq("fromState", "ERROR")
        ),
        StatAccount(
            "VehicleErrorTime", "VehicleStateTrace", "startOn", listOf(sum("value", "\$duration")),
            eq("fromState", "ERROR"), valueScale = 1000
        ),
        StatAccount(
            "VehicleOfflineTime", "VehicleStateTrace", "startOn", listOf(sum("value", "\$duration")),
            or(eq("fromState", ""), eq("fromState", "UNKNOWN"), eq("fromState", "UNAVAILABLE")), valueScale = 1000
        )
    )
)