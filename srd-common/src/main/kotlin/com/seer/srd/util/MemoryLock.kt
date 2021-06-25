package com.seer.srd.util

import com.seer.srd.WithMemoryLockFailed
import java.util.concurrent.ConcurrentHashMap

private val locks: MutableMap<String, Boolean> = ConcurrentHashMap()

fun withMemoryLock(name: String, maxRetries: Int, retryDelay: Long, work: () -> Unit) {
    var retryIndex = 0
    while (maxRetries <= 0 || retryIndex < maxRetries) {
        retryIndex++

        // todo not thread safe
        if (locks[name] == null || locks[name] == false) {
            locks[name] = true
            try {
                work()
            } finally {
                locks[name] = false
            }
            return
        }
        Thread.sleep(retryDelay)
    }
    if (maxRetries in 1..retryIndex) throw WithMemoryLockFailed()
}