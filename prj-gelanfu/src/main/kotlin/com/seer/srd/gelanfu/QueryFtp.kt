package com.seer.srd.gelanfu

import com.seer.srd.scheduler.ThreadFactoryHelper.buildNamedThreadFactory
import com.seer.srd.scheduler.backgroundCacheExecutor
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(buildNamedThreadFactory("query-ftp"))

const val ordersPath = "output/RBTJKBDLSE"

object QueryFtp {

    private val logger = LoggerFactory.getLogger(QueryFtp::class.java)

    @Volatile
    private var working = false

    fun start() {
        scheduledExecutorService.scheduleAtFixedRate(this::query, 1, 2, TimeUnit.SECONDS)
    }

    private fun query() {
        if (working) return
        working = true
        try {
            queryOrders()
        } catch (e: Exception) {
            logger.error("do fetch orders", e)
        } finally {
            working = false
        }
    }

    private fun queryOrders() {
        val files = outFtp.listFiles(ordersPath)

        val orderFiles = files.filter { file -> file.name.startsWith("WMTORD_") }
        logger.info("Found order file ${orderFiles.size}")
        orderFiles.forEach { file ->
            val inputStream = outFtp.retrieveFileStream("$ordersPath/${file.name}")
            backgroundCacheExecutor.submit { processOrder(file.name, inputStream) }
        }

        val cancelFiles = files.filter { file -> file.name.startsWith("WMCATO_") }
        logger.info("Found cancel file ${cancelFiles.size}")
        cancelFiles.forEach { file ->
            val inputStream = outFtp.retrieveFileStream("$ordersPath/${file.name}")
            backgroundCacheExecutor.submit { processCancel(file.name, inputStream) }
        }
    }

}

