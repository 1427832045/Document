package com.seer.srd

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.slf4j.LoggerFactory
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

object I18N {
    
    private val logger = LoggerFactory.getLogger(I18N::class.java)
    
    private val dict: MutableMap<String, MutableMap<String, String>> = HashMap()
    
    fun locale(key: String, lang: String, vararg args: Any?): String {
        val languages = dict[key] ?: return key
        val language = languages[lang] ?: return key
        return String.format(language, args)
    }
    
    fun loadDict(file: String = "/dict.csv") {
        val res = javaClass.getResourceAsStream(file) ?: throw SystemError("No $file")
        InputStreamReader(res, StandardCharsets.UTF_8).use { input ->
            val r = CSVParser(input, CSVFormat.EXCEL.withHeader()).toList()
            logger.info("Load dict items ${r.size}")
            for (record in r) {
                val key = record.get(0)
                val languages: MutableMap<String, String> = HashMap()
                dict[key] = languages
                languages["zh"] = record.get(1)
                languages["en"] = record.get(2)
                languages["jp"] = record.get(3)
            }
        }
        
    }
}

