package com.seer.srd.moshi.stat

import com.mongodb.client.model.Aggregates.group
import com.mongodb.client.model.Aggregates.match
import com.mongodb.client.model.Filters.*
import com.mongodb.client.model.UpdateOptions
import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager.collection
import com.seer.srd.db.MongoDBManager.getDatabase
import com.seer.srd.eventlog.EventLogLevel
import com.seer.srd.eventlog.SystemEvent
import com.seer.srd.eventlog.recordSystemEventLog
import com.seer.srd.moshi.CUSTOM_CONFIG
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.RobotTransport
import com.seer.srd.route.service.VehicleService.listVehicles
import com.seer.srd.util.setZonedDateTimePartByString
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import org.litote.kmongo.elemMatch
import org.litote.kmongo.eq
import org.litote.kmongo.set
import org.litote.kmongo.setTo
import org.slf4j.LoggerFactory
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

private val logger = LoggerFactory.getLogger("com.seer.srd.moshi.stat")

/** 一天的统计时段的定义 */
class DayPartDef(
    var label: String,
    var start: String, // HH:mm:ss 格式
    var end: String // HH:mm:ss 格式
)

private class StatJob(
    val account: StatAccount,
    val start: ZonedDateTime, // 闭
    val end: ZonedDateTime, // 开
    val level: StatTimeLevel,
    val timeKey: String
)

/** 一条完整的统计结果 */
class MoShiStatRecord(
    @BsonId var id: ObjectId = ObjectId(),
    var type: String = "", // 科目名称
    var vehicleName: String = "",
    var level: StatTimeLevel = StatTimeLevel.Day,
    var time: String = "",
    var value: Long = 0,
    var recordedOn: Instant = Instant.now()
)

enum class StatTimeLevel {
    Hour, DayPart, Day, Week, Month, Year
}

fun doStatFrequently() {
    logger.info("[stats start] frequently")
    for (sa in statAccounts) {
        // 计算前一个小时和当前小时的
        val thisHour = ZonedDateTime.now().truncatedTo(ChronoUnit.HOURS)
        doStatJob(hourJob(sa, thisHour))
        val lastHour = thisHour.minus(1, ChronoUnit.HOURS)
        doStatJob(hourJob(sa, lastHour))
    }
    logger.info("[stats end  ] frequently")
}

fun doStatDaily() {
    logger.info("[stats start] daily")
    val day = ZonedDateTime.now().truncatedTo(ChronoUnit.DAYS).minus(1, ChronoUnit.DAYS) // last day
    for (sa in statAccounts) {
        for (dayPartDef in CUSTOM_CONFIG.moshiStatDayPartDefs) {
            doStatJob(dayPartJob(sa, day, dayPartDef))
        }
        doStatJob(dayJob(sa, day))
        doStatJob(weekJob(sa, day))
        doStatJob(monthJob(sa, day))
        doStatJob(yearJob(sa, day))
    }
    logger.info("[stats end  ] daily")
}

fun forceStat(start: ZonedDateTime, end: ZonedDateTime) {
    logger.info("force start $start ~ $end")
    recordSystemEventLog("Stat", EventLogLevel.Info, SystemEvent.ForceStatStart, "$start ~ $end")
    var day = start.truncatedTo(ChronoUnit.DAYS)
    val endDay = end.truncatedTo(ChronoUnit.DAYS)
    while (!day.isAfter(endDay)) {
        logger.info("force start $day")
        try {
            for (sa in statAccounts) {
                for (h in 0..23) {
                    val startHour = day.truncatedTo(ChronoUnit.DAYS).plus(h.toLong(), ChronoUnit.HOURS)
                    doStatJob(hourJob(sa, startHour))
                }
                for (dayPartDef in CUSTOM_CONFIG.moshiStatDayPartDefs) {
                    doStatJob(dayPartJob(sa, day, dayPartDef))
                }
                doStatJob(dayJob(sa, day))
                doStatJob(weekJob(sa, day))
                doStatJob(monthJob(sa, day))
                doStatJob(yearJob(sa, day))
            }
        } catch (e: Exception) {
            logger.error("force stat", e)
        }
        logger.info("force end $day")
        day = day.plus(1, ChronoUnit.DAYS)
    }
    logger.info("force end $start ~ $end")
    recordSystemEventLog("Stat", EventLogLevel.Info, SystemEvent.ForceStatEnd, "$start ~ $end")
}

private fun doStatJob(job: StatJob) {
    try {
        val vehicles = listVehicles().map { it.name }
        if (vehicles.isEmpty()) logger.debug("无车辆信息，报表不记录")
        vehicles.forEach {
            val account = job.account
            val filters = arrayListOf<Bson>()
            filters.add(gte(account.timeColumn, job.start.toInstant()))
            filters.add(lt(account.timeColumn, job.end.toInstant()))
            //添加车辆信息
            if (account.vehicleColumn.isNullOrBlank())
                filters.add(RobotTask::transports.elemMatch(RobotTransport::processingRobot eq it))
            else filters.add(eq(account.vehicleColumn, it))
            if (account.filters != null) filters.add(account.filters)
            val pipeline = listOf(
                match(and(filters)),
                group(account.group, account.fieldAccumulators)
            )
            val r = getDatabase().getCollection(account.table).aggregate(pipeline)
            val records = r.toList().map { rr ->
                val value: Long = when (val rw = rr["value"]) {
                    is Long -> rw
                    is Int -> rw.toLong()
                    is Double -> rw.toLong()
                    is Float -> rw.toLong()
                    else -> throw BusinessError("Bad value type $rw")
                }
                MoShiStatRecord(
                    ObjectId(), account.type, it, job.level, job.timeKey, value / account.valueScale
                )
            }
            val csr = collection<MoShiStatRecord>()
            for (record in records) {
                csr.updateOne(
                    and(
                        MoShiStatRecord::type eq record.type,
                        MoShiStatRecord::level eq record.level,
                        MoShiStatRecord::time eq record.time,
                        MoShiStatRecord::vehicleName eq record.vehicleName
                    ),
                    set(MoShiStatRecord::value setTo record.value, MoShiStatRecord::recordedOn setTo record.recordedOn),
                    UpdateOptions().upsert(true)
                )
            }
        }
    } catch (e: Exception) {
        logger.error("do stat", e)
    }
}

private fun hourJob(sa: StatAccount, startHour: ZonedDateTime): StatJob {
    val endHour = startHour.plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS)
    val timeKey = startHour.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'+'HH"))
    return StatJob(sa, startHour, endHour, StatTimeLevel.Hour, timeKey)
}

private fun dayPartJob(sa: StatAccount, day: ZonedDateTime, dayPartDef: DayPartDef): StatJob {
    val start = setZonedDateTimePartByString(day, dayPartDef.start)
    val end = setZonedDateTimePartByString(day, dayPartDef.end)
    val timeKey = start.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + " " + dayPartDef.label
    return StatJob(sa, start, end, StatTimeLevel.DayPart, timeKey)
}

private fun dayJob(sa: StatAccount, startDay: ZonedDateTime): StatJob {
    val endDay = startDay.plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS)
    val timeKey = startDay.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
    return StatJob(sa, startDay, endDay, StatTimeLevel.Day, timeKey)
}

private fun weekJob(sa: StatAccount, day: ZonedDateTime): StatJob {
    val start = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val end = start.plus(1, ChronoUnit.WEEKS)
    val timeKey = start.format(DateTimeFormatter.ofPattern("yyyy-'W'ww"))
    return StatJob(sa, start, end, StatTimeLevel.Week, timeKey)
}

private fun monthJob(sa: StatAccount, day: ZonedDateTime): StatJob {
    val start = day.withDayOfMonth(1)
    val end = start.plus(1, ChronoUnit.MONTHS)
    val timeKey = start.format(DateTimeFormatter.ofPattern("yyyy-MM"))
    return StatJob(sa, start, end, StatTimeLevel.Month, timeKey)
}

private fun yearJob(sa: StatAccount, day: ZonedDateTime): StatJob {
    val start = day.withDayOfYear(1)
    val end = start.plus(1, ChronoUnit.YEARS)
    val timeKey = start.format(DateTimeFormatter.ofPattern("yyyy"))
    return StatJob(sa, start, end, StatTimeLevel.Year, timeKey)
}
