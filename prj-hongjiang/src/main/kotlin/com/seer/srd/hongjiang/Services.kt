package com.seer.srd.hongjiang

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

object Services {

  private val logger = LoggerFactory.getLogger(Services::class.java)

  val cutterBackMap: MutableMap<String, String> = ConcurrentHashMap()

}