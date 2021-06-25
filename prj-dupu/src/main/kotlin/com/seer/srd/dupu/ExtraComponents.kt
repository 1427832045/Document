package com.seer.srd.dupu

import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import com.seer.srd.robottask.RobotTask
import com.seer.srd.robottask.RobotTaskState
import com.seer.srd.robottask.component.TaskComponentDef
import com.seer.srd.robottask.component.TaskComponentParam
import com.seer.srd.robottask.component.parseComponentParamValue
import com.seer.srd.util.mapper
import org.litote.kmongo.eq
import org.litote.kmongo.find
import java.time.Instant

val extraComponents: List<TaskComponentDef> = listOf(
    TaskComponentDef(
        "extra", "IsSpecialSite", "是否是特殊站点", "", false,
        listOf(TaskComponentParam("site", "库位ID", "string")),
        true
    ) { component, ctx ->

        val site = parseComponentParamValue("site", component, ctx) as String
        val value = DupuService.isSpecial(site = site)
        ctx.setRuntimeVariable(component.returnName, value)
    },

    TaskComponentDef(
        "extra", "DispatchVehicleBySite", "根据站点分配AGV", "", false,
        listOf(TaskComponentParam("site", "库位ID", "string")), true
    ) { component, ctx ->

        val site = parseComponentParamValue("site", component, ctx) as String
        val vehicle = DupuService.dispatchVehicleBySite(site)
        ctx.setRuntimeVariable(component.returnName, vehicle)
    },

    TaskComponentDef(
        "extra", "saveMaterialInfo", "存储物料信息", "", false,
        listOf(
            TaskComponentParam("info", "物料信息", "string"),
            TaskComponentParam("workType", "岗位", "string")
        ), false
    ) { component, ctx ->
        // 存储物料信息，后续可能改到erp
        val workstation = parseComponentParamValue("workType", component, ctx) as String
        val map = parseComponentParamValue("info", component, ctx) as HashMap<String, String>
        val info = MaterialInfo(
            qrCode = map["qrCode"],
            productNo = map["productNo"],
            productSerial = map["productSerial"],
            workerNo = workstation,
            onlineDate = DupuService.buildTime(map["onlineDate"]),
            param1 = map["param1"],
            param2 = map["param2"],
            param3 = map["param3"]
        )

        val raw = mapper.readTree(map["qrCode"])
        if (raw["spareNo"] != null) info.spareNo =
            raw["spareNo"].toString().substringAfter("\"").substringBeforeLast("\"")
        if (raw["name"] != null) info.name = raw["name"].toString().substringAfter("\"").substringBeforeLast("\"")
        if (raw["spec"] != null) info.spec = raw["spec"].toString().substringAfter("\"").substringBeforeLast("\"")
        if (raw["type"] != null) info.type = raw["type"].toString().substringAfter("\"").substringBeforeLast("\"")
        if (raw["vendor"] != null) info.vendor = raw["vendor"].toString().substringAfter("\"").substringBeforeLast("\"")
        if (raw["productionDate"] != null) info.productionDate =
            Instant.parse(raw["productionDate"].toString().substring(1, 11) + "T08:00:00.000Z")

        MongoDBManager.collection<MaterialInfo>().insertOne(info)
    },

    TaskComponentDef(
        "extra", "getFrontStation", "获取上一工作站", "", false,
        listOf(TaskComponentParam("site", "库位ID", "string")), true
    ) { component, ctx ->
        val site = parseComponentParamValue("site", component, ctx) as String
        val station = DupuService.getFrontStationBySite(site = site)
        ctx.setRuntimeVariable(component.returnName, station)
    },

    TaskComponentDef(
        "extra", "getNextStation", "获取下一工作站", "", false,
        listOf(TaskComponentParam("site", "库位ID", "string")), true
    ) { component, ctx ->
        val site = parseComponentParamValue("site", component, ctx) as String
        val station = DupuService.getNextStationBySite(site = site)
        ctx.setRuntimeVariable(component.returnName, station)
    },

    TaskComponentDef(
        "extra", "getParkingStation", "获取停靠点", "", false,
        listOf(TaskComponentParam("site", "库位ID", "string")), true
    ) { component, ctx ->
        val site = parseComponentParamValue("site", component, ctx) as String
        val station = DupuService.getParkStationBySite(site = site)
        ctx.setRuntimeVariable(component.returnName, station)
    },

    TaskComponentDef(
        "extra", "checkExistTask", "检查重复任务", "", false, listOf(
            TaskComponentParam("def", "任务类型", "string"),
            TaskComponentParam("workStation", "工位", "string")
        ), false
    ) { component, ctx ->
        val def = parseComponentParamValue("def", component, ctx) as String
        val workStation = parseComponentParamValue("workStation", component, ctx) as String

        val tasks = MongoDBManager.collection<RobotTask>()
            .find(
                RobotTask::state eq RobotTaskState.Created,
                RobotTask::def eq def,
                RobotTask::workStations eq listOf(workStation)
            ).toList()
        if (tasks.isNotEmpty())
            throw BusinessError("有未完成任务=${tasks.first().id}，请勿重复下发！")
    },

    TaskComponentDef("extra", "checkPwd", "检查密码", "", false, listOf(
        TaskComponentParam("pwd", "密码", "string")), false) { component, ctx ->
        val pwd = parseComponentParamValue("pwd", component, ctx) as String
        if (pwd.isBlank() || CommonUtils.customConfig.adminPwd != pwd)
            throw BusinessError("操作失败，密码错误！")
    }
)

data class MaterialInfo(
    // 二维码信息
    // 扫描二维码后自动填充到相应字段
    var qrCode: String? = null, // 二维码信息
    var spareNo: String? = null,    // 零件编号
    var name: String? = null,   // 名称
    var spec: String? = null,   // 规格
    var type: String? = null,   // 型号
    var vendor: String? = null,     // 供应商
    var productionDate: Instant? = null, // 出厂日期
    // pda 手填信息
    var productNo: String?,  // 产品总成码
    var productSerial: String?,    // 总成生产流水号
    var workerNo: String?,   // 员工工号
    var onlineDate: Instant?,    // 上线日期
    var param1: String?,
    var param2: String?,
    var param3: String?,
    var createdOn: Instant = Instant.now(),
    var modifiedOn: Instant = Instant.now()
)