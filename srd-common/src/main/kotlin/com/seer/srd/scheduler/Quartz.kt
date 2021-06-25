package com.seer.srd.scheduler

import org.quartz.impl.StdSchedulerFactory
import org.slf4j.LoggerFactory

val scheduler = StdSchedulerFactory.getDefaultScheduler()

private val logger = LoggerFactory.getLogger("com.seer.srd.scheduler")

fun startQuartz() {
    scheduler.start()
}

fun endQuartz() {
    try {
        scheduler.shutdown()
    } catch (e: Exception) {
        logger.error("end quartz", e)
    }
}


