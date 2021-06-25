package com.seer.srd.moshi

import com.seer.srd.db.MongoDBManager
import com.seer.srd.storesite.StoreSite
import com.seer.srd.storesite.StoreSiteService
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.eq
import org.litote.kmongo.findOne
import org.litote.kmongo.set
import org.litote.kmongo.setTo

object Services {
  fun getFromSites(): List<String> {
    return MongoDBManager.collection<StoreSite>().find().toList().filter {
      !it.id.contains("T-")
    }.map { it.id }
  }

  fun getToSites(): List<String> {
    return MongoDBManager.collection<StoreSite>().find().toList().map { it.id }
  }

  fun getCabinetBySiteId(siteId: String): Cabinet? {
    CUSTOM_CONFIG.areaToCabinet.forEach {
      val cabinet = it.value
      if (cabinet.siteIdToAddress.containsKey(siteId))
        return it.value
    }
    return null
  }

}

data class SiteFireTask(
    @BsonId val id: ObjectId = ObjectId(),
    val siteId: String = "",
    val fireTask: MutableList<String> = mutableListOf()
)

data class TaskReq(
    val workStation: String = "",
    val workType: String = "",
    val params: LinkedHashMap<String, String> = LinkedHashMap()
)