package com.seer.srd.flex

import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager.collection
import com.seer.srd.flex.directive.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.bson.types.ObjectId
import org.litote.kmongo.setTo
import org.litote.kmongo.updateOneById
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

object FlexProcedureManager {

    private val proceduresCache: MutableMap<String, FlexProcedure> = ConcurrentHashMap()

    val defs: MutableMap<ObjectId, FlexProcedureDef> = ConcurrentHashMap()

    private val directives: MutableMap<String, FlexDirective> = ConcurrentHashMap()

    private val logger = LoggerFactory.getLogger(FlexProcedureManager::class.java)

    init {
        directives[SequentialExecution::class.simpleName!!] = SequentialExecution()
        directives[ParallelExecution::class.simpleName!!] = ParallelExecution()
        directives[DelayExecution::class.simpleName!!] = DelayExecution()
        directives[PrintExecution::class.simpleName!!] = PrintExecution()
        directives[RepeatNumExecution::class.simpleName!!] = RepeatNumExecution()
    }

    fun newFlexProcedure(fp: FlexProcedure) {
        val procedure = prepareDirectiveExecutions(fp)

        collection<FlexProcedure>().insertOne(procedure)
        proceduresCache[procedure.id] = procedure

        GlobalScope.launch { runFlexProcedure(procedure) }
    }

    private fun prepareDirectiveExecutions(procedure: FlexProcedure): FlexProcedure {
        val def = getProcedureDef(procedure)
        val executionMap: MutableMap<ObjectId, FlexDirectiveExecution> = HashMap()
        prepareDirectiveExecutions(def.rootDirective, executionMap)
        val newProcedure = procedure.copy(directives = executionMap)
        proceduresCache[newProcedure.id] = newProcedure
        return newProcedure
    }

    private fun prepareDirectiveExecutions(
        directiveConfig: FlexDirectiveConfig,
        executionMap: MutableMap<ObjectId, FlexDirectiveExecution>
    ) {
        executionMap[directiveConfig.id] = FlexDirectiveExecution(directiveConfig.id)
        for (childConfig in directiveConfig.children) {
            prepareDirectiveExecutions(childConfig, executionMap)
        }
    }

    private suspend fun runFlexProcedure(fp: FlexProcedure) {
        logger.debug("Run FlexProcedure start $fp.id")
        val def = getProcedureDef(fp)
        val de = getFlexDirectiveExecution(fp.id, def.rootDirective.id)
        runFlexDirective(de, fp.id, def)
        logger.debug("Run FlexProcedure end $fp.id")
    }

    private fun getProcedureDef(fp: FlexProcedure): FlexProcedureDef {
        return defs[fp.defId] ?: throw BusinessError("No FlexProcedureDef ${fp.defId}")
    }

    suspend fun runFlexDirective(de: FlexDirectiveExecution, procedureId: String, def: FlexProcedureDef) {
        if (de.isFinalState()) return

        logger.debug("Run FlexDirective start $procedureId/${de.configId}")
        val procedure = getFlexProcedureById(procedureId)
        val dc = def.configMap[de.configId] ?: throw BusinessError("No FlexDirectiveConfig ${procedure.defId}")

        val directive = directives[dc.type] ?: throw BusinessError("No FlexDirective ${dc.type}")

        try {
            directive.execute(de, dc, procedureId, def)
            updateFlexDirectiveStateToFinal(FlexProcedureState.Succeeded, de, procedureId)
        } catch (e: Exception) {
            updateFlexDirectiveStateToFinal(FlexProcedureState.Failed, de, procedureId)
            if (e !is FailedToExecutingDirective) { // 防止每层都抛异常，只有最内层抛
                logger.error("runFlexDirective", e)
                throw FailedToExecutingDirective(e)
            } else {
                throw e
            }
        }
        logger.debug("Run FlexDirective end $procedureId/${de.configId}")
    }

    private fun getFlexProcedureById(id: String): FlexProcedure {
        return proceduresCache[id] ?: throw BusinessError("No FlexProcedure $id")
    }

    fun getFlexDirectiveExecution(procedureId: String, configId: ObjectId): FlexDirectiveExecution {
        val procedure = getFlexProcedureById(procedureId)
        return procedure.directives[configId]
            ?: throw BusinessError("No FlexDirectiveExecution $procedureId/${configId.toHexString()}")
    }

    private fun updateFlexDirectiveStateToFinal(state: Int, de: FlexDirectiveExecution, procedureId: String) {
        val endedOn = Instant.now()
        val executingTime = if (de.startedOn != null) Duration.between(endedOn, de.startedOn).toMillis() else 0L
        val newExecution = de.copy(state = state, endedOn = endedOn, executingTime = executingTime)
        updateFlexDirectiveExecution(newExecution, procedureId)
    }

    fun updateFlexDirectiveExecution(de: FlexDirectiveExecution, procedureId: String) {
        val procedure = getFlexProcedureById(procedureId)
        val directives = procedure.directives.toMutableMap()
        directives[de.configId] = de
        val newProcedure = procedure.copy(directives = directives)
        proceduresCache[newProcedure.id] = newProcedure
        collection<FlexProcedure>().updateOneById(newProcedure.id, FlexProcedure::directives setTo directives)
    }

}