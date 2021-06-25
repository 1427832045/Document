package com.seer.srd.weiyi

import com.seer.srd.Application
import com.seer.srd.BusinessError
import com.seer.srd.CONFIG
import com.seer.srd.db.MongoDBManager
import com.seer.srd.http.HttpRequestMapping
import com.seer.srd.http.HttpServer.handle
import com.seer.srd.http.ReqMeta
import com.seer.srd.operator.OperatorInputDetails
import com.seer.srd.operator.OperatorOptionsSource
import com.seer.srd.operator.SelectOption
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.RobotTaskState
import com.seer.srd.robottask.component.*
import com.seer.srd.scheduler.GlobalTimer
import com.seer.srd.setVersion
import com.seer.srd.weiyi.ExtHandlers.cancelProductOutTask
import com.seer.srd.weiyi.ExtHandlers.enableAutoSort
import com.seer.srd.weiyi.ExtHandlers.getDefaultSPP
import io.javalin.http.HandlerType
import org.litote.kmongo.*
import org.slf4j.LoggerFactory
import com.seer.srd.weiyi.ExtHandlers.getProductInfo
import com.seer.srd.weiyi.ExtHandlers.getProductInfoStr
import com.seer.srd.weiyi.ExtHandlers.getProductStoreTask
import com.seer.srd.weiyi.ExtHandlers.mockProductInFinished
import com.seer.srd.weiyi.ExtHandlers.productStoreOutFromErp
import com.seer.srd.weiyi.ExtHandlers.setSiteFEmpty
import com.seer.srd.weiyi.ExtHandlers.sortProduct
import com.seer.srd.weiyi.ExtHandlers.mockProductInfo
import com.seer.srd.weiyi.ExtHandlers.mockProductOutFinished
import com.seer.srd.weiyi.ExtHandlers.mockProductSortFinshed
import com.seer.srd.weiyi.ExtHandlers.parseLabel
import com.seer.srd.weiyi.ExtHandlers.siteInfosToExcel
import com.seer.srd.weiyi.ExtHandlers.sortProductAutomatically
import com.seer.srd.weiyi.ExtHandlers.submitInFinished
import com.seer.srd.weiyi.ExtHandlers.submitOutFinished
import com.seer.srd.weiyi.ExtHandlers.submitSortFinished
import com.seer.srd.weiyi.OrderSendHandler.markExecutingTaskFinished
import com.seer.srd.weiyi.OrderSendHandler.updateExecutingTask
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger(WyApp::class.java)

object WyApp {

    private val extraComponents = ExtraComponents.extraComponents

    fun init() {
        setVersion("WeiYi", "1.0.5")

        registerRobotTaskComponents(extraComponents)

        Application.initialize()

        GlobalTimer.executor.scheduleAtFixedRate(
            ::sortProductAutomatically, 10000, 10000L, TimeUnit.MILLISECONDS
        )

        // 通过定时器更新正在执行的任务的状态
        GlobalTimer.executor.scheduleAtFixedRate(
            ::updateExecutingTask, 1000, 1000L, TimeUnit.MILLISECONDS
        )

        // ERP 向 SRD 下达产品出库任务
        handle(
            // siteInfosToExcel
            HttpRequestMapping(HandlerType.GET, "download-site-info", ::siteInfosToExcel,
                ReqMeta(auth = false, test = true)
            ),

            // 启用/禁用自动理库功能
            HttpRequestMapping(HandlerType.POST, "task-by-operator/enable-auto-sort", ::enableAutoSort,
                ReqMeta(auth = false, test = true)
            ),

            // 取消指定的出库任务
            HttpRequestMapping(HandlerType.POST, "task-by-operator/cancel-product-out-task",
                ::cancelProductOutTask, ReqMeta(auth = false, test = true)
            ),

            // SRD 请求 ERP，查询产品信息
            HttpRequestMapping(HandlerType.GET, "ext/product-info", ::getProductInfo,
                ReqMeta(auth = false, test = true)
            ),
            // SRD 请求 ERP，获取出库任务 - 云服务器联调时增加的接口
            HttpRequestMapping(HandlerType.GET, "ext/product-store-task", ::getProductStoreTask,
                ReqMeta(auth = false, test = true)
            ),
            // SRD 请求 ERP，告知产品入库完成
            HttpRequestMapping(HandlerType.POST, "ext/manual/product-in-finished/", ::submitInFinished,
                ReqMeta(auth = false, test = true, reqBodyDemo = listOf(
                    """{
                        |"FContractNo":"订字",
                        |"FMONO":"工单号",
                        |"FEmpName":"业务员名称",
                        |"FCustName":"客户名称",
                        |"FQtyPiece":"匹数",
                        |"FQty":"米数",
                        |"Store":"终点库位"
                        |}""".trimMargin()
                ))
            ),
            // SRD 请求 ERP，告知产品出库完成
            HttpRequestMapping(HandlerType.POST, "ext/manual/product-out-finished/", ::submitOutFinished,
                ReqMeta(auth = false, test = true, reqBodyDemo = listOf(
                    """{"FMONO":"工单号","fromSite":"业务员名称"}""".trimMargin()
                ))
            ),
            // SRD 请求 ERP，告知理库完成
            HttpRequestMapping(HandlerType.POST, "ext/manual/product-sort-finished/", ::submitSortFinished,
                ReqMeta(auth = false, test = true, reqBodyDemo = listOf(
                    """{"FMONO":"工单号","oldSite":"原库位","newSite":"新库位"}""".trimMargin()
                ))
            ),

            // ERP 向 SRD 下达产品出库任务
            HttpRequestMapping(HandlerType.POST, "ext/product-store-out", ::productStoreOutFromErp,
                ReqMeta(auth = false, test = true, reqBodyDemo = listOf())
            ),

            // 发起自动理库任务
            HttpRequestMapping(HandlerType.POST, "ext/auto-sort", ::sortProduct,
                ReqMeta(auth = false, test = true, reqBodyDemo = listOf())
            ),

            // 模拟平板 发起 搬运空托盘的任务
            HttpRequestMapping(HandlerType.POST, "task-by-operator/set-site-f-empty/", ::setSiteFEmpty,
                ReqMeta(auth = false, test = true, reqBodyDemo = listOf())
            ),

            // 模拟ERP， 用于自己测试时 接收数据
            HttpRequestMapping(HandlerType.GET, "ext/mock/product-info", ::mockProductInfo,
                ReqMeta(auth = false, test = false)
            ),
            HttpRequestMapping(HandlerType.GET, "ext/mock/product-store-in", ::mockProductInFinished,
                ReqMeta(auth = false, test = false)
            ),
            HttpRequestMapping(HandlerType.POST, "ext/mock/product-store-in", ::mockProductInFinished,
                ReqMeta(auth = false, test = false)
            ),
            HttpRequestMapping(HandlerType.POST, "ext/mock/product-store-out", ::mockProductOutFinished,
                ReqMeta(auth = false, test = false)
            ),
            HttpRequestMapping(HandlerType.POST, "ext/mock/product-sort", ::mockProductSortFinshed,
                ReqMeta(auth = false, test = false)
            ),

            HttpRequestMapping(HandlerType.POST, "mark-executing-task-finished/:taskId", ::markExecutingTaskFinished,
                ReqMeta(auth = false, test = true)
            )
        )

        OperatorInputDetails.addProcessor("GetProductInfo") { code -> getProductInfoStr(code) }

        OperatorOptionsSource.addReader("CurrentStatus") {
            val pp = getDefaultSPP()
            if (pp == null) {
                listOf(SelectOption("", ""))
            } else {
                val enabled = pp.enableAutoSort
                listOf(
                    SelectOption(
                        "$enabled",
                        "【${parseLabel(enabled)}】")
                )
            }
        }

        OperatorOptionsSource.addReader("EnableAutoSort") {
            val pp = getDefaultSPP()
            if (pp == null) {
                listOf(SelectOption("", ""))
            } else {
                val enabled = pp.enableAutoSort
                listOf(
                    SelectOption(
                        "${!enabled}",
                        "【${parseLabel(!enabled)}】")
                )
            }
        }

        OperatorOptionsSource.addReader("UpdateProductOutInfos") {
            // 从数据库中获取任务信息 TaskOutInfo
            val infoList = MongoDBManager.collection<TaskOutInfo>()
                .find(TaskOutInfo::executed eq false)
            val list: MutableList<SelectOption> = mutableListOf()
            if (infoList.count() > 0) {
                logger.debug("数据库中有未完成的任务")
                for (info in infoList) {
                    list += SelectOption(
                        info.json,
                        "#${infoList.indexOf(info)} - ${info.fbillNo}, ${info.fempName}, ${info.fcustName}"
                    )
                }
            } else {
                logger.debug("数据库中没有未完成的任务")
                list += SelectOption("", "无任务")
            }
            list
        }

    }
}

fun main() {
    try {
        // 记录更多跟电梯相关的日志
        logger.debug("released on 2021-01-18.")
        WyApp.init()
        ExtHandlers.init()
        Application.start()
    } catch (e: Exception) {
        logger.error(
            "\n>>>>>>>>>>>>>>>> ERROR OCCURRED!!! <<<<<<<<<<<<<<<<< " +
                "\n${e.message} " +
                "\n>>>>>>>>>>>>>>>> ERROR OCCURRED!!! <<<<<<<<<<<<<<<<<"
        )
        exitProcess(0)
    }
}