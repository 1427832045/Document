package com.seer.srd.scheduler

import com.seer.srd.CONFIG
import com.seer.srd.eventbus.EventBus.checkVehicleErrorInfo
import com.seer.srd.getVersions
import com.seer.srd.route.checkVehiclesDetails
import com.seer.srd.route.recalculateTopologyCost
import com.seer.srd.route.routeConfig
import com.seer.srd.scheduler.ThreadFactoryHelper.buildNamedThreadFactory
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

object GlobalTimer {

    private val logger = LoggerFactory.getLogger(GlobalTimer::class.java)

    //定义线程池，去查阅executors的文档，创建固定个数的多个线程
    val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(20, buildNamedThreadFactory("srd-main-scheduler"))

    fun startTimers() {
        val cc = CONFIG

        //是否查询车的详情信息
        if (cc.syncVehiclesDetails) {
            //scheduleAtFixedRate为定时器，为了实现周期查询车的信息
            executor.scheduleAtFixedRate(
                ::checkVehiclesDetails,//要执行的任务
                2000,//设置延迟第一次执行的时间
                cc.syncVehiclesDetailsDelay,//设置查询车的详情信息的周期
                TimeUnit.MILLISECONDS //时间单位
            )
        }

//        executor.scheduleAtFixedRate(
//            ::checkVehicleErrorInfo,
//            2000,
//            2000,
//            TimeUnit.MILLISECONDS
//        )

        executor.scheduleAtFixedRate(
            this::logSrdVersion,
            0,
            10,
            TimeUnit.MINUTES
        )

        if (routeConfig.useDynamicTopologyCost) {
            executor.scheduleAtFixedRate(
                ::recalculateTopologyCost,
                routeConfig.recalculateTopologyCostPeriodS,
                routeConfig.recalculateTopologyCostPeriodS,
                TimeUnit.SECONDS
            )
        }
    }

    fun stopTimers() {
        try {
            executor.shutdown()
        } catch (e: Exception) {
            logger.error("stop timers", e)
        }
    }

    private fun logSrdVersion() {
        logger.debug("SRD server version: ${getVersions()}")
    }

}

