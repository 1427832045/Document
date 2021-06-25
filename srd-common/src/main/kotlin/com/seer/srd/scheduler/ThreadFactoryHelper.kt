package com.seer.srd.scheduler

import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

object ThreadFactoryHelper {
    
    fun buildNamedThreadFactory(name: String): ThreadFactory {
        val counter = AtomicInteger(0)
        return ThreadFactory { r ->
            val no = counter.incrementAndGet()
            Thread(r, "$name-$no")
        }
    }
    
}

