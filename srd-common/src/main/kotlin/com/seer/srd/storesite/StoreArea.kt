package com.seer.srd.storesite

import com.seer.srd.Error400
import com.seer.srd.db.MongoDBManager.collection
import org.bson.codecs.pojo.annotations.BsonId
import org.litote.kmongo.eq
import org.litote.kmongo.findOne

data class StoreArea(
    @BsonId var id: String = "",
    var type: String = "",
    var level: Int = 0,
    var locked: Boolean = false,
    var emptySitesNum: Int = 0,
    var loadType: String = ""
)

fun getStoreAreaById(id: String): StoreArea? {
    return collection<StoreArea>().findOne(StoreArea::id eq id)
}

fun getExistedAreaSiteById(id: String): StoreArea {
    return getStoreAreaById(id) ?: throw Error400("NoSuchStoreArea", "No such store area")
}

//fun lockEmptySiteOfAreaByLoadType(areaId: String, loadType: String) {
//    val db = getMainMongoDB()
//    val c = db.getCollection<StoreArea>("StoreArea")
//    c.updateOne(and(eq("_id", areaId), gt("emptySitesNum", 0), )
//    const sql = "update StoreArea set emptySitesNum=emptySitesNum-1, loadType=? "
//    +"where id=? and emptySitesNum>0 and (loadType = '' loadType is null or loadType =?)"
//    const[{ changedRows }] = await ss . write (sql, [loadType, areaId, loadType])
//    if (changedRows !== 1) throw new NoMatchedArea ("库区状态不满足要求")
//    return lockEmptySiteOfArea(ss, areaId)
//}

//fun lockEmptySiteOfArea(areaId: String) {
//    const relations = await storeSiteAreaRelationRepo.find(ss, { criteria: objectCriteria({ areaId }) })
//    const siteIds = relations . map (r => r.siteId)
//    const site = await storeSiteRepo.findOne(ss, {
//        criteria: andCriteria([
//        singleCriteria("id", "in", siteIds), singleCriteria("filled", "==", 0), singleCriteria("locked", "==", 0)
//        ])
//    })
//    if (!site) throw new NoEmptyStoreSite (`库区[${areaId}]无可用空库位`)
//    await lockSiteOnlyIfNotLockAndEmpty (ss, site.id)
//    return site
//}
