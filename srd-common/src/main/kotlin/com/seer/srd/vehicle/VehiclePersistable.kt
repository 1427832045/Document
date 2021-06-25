package com.seer.srd.vehicle

import org.bson.codecs.pojo.annotations.BsonId

data class VehiclePersistable(
    @BsonId val id: String = "",
    val categories: Set<String> = emptySet(),
    val integrationLevel: String? = null,
    val mockPosition: String? = null
)