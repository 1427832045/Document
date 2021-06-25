package com.seer.srd.model

data class Group(
    val name: String,
    var properties: Map<String, String>,
    val members: Set<String>
) {

    override fun toString(): String {
        return "Group(name='$name')"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Group

        if (name != other.name) return false

        return true
    }

    override fun hashCode(): Int {
        return name.hashCode()
    }

}