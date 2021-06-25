package com.seer.srd

import com.seer.srd.device.DoorConfig
import com.seer.srd.device.lift.LiftConfig
import com.seer.srd.device.ZoneConfig
import com.seer.srd.device.charger.ChargerConfig
import com.seer.srd.device.converter.HttpToModbusTcpConverterConfig
import com.seer.srd.device.converter.HttpToTcpConverterConfig
import com.seer.srd.device.pager.PagerConfig
import com.seer.srd.operator.OperatorConfig
import com.seer.srd.stats.DayPartDef
import com.seer.srd.util.KeepCollectionConfig
import com.seer.srd.util.loadConfig
import java.io.File

val CONFIG = loadConfig("srd-config.yaml", Config::class) ?: Config()

class Config {
    var httpPort = 7100
    var httpRequestCacheSize = 4096L      // 默认值为 4096L
    var apiPrefix = "api"
    var sessionExpireMinutes = 60 * 24 * 15L
    var mongodbUrl = "mongodb://localhost:27017/srd"
    var mongoMainDatabase = "srd"

    private var uploadDir = ""
    var uiPath = ""
    
    var syncVehiclesDetails = true
    var syncVehiclesDetailsDelay = 1000L
    
    var statDayPartDefs: List<DayPartDef> = emptyList()
    
    var robotTaskListExtraColumns: List<RobotTaskListExtraColumn> = emptyList()
    var robotTaskListExtraFilters: List<RobotTaskListExtraFilter> = emptyList()
    var taskPriorities: List<TaskPriorityDef> = emptyList() // 任务优先级定义，必须从低到高排列
    var syncRouteOrderRetryDelay = 1000L // 内部接口，可以很快
    var processorRetryDelay = 3600L
    var transportRetryDelay = 1000L
    
    var operator: OperatorConfig? = null
    
    var vehicleModels: Map<String, List<String>> = emptyMap()
    
    var useDeadlock = false

    //设置状态轮训周期
    var lifts: List<LiftConfig> = emptyList()
    var liftStatusPollingPeriod = 500L
    
    var chargers: List<ChargerConfig> = emptyList()
    var chargerStatusPollingPeriod = 500L
    
    var doors: Map<String, DoorConfig> = emptyMap()
    var doorsStatusPollingPeriod = 1000L
    
    var zones: Map<String, ZoneConfig> = emptyMap()
    var zonesStatusPollingPeriod = 1000L

    var pagers: List<PagerConfig> = emptyList()
    var pagersStatusPollingPeriod = 200L

    var httpToModbusTcpConverterConfig = HttpToModbusTcpConverterConfig()
    var httpToTcpConverterConfig = HttpToTcpConverterConfig()
    
    var startFromDB = true

    var zoomInByScrollUp = true

    // for email
    var mailHost = "smtp.exmail.qq.com"
    var mailHostPort = 465
    var enableSSL = true
    var mailUser = ""
    var mailPassword = ""
    var defaultRecipients: List<String> = emptyList()
    var mailMark = "This is an email from SRD"

    // 24小时制
    var hoursOfRemoveExpiredFilesAndData: List<Int> = listOf(0)
    var keepCollectionConfigs: List<KeepCollectionConfig> = listOf(
        KeepCollectionConfig("日志文件", 30),
        KeepCollectionConfig("任务"),
        KeepCollectionConfig("运单"),
        KeepCollectionConfig("系统日志"),
        KeepCollectionConfig("操作日志"),
        KeepCollectionConfig("库位变更"),
        KeepCollectionConfig("机器人状态"),
        KeepCollectionConfig("Modbus写"),
        KeepCollectionConfig("Modbus读")
    )

    fun getFinalUploadDir(): String {
        //判断最后上传目录是否存在，如果不存在，则创建
        if (uploadDir.isNotBlank()) return uploadDir
        val uploadDir = File(System.getProperty("user.dir"), "upload")
        if (!uploadDir.exists()) uploadDir.mkdirs()
        return uploadDir.absolutePath
    }

    override fun toString(): String {
        return "Config(httpPort=$httpPort, apiPrefix='$apiPrefix', sessionExpireMinutes=$sessionExpireMinutes, mongodbUrl='$mongodbUrl', mongoMainDatabase='$mongoMainDatabase', uploadDir='$uploadDir', uiPath='$uiPath', syncVehiclesDetails=$syncVehiclesDetails, syncVehiclesDetailsDelay=$syncVehiclesDetailsDelay, statDayPartDefs=$statDayPartDefs, robotTaskListExtraColumns=$robotTaskListExtraColumns, robotTaskListExtraFilters=$robotTaskListExtraFilters, taskPriorities=$taskPriorities, syncRouteOrderRetryDelay=$syncRouteOrderRetryDelay, processorRetryDelay=$processorRetryDelay, transportRetryDelay=$transportRetryDelay, operator=$operator, vehicleModels=$vehicleModels, useDeadlock=$useDeadlock, lifts=$lifts, liftStatusPollingPeriod=$liftStatusPollingPeriod, chargers=$chargers, chargerStatusPollingPeriod=$chargerStatusPollingPeriod, doors=$doors, doorsStatusPollingPeriod=$doorsStatusPollingPeriod, zones=$zones, zonesStatusPollingPeriod=$zonesStatusPollingPeriod, startFromDB=$startFromDB, mailHost='$mailHost', mailHostPort=$mailHostPort, enableSSL=$enableSSL, mailUser='$mailUser', mailPassword='$mailPassword', defaultRecipients=$defaultRecipients, mailMark='$mailMark')"
    }
}

class RobotTaskListExtraColumn(
    var label: String = "",
    var fieldPath: String = ""
)

class RobotTaskListExtraFilter(
    var column: String,
    var label: String
)

class TaskPriorityDef(
    var name: String = "",
    var value: Int = 0
)
