package org.opentcs.drivers.vehicle

/**
 * Describes a single load handling device on a vehicle.
 */
class LoadHandlingDevice(
    val label: String, // 设备名
    val isFull: Boolean     // 此设备是否已被填充至其最大负载能力
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as LoadHandlingDevice

        if (label != other.label) return false
        if (isFull != other.isFull) return false

        return true
    }

    override fun hashCode(): Int {
        var result = label.hashCode()
        result = 31 * result + isFull.hashCode()
        return result
    }

    override fun toString(): String {
        return "LoadHandlingDevice(label='$label', full=$isFull)"
    }

}