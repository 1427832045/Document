package com.seer.srd

import com.seer.srd.util.mapper
import org.slf4j.LoggerFactory

object RbkError {

  private val logger = LoggerFactory.getLogger(RbkError::class.java)

  private val rbkFatal: MutableMap<String, String> = HashMap()

  private val rbkError: MutableMap<String, String> = HashMap()

  private val rbkWarning: MutableMap<String, String> = HashMap()

  fun loadRbkAlarmInfo() {
    val res = javaClass.getResourceAsStream("/rbk.error") ?: throw SystemError("No rbk.error file")
    val json = mapper.readValue(res,  HashMap::class.java)

    rbkFatal.putAll(json["fatal"] as Map<String, String>)
    rbkError.putAll(json["error"] as Map<String, String>)
    rbkWarning.putAll(json["warning"] as Map<String, String>)
    logger.debug("load rbk error info from rbk.error")
  }

  fun getRbkAlarmTypeOrNullByCode(code: String): String? {
    return when {
      rbkFatal[code] != null -> RbkAlarmType.FATAL.name
      rbkError[code] != null -> RbkAlarmType.ERROR.name
      rbkWarning[code] != null -> RbkAlarmType.WARNING.name
      else -> null
    }
  }

  fun getAlarmInfoOrNullByCode(code: String): String? {
    return when {
      rbkFatal[code] != null -> rbkFatal[code]
      rbkError[code] != null -> rbkError[code]
      rbkWarning[code] != null -> rbkWarning[code]
      else -> null
    }
  }

  fun getFatalMap(): Map<String, String> { return rbkFatal }

  fun getErrorMap(): Map<String, String> { return rbkError }

  fun getWarningMap(): Map<String, String> { return rbkWarning }
}

enum class RbkAlarmType {
  FATAL, ERROR, WARNING
}