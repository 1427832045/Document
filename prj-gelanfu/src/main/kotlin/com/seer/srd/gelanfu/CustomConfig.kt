package com.seer.srd.gelanfu

import com.seer.srd.util.loadConfig

val CUSTOM_CONFIG = loadConfig("srd-config.yaml", CustomConfig::class) ?: CustomConfig()

class CustomConfig {
    var outFtpHostname: String = "localhost"
    var outFtpPort: Int = 21
    var outFtpUsername: String = "anonymous"
    var outFtpPassword: String = ""

    var inFtpHostname: String = "localhost"
    var inFtpPort: Int = 21
    var inFtpUsername: String = "anonymous"
    var inFtpPassword: String = ""
}