package com.seer.srd.lps

import com.seer.srd.Application
import com.seer.srd.eventbus.EventBus
import com.seer.srd.http.HttpRequestMapping
import com.seer.srd.http.HttpServer.handle
import com.seer.srd.http.ReqMeta
import com.seer.srd.lps.ur.UrReset
import com.seer.srd.lps.ur.UrTcpServer
import com.seer.srd.robottask.component.registerRobotTaskComponents
import com.seer.srd.setVersion
import io.javalin.http.HandlerType

fun main() {
    setVersion("l2", "3.0.9")
    Application.initialize()
    
    handle(HttpRequestMapping(HandlerType.POST, "product/alterCategory", Handlers::handleAlterProductLineProductType,
        ReqMeta(auth = false, test = true)))
    handle(HttpRequestMapping(HandlerType.GET, "product/category", Handlers::handleListProductTypes,
        ReqMeta(auth = false, test = true)))
    handle(HttpRequestMapping(HandlerType.POST, "upCar/alter", Handlers::handleAlterUpCarProductType,
        ReqMeta(auth = false, test = true)))
    handle(HttpRequestMapping(HandlerType.GET, "upCar/productType", Handlers::handleListUpCarProductTypes,
        ReqMeta(auth = false, test = true)))
    handle(HttpRequestMapping(HandlerType.POST, "clearCar", Handlers::handleClearCar,
        ReqMeta(auth = false, test = true)))
    
    handle(HttpRequestMapping(HandlerType.GET, "listMagInfo", Handlers::handleListMagInfo,
        ReqMeta(auth = false, test = true)))

    handle(HttpRequestMapping(HandlerType.POST, "mag-id-product-type", Handlers::handleMatIdToProductTypeMappings,
        ReqMeta(auth = false, test = true, reqBodyDemo = listOf("""[{"magId": "A", "productType": "pt1"}]"""))))

    handle(HttpRequestMapping(HandlerType.GET, "product-line-types", Handlers::handleListProductLinesProductTypes,
        ReqMeta(auth = false, test = true)
    ))
    
    handle(HttpRequestMapping(HandlerType.POST, "clear-ocr-error", Handlers::handleClearOCRError,
        ReqMeta(auth = false, test = true)
    ))

    handle(HttpRequestMapping(HandlerType.POST, "task/ocr", Handlers::handleOCRTask,
        ReqMeta(auth = false, test = true)
    ))

    handle(HttpRequestMapping(HandlerType.POST, "light/off", Handlers::handleLight,
        ReqMeta(auth = false, test = true, reqBodyDemo = listOf("""{"msg": {"line": "line11"}}"""))
    ))

    ProductLineService.init()
    registerRobotTaskComponents(extraComponents)
    UrTcpServer.init()
    UrReset.listen()
    EventBus.robotTaskFinishedEventBus.add(UrReset::onRobotTaskFinished)
    Application.start()

}

const val SITE_NUM_ON_COLUMN_CAR = 9