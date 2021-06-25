package com.seer.srd.robottask.component

import com.seer.srd.route.getVehicleDetailsByName
import com.seer.srd.route.service.VehicleService

val vehicleComponents: List<TaskComponentDef> = listOf(
    TaskComponentDef(
        "vehicle", "getVehicleByName", "获取指定AGV", "",
        false, listOf(
        TaskComponentParam("name", "AGV名称", "string")
    ), true
    ) { component, ctx ->
        val vehicleName = parseComponentParamValue("name", component, ctx) as String
        val vehicle = VehicleService.getVehicle(vehicleName)
        ctx.setRuntimeVariable(component.returnName, vehicle)
    },
    TaskComponentDef(
        "vehicle", "getVehicleDetailsByName", "获取指定AGV的详情", "根据RBK版本返回不同信息",
        false, listOf(
            TaskComponentParam("name", "AGV名称", "string")
        ), true
    ) { component, ctx ->
        val vehicleName = parseComponentParamValue("name", component, ctx) as String
        val details = getVehicleDetailsByName(vehicleName)
        ctx.setRuntimeVariable(component.returnName, details)
    }
)
