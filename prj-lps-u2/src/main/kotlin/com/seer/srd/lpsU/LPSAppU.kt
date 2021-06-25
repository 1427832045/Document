package com.seer.srd.lpsU

import com.mongodb.client.model.Accumulators
import com.mongodb.client.model.Filters
import com.seer.srd.Application
import com.seer.srd.I18N.loadDict
import com.seer.srd.eventbus.EventBus
import com.seer.srd.http.HttpRequestMapping
import com.seer.srd.http.HttpServer.handle
import com.seer.srd.http.ReqMeta
import com.seer.srd.lpsU.ur.UrListener
import com.seer.srd.robottask.component.registerRobotTaskComponents
import com.seer.srd.setVersion
import com.seer.srd.stats.StatAccount
import com.seer.srd.stats.statAccounts
import io.javalin.http.HandlerType
import java.util.*

fun main() {
    setVersion("LPS1", "1.0.6")
    loadDict("/mat.csv")
    statAccounts.addAll(Collections.synchronizedList(listOf(
        StatAccount(
            "UpMatSum", "MatRecord", "recordOn", listOf(Accumulators.sum("value", 1)),
            Filters.eq("up", true)
        ),
        StatAccount(
            "DownMatSum", "MatRecord", "recordOn", listOf(Accumulators.sum("value", 1)),
            Filters.eq("up", false)
        )
    )))
    Application.initialize()
    
    handle(HttpRequestMapping(HandlerType.POST, "product/alterCategory", Handlers::handleAlterProductLineProductType,
        ReqMeta(auth = false, test = true)))
    handle(HttpRequestMapping(HandlerType.GET, "product/category", Handlers::handleListProductTypes,
        ReqMeta(auth = false, test = true)))
    handle(HttpRequestMapping(HandlerType.POST, "upCar/alter", Handlers::handleAlterUpCarProductType,
        ReqMeta(auth = false, test = true)))
    handle(HttpRequestMapping(HandlerType.POST, "upCar/fill", Handlers::handleFillUpCar,
        ReqMeta(auth = false, test = true)))
    handle(HttpRequestMapping(HandlerType.GET, "upCar/productType", Handlers::handleListUpCarProductTypes,
        ReqMeta(auth = false, test = true)))
    handle(HttpRequestMapping(HandlerType.GET, "upCar/getProductType", Handlers::handleUpCarProductTypeByCar,
        ReqMeta(auth = false, test = true)))
    handle(HttpRequestMapping(HandlerType.POST, "clearCar", Handlers::handleClearCar,
        ReqMeta(auth = false, test = true)))

    handle(HttpRequestMapping(HandlerType.GET, "mock/11:loader", Handlers::listMags11,
        ReqMeta(auth = false, test = false)))

    handle(HttpRequestMapping(HandlerType.GET, "mock/11:unloader", Handlers::listMags11un,
        ReqMeta(auth = false, test = false)))

    handle(HttpRequestMapping(HandlerType.GET, "mock/12:loader", Handlers::listMags12,
        ReqMeta(auth = false, test = false)))

    handle(HttpRequestMapping(HandlerType.GET, "mock/12:unloader", Handlers::listMags12un,
        ReqMeta(auth = false, test = false)))
    
//    handle(HttpRequestMapping(HandlerType.GET, "listMagInfo", Handlers::handleListMagInfo,
//        ReqMeta(auth = false, test = true)))

//    handle(HttpRequestMapping(HandlerType.POST, "mag-id-product-type", Handlers::handleMatIdToProductTypeMappings,
//        ReqMeta(auth = false, test = true, reqBodyDemo = listOf("""[{"magId": "A", "productType": "pt1"}]"""))))

    handle(HttpRequestMapping(HandlerType.GET, "product-line-types", Handlers::handleListProductLinesProductTypes,
        ReqMeta(auth = false, test = true)
    ))
    
//    handle(HttpRequestMapping(HandlerType.POST, "clear-ocr-error", Handlers::handleClearOCRError,
//        ReqMeta(auth = false, test = true)
//    ))

//    handle(HttpRequestMapping(HandlerType.POST, "task/ocr", Handlers::handleOCRTask,
//        ReqMeta(auth = false, test = true)
//    ))

    handle(HttpRequestMapping(HandlerType.POST, "light/off", Handlers::handleLight,
        ReqMeta(auth = false, test = true, reqBodyDemo = listOf("""{"msg": {"line": "line11"}}"""))
    ))

    handle(HttpRequestMapping(HandlerType.POST, "ocr", Handlers::handleOcr,
            ReqMeta(auth = false, test = true, reqBodyDemo = listOf("""{"carNum": "1", "categoryA": "A", "categoryB": "B"}"""))
    ))

    handle(HttpRequestMapping(HandlerType.POST, "buffer/restErrorCode", Handlers::handleBufferErrorCode,
        ReqMeta(auth = false, test = true, reqBodyDemo = listOf("""{"msg": {"line": "line11", "type": "up", "errorMsg": "xxx"}}"""))
    ))

    ProductLineService.init()
    registerRobotTaskComponents(ExtraComponent.extraComponents)
    UrListener.init()
    EventBus.robotTaskFinishedEventBus.add(UrListener::onRobotTaskFinished)
    Application.start()
    Services.init()

}

const val SITE_NUM_ON_COLUMN_CAR = 6