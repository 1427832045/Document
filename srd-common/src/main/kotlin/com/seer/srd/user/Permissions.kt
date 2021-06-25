package com.seer.srd.user

import java.util.concurrent.CopyOnWriteArrayList

enum class CommonPermissionSet {
    VehicleUpgrades,
    ListUser, CreateUser, ModifyUser,
    ReadUserRole, CreateUserRole, WriteUserRole,
//    ListScripts, ExecScripts,
    ListApi,
    ReadStoreSite, WriteStoreSite,
    ReadRobotTask, DeleteRobotTask, AbortRobotTask,
    ReadVehicle, WriteVehicle,
    ReadPlant, WritePlant,
    ReadRouteOrder,ReadRouteSequence,
    ManualTask,
    EventLog,
    Stats,
    StatsTable,
    StatsChart,
    DeviceDoor, DeviceLift, DeviceZone, DeviceCharger,
    CfgFile
}

val permissions: MutableList<String> = CopyOnWriteArrayList()

fun addPermissionsDefs(extra: List<String>) {
    permissions.addAll(extra)
}
