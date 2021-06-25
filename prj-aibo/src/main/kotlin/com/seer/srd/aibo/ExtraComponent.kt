package com.seer.srd.aibo

import com.seer.srd.robottask.component.TaskComponentDef
import org.slf4j.LoggerFactory

object ExtraComponent {

  private val logger = LoggerFactory.getLogger(ExtraComponent::class.java)

  val extraComponent = listOf(
      TaskComponentDef(
          "extra", "AiBo:", "", "", false, listOf(
      ), false) {_, ctx ->
      }
  )
}