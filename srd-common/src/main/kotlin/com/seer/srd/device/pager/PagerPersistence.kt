package com.seer.srd.device.pager

import com.seer.srd.db.MongoDBManager.collection
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.eq
import org.litote.kmongo.set
import org.litote.kmongo.setTo
import java.util.concurrent.ConcurrentHashMap

data class PersistedPager(
    @BsonId val id: ObjectId = ObjectId(),
    val name: String,
    var taskId: String = "",
    var signalType: Int = SignalType.TaskSuccess
)

object PagerPersistenceService {
    private val persistedPagers: MutableMap<String, PersistedPager> = ConcurrentHashMap()

    private val c = collection<PersistedPager>()

    init {
        loadPersistedPagers()
    }

    private fun loadPersistedPagers() {
        c.find().forEach { persistedPagers[it.name] = it }
    }

    fun getPersistedPagerByName(name: String): PersistedPager {
        return persistedPagers[name] ?: PersistedPager(name = name)
    }

    fun updatePersistedPager(update: PersistedPager) {
        val name = update.name
        if (persistedPagers[name] == null) {
            persistedPagers[name] = PersistedPager(name = name)
            c.insertOne(persistedPagers[name]!!)
        } else {
            persistedPagers[name]!!.taskId = update.taskId
            persistedPagers[name]!!.signalType = update.signalType
            onPersistedPagerChanged(persistedPagers[name]!!)
        }
    }

    private fun onPersistedPagerChanged(change: PersistedPager) {
        c.updateOne(PersistedPager::id eq change.id, set(
            PersistedPager::taskId setTo change.taskId,
            PersistedPager::signalType setTo change.signalType
        ))
    }
}

