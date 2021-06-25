package com.seer.srd.route.model

import org.bson.codecs.pojo.annotations.BsonId

data class PlantModelDO(
    @BsonId val id: String,
    val lockedPaths: Set<String> = emptySet()
)