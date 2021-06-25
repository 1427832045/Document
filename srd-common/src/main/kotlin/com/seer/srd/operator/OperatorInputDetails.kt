package com.seer.srd.operator

import java.util.concurrent.ConcurrentHashMap

typealias OperatorInputDetailProcessor = (input: String) -> String

object OperatorInputDetails {
    
    private val processors: MutableMap<String, OperatorInputDetailProcessor> = ConcurrentHashMap()
    
    init {
        addProcessor("TestInputDetail") { input ->
            "$input Details Details Details Details Details"
        }
    }
    
    fun addProcessor(name: String, processor: OperatorInputDetailProcessor) {
        processors[name] = processor
    }
    
    fun process(name: String, input: String): String {
        val processor = processors[name] ?: throw IllegalArgumentException("No input detail processor for $name")
        return processor(input)
    }
    
}

