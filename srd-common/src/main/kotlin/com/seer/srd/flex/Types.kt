package com.seer.srd.flex

import org.bson.codecs.pojo.annotations.BsonId
import org.bson.codecs.pojo.annotations.BsonIgnore
import org.bson.types.ObjectId
import java.time.Instant

data class FlexProcedure(
    @BsonId val id: String,
    val defId: ObjectId,
    val directives: Map<ObjectId, FlexDirectiveExecution> = emptyMap(),
    val tags: Set<String> = emptySet(),
    val state: Int = FlexProcedureState.Created,
    val createdOn: Instant = Instant.now(),
    val finishedOn: Instant? = null,
    val variable: Map<String, Any> = emptyMap()
)

object FlexProcedureState {
    val Created = 0
    val Executing = 100
    val Pausing = 400
    val Succeeded = 1000
    val Failed = 2000
    val Aborted = 3000
    val Rollbacked = 4000
}

data class FlexDirectiveExecution(
    val configId: ObjectId,
    val state: Int = FlexDirectiveState.Created,
    val startedOn: Instant? = null,
    val endedOn: Instant? = null,
    val executingTime: Long? = null
) {

    fun isFinalState(): Boolean {
        return state >= FlexDirectiveState.Succeeded
    }
}

object FlexDirectiveState {
    val Created = 0
    val Executing = 100
    val Succeeded = 1000
    val Failed = 2000
    val Aborted = 3000
    val Rollbacked = 4000
}

data class FlexProcedureDef(
    @BsonId val id: ObjectId,
    val name: String,
    val label: String,
    val rootDirective: FlexDirectiveConfig
) {
    @BsonIgnore
    val configMap: Map<ObjectId, FlexDirectiveConfig>

    init {
        val map: MutableMap<ObjectId, FlexDirectiveConfig> = HashMap()
        addDirectiveToMap(rootDirective, map)
        configMap = map
    }

    private fun addDirectiveToMap(directive: FlexDirectiveConfig, map: MutableMap<ObjectId, FlexDirectiveConfig>) {
        map[directive.id] = directive
        for (child in directive.children) addDirectiveToMap(child, map)
    }
}

data class FlexDirectiveConfig(
    val type: String,
    val arguments: Map<String, String> = emptyMap(),
    val returnName: String = "",
    val children: List<FlexDirectiveConfig> = emptyList(),
    val remark: String = "",
    val id: ObjectId = ObjectId()
)

class FailedToExecutingDirective(cause: Throwable) : Exception(cause)