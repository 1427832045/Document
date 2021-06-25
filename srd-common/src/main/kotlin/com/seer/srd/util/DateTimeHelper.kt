package com.seer.srd.util

import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoField

fun setInstantTimePartByString(instant: Instant, time: String): Instant {
    val times = time.split(":")
    val h = times[0].toLong()
    val m = times[1].toLong()
    val s = times[2].toLong()
    return instant
        .with(ChronoField.HOUR_OF_DAY, h)
        .with(ChronoField.MINUTE_OF_HOUR, m)
        .with(ChronoField.SECOND_OF_MINUTE, s)
}

fun setZonedDateTimePartByString(dt: ZonedDateTime, time: String): ZonedDateTime {
    val times = time.split(":")
    val h = times[0].toLong()
    val m = times[1].toLong()
    val s = times[2].toLong()
    return dt
        .with(ChronoField.HOUR_OF_DAY, h)
        .with(ChronoField.MINUTE_OF_HOUR, m)
        .with(ChronoField.SECOND_OF_MINUTE, s)
}