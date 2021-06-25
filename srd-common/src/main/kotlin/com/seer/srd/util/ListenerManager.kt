package com.seer.srd.util

import java.util.concurrent.CopyOnWriteArrayList

class ListenerManager<T> {

    private val listeners: MutableList<Listener<T>> = CopyOnWriteArrayList()

    fun add(listener: Listener<T>) {
        listeners.add(listener)
    }

    fun remove(listener: Listener<T>) {
        listeners.remove(listener)
    }

    fun fire(event: T) {
        listeners.toList().forEach { it(event) }
    }

}

typealias Listener<T> = (event: T) -> Any

