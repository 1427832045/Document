package com.seer.srd.siemensSH

import com.seer.srd.http.Handlers
import com.seer.srd.http.ReqMeta
import com.seer.srd.siemensSH.phase2.Phase2Handlers
import com.seer.srd.siemensSH.common.ComHandlers
import com.seer.srd.siemensSH.phase1.Phase1Handlers

fun registerDefaultHttpHandlers() {

    val root = Handlers("")
    root.post("recognize-test/:siteId", Phase2Handlers::forkLoadRecognizeOrNotTest, ReqMeta(test = true, auth = false))
    root.post("append-properties-test", Phase2Handlers::appendRecognizePropertyTest, ReqMeta(test = true, auth = false))

    root.post("close-warning-dialog", ComHandlers::closeWarningDialog, ReqMeta(test = true, auth = false,
        reqBodyDemo = listOf("""{"message":"this message for test!"}"""))
    )

    // 查询 整机和单元的关系
    root.get("list-material-product-mappings", Phase1Handlers::listMappings, ReqMeta(test = true, auth = false))
    // 获取 整机和单元的关系
    root.post("seer/siemens/material-product-mappings", Phase1Handlers::materialProductMapping,
        ReqMeta(test = true, auth = false, reqBodyDemo =
        listOf("""{"mappings":[
            |{"material":"material1", "product":"product1"},
            |{"material":"material2", "product":"product2"}
        |]}""".trimMargin()))
    )
    // 删除 整机和单元的关系
    root.post("remove-material-product-mappings", Phase1Handlers::removeMaterialProductMappings,
        ReqMeta(test = true, auth = false, reqBodyDemo =
        listOf("""{
            |    "start": "YYYY-MM-DD",
            |    "end": "YYYY-MM-DD",
            |    "products": [
            |        "product1",
            |        "product2"
            |    ],
            |    "materials": [
            |        "material1",
            |        "material2"
            |    ]
        |}""".trimMargin()))
    )

//    root.get("phase2-test", Phase2Handlers::test, ReqMeta(test = true, auth = false))

//    val ext = Handlers("ext")
    // 模拟 siemens 上位机返回 Z8码 & 800码 的对应关系
//    ext.get("sim/code-map", ComHandlers::codeMapSim, ReqMeta(test = false, auth = false))

    val operator = Handlers("task-by-operator")
    // 更新库位-记录货物码
    operator.post("record-content", ComHandlers::setSiteContentIfEmptyAndUnlocked,
        ReqMeta(test = true, auth = false, reqBodyDemo =
        listOf("""{"menuId":"","workType":"","workStation":"", "params":{"siteId":"CA-1-05", "code":"test1111"}}"""))
    )
    // 库位取货-清空货物码
    operator.post("clear-content", Phase1Handlers::clearLockedSiteContentIfFilledWithMat,
        ReqMeta(test = true, auth = false, reqBodyDemo =
        listOf("""{"menuId":"","workType":"","workStation":"", "params":{"siteId":"CA-1-05"}}"""))
    )
    // 批量创建 原料运至产线 任务
    operator.post("create-take-mat-to-ps-tasks", Phase2Handlers::createTakeMatToPsTasks,
        ReqMeta(test = true, auth = false, reqBodyDemo =
        listOf("""{"menuId":"","workType":"","workStation":"", "params":{"siteId":"CA-1-05", "code":"test1111"}}"""))
    )
    // 批量创建 区域E(单元房)运至station 任务
    operator.post("create-e-to-station-tasks", Phase1Handlers::createEToStation,
        ReqMeta(test = true, auth = false, reqBodyDemo =
        listOf("""{"menuId":"","workType":"","workStation":"", "params":{"siteId":"CA-1-05", "code":"test1111"}}"""))
    )
    // 批量创建 单元满料车呼叫 任务 （区域E(单元房) AE1 -> AE3）
    operator.post("create-take-mat-from-ae1-to-ae3-tasks", Phase1Handlers::createTakeMatFromAE1ToAE3Tasks,
        ReqMeta(test = true, auth = false, reqBodyDemo =
        listOf("""{"menuId":"","workType":"","workStation":"", "params":{"siteId":"CA-1-05", "code":"test1111"}}"""))
    )
    // 更新 Z8码 & 800码 的对应关系 （弃用）
//    operator.post("code-map", ComHandlers::getCodeMap, ReqMeta(test = true, auth = false))

    operator.post("reset", ComHandlers::resetResource, ReqMeta(test = true, auth = false))

}