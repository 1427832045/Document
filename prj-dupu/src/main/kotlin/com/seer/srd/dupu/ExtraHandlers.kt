package com.seer.srd.dupu

import com.seer.srd.CONFIG
import com.seer.srd.http.HttpRequestMapping
import com.seer.srd.http.HttpServer
import com.seer.srd.http.HttpServer.handle
import com.seer.srd.operator.OperatorInputDetails
import com.seer.srd.operator.OperatorOptionsSource
import com.seer.srd.operator.SelectOption
import io.javalin.http.HandlerType

fun registerExtraHandler() {

    handle(
        // 启动/禁用自动充电
        HttpRequestMapping(HandlerType.POST, "task-by-operator/charge", DupuService::enableCharge, HttpServer.auth())
    )

    OperatorOptionsSource.addReader("CurrentStatus") {
        val enabled = DupuApp.autoCharge
        listOf(
            SelectOption(
                "$enabled",
                "【${DupuService.parseLabel(enabled)}】")
        )
    }

    OperatorInputDetails.addProcessor("GetProductInfo" ){qrCode -> DupuService.getProductInfoStr(qrCode)}

    OperatorOptionsSource.addReader("EnableAutoCharge") {
        val enabled = DupuApp.autoCharge
        listOf(
            SelectOption(
                "$enabled",
                "【${DupuService.parseLabel(!enabled)}】")
        )
    }

    OperatorOptionsSource.addReader("DpStationOperations") {
        listOf(
            SelectOption("update", "【占用】"),
            SelectOption("", "【清空】")
        )
    }

    OperatorOptionsSource.addReader("DpWorkStations") {
        val config = CONFIG.operator

        config?.workStations?.map { SelectOption(it.id, it.label) } ?: listOf(SelectOption("", " 未配置工作站 "))
    }

}
