package com.seer.srd.scheduler

import com.seer.srd.scheduler.ThreadFactoryHelper.buildNamedThreadFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private val backgroundFixedExecutorThreadFactory = buildNamedThreadFactory("srd-bg-fixed")
private val backgroundCacheExecutorThreadFactory = buildNamedThreadFactory("srd-bg-cache")

val backgroundFixedExecutor: ExecutorService = Executors.newFixedThreadPool(25, backgroundFixedExecutorThreadFactory)
val backgroundCacheExecutor: ExecutorService = Executors.newCachedThreadPool(backgroundCacheExecutorThreadFactory)

fun stopExecutors() {
    backgroundFixedExecutor.shutdown()
    backgroundCacheExecutor.shutdown()
}