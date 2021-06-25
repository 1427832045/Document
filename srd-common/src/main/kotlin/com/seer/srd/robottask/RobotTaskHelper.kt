package com.seer.srd.robottask

import com.seer.srd.TaskPriorityDef
import com.seer.srd.CONFIG
import com.seer.srd.I18N.locale
import java.time.Instant
import java.time.LocalDateTime
import java.time.Month
import java.time.ZoneOffset

fun getTaskPriorities(lang: String): List<TaskPriorityDef> {
    var taskPriorities = CONFIG.taskPriorities
    if (taskPriorities.isEmpty()) taskPriorities = getDefaultTaskPriorities(lang)
    return taskPriorities.sortedBy { it.value }
}

private fun getDefaultTaskPriorities(lang: String): List<TaskPriorityDef> = listOf(
    TaskPriorityDef(locale("Priority_Normal", lang), 0),
    TaskPriorityDef(locale("Priority_High", lang), 10),
    TaskPriorityDef(locale("Priority_VeryHigh", lang), 30)
)

fun taskPriorityToDeadline(priority: Int?): Instant {
    val ahead = (priority ?: 0) // 优先级一个整数对应一分钟
    val m = LocalDateTime.of(2020, Month.JANUARY, 1, 0, 0, 0).plusMinutes(-ahead.toLong())
    return m.toInstant(ZoneOffset.UTC)
}