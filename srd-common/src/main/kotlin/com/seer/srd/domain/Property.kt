package com.seer.srd.domain

data class Property(
    var key: String = "",
    var value: String = ""
)

fun propertyListToMap(list: List<Property>): Map<String, String> {
    val map: MutableMap<String, String> = HashMap()
    for (p in list) map[p.key] = p.value
    return map
}