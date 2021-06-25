package com.seer.srd.robottask

import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import java.time.Instant

class RobotTask(
    @BsonId var id: String = ObjectId().toHexString(),
    var def: String = "",
    var createdOn: Instant = Instant.now(),
    var modifiedOn: Instant = Instant.now(),
    var finishedOn: Instant? = null,
    var duration: Long = 0, // 任务持续时间（从开始到结束，仅成功的任务）
    var state: Int = RobotTaskState.Created,
    var outOrderNo: String? = null,
    var persistedVariables: MutableMap<String, Any?> = HashMap(),
    var workTypes: MutableList<String> = ArrayList(), // 与此订单有关的岗位
    var workStations: MutableList<String> = ArrayList(), // 于此订单有关的工位
    var priority: Int = 0, // 越大优先级越高
    var noTransport: Boolean = false, // 不需要运输的任务
    var transports: List<RobotTransport> = emptyList()
)

class RobotTransport(
    var taskId: String = "",
    var routeOrderName: String = "",
    var category: String? = null,
    var deadline: Instant? = null,
    var intendedRobot: String? = null,
    var processingRobot: String? = null,
    var processingRobotAssignedOn: Instant? = null,
    var seqId: String? = null,
    var seqStartSentOn: Instant? = null,
    var timeCost: Int = 0,
    var finishedOn: Instant? = null,
    var state: Int = RobotTransportState.Created,
    var failReason: String = "",
    var stages: List<RobotStage> = emptyList()
)

class RobotStage(
    var state: Int = RobotStageState.Created,
    var routeState: String = "",
    var location: String = "",
    var area: String = "",
    var operation: String = "",
    var properties: String = "",
    var blockReason: String = "",
    var timeCost: Long = 0,
    var finishedOn: Instant? = null
)

object RobotTaskState {
    const val Created = 0 // 已创建
    const val Success = 1000 // 成功
    const val Aborted = 1800 // 强制终止
    const val Failed = 2000 // 失败
}

object RobotTransportState {
    const val Created = 0 // 已创建
    const val Enabled = 500 // 可执行
    const val Sent = 600 // 成功
    const val Success = 1000 // 成功
    const val Failed = 2000 // 失败
    const val Skipped = 8000 // 跳过
}

object RobotStageState {
    const val Created = 0 // 已创建
    const val Success = 1000 // 成功
    const val Failed = 2000 // 失败
}



