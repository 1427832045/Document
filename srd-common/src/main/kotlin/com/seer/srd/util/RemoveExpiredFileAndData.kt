package com.seer.srd.util

import com.seer.srd.BusinessError
import com.seer.srd.CONFIG
import com.seer.srd.db.MongoDBManager
import com.seer.srd.eventlog.*
import com.seer.srd.robottask.RobotTask
import com.seer.srd.scheduler.backgroundFixedExecutor
import org.litote.kmongo.lt
import org.opentcs.data.order.TransportOrder
import org.slf4j.LoggerFactory
import java.io.File
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class KeepCollectionConfig(
    // 需要删除的数据的或者文件的描述，对于文件而言，目前只能删除"日志文件"
    var description: String = "",

    // 默认配置下，只保留最近1年（365天）的数据库数据，或者日志文件。
    var keepDays: Long = 365L
)

private val logger = LoggerFactory.getLogger("com.seer.srd")

fun removeExpiredCollectionsAndFiles() {
    backgroundFixedExecutor.submit {
        val listOfCollections = CONFIG.keepCollectionConfigs
        val now = LocalDateTime.now()
        val time = now.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        if (!CONFIG.hoursOfRemoveExpiredFilesAndData.contains(now.hour) || !time.matches(Regex("^\\d{2}:00:\\d{2}$")))
            return@submit
        listOfCollections.forEach {
            val description = it.description.trim()
            logger.info("remove expired Collections and Files: description=$description, days=${it.keepDays}")
            try {
                val instant = now.toInstant(ZoneOffset.UTC).plus(0 - it.keepDays, ChronoUnit.DAYS)
                when (description) {
                    "日志文件" -> {
                        // 删除过期的日志文件
                        removeExpiredLogFiles(instant)
                    }
                    "任务" -> MongoDBManager.collection<RobotTask>().deleteMany(RobotTask::createdOn lt instant)
                    "运单" -> MongoDBManager.collection<TransportOrder>().deleteMany(TransportOrder::creationTime lt instant)
                    "系统日志" -> MongoDBManager.collection<SystemEventLog>().deleteMany(SystemEventLog::createdOn lt instant)
                    "操作日志" -> MongoDBManager.collection<UserOperationLog>().deleteMany(UserOperationLog::createdOn lt instant)
                    "库位变更" -> MongoDBManager.collection<StoreSiteChange>().deleteMany(StoreSiteChange::timestamp lt instant)
                    "机器人状态" -> MongoDBManager.collection<VehicleStateTrace>().deleteMany(VehicleStateTrace::startOn lt instant)
                    "Modbus写" -> MongoDBManager.collection<ModbusWriteLog>().deleteMany(ModbusWriteLog::createdOn lt instant)
                    "Modbus读" -> MongoDBManager.collection<ModbusReadLog>().deleteMany(ModbusReadLog::createdOn lt instant)
                    else -> throw BusinessError("unrecognized description=$description")
                }
            } catch (e: Exception) {
                logger.error("{remove expired Collections and Files failed: }", e)
            }
        }
    }
}

private fun removeExpiredLogFiles(limitDate: Instant) {
    backgroundFixedExecutor.submit {
        val cfgDir = File(System.getProperty("user.dir"), "logs")
        if (!cfgDir.exists()) cfgDir.mkdirs()

        val files = cfgDir.listFiles()
        if (files.isNullOrEmpty()) {
            logger.info("remove expired log files failed: no files...")
            return@submit
        }

        files.filter { it.name.matches(Regex("^(srd)(.\\d{4}-\\d{1,2}-\\d{1,2})?(.log)$")) }.forEach {
            // 只需要处理历史log，不需要处理当天的log
            try {
                if (it.name.matches(Regex("^(srd.)(-[a-zA-Z]+)?\\d{4}-\\d{1,2}-\\d{1,2}(.log)$"))) {
                    val dataString = it.name.substring(it.name.indexOf('.') + 1, it.name.lastIndexOf('.'))
                    val data = LocalDate.parse(dataString, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                        .atStartOfDay(ZoneId.systemDefault())
                    if (data.toInstant().isBefore(limitDate)) {
                        it.delete()
                    }
                }
            } catch (e: Exception) {
                logger.error("remove expired log files failed: ", e)
            }
        }
    }
}