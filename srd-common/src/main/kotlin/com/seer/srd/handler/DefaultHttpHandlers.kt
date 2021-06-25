package com.seer.srd.handler

import com.seer.srd.eventlog.StoreSiteChangeLogger.handleListStoreSiteChanges
import com.seer.srd.eventlog.VehicleStateLogger.handleListVehicleStateTrace
import com.seer.srd.handler.FileHandler.handleCustomTaskDir
import com.seer.srd.handler.FileHandler.handleCustomTaskFiles
import com.seer.srd.handler.FileHandler.handleDownloadCustomTaskFiles
import com.seer.srd.handler.FileHandler.handleDownloadSrdLogFiles
import com.seer.srd.handler.FileHandler.handleSrdLogDir
import com.seer.srd.handler.FileHandler.handleSrdLogFiles
import com.seer.srd.handler.device.registerDeviceHttpHandlers
import com.seer.srd.handler.route.driver.registerVehicleDriverHttpHandlers
import com.seer.srd.handler.route.handleGetVehicleDetails
import com.seer.srd.handler.route.registerRouteHttpHandlers
import com.seer.srd.http.Handlers
import com.seer.srd.http.HttpServer.auth
import com.seer.srd.http.HttpServer.noAuth
import com.seer.srd.http.HttpServer.permit
import com.seer.srd.http.ReqMeta
import com.seer.srd.robottask.registerTaskRoutes
import com.seer.srd.user.CommonPermissionSet.*

fun registerDefaultHttpHandlers() {
    val root = Handlers("")
    
    root.post("upload-tmp", FileHandler::handleUploadTmp, permit(VehicleUpgrades.name))
    root.get("file/upload-tmp/:file", FileHandler::handleDownloadTmp, noAuth())

    root.get("cfg-file", CfgHandler::handleGetCfgFile, noAuth())
    root.get("cfg-file/files", CfgHandler::listCfgHistoryFiles, noAuth())
    root.get("cfg-file/files/:name", CfgHandler::handleGetCfgByName, noAuth())
    root.get("cfg-file/by-name", CfgHandler::handleDownloadFileByName, noAuth())
    root.post("cfg-file/upload", CfgHandler::handleChangeCfgFile, permit(CfgFile.name))

    val c = Handlers("c")
    c.get("ping", ::handlePing, auth())
    c.post("sign-in", ::handleSignIn, noAuth())
    c.post("sign-out", ::handleSignOut, auth())
    c.get("permission-defs", ::handleListPermissionDefs, noAuth())

    c.get("log-file/dirs", ::handleSrdLogDir, noAuth())
    c.get("log-file/files", ::handleSrdLogFiles, noAuth())
    c.post("log-file/download-file", ::handleDownloadSrdLogFiles, noAuth())

    c.get("custom-task-file/dirs", ::handleCustomTaskDir, noAuth())
    c.get("custom-task-file/files", ::handleCustomTaskFiles, noAuth())
    c.get("custom-task-file/download-file", ::handleDownloadCustomTaskFiles, noAuth())

    c.get("user", ::handleListUser)
    c.get("user/:id", ::handleGetUserForEdit)
    c.post("user", ::handleCreateUser, permit(CreateUser.name))
    c.put("user/:id", ::handleUpdateUser, permit(ModifyUser.name))
    
    c.get("user-role", ::handleListRoles)
    c.get("user-role/_all", ::handleGetAllRoles)
    c.get("user-role/:id", ::handleFindRole)
//    c.post("user-role", ::handleCreateRole, permit(WriteUserRole.name))
    c.post("user-role", ::handleCreateRole, permit(CreateUserRole.name))
    c.put("user-role/:id", ::handleUpdateRole, permit(WriteUserRole.name))
    
    c.get("api-list", ::handleListApi, permit(ListApi.name))
    
    root.get("ui/config", ::handleGetUiConfig, noAuth())
    
    root.get("system/versions", SystemHandler::handleGetSystemVersions, noAuth())
    root.get("system/status", SystemHandler::handleGetSystemStatus, noAuth())
    root.post("system/status", SystemHandler::handleSetSystemStatus)
    
    root.post("system/recover-all", SystemHandler::handleRecoverAll, ReqMeta(test = true))
    
    // val upload = UploadHandler(app.config)
    // app.httpServer.addRequestHandlers(RequestHandler(HandlerType.POST, "upload", upload::upload, auth = true))
    
    root.get("event-log/system-event", ::handleListSystemEvents, noAuth())
    root.get("event-log/modbus-read", ::handleListModbusReadLogs, noAuth())
    root.get("event-log/modbus-write", ::handleListModbusWriteLogs, noAuth())
    root.post("event-log/user-operation", ::handleRecordUserOperation)
    root.get("event-log/user-operation", ::handleListUserOperations, noAuth())
    root.get("event-log/vehicles", ::handleListVehicleStateTrace, noAuth())
    root.get("event-log/store-sites", ::handleListStoreSiteChanges, noAuth())
    
    root.get("stats/config", ::handleGetStatsConfig, noAuth())
    root.get("stats", ::handleListStatRecords, noAuth())
    root.post("stats/data-excel", ::handleStatsToExcel, permit(Stats.name))
    root.post("stats/force", ::handleForceStat, permit(Stats.name))
    
    root.get("ping-route", RouteHandler::pingRoute, noAuth())
    
    val v = Handlers("vehicles")
    v.get("", VehicleHandler::handleListVehicles, noAuth())
    v.post("pause/:flag", VehicleHandler::handlePauseAll, permit(WriteVehicle.name))
    v.post("enable/:name", VehicleHandler::handleEnableAdapter, permit(WriteVehicle.name))
    v.post("disable/:name", VehicleHandler::handleDisableAdapter, permit(WriteVehicle.name))
    v.post("acquire/:name", VehicleHandler::handleAcquireVehicle, permit(WriteVehicle.name))
    v.post("disown/:name", VehicleHandler::handleDisownVehicle, permit(WriteVehicle.name))
    v.post("clear-errors/:name", VehicleHandler::handleClearVehicleErrors, permit(WriteVehicle.name))
    v.post("enable-many", VehicleHandler::handleEnableManyAdapters, permit(WriteVehicle.name))
    v.post("disable-many", VehicleHandler::handleDisableManyAdapters, permit(WriteVehicle.name))
    v.post("acquire-many", VehicleHandler::handleAcquireMany, permit(WriteVehicle.name))
    v.post("disown-many", VehicleHandler::handleDisownMany, permit(WriteVehicle.name))
    v.post("clear-many-errors", VehicleHandler::handleClearManyErrors, permit(WriteVehicle.name))

    v.post("integrationLevel/:level", VehicleHandler::handleChangeAllIntegrationLevel, permit(WriteVehicle.name))
    v.post("change-integration-level", VehicleHandler::handleChangeIntegrationLevel, permit(WriteVehicle.name))
    v.post("withdrawal", VehicleHandler::handleWithdrawal, permit(WriteVehicle.name))
    v.post("change-processable-categories", VehicleHandler::handleChangeCategories, permit(WriteVehicle.name))
    v.post("set-properties", VehicleHandler::handleSetVehicleProperties, permit(WriteVehicle.name))
    
    root.get("vehicles-details", VehicleHandler::handleListVehiclesDetails, noAuth())
    root.get("vehicles-details/:vehicle", ::handleGetVehicleDetails, noAuth())
    
    root.get("vehicles-channels", VehicleHandler::handleListVehicleChannels, noAuth())
    
    val vu = Handlers("vehicles-upgrade")
    vu.get("latest-task", VehiclesUpgradeHandler::handleGetLatestTask, noAuth())
    vu.post("start", VehiclesUpgradeHandler::handleStartNewTask, noAuth())
    vu.post("abort", VehiclesUpgradeHandler::handleAbortTask, noAuth())
    
    root.get("plant-model", PlantModelHandler::handleGetModel, noAuth())
    root.get("plant-model/records", PlantModelHandler::handleListPlantModelRecord)
    root.get("plant-model/records/:no", PlantModelHandler::handleGetPlantModelByVersion)
    root.get("plant-model/by-no", PlantModelHandler::downloadModelFileByNo)
    root.post("plant-model", PlantModelHandler::handleChangeModel, permit(WritePlant.name))
    root.post("plant-model/activate/:no", PlantModelHandler::handleActivatePlantModelByNo, permit(WritePlant.name))
    root.post("plant-model/remove/:no", PlantModelHandler::handleRemovePlantModelByNo, permit(WritePlant.name))
    root.post("plant-model/paths/:name/lock", PlantModelHandler::handleLockPath, permit(WritePlant.name))
    root.get("plant-model/locked-paths", PlantModelHandler::handleListLockedPaths, noAuth())
    
    root.get("route-transport-order", RouteHandler::handleListRouteOrders, noAuth())
    
    root.get("route-transport-sequence", RouteHandler::handleListRouteSequences, noAuth())
    root.post("route-transport-order/withdraw", RouteHandler::handleWithdrawRouteOrders)
    
    root.get("store-site/_all", ::handleListStoreSite, noAuth())
    root.post("store-site/batch", ::handleUpdateInBatch, permit(WriteStoreSite.name))
    root.get("store-site/config", ::handleGetStoreSiteConfig, noAuth())
    root.post("store-site/config", ::handleResetStoreSiteConfig, permit(WriteStoreSite.name))
    
    root.get("robot-task", ::handleListRobotTasks, noAuth())
    root.get("robot-task/_mine", ::handleListMyRobotTask, noAuth())
    root.delete("robot-task/:id", ::handleRemoveRobotTask)
    root.post("robot-task/:id/abort", ::handleAbortRobotTask)
    root.post("robot-task/:id/release", ::handleReleaseOwnedSites)
    root.post("robot-task/:id/priority", ::handleAdjustRobotTaskPriority, noAuth())
    root.post("robot-task/data-excel", ::handleTasksToExcel, noAuth())
    root.delete("robot-task-batch/_finished", ::handleRemoveAllFinishedRobotTasks, ReqMeta(test = true))
    
    root.get("robot-task-def", ::handleListRobotTaskDefs, noAuth())
    root.get("robot-task-def/:name", ::handleGetRobotTaskDef, noAuth())
    root.put("robot-task-def/:name", ::handleUpdateRobotTaskDef)
    root.post("robot-task-def/_import", ::handleImportRobotTaskDefs)
    root.delete("robot-task-def/:name", ::handleRemoveRobotTaskDef)
    root.get("robot-task-component-defs", ::handleListComponentsDefs)
    
    root.get("manual-tasks/_all", ::handleGetManualTasks, noAuth())
    root.post("manual-tasks", ::handleCreateManualTask, noAuth())
    root.put("manual-tasks/:id", ::handleUpdateManualTask, noAuth())
    root.delete("manual-tasks/:id", ::handleRemoveManualTask, noAuth())
    root.post("manual-tasks/exec/:id", ::handleExecuteManualTask, noAuth())
    root.post("manual-tasks/exec-temp", ::handleExecuteTempManualTask, noAuth())

    root.get("/l2-operator/config", OperatorHandler::handleOperatorConfig, noAuth())
    root.get("/l2-operator/input-details/:name", OperatorHandler::handleOperatorInputDetails, noAuth())
    root.get("/l2-operator/options-source/:name", OperatorHandler::handleOperatorOptionsSource, noAuth())

    root.get("/dashboard/task", ::handleGetRobotTaskDashboardData, noAuth())
    root.get("/dashboard/configs", ::handleGetDashboardConfigs, noAuth())
    root.post("/dashboard/configs", ::handleSetDashboardConfigs)

    root.get("/custom-data", CustomDataHandler::handleListCustomData, noAuth())

    val mail = Handlers("mail")
    mail.post("", ::handleSendMail, noAuth())
    
    registerRouteHttpHandlers()
    registerVehicleDriverHttpHandlers()
    registerDeviceHttpHandlers()

    registerTaskRoutes()
}