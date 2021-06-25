package org.opentcs.components.kernel.services;

import org.opentcs.access.KernelRuntimeException;
import org.opentcs.data.order.TransportOrderState;

/**
 * 与 Dispatcher 协作
 */
public interface DispatcherService {

    /**
     * 显式触发 dispatching
     */
    void dispatch() throws KernelRuntimeException;

    /**
     * 撤销车上的运单
     *
     * @param immediateAbort If {@code false}, this method once will initiate the withdrawal, leaving
     *                       the transport order assigned to the vehicle until it has finished the movements that it has
     *                       already been ordered to execute. The transport order's state will change to
     *                       {@link TransportOrderState#WITHDRAWN}. If {@code true}, the dispatcher will withdraw the order from the
     *                       vehicle without further waiting.
     */
    void withdrawByVehicle(String vehicleName, boolean immediateAbort) throws KernelRuntimeException;

    /**
     * Withdraw any order that a vehicle might be processing, set its state to {@link TransportOrderState#FAILED}
     * and stop the vehicle.
     *
     * @param immediateAbort If {@code false}, this method once will initiate the withdrawal, leaving
     *                       the transport order assigned to the vehicle until it has finished the movements that it has
     *                       already been ordered to execute. The transport order's state will change to
     *                       {@link TransportOrderState#WITHDRAWN}. If {@code true}, the dispatcher will withdraw the order from the
     *                       vehicle without further waiting.
     * @param disableVehicle Whether to set the processing state of the vehicle currently processing
     *                       the transport order to ProcState.UNAVAILABLE to prevent
     *                       immediate redispatching of the vehicle.
     * instead.
     */
    void withdrawByVehicle(String vehicleName,
                           boolean immediateAbort,
                           boolean disableVehicle)
            throws KernelRuntimeException;

    /**
     * 撤销指定运单
     *
     * @param immediateAbort If {@code false}, this method once will initiate the withdrawal, leaving
     *                       the transport order assigned to the vehicle until it has finished the movements that it has
     *                       already been ordered to execute. The transport order's state will change to
     *                       {@link TransportOrderState#WITHDRAWN}. If {@code true}, the dispatcher will withdraw the order from the
     *                       vehicle without further waiting.
     */
    void withdrawByTransportOrder(String orderName, boolean immediateAbort)
            throws KernelRuntimeException;

    void withdrawByTransportOrder(String orderName, boolean immediateAbort, boolean disableVehicle)
            throws KernelRuntimeException;
}
