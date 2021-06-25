package com.seer.srd.operator

import java.util.concurrent.ConcurrentHashMap

typealias OperatorOptionsSourceReader = () -> List<SelectOption>

data class SelectOption(
    val value: String = "",
    val label: String = ""
)

object OperatorOptionsSource {
    
    private val readers: MutableMap<String, OperatorOptionsSourceReader> = ConcurrentHashMap()
    
    init {
        addReader("TestOptionsSourceOrders") {
            listOf(
                SelectOption("o1", "待处理运单1 - 大王 - 红酒 - 32923323323 - 机加工"),
                SelectOption("o2", "待处理运单2 - 炸弹 - 蜡烛 - 32923251513 - 数字车间")
            )
        }
    }
    
    fun addReader(name: String, reader: OperatorOptionsSourceReader) {
        readers[name] = reader
    }
    
    fun read(name: String): List<SelectOption> {
        val reader = readers[name] ?: throw IllegalArgumentException("No operator options reader for $name")
        return reader()
    }
    
}