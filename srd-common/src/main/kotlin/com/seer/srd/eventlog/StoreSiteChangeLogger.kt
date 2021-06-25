package com.seer.srd.eventlog

import com.mongodb.client.model.Sorts
import com.seer.srd.db.MongoDBManager.collection
import com.seer.srd.domain.ChangeTrace
import com.seer.srd.domain.PagingResult
import com.seer.srd.http.getPageNo
import com.seer.srd.http.getPageSize
import com.seer.srd.storesite.StoreSite
import io.javalin.http.Context
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import java.time.Instant

object StoreSiteChangeLogger {

    fun onStoreSitesChanged(changes: List<ChangeTrace<StoreSite>>, remark: String, timestamp: Instant) {
        val records = changes.map { StoreSiteChange(ObjectId(), it.from, it.to, remark, timestamp) }
        collection<StoreSiteChange>().insertMany(records)
    }

    fun handleListStoreSiteChanges(ctx: Context) {
        val pageNo = getPageNo(ctx)
        val pageSize = getPageSize(ctx)
        val c = collection<StoreSiteChange>()
        val total = c.countDocuments()
        val page = c.find()
            .sort(Sorts.orderBy(Sorts.descending("timestamp")))
            .limit(pageSize).skip((pageNo - 1) * pageSize)
            .toList()
        ctx.json(PagingResult(total, page, pageNo, pageSize))
    }

}

data class StoreSiteChange(
    @BsonId val id: ObjectId,
    val from: StoreSite,
    val to: StoreSite,
    val remark: String,
    val timestamp: Instant
)