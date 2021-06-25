package org.opentcs.kernel

import com.google.inject.Guice
import com.google.inject.Injector
import com.google.inject.Module
import com.google.inject.util.Modules
import org.opentcs.strategies.basic.dispatching.DefaultDispatcherModule
import org.opentcs.strategies.basic.routing.DefaultRouterModule
import org.opentcs.strategies.basic.scheduling.DefaultSchedulerModule

private var injector: Injector? = null
//private val emsSntl = EmsSntl()

fun main() {
    runKernel()
}

fun runKernel() {
    // sunny 使用原来 EMS 软加密方式，兼容部分老客户加密方式
//    if (!emsSntl.checkAuthority()) exitProcess(0)
    // yy 加了这个安全管理导致安全检查更严格而报错
    //System.setSecurityManager(new SecurityManager());

    //Thread.setDefaultUncaughtExceptionHandler(UncaughtExceptionLogger(false))

    val theInjector = Guice.createInjector(customConfigurationModule())
    injector = theInjector
    theInjector.getInstance(KernelStarter::class.java).startKernel()
}

fun getInjector(): Injector? {
    return injector
}

/**
 * Builds and returns a Guice module containing the custom configuration for the kernel
 * application, including additions and overrides by the user.
 */
private fun customConfigurationModule(): Module {
    val defaultModules = listOf(
        DefaultKernelInjectionModule(),
        DefaultDispatcherModule(),
        DefaultRouterModule(),
        DefaultSchedulerModule()
        //RedisCommAdapterModule(),
        //LoopbackCommAdapterModule()
    )
    return Modules.override(defaultModules).with()
}
