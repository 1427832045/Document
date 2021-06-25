package org.opentcs.drivers.vehicle

import com.seer.srd.model.Triple
import com.seer.srd.route.routeConfig
import com.seer.srd.util.mapper
import com.seer.srd.vehicle.Vehicle
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.time.Instant

/**
 * An observable model of a vehicle's and its comm adapter's attributes.
 */
class VehicleProcessModel(val vehicleName: String) {
    
    private val pcs = PropertyChangeSupport(this)
    
    var isCommAdapterEnabled = false
        set(value) {
            val oldValue = isCommAdapterEnabled
            field = value
            firePropertyChange(Attribute.COMM_ADAPTER_ENABLED.name, oldValue, value)
        }
    
    var vehiclePosition: String? = null
        set(value) {
            val oldValue = vehiclePosition
            field = value
            firePropertyChange(Attribute.POSITION.name, oldValue, value)
        }
    
    var precisePosition: Triple? = null
        set(value) {
            val oldValue = precisePosition
            field = value
            firePropertyChange(Attribute.PRECISE_POSITION.name, oldValue, value)
        }
    
    var orientationAngle = Double.NaN
        set(value) {
            val oldValue = orientationAngle
            field = value
            firePropertyChange(Attribute.ORIENTATION_ANGLE.name, oldValue, value)
            
        }
    
    var energyLevel = -1
        set(value) {
            if (value in 0..100)
            {
                val oldValue = energyLevel
                field = value
                firePropertyChange(Attribute.ENERGY_LEVEL.name, oldValue, value)
            }
        }
    
    var loadHandlingDevices: List<LoadHandlingDevice> = ArrayList()
        set(value) {
            val oldValue = loadHandlingDevices
            field = value
            firePropertyChange(Attribute.LOAD_HANDLING_DEVICES.name, oldValue, value)
        }
    
    @Volatile
    var state = Vehicle.State.UNKNOWN
        set(value) {
            val oldState = state
            field = value
            firePropertyChange(Attribute.STATE.name, oldState, value)
        }
    
    var errorInfos: List<ErrorInfo> = ArrayList()
    
    var details = "{}"  // check update by checkVehiclesDetails function
        set(value) {
            field = value
            if (!routeConfig.newCommAdapter) {
                val infoJson = mapper.readTree(value)
                // errorInfo字段存在于RBK版本3.2.9，之前的版本没有该字段
                val errorIterator = infoJson["errorinfo"]?.asIterable() ?: emptyList()
                val errorInfoList = mutableListOf<ErrorInfo>()
                if (errorIterator.none()) {
                    // rbk3.2.8及之前
                    val fatals = infoJson["fatals"]?.asIterable() ?: emptyList()
                    val errors = infoJson["errors"]?.asIterable() ?: emptyList()
                    val warnings = infoJson["warnings"]?.asIterable() ?: emptyList()
                    fatals.forEach {
                        it.fieldNames().forEach { name ->
                            val code = name.toIntOrNull()
                            if (code != null) {
                                val timestamp = Instant.ofEpochSecond(it["$code"].asLong())
                                val count = it["times"]?.asInt() ?: 1
                                val message = it["desc"].asText()
                                errorInfoList.add(ErrorInfo(
                                    timestamp = timestamp,
                                    count = count,
                                    level = "fatals",
                                    message = "[$code] $message"
                                ))
                            }
                        }
                    }
                    errors.forEach {
                        it.fieldNames().forEach { name ->
                            val code = name.toIntOrNull()
                            if (code != null) {
                                val timestamp = Instant.ofEpochSecond(it["$code"].asLong())
                                val count = it["times"]?.asInt() ?: 1
                                val message = it["desc"].asText()
                                errorInfoList.add(ErrorInfo(
                                    timestamp = timestamp,
                                    count = count,
                                    level = "errors",
                                    message = "[$code] $message"
                                ))
                            }
                        }
                    }
                    warnings.forEach {
                        it.fieldNames().forEach { name ->
                            val code = name.toIntOrNull()
                            if (code != null) {
                                val timestamp = Instant.ofEpochSecond(it["$code"].asLong())
                                val count = it["times"]?.asInt() ?: 1
                                val message = it["desc"].asText()
                                errorInfoList.add(ErrorInfo(
                                    timestamp = timestamp,
                                    count = count,
                                    level = "warnings",
                                    message = "[$code] $message"
                                ))
                            }
                        }
                    }
                    this.errorInfos = errorInfoList
                } else {
                    // rbk3.2.9
                    errorIterator.forEach {
                        val code = it["code"]?.asInt() ?: 0
                        if (code != 0) {
                            val timestamp = Instant.parse(it["timestamp"].asText())
                            val count = it["count"]?.asInt() ?: 1
                            val level = when (it["level"].asText().toLowerCase()) {
                                "warning" -> "warnings"
                                "error" -> "errors"
                                "fatal" -> "fatals"
                                else -> "unknown"
                            }
                            val message = it["message"].asText()
                            errorInfoList.add(ErrorInfo(
                                timestamp = timestamp,
                                count = count,
                                level = level,
                                message = "[$code] $message"
                            ))
                        }
                    }
                    this.errorInfos = errorInfoList
                }
            }
        }

    var isDominating = false
    var owner = ""
        set(value) {
            val oldValue = owner
            field = value
            firePropertyChange(Attribute.OWNER.name, oldValue, value)
        }

    var blocked = false
        set(value) {
            val oldValue = blocked
            field = value
            firePropertyChange(Attribute.VEHICLE_BLOCKED.name, oldValue, value)
        }

    var relocStatus: Int = -1
        set(value) {
            val oldValue = relocStatus
            field = value
            firePropertyChange(Attribute.RELOC_STATUS.name, oldValue, value)
        }

    
    fun addPropertyChangeListener(listener: PropertyChangeListener?) {
        pcs.addPropertyChangeListener(listener)
    }
    
    fun removePropertyChangeListener(listener: PropertyChangeListener?) {
        pcs.removePropertyChangeListener(listener)
    }
    
    fun getName() = vehicleName
    
    fun commandEnqueued(enqueuedCommand: MovementCommand) {
        firePropertyChange(Attribute.COMMAND_ENQUEUED.name, null, enqueuedCommand)
    }
    
    fun commandSent(sentCommand: MovementCommand) {
        firePropertyChange(Attribute.COMMAND_SENT.name, null, sentCommand)
    }
    
    fun commandExecuted(executedCommand: MovementCommand) {
        firePropertyChange(Attribute.COMMAND_EXECUTED.name, null, executedCommand)
    }
    
    fun commandFailed(failedCommand: MovementCommand) {
        firePropertyChange(Attribute.COMMAND_FAILED.name, null, failedCommand)
    }
    
    private fun firePropertyChange(propertyName: String, oldValue: Any?, newValue: Any?) {
        pcs.firePropertyChange(propertyName, oldValue, newValue)
    }
    
    /**
     * Notification arguments to indicate some change.
     */
    enum class Attribute {
        /**
         * Indicates a change of the comm adapter's *enabled* setting.
         */
        COMM_ADAPTER_ENABLED,
        
        /**
         * Indicates a change of the vehicle's position.
         */
        POSITION,
        
        /**
         * Indicates a change of the vehicle's precise position.
         */
        PRECISE_POSITION,
        
        /**
         * Indicates a change of the vehicle's orientation angle.
         */
        ORIENTATION_ANGLE,
        
        /**
         * Indicates a change of the vehicle's energy level.
         */
        ENERGY_LEVEL,
        
        /**
         * Indicates a change of the vehicle's load handling devices.
         */
        LOAD_HANDLING_DEVICES,
        
        /**
         * Indicates a change of the vehicle's state.
         */
        STATE,
        
        /**
         * Indicates a new comm adapter event was published.
         */
        COMM_ADAPTER_EVENT,
        
        /**
         * Indicates a command was enqueued.
         */
        COMMAND_ENQUEUED,
        
        /**
         * Indicates a command was sent.
         */
        COMMAND_SENT,
        
        /**
         * Indicates a command was executed successfully.
         */
        COMMAND_EXECUTED,
        
        /**
         * Indicates a command failed.
         */
        COMMAND_FAILED,

        /**
         * Vehicle's control owner.
         */
        OWNER,

        /**
         * If vehicle is blocked by obstacle.
         */
        VEHICLE_BLOCKED,

        /**
         * Vehicle's relocation status.
         */
        RELOC_STATUS
    }
    
    class ErrorInfo(
        var timestamp: Instant, var count: Int, var level: String, var message: String
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            
            other as ErrorInfo
            
            if (timestamp != other.timestamp) return false
            if (count != other.count) return false
            if (level != other.level) return false
            if (message != other.message) return false
            
            return true
        }
        
        override fun hashCode(): Int {
            var result = timestamp.hashCode()
            result = 31 * result + count
            result = 31 * result + level.hashCode()
            result = 31 * result + message.hashCode()
            return result
        }
        
        override fun toString(): String {
            return "ErrorInfo(timestamp=$timestamp, count=$count, level='$level', message='$message')"
        }
        
        
    }
    
}