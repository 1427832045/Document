package com.seer.srd.jinfeng


class TaskType(
    var taskType: String = "",
    var stationList: List<Station> = listOf()
)

class Station(
    var name: String = "",//工作站名字
    var properties: List<StationProperty> = listOf()
)

class StationProperty(
    var key: String = "",
    var value: String = ""
)
