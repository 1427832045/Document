package org.opentcs.common;

/**
 * This interface provides access to vehicle-property keys that are used in both the
 * plant overview as well as in the kernel.
 */
public interface LoopbackAdapterConstants {

    /**
     * The key of the vehicle property that specifies the vehicle's initial position.
     */
    String PROPKEY_INITIAL_POSITION = "robot:initialPosition";
    /**
     * The key of the vehicle property that specifies the default operating time.
     */
    String PROPKEY_OPERATING_TIME = "robot:operatingTime";
    /**
     * The key of the vehicle property that specifies which operation loads the load handling device.
     */
    String PROPKEY_LOAD_OPERATION = "robot:loadOperation";
    /**
     * The key of the vehicle property that specifies which operation unloads the load handling device.
     */
    String PROPKEY_UNLOAD_OPERATION = "robot:unloadOperation";
    /**
     * The key of the vehicle property that specifies the maximum acceleration of a vehicle.
     */
    String PROPKEY_ACCELERATION = "robot:acceleration";
    /**
     * The key of the vehicle property that specifies the maximum decceleration of a vehicle.
     */
    String PROPKEY_DECELERATION = "robot:deceleration";

}
