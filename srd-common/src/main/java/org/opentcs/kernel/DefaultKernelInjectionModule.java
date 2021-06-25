package org.opentcs.kernel;

import com.google.inject.multibindings.MapBinder;
import com.google.inject.multibindings.Multibinder;
import com.seer.srd.route.RedisConfiguration;
import com.seer.srd.route.RouteConfigKt;
import org.opentcs.access.Kernel;
import org.opentcs.access.LocalKernel;
import org.opentcs.components.kernel.services.DispatcherService;
import org.opentcs.components.kernel.services.RouterService;
import org.opentcs.components.kernel.services.SchedulerService;
import org.opentcs.customizations.kernel.KernelInjectionModule;
import org.opentcs.drivers.vehicle.synchronizer.*;
import org.opentcs.kernel.services.StandardDispatcherService;
import org.opentcs.kernel.services.StandardRouterService;
import org.opentcs.kernel.services.StandardSchedulerService;
import org.opentcs.kernel.vehicles.synchronizer.*;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import javax.inject.Singleton;

/**
 * A Guice module for the openTCS kernel application.
 */
public class DefaultKernelInjectionModule extends KernelInjectionModule {

    @Override
    protected void configure() {
        bind(StandardKernel.class).in(Singleton.class);
        bind(LocalKernel.class).to(StandardKernel.class);

        configureKernelStatesDependencies();
        configureKernelServicesDependencies();
        configureDeviceSynchronizer();
        configureRedisPool();
        // Ensure all of these binders are initialized.
        extensionsBinderAllModes();
        extensionsBinderModelling();
        extensionsBinderOperating();
    }

    private void configureKernelServicesDependencies() {
        bind(StandardRouterService.class).in(Singleton.class);
        bind(RouterService.class).to(StandardRouterService.class);

        bind(StandardDispatcherService.class).in(Singleton.class);
        bind(DispatcherService.class).to(StandardDispatcherService.class);

        bind(StandardSchedulerService.class).in(Singleton.class);
        bind(SchedulerService.class).to(StandardSchedulerService.class);
    }

    private void configureKernelStatesDependencies() {
        // A map for KernelState instances to be provided at runtime.
        MapBinder<Kernel.State, KernelState> stateMapBinder
                = MapBinder.newMapBinder(binder(), Kernel.State.class, KernelState.class);
        stateMapBinder.addBinding(Kernel.State.SHUTDOWN).to(KernelStateShutdown.class);
        stateMapBinder.addBinding(Kernel.State.MODELLING).to(KernelStateModelling.class);
        stateMapBinder.addBinding(Kernel.State.OPERATING).to(KernelStateOperating.class);
    }

    private void configureDeviceSynchronizer() {
        bind(RequestCloseDoor.class).in(Singleton.class);
        bind(RequestOccupyLift.class).in(Singleton.class);
        bind(RequestUnoccupyLift.class).in(Singleton.class);
        bind(RequestOpenDoor.class).in(Singleton.class);
        bind(RequestCallLift.class).in(Singleton.class);
        bind(RequestGoLift.class).in(Singleton.class);
        bind(QueryDoorOpen.class).in(Singleton.class);
        bind(QueryLiftArriveAndOpen.class).in(Singleton.class);
        bind(RequestBeforeAtLocation.class).in(Singleton.class);
        bind(RequestAtSendAtLocation.class).in(Singleton.class);
        bind(RequestAfterExecutedAtLocation.class).in(Singleton.class);
        bind(QueryBeforeAtLocation.class).in(Singleton.class);
        bind(QueryAtExecutedAtLocation.class).in(Singleton.class);
        bind(RequestEnterMutexZone.class).in(Singleton.class);
        bind(RequestLeaveMutexZone.class).in(Singleton.class);
        bind(QueryMutexZone.class).in(Singleton.class);

        Multibinder<RequestAfterExecuted> reqsAfterExecuted
                = Multibinder.newSetBinder(binder(), RequestAfterExecuted.class);
        reqsAfterExecuted.addBinding().to(RequestCloseDoor.class); // key:"passDoor"
        reqsAfterExecuted.addBinding().to(RequestOccupyLift.class); // key:"occupyLift"
        reqsAfterExecuted.addBinding().to(RequestUnoccupyLift.class); // key:"unoccupyLift"
        reqsAfterExecuted.addBinding().to(RequestLeaveMutexZone.class); // key:"leaveMutexZone"
        reqsAfterExecuted.addBinding().to(RequestAfterExecutedAtLocation.class);// key:"requestAfterExecuted"

        Multibinder<RequestAtSend> reqsAtSend
                = Multibinder.newSetBinder(binder(), RequestAtSend.class);
        reqsAtSend.addBinding().to(RequestAtSendAtLocation.class);

        Multibinder<RequestWhile> reqsWhile
                = Multibinder.newSetBinder(binder(), RequestWhile.class);
        reqsWhile.addBinding().to(RequestOpenDoor.class); // key:"openDoor","passDoor"
        reqsWhile.addBinding().to(RequestCallLift.class); // key:"callLift","occupyLift"
        reqsWhile.addBinding().to(RequestGoLift.class); // key:"goLift","unoccupyLift"

        Multibinder<RequestBefore> reqsBefore
                = Multibinder.newSetBinder(binder(), RequestBefore.class);
        reqsBefore.addBinding().to(RequestOpenDoor.class); // key:"openDoor","passDoor"
        reqsBefore.addBinding().to(RequestCallLift.class); // key:"callLift","occupyLift"
        reqsBefore.addBinding().to(RequestGoLift.class); // key:"goLift","unoccupyLift"
        reqsBefore.addBinding().to(RequestEnterMutexZone.class); // key:"enterMutexZone"
        reqsBefore.addBinding().to(RequestBeforeAtLocation.class); // key:"requestBefore"

        Multibinder<QueryBefore> querysBefore
                = Multibinder.newSetBinder(binder(), QueryBefore.class);
        querysBefore.addBinding().to(QueryDoorOpen.class); // key:"passDoor"
        querysBefore.addBinding().to(QueryLiftArriveAndOpen.class); // key:"occupyLift", "unoccupyLift"
        querysBefore.addBinding().to(QueryMutexZone.class); // key:"enterMutexZone"
        querysBefore.addBinding().to(QueryBeforeAtLocation.class); // key:"queryBefore"

        Multibinder<QueryAtExecuted> querysAtExecuted
                = Multibinder.newSetBinder(binder(), QueryAtExecuted.class);
        querysAtExecuted.addBinding().to(QueryAtExecutedAtLocation.class); // key:"queryAtExecuted"

        bind(DefaultDeviceSynchronizer.class).in(Singleton.class);
        bind(DeviceSynchronizer.class).to(DefaultDeviceSynchronizer.class);
    }

    private void configureRedisPool() {
        RedisConfiguration configuration = RouteConfigKt.getRouteConfig().getRedis();

        JedisPoolConfig jdconfig = new JedisPoolConfig();
        jdconfig.setMaxTotal(configuration.getMaxTotal());
        jdconfig.setMaxIdle(configuration.getMaxIdle());
        jdconfig.setTestOnBorrow(configuration.getTestOnBorrow());
        jdconfig.setBlockWhenExhausted(configuration.getBlockWhenExhausted());
        jdconfig.setMaxWaitMillis(configuration.getMaxWaitMillis());
        JedisPool jedisPool = new JedisPool(jdconfig, configuration.getIp(), configuration.getPort());

        bind(JedisPool.class).toInstance(jedisPool);
    }
}
