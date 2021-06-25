package org.opentcs.components

interface Lifecycle {
    fun initialize()
    fun isInitialized(): Boolean
    fun terminate()
}