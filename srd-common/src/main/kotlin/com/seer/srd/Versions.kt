package com.seer.srd

private val versions: MutableMap<String, String> = HashMap()

fun getVersion(key: String): String? {
    return versions[key]
}

fun getVersions(): Map<String, String> {
    return versions
}

fun setVersion(key: String, v: String) {
    versions[key] = v
}
