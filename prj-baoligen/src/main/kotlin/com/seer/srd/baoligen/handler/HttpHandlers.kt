package com.seer.srd.baoligen.handler

import com.seer.srd.http.Handlers
import com.seer.srd.http.ReqMeta

fun registerDefaultHttpHandlers() {

    val ext = Handlers("ext")
    // 创建 运输物料 和 回收料桶 任务
    ext.post("new-task", ::newTask, ReqMeta(test = true, auth = false, reqBodyDemo = listOf(
        """{"taskType":"transportMat/returnTray", "fromSite": "LOC2", "toSite":"LOC11"}"""
    )))

    // 撤销任务
    ext.post("task/:taskId/abort", ::customAbortTask, ReqMeta(test = true, auth = false))

    // 查询执行任务的AGV名称
    ext.get("processing-robot/:taskId", ::processingRobot, ReqMeta(test = true, auth = false))

    // AGV在起点完成取料
    ext.post("take-mat-finished", ::customTakeMatFinished, ReqMeta(test = true, auth = false, reqBodyDemo = listOf(
        """{
            |"taskId": "taskId11111", 
            |"agvName": "AMB-01", 
            |"currentLocation": "site2", 
            |"status": "已经取走料桶", 
            |"currentTime": "2020-11-16T12:00:00.000z" 
        |}""".trimMargin()
    )))

    // 运输任务已经完成
    ext.post("task-finished", ::customTaskFinished, ReqMeta(test = true, auth = false, reqBodyDemo = listOf(
        """{
            |"taskId": "taskId11111", 
            |"fromLocation": "site1", 
            |"endLocation": "site2"
        |}""".trimMargin()
    )))

    val mock = Handlers("ext/v1/srd/mock")
    mock.post("take-mat-finished", ::customTaskMatFinishedMock, ReqMeta(test = false, auth = false))
    mock.post("task-finished", ::customTaskFinishedMock, ReqMeta(test = false, auth = false))
}