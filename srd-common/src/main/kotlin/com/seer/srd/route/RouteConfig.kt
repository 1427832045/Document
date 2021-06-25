package com.seer.srd.route

import com.seer.srd.util.loadConfig

val routeConfig = loadConfig("srd-config.yaml", RouteConfig::class) ?: RouteConfig()

class RouteConfig {
    
    var kernelApp: KernelApplicationConfiguration = KernelApplicationConfiguration()
    var synchronizer: DeviceSynchronizerConfiguration = DeviceSynchronizerConfiguration()
    var shortestPath: ShortestPathConfiguration = ShortestPathConfiguration()
    
    var routeToCurrentPosition: Boolean = false
    
    var recoverEnabled: Boolean = false
    
    var recoveryEvaluatorThreshold: Double = 0.7
    
    var dispatcher: DispatcherConfiguration = DispatcherConfiguration()
    
    var commAdapterIO = VehicleCommAdapterIOType.Redis
    // new protocol
    var newCommAdapter = false
    var reportConfig = ReportConfig()
    var srdNickName = "srd"
    var checkWithOwnerIp = true
    
    var commandQueueCapacity = 2
    var rechargeOperation = "CHARGE"
    var sentQueueCapacity = 2
    var vehicleAdapterTcpServerPort = 15400
    
    var redis: RedisConfiguration = RedisConfiguration()
    
    var vehicleSimulation = VehicleSimulation.None
    var simulationTimeFactor: Double = 1.0
    
    // 当机器人在线路外恢复故障时，仍强制重发命令。
    var forceResend = true

    // 是否抢占当前无人占用的空闲机器人
    var autoOwnRobot = true

    // 是否用 websocket 向外汇报运单状态
    var reportTransportOrderChanged = false

    // 旧版 vehicle adapter 的两个超时参数
    var rbkStatusTimeout: Long = 15
    var rbkAckTimeout: Long = 10

    // 为 true 时，向 rbk 发送整条命令队列
    var sendCommandArrayToRbk = true

    // 是否使用KM算法分配任务
    var useKMAssign = false

    // TCP 仿真机器人是否 mock VehicleDetails。
    var mockVehicleDetails = false

    // 当机器人阻挡时，重新规划路径
    var rerouteWhenBlock = false

    // 延迟判断机器人阻挡的时间 ms
    var vehicleBlockDelay: Long = 0

    // 判断机器人停下的时间延迟 ms
    var robotStoppingDelayMs: Long = 2000

    // 是否启用动态路线权值
    var useDynamicTopologyCost: Boolean = false
    // 重算动态权值的周期,单位 s
    var recalculateTopologyCostPeriodS: Long = 10
    // 报错机器人对周围路径的权值影响, mm
    var errorVehicleCostFactor: Long = 1000000
    // 非报错机器人对周围路径的权值影响, mm/s
    var vehicleCostFactorPs: Long = 1000
    // 判断机器人运行时间过久的时间阈值, ms
    var vehicleExecutingTooLongMs: Long = 20000

    override fun toString(): String {
        return "RouteConfig(kernelApp=$kernelApp, synchronizer=$synchronizer, shortestPath=$shortestPath, routeToCurrentPosition=$routeToCurrentPosition, recoverEnabled=$recoverEnabled, recoveryEvaluatorThreshold=$recoveryEvaluatorThreshold, dispatcher=$dispatcher, commAdapterIO=$commAdapterIO, newCommAdapter=$newCommAdapter, reportConfig=$reportConfig, srdNickName='$srdNickName', checkWithOwnerIp=$checkWithOwnerIp, commandQueueCapacity=$commandQueueCapacity, rechargeOperation='$rechargeOperation', sentQueueCapacity=$sentQueueCapacity, vehicleAdapterTcpServerPort=$vehicleAdapterTcpServerPort, redis=$redis, vehicleSimulation=$vehicleSimulation, simulationTimeFactor=$simulationTimeFactor, forceResend=$forceResend, autoOwnRobot=$autoOwnRobot, reportTransportOrderChanged=$reportTransportOrderChanged, rbkStatusTimeout=$rbkStatusTimeout, rbkAckTimeout=$rbkAckTimeout, sendCommandArrayToRbk=$sendCommandArrayToRbk, useKMAssign=$useKMAssign, mockVehicleDetails=$mockVehicleDetails, rerouteWhenBlock=$rerouteWhenBlock, vehicleBlockDelay=$vehicleBlockDelay, robotStoppingDelayMs=$robotStoppingDelayMs, useDynamicTopologyCost=$useDynamicTopologyCost, recalculateTopologyCostPeriodS=$recalculateTopologyCostPeriodS, errorVehicleCostFactor=$errorVehicleCostFactor, vehicleCostFactorPs=$vehicleCostFactorPs, vehicleExecutingTooLongMs=$vehicleExecutingTooLongMs)"
    }
}

class KernelApplicationConfiguration(
    var saveModelOnTerminateModelling: Boolean = false,
    var saveModelOnTerminateOperating: Boolean = false,
    var updateRoutingTopologyOnPathLockChange: Boolean = true
) {
    override fun toString(): String {
        return "KernelApplicationConfiguration(saveModelOnTerminateModelling=$saveModelOnTerminateModelling, saveModelOnTerminateOperating=$saveModelOnTerminateOperating, updateRoutingTopologyOnPathLockChange=$updateRoutingTopologyOnPathLockChange)"
    }
}

class DeviceSynchronizerConfiguration(
    var doorRoute: String = "http://localhost:52000/devices/api/v1/",
    var liftRoute: String = "http://localhost:52000/devices/api/v1/",
    var mutexZoneRoute: String = "http://localhost:52000/devices/api/v1/",
    var locationRoute: String = "http://localhost:52000/devices/api/v1/"
) {
    override fun toString(): String {
        return "DeviceSynchronizerConfiguration(doorRoute='$doorRoute', liftRoute='$liftRoute', mutexZoneRoute='$mutexZoneRoute', locationRoute='$locationRoute')"
    }
}

class ShortestPathConfiguration(
    var algorithm: Algorithm = Algorithm.DIJKSTRA,
    var edgeEvaluators: List<EvaluatorType> = listOf(EvaluatorType.DISTANCE, EvaluatorType.EXPLICIT_PROPERTIES)
) {
    override fun toString(): String {
        return "ShortestPathConfiguration(algorithm=$algorithm, edgeEvaluators=$edgeEvaluators)"
    }
}

class ReportConfig(
    var interval: Int = 1000,
    var excludedFields: List<String> = emptyList(),
    var includedFields: List<String> = emptyList()
) {
    override fun toString(): String {
        return "ReportConfig(interval=$interval, excludedFields=$excludedFields, includedFields=$includedFields)"
    }
}

enum class Algorithm(val isHandlingNegativeCosts: Boolean) {
    DIJKSTRA(false), BELLMAN_FORD(true), FLOYD_WARSHALL(false);
}

enum class EvaluatorType {
    DISTANCE, TRAVELTIME, HOPS, EXPLICIT, EXPLICIT_PROPERTIES
}

class DispatcherConfiguration(
    var dismissUnroutableTransportOrders: Boolean = true,
    var assignRedundantOrders: Boolean = false,
    var rerouteTrigger: RerouteTrigger = RerouteTrigger.TOPOLOGY_CHANGE,
    var reroutingImpossibleStrategy: ReroutingImpossibleStrategy = ReroutingImpossibleStrategy.PAUSE_IMMEDIATELY,
    var dispatchForSubprojects: Boolean = true,
    var parkIdleVehicles: Boolean = true,
    var considerParkingPositionPriorities: Boolean = false,
    var reparkVehiclesToHigherPriorityPositions: Boolean = false,
    var rechargeIdleVehicles: Boolean = true,
    var twoStageRecharge: Boolean = false,  // 充电运单里，先发到充电站前置点，再到充电站。
    var keepRechargingUntilFullyCharged: Boolean = false,
    var parkVehicleWhenFullyCharged: Boolean = true,
    var idleVehicleRedispatchingInterval: Long = 5000,
    var orderPriorities: List<String> = listOf("BY_DEADLINE"),
    var orderCandidatePriorities: List<String> = listOf("BY_INITIAL_ROUTING_COSTS", "BY_DEADLINE"),
    var vehiclePriorities: List<String> = listOf("BY_ENERGY_LEVEL", "IDLE_FIRST"),
    var vehicleCandidatePriorities: List<String> = listOf("BY_INITIAL_ROUTING_COSTS", "BY_ENERGY_LEVEL"),
    var deadlineAtRiskPeriod: Long = 60000,
    var parkAndRechargeDelayMs: Long = -1000,
    var fastWithdrawal: Boolean = false
) {
    override fun toString(): String {
        return "DispatcherConfiguration(dismissUnroutableTransportOrders=$dismissUnroutableTransportOrders, assignRedundantOrders=$assignRedundantOrders, rerouteTrigger=$rerouteTrigger, reroutingImpossibleStrategy=$reroutingImpossibleStrategy, dispatchForSubprojects=$dispatchForSubprojects, parkIdleVehicles=$parkIdleVehicles, considerParkingPositionPriorities=$considerParkingPositionPriorities, reparkVehiclesToHigherPriorityPositions=$reparkVehiclesToHigherPriorityPositions, rechargeIdleVehicles=$rechargeIdleVehicles, twoStageRecharge=$twoStageRecharge, keepRechargingUntilFullyCharged=$keepRechargingUntilFullyCharged, parkVehicleWhenFullyCharged=$parkVehicleWhenFullyCharged, idleVehicleRedispatchingInterval=$idleVehicleRedispatchingInterval, orderPriorities=$orderPriorities, orderCandidatePriorities=$orderCandidatePriorities, vehiclePriorities=$vehiclePriorities, vehicleCandidatePriorities=$vehicleCandidatePriorities, deadlineAtRiskPeriod=$deadlineAtRiskPeriod, parkAndRechargeDelayMs=$parkAndRechargeDelayMs, fastWithdrawal=$fastWithdrawal)"
    }
}

enum class RerouteTrigger {
    NONE, DRIVE_ORDER_FINISHED, TOPOLOGY_CHANGE
}

enum class ReroutingImpossibleStrategy {
    IGNORE_PATH_LOCKS, PAUSE_IMMEDIATELY, PAUSE_AT_PATH_LOCK
}

class RedisConfiguration(
    var maxTotal: Int = 1000,
    var maxIdle: Int = 1000,
    var testOnBorrow: Boolean = true,
    var blockWhenExhausted: Boolean = true,
    var maxWaitMillis: Long = 5000,
    var ip: String = "localhost",
    var port: Int = 6379
) {
    override fun toString(): String {
        return "RedisConfiguration(maxTotal=$maxTotal, maxIdle=$maxIdle, testOnBorrow=$testOnBorrow, blockWhenExhausted=$blockWhenExhausted, maxWaitMillis=$maxWaitMillis, ip='$ip', port=$port)"
    }
}

enum class VehicleCommAdapterIOType {
    None, Redis, Http, Tcp, AioTcp
}

enum class VehicleSimulation {
    None, Http, Tcp
}