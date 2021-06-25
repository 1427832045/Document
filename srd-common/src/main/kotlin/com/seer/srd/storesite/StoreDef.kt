
package com.seer.srd.storesite

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.mongodb.client.model.ReplaceOptions
import com.seer.srd.db.MongoDBManager
import com.seer.srd.db.MongoDBManager.collection
import org.bson.codecs.pojo.annotations.BsonId
import org.litote.kmongo.eq
import org.litote.kmongo.findOne
import org.litote.kmongo.updateOneById
import java.util.*
import kotlin.collections.ArrayList


private const val DEFAULT_ID = "default"

@JsonIgnoreProperties(ignoreUnknown = true)
data class StoreConfig(
    @BsonId var id: String = DEFAULT_ID,
    var types: List<StoreType> = emptyList()
)

data class StoreType(
    var name: String = "",
    var label: String = "",
    var sites: List<StoreSiteDef> = emptyList()
)

data class StoreSiteDef(
    var id: String = "",
    var label: String = ""
)

fun getStoreConfig(): StoreConfig {
    return collection<StoreConfig>().findOne(StoreConfig::id eq DEFAULT_ID) ?: StoreConfig()
}

fun changeStoreConfig(sc: StoreConfig) {
    sc.id = DEFAULT_ID

    collection<StoreConfig>().replaceOne(StoreConfig::id eq DEFAULT_ID, sc, ReplaceOptions().upsert(true))

    val curSites = StoreSiteService.listStoreSites()
    val curSiteIds = curSites.map { it.id }
    val sites = mutableListOf<StoreSite>()

    for (type in sc.types) {
        for (siteDef in type.sites) {
            if (siteDef.id !in curSiteIds) {
                sites.add(StoreSite(id = siteDef.id, type = type.name, label = siteDef.label))
            } else {
                sites.add(curSites.findLast { it.id == siteDef.id }!!.copy(type = type.name, label = siteDef.label))
            }
        }
    }
    StoreSiteService.replaceStoreSites(sites)
}