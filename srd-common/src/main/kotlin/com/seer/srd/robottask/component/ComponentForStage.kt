package com.seer.srd.robottask.component

import com.fasterxml.jackson.module.kotlin.jacksonTypeRef
import com.seer.srd.domain.Property
import com.seer.srd.robottask.RobotStage
import com.seer.srd.robottask.RobotTransport
import com.seer.srd.util.mapper

val stageComponents: List<TaskComponentDef> = listOf(
    TaskComponentDef(
        "stage", "SetLocationName", "设置阶段的工作站", "",
        false, listOf(
            TaskComponentParam("destination", "阶段", "string"),
            TaskComponentParam("value", "工作站", "string")
        ), false
    ) { component, ctx ->
        val destination = parseComponentParamValue("destination", component, ctx) as RobotStage
        val value = parseComponentParamValue("value", component, ctx) as String?
        if (value.isNullOrBlank()) throw IllegalArgumentException("工作站不能为空")
        destination.location = value
    },
    TaskComponentDef(
        "stage", "SetTransportMultiStageLocationName", "设置同一运单多个阶段的工作站", "",
        false, listOf(
            TaskComponentParam("transport", "运单", "string"),
            TaskComponentParam("fromIndex", "起始索引", "int"),
            TaskComponentParam("toIndex", "结束索引", "int"),
            TaskComponentParam("value", "工作站", "string")
        ), false
    ) { component, ctx ->
        val transport = parseComponentParamValue("transport", component, ctx) as RobotTransport
        val fromIndex = parseComponentParamValue("fromIndex", component, ctx) as Int
        val toIndex = parseComponentParamValue("toIndex", component, ctx) as Int
        val value = parseComponentParamValue("value", component, ctx) as String
        for (i in fromIndex..toIndex) transport.stages[i].location = value
    },
    TaskComponentDef(
        "stage", "SetLocationArea", "设置阶段的工作区域", "",
        false, listOf(
            TaskComponentParam("destination", "阶段", "string"),
            TaskComponentParam("value", "工作区域", "string")
        ), false
    ) { component, ctx ->
        val destination = parseComponentParamValue("destination", component, ctx) as RobotStage
        val value = parseComponentParamValue("value", component, ctx) as String
        destination.area = value
    },
    TaskComponentDef(
        "stage", "SetDestinationProperties", "设置阶段的属性字符串", "",
        false, listOf(
            TaskComponentParam("destination", "阶段", "string"),
            TaskComponentParam("propertiesStr", "属性字符串", "string")
        ), false
    ) { component, ctx ->
        val destination = parseComponentParamValue("destination", component, ctx) as RobotStage
        val propertiesStr = parseComponentParamValue("propertiesStr", component, ctx) as String
        destination.properties = propertiesStr
    },
    TaskComponentDef(
        "stage", "ReplaceDestinationProperty", "设置阶段的属性键值", "",
        false, listOf(
            TaskComponentParam("destination", "阶段", "string"),
            TaskComponentParam("key", "属性键", "string"),
            TaskComponentParam("value", "属性值", "string")
        ), false
    ) { component, ctx ->
        val destination = parseComponentParamValue("destination", component, ctx) as RobotStage
        @Suppress("UNCHECKED_CAST") val properties: List<Property> =
            if (destination.properties.isBlank()) {
                ArrayList()
            } else {
                mapper.readValue(destination.properties, jacksonTypeRef<List<Property>>())
            }
        val key = parseComponentParamValue("key", component, ctx) as String
        val value = parseComponentParamValue("value", component, ctx).toString()
        val newProperties = properties.filter { it.key != key }.toMutableList() // 删除掉原来的同名键
        newProperties.add(Property(key, value))
        destination.properties = mapper.writeValueAsString(newProperties)
    }
    //TaskComponentDef(
    //    "stage", "ScanCodeToLocationName", "获取扫码得到的工作站", "",
    //    false, false, listOf(
    //        TaskComponentParam("destination", "阶段", "string")
    //    )
    //) { component, ctx ->
    //    val destination = parseComponentParamValue("destination", component, ctx): RobotStage
    //    val code = takeDestinationCode()
    //    destination.location = code
    //}
)