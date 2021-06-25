package com.seer.srd

import com.seer.srd.db.MongoDBManager.disposeMongoDB
import com.seer.srd.db.MongoDBManager.initMongoDB
import com.seer.srd.device.DoorService
import com.seer.srd.device.lift.LiftService
import com.seer.srd.device.ZoneService
import com.seer.srd.device.charger.ChargerService
import com.seer.srd.device.pager.PagerService
import com.seer.srd.eventlog.EventLogLevel
import com.seer.srd.eventlog.SystemEvent
import com.seer.srd.eventlog.recordSystemEventLog
import com.seer.srd.handler.registerDefaultHttpHandlers
import com.seer.srd.http.HttpServer.startHttpServer
import com.seer.srd.robottask.RobotTaskService.startNotFinishedRobotTasks
import com.seer.srd.robottask.component.registerRobotTaskComponents
import com.seer.srd.robottask.loadRobotTaskDefs
import com.seer.srd.route.deadlock.detectAndSolveDeadlock
import com.seer.srd.route.deadlock.searchalgorithms.registerSearchAlgorithms4DeadlockSolving
import com.seer.srd.route.service.OrderSequenceService
import com.seer.srd.route.service.TransportOrderService
import com.seer.srd.scheduler.*
import com.seer.srd.stats.initStats
import com.seer.srd.storesite.StoreSiteService.loadStoreSites
import com.seer.srd.user.CommonPermissionSet
import com.seer.srd.user.UserManager.prepareDefaultAdmin
import com.seer.srd.user.addPermissionsDefs
import com.seer.srd.util.removeExpiredCollectionsAndFiles
import org.opentcs.kernel.runKernel
import java.io.File
import java.util.concurrent.TimeUnit

object Application {

    init {
        I18N.loadDict()
//        RbkError.loadRbkAlarmInfo()
//        ACL.log()
        addPermissionsDefs(CommonPermissionSet.values().map { it.name })
        registerRobotTaskComponents()//注册任务组件
        registerOnExit()
    }

    fun initialize() {
        setVersion("srd", "3.0.104")

        initMongoDB()//初始化数据库
        prepareDefaultAdmin()

        loadRobotTaskDefs() // 先加载机器人任务的定义再注册处理器

        registerDefaultHttpHandlers()

        //读取最后上传的目录
        val uploadDir = File(CONFIG.getFinalUploadDir())
        if (!uploadDir.exists()) uploadDir.mkdirs()

        //是否检测死锁
        if (CONFIG.useDeadlock) {
            registerSearchAlgorithms4DeadlockSolving()
        }
    }

    fun start() {
        startHttpServer(CONFIG.httpPort)

        //决定重启之后要不要执行未完成的任务
        if (!CONFIG.startFromDB) {
            //不执行
            TransportOrderService.failUnfinishedOrders()
            OrderSequenceService.finishUnfinishedSequences()
        }
        //运行加密
        runKernel()

        //电梯中控等部件的初始化
        LiftService.init()//电梯
        DoorService.init()//门
        ZoneService.init()//交通管制区
        ChargerService.init()//充电
        PagerService.init()

        //这个是在干啥
        loadStoreSites()
        //执行未完成的任务
        startNotFinishedRobotTasks()

        //时间相关的，可能是设置全局时钟什么的
        GlobalTimer.startTimers()
        startQuartz()

        initStats()

        //是否检测死锁
        if (CONFIG.useDeadlock) {
            detectAndSolveDeadlock()
        }

        //周期性清理已经过期的任务
        GlobalTimer.executor.scheduleAtFixedRate(::removeExpiredCollectionsAndFiles, 10, 30, TimeUnit.SECONDS)

        //记录系统时间日志
        recordSystemEventLog("System", EventLogLevel.Info, SystemEvent.AppBoot, "")
    }

    /*
    用于关闭一些连接和服务
    ShutdownHook只是一个已初始化但没有启动的线程。
    虚拟机开始启用其关闭序列时，它会以某种未指定的顺序启动所有已注册的关闭挂钩，并让它们同时运行。
    运行完所有的挂钩后，如果已启用退出终结，那么虚拟机接着会运行所有未调用的终结方法。最后，虚拟机会暂停
    这些 shutdown hook 都是些线程对象，因此，你的清理工作要写在 run() 里。
    根据 Java API，你的清理工作不能太重了，
    要尽快结束。但仍然可以对数据库进行操作。
    应将关闭挂钩编写为线程安全的，并尽可能地避免死锁。关闭挂钩还应该不盲目地依靠某些服务，这些服务可能已注册了自己的关闭挂钩，所以其本身可能正处于关闭进程中。

     */
    private fun registerOnExit() {
        Runtime.getRuntime().addShutdownHook(Thread {
            GlobalTimer.stopTimers()
            stopExecutors()
            endQuartz()

            LiftService.dispose()
            DoorService.dispose()
            ZoneService.dispose()
            ChargerService.dispose()

            disposeMongoDB()
        })
    }
}

