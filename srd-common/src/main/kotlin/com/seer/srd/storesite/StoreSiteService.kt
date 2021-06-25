package com.seer.srd.storesite

import com.mongodb.client.model.Filters.and
import com.seer.srd.*
import com.seer.srd.db.MongoDBManager.collection
import com.seer.srd.domain.ChangeTrace
import com.seer.srd.eventbus.EventBus.onStoreSitesChanged
import org.bson.Document
import org.bson.codecs.pojo.annotations.BsonId
import org.litote.kmongo.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.toList

data class StoreSite(
    @BsonId val id: String = "",
    val type: String = "",
    val filled: Boolean = false,
    val content: String = "",
    val locked: Boolean = false,
    val label: String = "",
    val disabled: Boolean = false,
    val lockedBy: String = ""
)

enum class StandardContent {
    EmptyTray // 空托盘
}

object StoreSiteService {

    private val logger = LoggerFactory.getLogger(StoreSiteService::class.java)

    private val storeSites: MutableMap<String, StoreSite> = ConcurrentHashMap()

    fun loadStoreSites() {
        storeSites.clear()
        val list = collection<StoreSite>().find().toList()
        for (ss in list) storeSites[ss.id] = ss
    }

    fun listStoreSites(): List<StoreSite> {
        return storeSites.values.sortedBy { it.id }.toList()
    }

    fun getStoreSiteById(id: String): StoreSite? {
        return storeSites[id]
    }

    fun getExistedStoreSiteById(id: String): StoreSite {
        return getStoreSiteById(id) ?: throw NoSuchStoreSite(id)
    }

    @Synchronized
    fun replaceStoreSites(sites: List<StoreSite>) {
        try {
            val cStoreSite = collection<StoreSite>()
            cStoreSite.deleteMany(Document())
            cStoreSite.insertMany(sites)
            loadStoreSites()

            onStoreSitesChanged(emptyList(), "Replace") // 发空
        } catch (err: Exception) {
            logger.error("replaceStoreSites error for ${err.message}!")
        }
    }

    // 锁定库位设置lockedBy = taskId，释放库位设置lockedBy = ""
    @Synchronized
    fun changeSiteLocked(siteId: String, locked: Boolean, taskId: String, remark: String) {
        val site = getExistedStoreSiteById(siteId)
        collection<StoreSite>().updateOne(StoreSite::id eq siteId, set(StoreSite::locked setTo locked, StoreSite::lockedBy setTo taskId))
        val newSite = site.copy(locked = locked, lockedBy = taskId)
        storeSites[siteId] = newSite
        onStoreSitesChanged(listOf(ChangeTrace(site, newSite)), remark)
    }

    // 锁定库位设置lockedBy = taskId，释放库位设置lockedBy = ""
    @Synchronized
    fun changeSitesLockedByIds(siteIds: List<String>, locked: Boolean, taskId: String, remark: String) {
        val sites = siteIds.map { getExistedStoreSiteById(it) }
        collection<StoreSite>().updateMany(StoreSite::id `in` siteIds, set(StoreSite::locked setTo locked, StoreSite::lockedBy setTo taskId))
        val changes = sites.map {
            val newSite = it.copy(locked = locked, lockedBy = taskId)
            storeSites[it.id] = newSite
            ChangeTrace(it, newSite)
        }
        onStoreSitesChanged(changes, remark)
    }

    // 锁定库位设置lockedBy = taskId，释放库位设置lockedBy = ""
    @Synchronized
    fun lockSiteIfNotLock(siteId: String, taskId: String, remark: String) {
        val site = getExistedStoreSiteById(siteId)
        val r = collection<StoreSite>().updateOne(
            and(StoreSite::id eq siteId, StoreSite::locked eq false),
            set(StoreSite::locked setTo true, StoreSite::lockedBy setTo taskId)
        )
        if (r.modifiedCount != 1L) throw FailedLockStoreSite(siteId)
        val newSite = site.copy(locked = true, lockedBy = taskId)
        storeSites[siteId] = newSite
        onStoreSitesChanged(listOf(ChangeTrace(site, newSite)), remark)
    }

    // 锁定库位设置lockedBy = taskId，释放库位设置lockedBy = ""
    @Synchronized
    fun lockSiteIfNotLockAndEmpty(siteId: String, taskId: String, remark: String) {
        val site = getExistedStoreSiteById(siteId)
        val r = collection<StoreSite>().updateOne(
            and(StoreSite::id eq siteId, StoreSite::locked eq false, StoreSite::filled eq false),
            set(StoreSite::locked setTo true, StoreSite::lockedBy setTo taskId)
        )
        if (r.modifiedCount != 1L) throw FailedLockStoreSite(siteId)
        val newSite = site.copy(locked = true, lockedBy = taskId)
        storeSites[siteId] = newSite
        onStoreSitesChanged(listOf(ChangeTrace(site, newSite)), remark)
    }

    // 锁定库位设置lockedBy = taskId，释放库位设置lockedBy = ""
    @Synchronized
    fun lockEmptySiteOfTypeAndUnlocked(type: String, taskId: String, remark: String): StoreSite {
        val site = collection<StoreSite>()
            .findOne(and(StoreSite::type eq type, StoreSite::filled eq false, StoreSite::locked eq false))
            ?: throw NoEmptyStoreSite(type)
        lockSiteIfNotLockAndEmpty(site.id, taskId, remark)
        return site
    }

    // 锁定库位设置lockedBy = taskId，释放库位设置lockedBy = ""
    @Synchronized
    fun lockFirstSiteWithEmptyTray(ids: List<String>, taskId: String, remark: String): StoreSite {
        val filter = and(
            StoreSite::filled eq true, StoreSite::locked eq false, StoreSite::content eq StandardContent.EmptyTray.name,
            StoreSite::id `in` ids
        )
        val site = collection<StoreSite>().findOne(filter) ?: throw NoMatchSite("无带有空托盘的库位")
        lockSiteIfNotLock(site.id, taskId, remark)
        return site
    }

    @Synchronized
    fun unlockSiteIfLocked(siteId: String, remark: String) {
        val site = getExistedStoreSiteById(siteId)
        val r = collection<StoreSite>().updateOne(
            and(StoreSite::id eq siteId, StoreSite::locked eq true),
            set(StoreSite::locked setTo false, StoreSite::lockedBy setTo "")
        )
        if (r.modifiedCount != 1L) throw FailedUnlockStoreSite(siteId)
        val newSite = site.copy(locked = false, lockedBy = "")
        storeSites[siteId] = newSite
        onStoreSitesChanged(listOf(ChangeTrace(site, newSite)), remark)
    }

    @Synchronized
    fun changeSiteFilled(siteId: String, filled: Boolean, remark: String) {
        val site = getExistedStoreSiteById(siteId)
        val update = if (filled) set(StoreSite::filled setTo true)
        else set(StoreSite::filled setTo false, StoreSite::content setTo "")
        collection<StoreSite>().updateOne(StoreSite::id eq siteId, update)
        val newSite = if (filled) site.copy(filled = true)
        else site.copy(filled = false, content = "")
        storeSites[siteId] = newSite
        onStoreSitesChanged(listOf(ChangeTrace(site, newSite)), remark)
    }

    @Synchronized
    fun changeSiteEmptyAndRetainContent(siteId: String, remark: String) {
        val site = getExistedStoreSiteById(siteId)
        val update = set(StoreSite::filled setTo false)
        collection<StoreSite>().updateOne(StoreSite::id eq siteId, update)
        val newSite = site.copy(filled = false)
        storeSites[siteId] = newSite
        onStoreSitesChanged(listOf(ChangeTrace(site, newSite)), remark)
    }

    // 锁定库位设置lockedBy = taskId，释放库位设置lockedBy = ""
    @Synchronized
    fun releaseOwnedSites(taskId: String, remark: String) {
        val sites = storeSites.filter { it.value.lockedBy == taskId && it.value.locked }.values.toList()
        val update = set(StoreSite::lockedBy setTo "", StoreSite::locked setTo false)
        collection<StoreSite>().updateMany(StoreSite::id `in` sites.map { it.id }, update)
        val changes = sites.map {
            val newSite = it.copy(lockedBy = "", locked = false)
            storeSites[it.id] = newSite
            ChangeTrace(it, newSite)
        }
        onStoreSitesChanged(changes, remark)
    }

    @Synchronized
    fun changeSitesFilledByIds(siteIds: List<String>, filled: Boolean, remark: String) {
        val sites = siteIds.map { getExistedStoreSiteById(it) }
        val update = if (filled) set(StoreSite::filled setTo true)
        else set(StoreSite::filled setTo false, StoreSite::content setTo "")
        collection<StoreSite>().updateMany(StoreSite::id `in` siteIds, update)
        val changes = sites.map {
            val newSite = if (filled) it.copy(filled = true)
            else it.copy(filled = false, content = "")
            storeSites[it.id] = newSite
            ChangeTrace(it, newSite)
        }
        onStoreSitesChanged(changes, remark)
    }

    @Synchronized
    fun setEmptyIfFilledAndUnlocked(siteId: String, remark: String) {
        val site = getExistedStoreSiteById(siteId)
        val r = collection<StoreSite>().updateOne(
            and(StoreSite::id eq siteId, StoreSite::locked eq false, StoreSite::filled eq true),
            set(StoreSite::filled setTo false)
        )
        if (r.modifiedCount != 1L) throw FailedChangedStoreSiteState(siteId)
        val newSite = site.copy(filled = false)
        storeSites[siteId] = newSite
        onStoreSitesChanged(listOf(ChangeTrace(site, newSite)), remark)
    }

    @Synchronized
    fun changeSiteWithEmptyTray(siteId: String, remark: String) {
        val site = getExistedStoreSiteById(siteId)
        collection<StoreSite>().updateOne(
            StoreSite::id eq siteId,
            set(StoreSite::filled setTo true, StoreSite::content setTo StandardContent.EmptyTray.name)
        )
        val newSite = site.copy(content = StandardContent.EmptyTray.name)
        storeSites[siteId] = newSite
        onStoreSitesChanged(listOf(ChangeTrace(site, newSite)), remark)
    }

    /** 假设某类型的库位的内容都是一样的。如果所有库位都为空，返回 null。 */
    fun getSiteContentOfType(type: String): String? {
        val first = collection<StoreSite>().find(StoreSite::type eq type).first { site -> site.content.isNotBlank() }
        return first?.content
    }

    @Synchronized
    fun setSiteContent(siteId: String, content: String, remark: String) {
        val site = getExistedStoreSiteById(siteId)
        collection<StoreSite>().updateOne(StoreSite::id eq siteId, set(StoreSite::content setTo content))
        val newSite = site.copy(content = content)
        storeSites[siteId] = newSite
        onStoreSitesChanged(listOf(ChangeTrace(site, newSite)), remark)
    }

    @Synchronized
    fun setSiteContentWithFilled(siteId: String, content: String, remark: String) {
        val site = getExistedStoreSiteById(siteId)
        val update = set(StoreSite::filled setTo true, StoreSite::content setTo content)
        collection<StoreSite>().updateOne(StoreSite::id eq siteId, update)
        val newSite = site.copy(filled = true, content = content)
        storeSites[siteId] = newSite
        onStoreSitesChanged(listOf(ChangeTrace(site, newSite)), remark)
    }

    @Synchronized
    fun setSiteLabel(siteId: String, label: String, remark: String) {
        val site = getExistedStoreSiteById(siteId)
        collection<StoreSite>().updateOne(StoreSite::id eq siteId, set(StoreSite::label setTo label))
        val newSite = site.copy(label = label)
        storeSites[siteId] = newSite
        onStoreSitesChanged(listOf(ChangeTrace(site, newSite)), remark)
    }
    
    @Synchronized
    fun replaceSitesAndRetainOthers(sites: List<StoreSite>, remark: String) {
        val traces = mutableListOf<ChangeTrace<StoreSite>>()
        sites.forEach { newSite ->
            val site = StoreSiteService.getExistedStoreSiteById(newSite.id)
            collection<StoreSite>().replaceOne(StoreSite::id eq site.id, newSite)
            traces.add(ChangeTrace(site, newSite))
            storeSites[site.id] = newSite
        }
        onStoreSitesChanged(traces, remark)
    }

}

