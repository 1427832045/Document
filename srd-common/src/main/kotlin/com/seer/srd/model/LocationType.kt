package com.seer.srd.model

data class LocationType(
    val name: String,
    var properties: Map<String, String>,
    val allowedOperations: List<String> = emptyList()
) {

    fun isAllowedOperation(operation: String): Boolean {
        return allowedOperations.contains(operation)
    }

    override fun toString(): String {
        return "LocationType(name='$name')"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as LocationType

        if (name != other.name) return false

        return true
    }

    override fun hashCode(): Int {
        return name.hashCode()
    }

}