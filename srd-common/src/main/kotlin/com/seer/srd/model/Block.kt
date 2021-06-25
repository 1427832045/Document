package com.seer.srd.model

data class Block(
    val name: String,
    var properties: Map<String, String>,
    val type: BlockType,
    val members: Set<String>
) {

    override fun toString(): String {
        return "Block(name='$name')"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Block

        if (name != other.name) return false

        return true
    }

    override fun hashCode(): Int {
        return name.hashCode()
    }

}

