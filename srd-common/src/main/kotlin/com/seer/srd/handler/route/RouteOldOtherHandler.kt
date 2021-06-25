package com.seer.srd.handler.route

import com.seer.srd.SystemError
import com.seer.srd.route.kernelExecutor
import io.javalin.http.Context
import org.opentcs.access.Kernel
import org.opentcs.access.LocalKernel
import org.opentcs.kernel.getInjector
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

private val LOG = LoggerFactory.getLogger("com.seer.srd.handler")

/** Describes the version of the running kernel. */
data class Version(
    val baselineVersion: String = "0",
    val customizationName: String = "0",
    val customizationVersion: String = "0"
)

fun handleRouteGetVersion(ctx: Context) {
    ctx.json(Version())
}

data class Status(
    val heapSize: Long = Runtime.getRuntime().totalMemory(),
    val maxHeapSize: Long = Runtime.getRuntime().maxMemory(),
    val freeInHeap: Long = Runtime.getRuntime().freeMemory()
)

fun handleRouteGetStatus(ctx: Context) {
    ctx.json(Status())
}

fun handleRouteDeleteKernel(ctx: Context) {
    LOG.info("Initiating kernel shutdown as requested")

    val injector = getInjector() ?: throw SystemError("No Injector")
    val kernel = injector.getInstance(LocalKernel::class.java)

    kernelExecutor.schedule({ kernel.state = Kernel.State.SHUTDOWN }, 1, TimeUnit.SECONDS)

    ctx.status(200)
}

fun handleRouteGetEvents(ctx: Context) {
    //val minSeqNo = ctx.queryParam("minSequenceNo")?.toLong() ?: 0
    //val maxSeqNo = ctx.queryParam("maxSequenceNo")?.toLong() ?: 0
    //val timeout = (ctx.queryParam("timeout")?.toLong() ?: 1000).coerceAtMost(1000)

    //val events = statusEventDispatcher.fetchEvents(minSeqNo, maxSeqNo, timeout)
    ctx.json(emptyList<Any>())
}