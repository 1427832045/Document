package com.seer.srd.route

import com.seer.srd.route.event.SimpleEventBus
import org.opentcs.common.LoggingScheduledThreadPoolExecutor
import org.opentcs.util.logging.UncaughtExceptionLogger
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory

val kernelExecutor: ScheduledExecutorService = LoggingScheduledThreadPoolExecutor(
    1,
    ThreadFactory { runnable: Runnable? ->
        val thread = Thread(runnable, "kernelExecutor")
        thread.uncaughtExceptionHandler = UncaughtExceptionLogger(false)
        thread
    }
)

val eventBus = SimpleEventBus()

// A single global synchronization object for the kernel.
val globalSyncObject = Object()

object DeadlockState {
    var solvingDeadlock = false
}