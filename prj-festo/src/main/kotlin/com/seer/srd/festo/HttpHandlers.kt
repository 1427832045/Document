package com.seer.srd.festo

import com.seer.srd.festo.phase4.handleChangeSiteFilled
import com.seer.srd.festo.phase4.handleCreateComplexTask
import com.seer.srd.festo.phase4.handleFillSitesByType
import com.seer.srd.http.Handlers
import com.seer.srd.http.ReqMeta


fun registerDefaultHttpHandlers() {
    val operator = Handlers("task-by-operator")
    // 更新库位：将库位设置为空/满
    operator.post("change-site-filled", ::handleChangeSiteFilled, ReqMeta(test = true, auth = false))

    // 空托盘补充：将指定区域的所有库位状态变更为满（被占用状态） 送回空托盘1库区 / 送回空托盘2库区
    operator.post("fill-sites-by-type", ::handleFillSitesByType, ReqMeta(test = true, auth = false))

    // 创建空托盘任务或者满托盘任务
    operator.post("complex-task", ::handleCreateComplexTask, ReqMeta(test = true, auth = false, reqBodyDemo = listOf(
        """{
            |"menuId": "",
            |"workType": "",
            |"workStation":"", 
            |"params": {
                |"type": "Empty",
                |"fromSiteId": "",
                |"toSiteId": ""
            |}
        |}""".trimMargin()
    )))
}