package org.opentcs.kernel.vehicles;

import com.google.inject.assistedinject.Assisted;
import com.seer.srd.BusinessError;
import com.seer.srd.eventbus.EventBus;
import com.seer.srd.eventbus.VehicleChangedEvent;
import com.seer.srd.model.Path;
import com.seer.srd.model.Point;
import com.seer.srd.route.RouteConfigKt;
import com.seer.srd.route.WhiteBoardKt;
import com.seer.srd.route.service.PlantModelService;
import com.seer.srd.route.service.TransportOrderService;
import com.seer.srd.route.service.VehicleService;
import com.seer.srd.vehicle.driver.AbstractVehicleCommAdapter;
import com.seer.srd.vehicle.driver.VehicleCommAdapter;
import kotlin.jvm.functions.Function1;
import org.opentcs.components.kernel.ResourceAllocationException;
import org.opentcs.components.kernel.Scheduler;
import org.opentcs.components.kernel.services.DispatcherService;
import org.opentcs.data.ObjectUnknownException;
import com.seer.srd.model.Location;
import com.seer.srd.model.Triple;
import com.seer.srd.vehicle.Vehicle;
import org.opentcs.data.order.DriveOrder;
import org.opentcs.data.order.Route;
import org.opentcs.data.order.Step;
import org.opentcs.data.order.TransportOrder;
import org.opentcs.drivers.vehicle.*;
import org.opentcs.drivers.vehicle.VehicleProcessModel.Attribute;
import org.opentcs.strategies.basic.dispatching.RerouteUtil;
import org.opentcs.util.ExplainedBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;
import static org.opentcs.util.Assertions.checkArgument;
import static org.opentcs.util.Assertions.checkState;

/**
 * Realizes a bidirectional connection between the kernel and a communication adapter controlling a vehicle.
 * YY：ProcessModelEvent 没人监听，不触发了
 */
public class DefaultVehicleController implements VehicleController, PropertyChangeListener {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultVehicleController.class);

    private final DispatcherService dispatcherService;

    private final Scheduler scheduler;

    private final Vehicle vehicle;

    private final AbstractVehicleCommAdapter commAdapter;

    private final RerouteUtil rerouteUtil;

    private volatile boolean initialized;
    /**
     * A list of commands that still need to be sent to the communication adapter.
     */
    private final Queue<MovementCommand> futureCommands = new LinkedList<>();
    /**
     * A command for which a resource allocation is pending and which has not yet been sent to the
     * adapter.
     */
    private volatile MovementCommand pendingCommand;
    /**
     * A set of resources for which allocation is pending.
     */
    private volatile Set<String> pendingResources;
    /**
     * A list of commands that have been sent to the communication adapter.
     */
    private final Queue<MovementCommand> commandsSent = new LinkedList<>();
    /**
     * The last command that has been executed.
     */
    private MovementCommand lastCommandExecuted;
    /**
     * The resources this controller has allocated for each command.
     */
    private final Queue<Set<String>> allocatedResources = new LinkedList<>();
    /**
     * The drive order that the vehicle currently has to process.
     */
    private volatile DriveOrder currentDriveOrder;

    // 解决 java 和 kotlin 之间的一点问题
    private Function1<? super VehicleChangedEvent, ?> onVehicleChangedObject;

    private volatile Long latestSendOrFinishCommandTime = null;

    @Override
    public Long getLatestSendOrFinishCommandTime() {
        return latestSendOrFinishCommandTime;
    }

    /**
     * Flag indicating that we're currently waiting for resources to be allocated
     * by the scheduler, ensuring that we do not allocate more than one set of
     * resources at a time (which can cause deadlocks).
     */
    private volatile boolean waitingForAllocation;

    private volatile boolean onSafeClearing = false;

    @Inject
    public DefaultVehicleController(@Assisted @Nonnull Vehicle vehicle,
                                    @Assisted @Nonnull AbstractVehicleCommAdapter adapter,
                                    @Nonnull DispatcherService dispatcherService,
                                    @Nonnull Scheduler scheduler,
                                    @Nonnull RerouteUtil rerouteUtil) {
        this.vehicle = requireNonNull(vehicle, "vehicle");
        this.commAdapter = requireNonNull(adapter, "adapter");
        this.dispatcherService = requireNonNull(dispatcherService, "dispatcherService");
        this.scheduler = requireNonNull(scheduler, "scheduler");
        this.rerouteUtil = requireNonNull(rerouteUtil, "rerouteUtil");
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void initialize() {
        if (isInitialized()) return;

        this.onVehicleChangedObject = this::onVehicleChanged;
        EventBus.INSTANCE.getVehicleEventManager().add(this.onVehicleChangedObject);

        commAdapter.getProcessModel().addPropertyChangeListener(this);
        updateVehicleState(commAdapter.getProcessModel().getState());

        // Add a first entry into allocatedResources to shift freeing of resources
        // in commandExecuted() by one - we need to free the resources allocated for
        // the command before the one executed there.
        allocatedResources.add(null);

        initialized = true;
    }

    @Override
    public void terminate() {
        if (!isInitialized()) {
            return;
        }

        commAdapter.getProcessModel().removePropertyChangeListener(this);
        // Reset the vehicle's position.
        updatePosition(null, null);
        VehicleService.INSTANCE.updateVehiclePrecisePosition(vehicle.getName(), null);
        // Free all allocated resources.
        clearCommandQueue();
        freeAllResources();
        pendingResources = null;

        updateVehicleState(Vehicle.State.UNKNOWN);

        EventBus.INSTANCE.getVehicleEventManager().remove(this.onVehicleChangedObject);
        this.onVehicleChangedObject = null;

        initialized = false;
    }

    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        if (evt.getSource() != commAdapter.getProcessModel()) return;
        handleProcessModelEvent(evt);
    }

    private Object onVehicleChanged(VehicleChangedEvent event) {
        if (!(event.getOldVehicle() != null && event.getOldVehicle().getName().equals(vehicle.getName())
                || event.getNewVehicle() != null && event.getNewVehicle().getName().equals(vehicle.getName())))
            return ""; // 为了兼容 Java 和 Kotlin 的细微差异
        if (event.getOldVehicle() != null && event.getNewVehicle() != null &&
                event.getNewVehicle().getIntegrationLevel() != event.getOldVehicle().getIntegrationLevel()) {
            onIntegrationLevelChange(event.getOldVehicle(), event.getNewVehicle());
        }
        return "";
    }

    @Override
    public void setDriveOrder(@Nonnull DriveOrder newOrder, @Nonnull Map<String, String> orderProperties)
            throws IllegalStateException {
        synchronized (commAdapter.lock()) {
            requireNonNull(newOrder, "newOrder");
            requireNonNull(orderProperties, "orderProperties");
            requireNonNull(newOrder.getRoute(), "newOrder.getRoute()");
            // Assert that there isn't still is a drive order that hasn't been finished/removed, yet.
            checkState(currentDriveOrder == null,
                    "%s still has an order! Current order: %s, new order: %s",
                    vehicle.getName(),
                    currentDriveOrder,
                    newOrder);

            scheduler.claim(this, asResourceSequence(newOrder.getRoute().getSteps()));

            LOG.debug("Set DriveOrder {} for vehicle {}", newOrder, vehicle.getName());

            currentDriveOrder = newOrder;
            lastCommandExecuted = null;
            VehicleService.INSTANCE.updateVehicleRouteProgressIndex(vehicle.getName(), Vehicle.ROUTE_INDEX_DEFAULT);
            createFutureCommands(newOrder, orderProperties);

            if (canSendNextCommand()) {
                allocateForNextCommand();
            }

            // Set the vehicle's next expected position.
            String nextPoint = newOrder.getRoute().getSteps().get(0).getDestinationPoint();
            VehicleService.INSTANCE.updateVehicleNextPosition(vehicle.getName(), nextPoint);
        }
    }

    public void recoverDriveOrder(@Nonnull DriveOrder newOrder,
                                  @Nonnull Map<String, String> orderProperties)
            throws IllegalStateException {
        synchronized (commAdapter.lock()) {
            requireNonNull(newOrder, "newOrder");
            requireNonNull(orderProperties, "orderProperties");
            requireNonNull(newOrder.getRoute(), "newOrder.getRoute()");
            // Assert that there isn't still is a drive order that hasn't been finished/removed, yet.
            checkState(currentDriveOrder == null,
                    "%s still has an order! Current order: %s, new order: %s",
                    vehicle.getName(),
                    currentDriveOrder,
                    newOrder);

            LOG.debug("Recover DriveOrder {} for vehicle {}", newOrder, vehicle.getName());
            currentDriveOrder = newOrder;
            lastCommandExecuted = null;
        }
    }

    @Override
    public void updateDriveOrder(@Nonnull DriveOrder newOrder, @Nonnull Map<String, String> orderProperties) {
        synchronized (commAdapter.lock()) {
            if (currentDriveOrder == null) {
                // set driveOrder from transportOrder
                LOG.debug("Set drive order from transport order for vehicle {}", vehicle.getName());
                String orderId = VehicleService.INSTANCE.getVehicle(vehicle.getName()).getTransportOrder();
                TransportOrder order = orderId != null ?
                        TransportOrderService.INSTANCE.getOrderOrNull(orderId) : null;
                DriveOrder newDriveOrder = order != null ? order.getCurrentDriveOrder() : null;
                if (newDriveOrder != null) recoverDriveOrder(newDriveOrder, orderProperties);
            }

            checkState(currentDriveOrder != null, "There's no drive order to be updated");
            requireNonNull(newOrder, "newOrder");

//            checkArgument(driveOrdersContinual(currentDriveOrder, newOrder),
//                    "The new drive order contains steps the vehicle didn't process for the current "
//                            + "drive order.");

            // XXX Be a bit more thoughtful of which resource to claim/unclaim
            // XXX Unclaim only resources that would have been allocated in the future...
            scheduler.unclaim(this);
            // XXX ...and therefore claim only the resource that now will be allocated in the future
            Route route = newOrder.getRoute();
            if (route == null) throw new BusinessError("route is null", null);
            scheduler.claim(this, asResourceSequence(route.getSteps()));

            // Update the current drive order and future commands
            LOG.debug("Update DriveOrder {} for vehicle {}", newOrder, vehicle.getName());
            currentDriveOrder = newOrder;
            // There is a new drive order, so discard all the future/scheduled commands of the old one.
            discardFutureCommands();

            createFutureCommands(newOrder, orderProperties);
            // The current drive order got updated but our queue of future commands now contains commands
            // that have already been processed, so discard these
            discardSentFutureCommands();

            // Get an up-tp-date copy of the vehicle
            Vehicle updatedVehicle = VehicleService.INSTANCE.getVehicle(vehicle.getName());
            // Trigger the vehicle's route to be re-drawn
            VehicleService.INSTANCE.updateVehicleRouteProgressIndex(vehicle.getName(), updatedVehicle.getRouteProgressIndex());

            // The vehicle may now process previously restricted steps
            if (canSendNextCommand()) {
                allocateForNextCommand();
            }
        }
    }

    private boolean driveOrdersContinual(DriveOrder oldOrder, DriveOrder newOrder) {
        LOG.debug("Checking drive order continuity for {} and {}.", oldOrder, newOrder);

        // Get an up-tp-date copy of the vehicle
        Vehicle updatedVehicle = VehicleService.INSTANCE.getVehicle(vehicle.getName());
        int routeProgessIndex = updatedVehicle.getRouteProgressIndex();
        if (routeProgessIndex == -1) {
            return true;
        }

        Route oldRoute = oldOrder.getRoute();
        if (oldRoute == null) throw new BusinessError("route is null", null);
        Route newRoute = newOrder.getRoute();
        if (newRoute == null) throw new BusinessError("route is null", null);
        List<Step> oldSteps = oldRoute.getSteps();
        List<Step> newSteps = newRoute.getSteps();

        List<Step> oldProcessedSteps = oldSteps.subList(0, routeProgessIndex + 1);
        List<Step> newProcessedSteps = newSteps.subList(0, routeProgessIndex + 1);

        LOG.debug("Comparing {} and {} for equality.", oldProcessedSteps, newProcessedSteps);
//        return Objects.equals(oldProcessedSteps, newProcessedSteps); 这两者确实可能不一样，但又必须继续下去，所以这句注掉
        // TODO 此时，如果两个 route 接不上，是否应该:
//        VehicleService.INSTANCE.updateVehicleRouteProgressIndex(vehicle.getName(), Vehicle.ROUTE_INDEX_DEFAULT);
        return true;
    }

    private void discardFutureCommands() {
        futureCommands.clear();
        if (waitingForAllocation) {
            LOG.debug("{}: Discarding pending command but still waiting for allocation: {}",
                    vehicle.getName(),
                    pendingCommand);
        }
        pendingCommand = null;
        waitingForAllocation = false;
        pendingResources = null;
    }

    private void discardSentFutureCommands() {
        MovementCommand lastCommandSent = null;
        if (commandsSent.isEmpty()) {
            if (lastCommandExecuted == null) {
                // There are no commands to be discarded
                // discard commands according to vehicle position
                LOG.debug("Discarding future commands according to vehicle position.");
                String vehiclePosition = VehicleService.INSTANCE.getVehicle(vehicle.getName())
                        .getCurrentPosition();
                boolean vehicleOnTrack = false;
                for (MovementCommand cmd : futureCommands) {
                    if (cmd.getStep().getDestinationPoint().equals(vehiclePosition)) {
                        vehicleOnTrack = true;
                        lastCommandSent = cmd;
                        break;
                    }
                }

                if (!vehicleOnTrack) {
                    return;
                }

                for (int i = 0; i <= lastCommandSent.getStep().getRouteIndex(); i++) {
                    MovementCommand cmd = futureCommands.peek();
                    if (!cmd.isFinalMovement()) {
                        futureCommands.poll();
                        LOG.debug("Discard command: {}", cmd.toString());
                    } else {
                        LOG.debug("Command is final movement, cannot poll: {}", cmd.toString());
                    }
                }
                return;
            } else {
                // No commands in the 'sent queue', but the vehicle already executed some commands
                lastCommandSent = lastCommandExecuted;
            }
        } else {
            List<MovementCommand> commandsSentList = new ArrayList<>(commandsSent);
            lastCommandSent = commandsSentList.get(commandsSentList.size() - 1);
        }

        LOG.debug("Discarding future commands up to '{}' (inclusively): {}", lastCommandSent, futureCommands);
        for (int i = 0; i < lastCommandSent.getStep().getRouteIndex() + 1; i++) {
            futureCommands.poll();
        }
    }

    @Override
    public void clearDriveOrder() {
        synchronized (commAdapter.lock()) {
            LOG.debug("Clearing DriveOrder: {}", this.currentDriveOrder);
            currentDriveOrder = null;

            // Clear pending resource allocations. If they still arrive, we will
            // refuse them in allocationSuccessful().
            waitingForAllocation = false;
            pendingResources = null;

            VehicleService.INSTANCE.updateVehicleRouteProgressIndex(vehicle.getName(), Vehicle.ROUTE_INDEX_DEFAULT);
        }
    }

    @Override
    public void abortDriveOrder() {
        synchronized (commAdapter.lock()) {
            if (currentDriveOrder == null) {
                LOG.debug("{}: No drive order to be aborted", vehicle.getName());
                return;
            }
            futureCommands.clear();
        }
    }

    @Override
    public void fastAbortDriveOrder() {
        synchronized (commAdapter.lock()) {
            if(commAdapter.isInitialized()) {
                commAdapter.safeClearCommandQueue();
                onSafeClearing = true;
            }
        }
    }

    @Override
    public DriveOrder getCurrentDriveOrder() {
        return currentDriveOrder;
    }

    @Override
    public void clearCommandQueue() {
        synchronized (commAdapter.lock()) {
            if(commAdapter.isInitialized()) {
                commAdapter.clearCommandQueue();
            }
            LOG.debug("Clearing commands sent: {}", this.commandsSent);
            commandsSent.clear();
            LOG.debug("Clearing future commands: {}", this.futureCommands);
            futureCommands.clear();
            LOG.debug("Clearing pending command: {}", this.pendingCommand);
            pendingCommand = null;
            onSafeClearing = false;
            // Free all resource sets that were reserved for future commands, except the current one...
            Set<String> neededResources = allocatedResources.poll();
            for (Set<String> resSet : allocatedResources) {
                if (resSet != null) {
                    scheduler.free(this, resSet);
                }
            }
            allocatedResources.clear();
            // Put the resources for the current command/position back in...
            allocatedResources.add(neededResources);
            VehicleService.INSTANCE.updateVehicleAllocations(vehicle.getName(),
                                                             scheduler.getAllocationsByName(vehicle.getName()));
        }
    }

    @Override
    public void safeClearCommandQueue() {
        synchronized (commAdapter.lock()) {
            if(commAdapter.isInitialized()) {
                commAdapter.clearCommandQueue();
            }
            LOG.debug("Clearing commands sent: {}", this.commandsSent);
            commandsSent.clear();
            LOG.debug("Clearing future commands: {}", this.futureCommands);
            futureCommands.clear();
            LOG.debug("Clearing pending command: {}", this.pendingCommand);
            pendingCommand = null;
            onSafeClearing = false;
            lastCommandExecuted = null;
            // 放到点上
            Vehicle sourcePos = VehicleService.INSTANCE.getVehicle(vehicle.getName());
            Point currentPoint = PlantModelService.INSTANCE.getPlantModel().getPoints().get(sourcePos.getCurrentPosition());
            updatePositionWithoutOrder(currentPoint);
        }
    }

    @Override
    @Deprecated
    public void resetVehiclePosition() {
        synchronized (commAdapter.lock()) {
            checkState(currentDriveOrder == null, "%s: Vehicle has a drive order", vehicle.getName());
            checkState(!waitingForAllocation, "%s: Vehicle is waiting for resource allocation", vehicle.getName());
            setVehiclePosition(null);
        }
    }

    @Override
    @Nonnull
    public ExplainedBoolean canProcess(@Nonnull List<String> operations) {
        requireNonNull(operations, "operations");

        synchronized (commAdapter.lock()) {
            return commAdapter.canProcess();
        }
    }

    @Override
    public void sendCommAdapterMessage(@Nullable Object message) {
        synchronized (commAdapter.lock()) {
            commAdapter.processMessage(message);
        }
    }

    @Override
    public void sendCommAdapterCommand(AdapterCommand command) {
        synchronized (commAdapter.lock()) {
            commAdapter.execute(command);
        }
    }

    @Override
    public Queue<MovementCommand> getCommandsSent() {
        return new LinkedList<>(commandsSent);
    }

    @Override
    @Nonnull
    public String getId() {
        return vehicle.getName();
    }

    @Override
    public boolean allocationSuccessful(@Nonnull Set<String> resources) {
        requireNonNull(resources, "resources");

        // Look up the command the resources were required for.
        MovementCommand command;
        synchronized (commAdapter.lock()) {
            // Check if we've actually been waiting for these resources now. If not,
            // let the scheduler know that we don't want them.
            if (!Objects.equals(resources, pendingResources)) {
                LOG.warn("{}: Allocated resources ({}) != pending resources ({}), refusing them",
                        vehicle.getName(),
                        resources,
                        pendingResources);
                return false;
            }

            command = pendingCommand;
            // If there was no command in the queue, it must have been withdrawn in
            // the meantime - let the scheduler know that we don't need the resources
            // any more.
            if (command == null) {
                LOG.warn("{}: No pending command, pending resources = {}, refusing allocated resources: {}",
                        vehicle.getName(),
                        pendingResources,
                        resources);
                waitingForAllocation = false;
                pendingResources = null;
                // In case the contoller's vehicle got rerouted while waiting for resource allocation
                // the pending command is reset and therefore the associated allocation will be ignored.
                // Since there's now a new/updated route we need to trigger the next allocation. Otherwise
                // the vehicle would wait forever to get the next command.
                if (canSendNextCommand()) {
                    allocateForNextCommand();
                }
                return false;
            }
            // 已经处在快速撤销的状态
            if (RouteConfigKt.getRouteConfig().getDispatcher().getFastWithdrawal()) {
                if (onSafeClearing) {
                    LOG.info("{} is on safe clearing, refusing allocated resources: {}.", vehicle.getName(), resources);
                    return false;
                }
            }

            pendingCommand = null;
            pendingResources = null;

            allocatedResources.add(resources);
            // Send the command to the communication adapter.
            checkState(commAdapter.appendToCommandQueue(command),
                    "Comm adapter did not accept command");
            commandsSent.add(command);
            latestSendOrFinishCommandTime = System.currentTimeMillis();

            // Check if the communication adapter has capacity for another command.
            waitingForAllocation = false;
            if (canSendNextCommand()) {
                allocateForNextCommand();
            }
        }
        VehicleService.INSTANCE.updateVehicleAllocations(vehicle.getName(),
                scheduler.getAllocationsByName(vehicle.getName()));
        // Let the scheduler know we've accepted the resources given.
        return true;
    }

    @Override
    public void allocationFailed(@Nonnull Set<String> resources) {
        requireNonNull(resources, "resources");
        throw new IllegalStateException("Failed to allocate: " + resources);
    }

    @Override
    public String toString() {
        return "DefaultVehicleController{" + "vehicleName=" + vehicle.getName() + '}';
    }

    @Override
    public List<VehicleProcessModel.ErrorInfo> getErrorInfos() {
        final List<VehicleProcessModel.ErrorInfo> infos;
        synchronized (commAdapter.lock()) {
            infos = commAdapter.getProcessModel().getErrorInfos();
        }
        return infos;
    }

    @Override
    public String getDetails() {
        final String details;
        synchronized (commAdapter.lock()) {
            details = commAdapter.getProcessModel().getDetails();
        }
        return details;
    }

    private void handleProcessModelEvent(PropertyChangeEvent evt) {
        if (Objects.equals(evt.getPropertyName(), Attribute.POSITION.name())) {
            updateVehiclePosition((String) evt.getNewValue());
        } else if (Objects.equals(evt.getPropertyName(), Attribute.PRECISE_POSITION.name())) {
            updateVehiclePrecisePosition((Triple) evt.getNewValue());
        } else if (Objects.equals(evt.getPropertyName(), Attribute.ORIENTATION_ANGLE.name())) {
            VehicleService.INSTANCE.updateVehicleOrientationAngle(vehicle.getName(), (Double) evt.getNewValue());
        } else if (Objects.equals(evt.getPropertyName(), Attribute.ENERGY_LEVEL.name())) {
            VehicleService.INSTANCE.updateVehicleEnergyLevel(vehicle.getName(), (Integer) evt.getNewValue());
        } else if (Objects.equals(evt.getPropertyName(), Attribute.LOAD_HANDLING_DEVICES.name())) {
            //noinspection unchecked
            VehicleService.INSTANCE.updateVehicleLoadHandlingDevices(vehicle.getName(), (List<LoadHandlingDevice>) evt.getNewValue());
        } else if (Objects.equals(evt.getPropertyName(), Attribute.STATE.name())) {
            updateVehicleState((Vehicle.State) evt.getNewValue());
        } else if (Objects.equals(evt.getPropertyName(), Attribute.COMMAND_EXECUTED.name())) {
            commandExecuted((MovementCommand) evt.getNewValue());
        } else if (Objects.equals(evt.getPropertyName(), Attribute.COMMAND_FAILED.name())) {
            dispatcherService.withdrawByVehicle(vehicle.getName(), true, false);
        } else if (Objects.equals(evt.getPropertyName(), Attribute.COMM_ADAPTER_EVENT.name())) {
            WhiteBoardKt.getEventBus().onEvent(evt.getNewValue());
        } else if (Objects.equals(evt.getPropertyName(), Attribute.OWNER.name())) {
            VehicleService.INSTANCE.updateVehicleOwner(vehicle.getName(), (String) evt.getNewValue(), commAdapter.getProcessModel().isDominating());
        } else if (Objects.equals(evt.getPropertyName(), Attribute.VEHICLE_BLOCKED.name())) {
            if ((Boolean)evt.getNewValue() && RouteConfigKt.getRouteConfig().getRerouteWhenBlock()) {
                tryRerouteWhenBlocked();
            }
        } else if (Objects.equals(evt.getPropertyName(), Attribute.RELOC_STATUS.name())) {
            VehicleService.INSTANCE.updateVehicleRelocStatus(vehicle.getName(), (Integer) evt.getNewValue());
        }
    }

    private void tryRerouteWhenBlocked() {
        LOG.info("{}: reroute when blocked.", vehicle.getName());
        synchronized (commAdapter.lock()) {
            MovementCommand currentExecutingCommand = getCommandsSent().peek();
            // 当前是有在执行且未完成的命令的
            if (currentExecutingCommand != null) {
                Point currentPos = null;
                List<Path> exceptPaths = new LinkedList<>();
                final String sourcePos = currentExecutingCommand.getStep().getSourcePoint();
                final String destPos = currentExecutingCommand.getStep().getDestinationPoint();
                LOG.info("{} unfinished command is {} to {}", vehicle.getName(), sourcePos, destPos);
                // 如果车的位置，是在当前路径的源点处
                if (sourcePos != null && sourcePos.equals(commAdapter.getProcessModel().getVehiclePosition())) {
                    currentPos = PlantModelService.INSTANCE.getPlantModel().getPoints().get(sourcePos);
                    // 暂定，只有当前路径不能走
                    Path exceptPath = PlantModelService.INSTANCE.getPlantModel().getPaths().get(
                            currentExecutingCommand.getStep().getPath()
                    );
                    exceptPaths.add(exceptPath);
                }
                // 如果车的位置，是在当前路径的终点附近
                else if (destPos.equals(commAdapter.getProcessModel().getVehiclePosition())) {
                    currentPos = PlantModelService.INSTANCE.getPlantModel().getPoints().get(destPos);
                    exceptPaths.addAll(
                            PlantModelService.INSTANCE.getPlantModel().getPaths()
                            .values()
                            .stream()
                            .filter(path -> path.getSourcePoint().equals(destPos))
                            .filter(path -> !path.getDestinationPoint().equals(sourcePos))
                            .collect(Collectors.toList())
                    );
                }
                // 执行 reroute
                if (currentPos != null && !exceptPaths.isEmpty()) {
                    // clear command queue
                    if(commAdapter.isInitialized()) {
                        commAdapter.clearCommandQueue();
                    }
                    LOG.debug("Clearing commands sent: {}", this.commandsSent);
                    commandsSent.clear();
                    LOG.debug("Clearing future commands: {}", this.futureCommands);
                    futureCommands.clear();
                    LOG.debug("Clearing pending command: {}", this.pendingCommand);
                    pendingCommand = null;
                    lastCommandExecuted = null;
                    // 放到点上
                    updatePositionWithoutOrder(currentPos);
                    rerouteUtil.reroute(VehicleService.INSTANCE.getVehicle(vehicle.getName()), exceptPaths);
                }
            }
        }
    }

    private void updateVehiclePrecisePosition(Triple precisePosition)
            throws ObjectUnknownException {
        // Get an up-to-date copy of the vehicle
        Vehicle currVehicle = VehicleService.INSTANCE.getVehicle(vehicle.getName());

        if (currVehicle.getIntegrationLevel() != Vehicle.IntegrationLevel.TO_BE_IGNORED) {
            VehicleService.INSTANCE.updateVehiclePrecisePosition(vehicle.getName(), precisePosition);
        }
    }

    private void updateVehiclePosition(String position) {
        Vehicle currVehicle = VehicleService.INSTANCE.getVehicle(vehicle.getName());

        if (currVehicle.getIntegrationLevel() == Vehicle.IntegrationLevel.TO_BE_RESPECTED
                || currVehicle.getIntegrationLevel() == Vehicle.IntegrationLevel.TO_BE_UTILIZED) {
            setVehiclePosition(position);
        } else if (currVehicle.getIntegrationLevel() == Vehicle.IntegrationLevel.TO_BE_NOTICED) {
            Point point = PlantModelService.INSTANCE.getPlantModel().getPoints().get(position);
            updatePosition(point.getName(), null);
        }
    }

    public static String collectExceptionStackMsg(Exception e){
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw, true));
        String strs = sw.toString();
        return strs;
    }

    private void setVehiclePosition(String position) {
        // Place the vehicle on the given position, regardless of what the kernel
        // might expect. The vehicle is physically there, even if it shouldn't be.
        // The same is true for null values - if the vehicle says it's not on any
        // known position, it has to be treated as a fact.
        Point point;
        if (position == null) {
            point = null;
        } else {
            point = PlantModelService.INSTANCE.getPlantModel().getPoints().get(position);
            // If the new position is not in the model, ignore it. (Some vehicles/drivers send
            // intermediate positions that cannot be order destinations and thus do not exist in
            // the model.
            if (point == null) {
                LOG.warn("{}: At unknown position {}", vehicle.getName(), position);
                return;
            }
        }
        synchronized (commAdapter.lock()) {
            // 这里是否会因循环调用产生死锁问题？
//            if (position == null) {
//                commAdapter.getProcessModel().setVehiclePosition(null); // 否则，adapter里的position将会一直保持原值
//            }
            // If the current drive order is null, just set the vehicle's position.
            if (currentDriveOrder == null) {
                LOG.debug("{}: Reported new position {} and we do not have a drive order.",
                        vehicle.getName(),
                        point);
                LOG.error(collectExceptionStackMsg(new Exception("Who is calling this??")));
                updatePositionWithoutOrder(point);
            } else if (commandsSent.isEmpty()) {
                // We have a drive order, but can't remember sending a command to the
                // vehicle. Just set the position without touching the resources, as
                // that might cause even more damage when we actually send commands
                // to the vehicle.
                LOG.error("{}: Reported new position {} and we didn't send any commands of drive order.",
                        vehicle.getName(),
                        point);
                if (point == null) {
                    updatePosition(null, null);
                }
                else {
                    synchronized (commAdapter.lock()) {
                        Vehicle updatedVehicle = VehicleService.INSTANCE.getVehicle(vehicle.getName());
                        // 机器人把所有已下发的任务都执行完了，或者没在已发路径上，并且状态不是 EXECUTING
                        if (!commAdapter.canResend(position) && !updatedVehicle.getState().equals(Vehicle.State.EXECUTING)) {
                            if(commAdapter.isInitialized()) {
                                commAdapter.clearCommandQueue();
                            }
                            LOG.debug("Clearing commands sent: {}", this.commandsSent);
                            commandsSent.clear();
                            LOG.debug("Clearing future commands: {}", this.futureCommands);
                            futureCommands.clear();
                            LOG.debug("Clearing pending command: {}", this.pendingCommand);
                            pendingCommand = null;
                            lastCommandExecuted = null;
                            // 放到点上
                            updatePositionWithoutOrder(point);
                            if (scheduler.isAllocatedBy(point.getName(), this)) {
                                rerouteUtil.reroute(VehicleService.INSTANCE.getVehicle(vehicle.getName()), null);
                            } else {
                                LOG.info("Vehicle {} cannot be allocated on point {}, so cannot reroute.", vehicle.getName(), point.getName());
                            }
                        }
                    }
                }
            } else {
                updatePositionWithOrder(position, point);
            }
        }
    }

    private void commandExecuted(MovementCommand executedCommand) {
        requireNonNull(executedCommand, "executedCommand");

        synchronized (commAdapter.lock()) {
            // Check if the executed command is the one we expect at this point.
            MovementCommand expectedCommand = commandsSent.peek();
            if (!Objects.equals(expectedCommand, executedCommand)) {
                LOG.warn("{}: Communication adapter executed unexpected command: {} != {}",
                        vehicle.getName(),
                        executedCommand,
                        expectedCommand);
                // XXX The communication adapter executed an unexpected command. Do something!
            }
            // Remove the command from the queue, since it has been processed successfully.
            lastCommandExecuted = commandsSent.remove();
            latestSendOrFinishCommandTime = System.currentTimeMillis();
            // Free resources allocated for the command before the one now executed.
            Set<String> oldResources = allocatedResources.poll();
            if (oldResources != null) {
                LOG.debug("{}: Freeing resources: {}", vehicle.getName(), oldResources);
                scheduler.free(this, oldResources);
                VehicleService.INSTANCE.updateVehicleAllocations(vehicle.getName(),
                                                                 scheduler.getAllocationsByName(vehicle.getName()));
            } else {
                LOG.debug("{}: Nothing to free.", vehicle.getName());
            }
            // Check if there are more commands to be processed for the current drive order.
            if (pendingCommand == null && futureCommands.isEmpty()) {
                LOG.debug("{}: No more commands in current drive order", vehicle.getName());
                // Check if there are still commands that have been sent to the communication adapter but
                // not yet executed. If not, the whole order has been executed completely - let the kernel
                // know about that so it can give us the next drive order.
                if (commandsSent.isEmpty() && !waitingForAllocation) {
                    LOG.debug("{}: Current drive order processed", vehicle.getName());
                    currentDriveOrder = null;
                    // Let the kernel/dispatcher know that the drive order has been processed completely (by
                    // setting its state to AWAITING_ORDER).
                    VehicleService.INSTANCE.updateVehicleRouteProgressIndex(vehicle.getName(), Vehicle.ROUTE_INDEX_DEFAULT);
                    VehicleService.INSTANCE.updateVehicleProcState(vehicle.getName(), Vehicle.ProcState.AWAITING_ORDER);
                }
            }
            // There are more commands to be processed.
            // Check if we can send another command to the comm adapter.
            else if (canSendNextCommand()) {
                allocateForNextCommand();
            }
        }
    }

    private void createFutureCommands(DriveOrder newOrder, Map<String, String> orderProperties) {
        // Start processing the new order, i.e. fill futureCommands with corresponding command objects.
        String op = newOrder.getDestination().getOperation();
        Route orderRoute = newOrder.getRoute();
        Point finalDestination = PlantModelService.INSTANCE.getPlantModel().getPoints().get(orderRoute.getFinalDestinationPoint());
        Location finalDestinationLocation = PlantModelService.INSTANCE.getPlantModel()
                .getLocations().get(newOrder.getDestination().getDestination());
        Map<String, String> destProperties = newOrder.getDestination().getProperties();
        Map<String, String> locationProperties = null == finalDestinationLocation ?
                new HashMap<>() : finalDestinationLocation.getProperties();

        Iterator<Step> stepIter = orderRoute.getSteps().iterator();
        while (stepIter.hasNext()) {
            Step curStep = stepIter.next();
            // Ignore report positions on the route.
            Point destPoint = PlantModelService.INSTANCE.getPlantModel().getPoints().get(curStep.getDestinationPoint());
            if (destPoint.isHaltingPosition()) {
                boolean isFinalMovement = !stepIter.hasNext();

                String operation = isFinalMovement ? op : MovementCommand.NO_OPERATION;
                Location location = isFinalMovement ? finalDestinationLocation : null;

                Map<String, String> properties = new HashMap<>();
                if (isFinalMovement) {
                    properties = mergeProperties(orderProperties, destProperties, locationProperties);
                }

                if (null != curStep.getPath()) {
                    properties.putAll(PlantModelService.INSTANCE.getPlantModel().getPaths().get(curStep.getPath()).getProperties());
                }

                futureCommands.add(new MovementCommand(curStep,
                        operation,
                        location,
                        isFinalMovement,
                        finalDestinationLocation,
                        finalDestination,
                        op,
                        properties,
                        vehicle.getName(),
                        requireNonNull(newOrder.getTransportOrder()),
                        TransportOrderService.INSTANCE.getOrder(newOrder.getTransportOrder()).getCurrentDriveOrderIndex()
                        ));
            }
        }
    }

    private void updateVehicleState(Vehicle.State newState) {
        requireNonNull(newState, "newState");
        // If the communication adapter knows the state of the vehicle and is not
        // marked as connected with us, mark it as connected now. - It knows the
        // vehicle's state, so it must be connected to it.
        VehicleService.INSTANCE.updateVehicleState(vehicle.getName(), newState);
        LOG.info("Vehicle {} state changed to {}",vehicle.getName(), newState.name());
    }

    /**
     * Checks if we can send another command to the communication adapter without
     * overflowing its capacity and with respect to the number of commands still
     * in our queue and allocation requests to the scheduler in progress.
     *
     * @return <code>true</code> if, and only if, we can send another command.
     */
    private boolean canSendNextCommand() {
        int sendableCommands = Math.min(commAdapter.getCommandQueueCapacity() - commandsSent.size(),
                futureCommands.size());
        if (sendableCommands <= 0) {
            LOG.debug("{}: Cannot send, number of sendable commands: {}",
                    vehicle.getName(),
                    sendableCommands);
            LOG.debug("controller cannot send, CommandQueueCapacity is {}, commandsSent.size is {},futureCommands.size is {}",
                    commAdapter.getCommandQueueCapacity(), commandsSent.size(), futureCommands.size());
            return false;
        }
        if (!futureCommands.peek().getStep().getExecutionAllowed()) {
            LOG.debug("{}: Cannot send, movement execution is not allowed", vehicle.getName());
            return false;
        }
        if (waitingForAllocation) {
            LOG.debug("{}: Cannot send, waiting for allocation", vehicle.getName());
            return false;
        }
        return true;
    }

    /**
     * Allocate the resources needed for executing the next command.
     */
    private void allocateForNextCommand() {
        checkState(pendingCommand == null, "pendingCommand != null");

        // Find out which resources are actually needed for the next command.
        MovementCommand moveCmd = futureCommands.poll();
        pendingResources = getNeededResources(moveCmd);
        LOG.debug("{}: Allocating resources: {}", vehicle.getName(), pendingResources);
        scheduler.allocate(this, pendingResources);
        // Remember that we're waiting for an allocation. This ensures that we only
        // wait for one allocation at a time, and that we get the resources from the
        // scheduler in the right order.
        waitingForAllocation = true;
        pendingCommand = moveCmd;
    }

    /**
     * Returns a set of resources needed for executing the given command.
     *
     * @param cmd The command for which to return the needed resources.
     * @return A set of resources needed for executing the given command.
     */
    private Set<String> getNeededResources(MovementCommand cmd) {
        requireNonNull(cmd, "cmd");

        Set<String> result = new HashSet<>();
        result.add(cmd.getStep().getDestinationPoint());
        if (cmd.getStep().getPath() != null) {
            result.add(cmd.getStep().getPath());
        }
        return result;
    }

    /**
     * Frees all resources allocated for the vehicle.
     */
    private void freeAllResources() {
        scheduler.freeAll(this);
        allocatedResources.clear();
        VehicleService.INSTANCE.updateVehicleAllocations(vehicle.getName(),
                                                         scheduler.getAllocationsByName(vehicle.getName()));
    }

    /**
     * Returns the next command expected to be executed by the vehicle, skipping the current one.
     *
     * @return The next command expected to be executed by the vehicle.
     */
    private MovementCommand findNextCommand() {
        MovementCommand nextCommand = commandsSent.stream()
                .skip(1)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        if (nextCommand == null) {
            nextCommand = pendingCommand;
        }

        if (nextCommand == null) {
            nextCommand = futureCommands.stream()
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }

        return nextCommand;
    }

    private void updatePositionWithoutOrder(Point point) {
        LOG.info("Update {} position to {} without order...", vehicle.getName(), point);
        // Allocate only the resources required for occupying the new position.
        if (point != null) {
            Set<String> exceptResource = new HashSet<>();
            exceptResource.add(point.getName());
            scheduler.freeAllExcept(this, exceptResource);
            allocatedResources.clear();
            VehicleService.INSTANCE.updateVehicleAllocations(vehicle.getName(),
                    scheduler.getAllocationsByName(vehicle.getName()));
        } else {
            freeAllResources();
        }
        // If the vehicle is at an unknown position, it's impossible to say
        // which resources it needs, so don't allocate any in that case.
        if (point != null) {
            try {
                Set<String> requiredResource = new HashSet<>();
                requiredResource.add(point.getName());
                scheduler.allocateNow(this, requiredResource);
                allocatedResources.add(requiredResource);
                VehicleService.INSTANCE.updateVehicleAllocations(vehicle.getName(),
                                                                 scheduler.getAllocationsByName(vehicle.getName()));
            } catch (ResourceAllocationException exc) {
                LOG.warn("{}: Could not allocate required resources immediately, ignored.",
                        vehicle.getName(),
                        exc);
            }
        }
        updatePosition(point != null ? point.getName() : null, null);
    }

    private void updatePositionWithOrder(String position, Point point) {
        // If a drive order is being processed, check if the reported position
        // is the one we expect.
        MovementCommand moveCommand = commandsSent.stream().findFirst().get();
        LOG.debug("commandsSent = {}", commandsSent);
        String dstPoint = moveCommand.getStep().getDestinationPoint();
        if (dstPoint.equals(position)) {
            // Update the vehicle's progress index.
            VehicleService.INSTANCE.updateVehicleRouteProgressIndex(vehicle.getName(), moveCommand.getStep().getRouteIndex());
            // Let the scheduler know where we are now.
            scheduler.updateProgressIndex(this, moveCommand.getStep().getRouteIndex());
        } else if (position == null) {
//            updateVehicleState(Vehicle.State.ERROR);
            LOG.info("{}: Resetting position for vehicle", vehicle.getName());
        } else {
            LOG.error("{}: Reported position: {}, expected: {}.", vehicle.getName(), position, dstPoint);
            synchronized (commAdapter.lock()) {
                Vehicle updatedVehicle = VehicleService.INSTANCE.getVehicle(vehicle.getName());
                // 机器人把所有已下发的任务都执行完了，或者没在已发路径上，并且状态不是 EXECUTING
                if (!commAdapter.canResend(position) && !updatedVehicle.getState().equals(Vehicle.State.EXECUTING)) {
                    if(commAdapter.isInitialized()) {
                        commAdapter.clearCommandQueue();
                    }
                    LOG.debug("Clearing commands sent: {}", this.commandsSent);
                    commandsSent.clear();
                    LOG.debug("Clearing future commands: {}", this.futureCommands);
                    futureCommands.clear();
                    LOG.debug("Clearing pending command: {}", this.pendingCommand);
                    pendingCommand = null;
                    lastCommandExecuted = null;
                    // 放到点上
                    updatePositionWithoutOrder(point);
                    if (scheduler.isAllocatedBy(point.getName(), this)) {
                        rerouteUtil.reroute(VehicleService.INSTANCE.getVehicle(vehicle.getName()), null);
                    } else {
                        LOG.info("Vehicle {} cannot be allocated on point {}, so cannot reroute.", vehicle.getName(), point.getName());
                    }
                }
            }
        }

        if (point != null) {
            updatePosition(point.getName(), extractNextPosition(findNextCommand()));
        }
        else {
            updatePosition(null, extractNextPosition(findNextCommand()));
        }
    }

    private void updatePosition(String posPointName, String nextPosPointName) {
        VehicleService.INSTANCE.updateVehiclePosition(vehicle.getName(), posPointName);
        VehicleService.INSTANCE.updateVehicleNextPosition(vehicle.getName(), nextPosPointName);
    }

    private void onIntegrationLevelChange(Vehicle prevVehicleState, Vehicle currVehicleState) {
        Vehicle.IntegrationLevel prevIntegrationLevel = prevVehicleState.getIntegrationLevel();
        Vehicle.IntegrationLevel currIntegrationLevel = currVehicleState.getIntegrationLevel();

        synchronized (commAdapter.lock()) {
            if (currIntegrationLevel == Vehicle.IntegrationLevel.TO_BE_IGNORED) {
                // Reset the vehicle's position to free all allocated resources
                resetVehiclePosition();
                VehicleService.INSTANCE.updateVehiclePrecisePosition(vehicle.getName(), null);
            } else if (currIntegrationLevel == Vehicle.IntegrationLevel.TO_BE_NOTICED) {
                // Reset the vehicle's position to free all allocated resources
                resetVehiclePosition();
                // Update the vehicle's position in its model, but don't allocate any resources
                VehicleProcessModel processModel = commAdapter.getProcessModel();
                if (processModel.getVehiclePosition() != null) {
                    Point point = PlantModelService.INSTANCE.getPlantModel()
                            .getPoints().get(processModel.getVehiclePosition());
                    VehicleService.INSTANCE.updateVehiclePosition(vehicle.getName(), point.getName());
                }
                VehicleService.INSTANCE.updateVehiclePrecisePosition(vehicle.getName(), processModel.getPrecisePosition());
            } else if ((currIntegrationLevel == Vehicle.IntegrationLevel.TO_BE_RESPECTED
                    || currIntegrationLevel == Vehicle.IntegrationLevel.TO_BE_UTILIZED)
                    && (prevIntegrationLevel == Vehicle.IntegrationLevel.TO_BE_IGNORED
                    || prevIntegrationLevel == Vehicle.IntegrationLevel.TO_BE_NOTICED)) {
                // Allocate the vehicle's current position and implicitly update its model's position
                allocateVehiclePosition();
            }

            updateVehicleProcState(currIntegrationLevel, currVehicleState);
        }
    }

    // XXX In the future the integration level won't implicitly affect the proc state, anymore
    private void updateVehicleProcState(Vehicle.IntegrationLevel currIntegrationLevel, Vehicle vehicle) {
        if (currIntegrationLevel == Vehicle.IntegrationLevel.TO_BE_UTILIZED) {
            if (vehicle.hasProcState(Vehicle.ProcState.UNAVAILABLE)) {
                VehicleService.INSTANCE.updateVehicleProcState(vehicle.getName(), Vehicle.ProcState.IDLE);
            }
        }
    }

    private void allocateVehiclePosition() {
        VehicleProcessModel processModel = commAdapter.getProcessModel();
        // We don't want to set the vehicle position right away, since the vehicle's currently
        // allocated resources would be freed in the first place. We need to check, if the vehicle's
        // current position is already part of it's allocated resources.
        if (!alreadyAllocated(processModel.getVehiclePosition())) {
            // Set vehicle's position to allocate the resources
            setVehiclePosition(processModel.getVehiclePosition());
            VehicleService.INSTANCE.updateVehiclePrecisePosition(vehicle.getName(), processModel.getPrecisePosition());
        }
    }

    private boolean alreadyAllocated(String position) {
        return allocatedResources.stream()
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .anyMatch(resource -> resource.equals(position));
    }

    private static String extractNextPosition(MovementCommand nextCommand) {
        if (nextCommand == null) {
            return null;
        } else {
            return nextCommand.getStep().getDestinationPoint();
        }
    }

    /**
     * Merges the properties of a transport order and those of a drive order.
     *
     * @param orderProps The properties of a transport order.
     * @param destProps  The properties of a drive order destination.
     * @return The merged properties.
     */
    private static Map<String, String> mergeProperties(Map<String, String> orderProps,
                                                       Map<String, String> destProps,
                                                       Map<String, String> locProps) {
        requireNonNull(orderProps, "orderProps");
        requireNonNull(destProps, "destProps");
        requireNonNull(locProps, "locProps");

        Map<String, String> result = new HashMap<>();
        result.putAll(orderProps);
        result.putAll(destProps);
        result.putAll(locProps);
        return result;
    }

    private static List<Set<String>> asResourceSequence(@Nonnull List<Step> steps) {
        requireNonNull(steps, "steps");

        List<Set<String>> result = new ArrayList<>(steps.size());
        for (Step step : steps) {
            String path = step.getPath();
            result.add(new HashSet<>(Arrays.asList(step.getDestinationPoint(), path)));
        }
        return result;
    }
}
