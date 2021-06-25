package com.seer.srd.util

fun isFalseLike(value: Any?): Boolean {
    if (value == null) return true
    if (value is String) return value.isBlank()
    if (value is Boolean) return !value
    if (value is Number) return value == 0
    return false
}

fun splitTrim(str: String?, sep: String): List<String> {
    if (str == null) return emptyList()
    return str.split(sep).map { it.trim() }.filter { it.isNotBlank() }.toList()
}