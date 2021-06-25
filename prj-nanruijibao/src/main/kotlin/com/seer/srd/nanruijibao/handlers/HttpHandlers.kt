package com.seer.srd.nanruijibao.handlers

import com.seer.srd.handler.handleUpdateInBatch
import com.seer.srd.http.Handlers
import com.seer.srd.http.HttpServer
import com.seer.srd.http.HttpServer.noAuth
import com.seer.srd.http.ReqMeta
import com.seer.srd.user.CommonPermissionSet

fun registerDefaultHttpHandlers() {
    val operator = Handlers("task-by-operator")
    // 切换岗位和工位时，用于校验“确认密码”   PAD -> SRD-K
    operator.post("check-password", ::checkPassword, ReqMeta(test = true, auth = false))

    // 清空库位
    operator.post("empty-store-site", ::emptyStoreSite, ReqMeta(test = true, auth = false,
        reqBodyDemo = listOf("""{"menuId": "", "workType": "", "workStation": "", "params": {"siteId": "1F-A-1" }}""")
    ))

    // 已放置货物 - 记录货物信息、占用库位
    operator.post("fill-store-site", ::fillStoreSite, ReqMeta(test = true, auth = false,
        reqBodyDemo = listOf("""{"menuId": "", "workType": "", "workStation": "", "params": {"siteId": "1F-A-1" }}""")
    ))

    // 更新AGV标签
    operator.post("update-agv-tag", ::updateVehicleTag, ReqMeta(test = true, auth = false,
        reqBodyDemo = listOf("""{"menuId": "", "workType": "", "workStation": "", "params": {"vehicleName": "DN-01", "tag": true }}""")
    ))

    // 安装、拆卸工装完成
    operator.post("opt-ext-device-finished", ::optExtDeviceFinished, ReqMeta(test = true, auth = false,
        reqBodyDemo = listOf("""{"menuId": "", "workType": "", "workStation": "", "params": {"vehicleName": "DN-01", "tag": true }}""")
    ))

    val ext = Handlers("ext")
    ext.post("robot-task/:id/abort", ::abortRobotTask, noAuth())
    ext.post("store-site/batch", ::updateStoreSiteInBatch, noAuth())

}

