package com.seer.srd.siemensSH

import com.mongodb.client.model.Filters
import com.seer.srd.Error400
import com.seer.srd.db.MongoDBManager
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import org.litote.kmongo.*
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.collections.toList

data class MappingReqBody(
    val createdOn: Instant = Instant.now(),
    val mappings: List<MaterialProductMapping> = listOf()
)

data class MaterialProductMapping(
    @BsonId val id: ObjectId = ObjectId(),
    val createdOn: Instant = Instant.now(),
    var product: String? = null,
    var material: String? = null,
    var processed: Boolean = false                   // 当前记录是否已经被使用
)

data class RemoveParams(
    val start: String? = null,
    val end: String? = null,
    val products: List<String> = listOf(),
    val materials: List<String> = listOf(),
    var startInstant: Instant? = null,
    var endInstant: Instant? = null
)

object MaterialProductMappingService {

    private val logger = LoggerFactory.getLogger(MaterialProductMappingService::class.java)

    private val c = MongoDBManager.collection<MaterialProductMapping>()

    fun listMappings(): List<MaterialProductMapping> {
        return c.find().toList()
    }

    fun getMappingsByMaterial(material: String): List<MaterialProductMapping> {
        return c.find(MaterialProductMapping::material eq material).toList()
    }

    fun getMappingsByProduct(product: String): List<MaterialProductMapping> {
        return c.find(MaterialProductMapping::product eq product).toList()
    }

    @Synchronized
    fun recordNewMappings(mappings: List<MaterialProductMapping>) {
        logger.info("record new mappings(product->material): ${mappings.map { "${it.product}->${it.material}" }}")

        val newMappings: MutableList<MaterialProductMapping> = mutableListOf()
        val repeated: MutableList<MaterialProductMapping> = mutableListOf()
        for (mapping in mappings) {
            val material = mapping.material ?: continue
            // 检查 material 是否已经被记录
            val mats = getMappingsByMaterial(material)
            if (mats.isEmpty()) newMappings.add(mapping)
            else repeated.add(mapping)
        }

        if (newMappings.isNotEmpty()) {
            logger.debug("new materials: ${newMappings.map { it.material }}")
            c.insertMany(newMappings)
        }

        if (repeated.isNotEmpty())
            logger.debug("recorded materials: ${repeated.map { it.material }}")
    }

    @Synchronized
    fun markRecordsUsedByMaterials(mats: List<String>, processed: Boolean, remark: String) {
        logger.debug("mark materials:$mats to used=$processed for $remark.")
        c.updateMany(MaterialProductMapping::material `in` mats, set(MaterialProductMapping::processed setTo processed))
    }

    @Synchronized
    fun removeMappings(params: RemoveParams): Long {
        val startInstant = params.startInstant
        val endInstant = params.endInstant
        val products = params.products
        val materials = params.materials

        val filters = arrayListOf<Bson>()
        if (startInstant != null) filters.add(MaterialProductMapping::createdOn gte startInstant)
        if (endInstant != null) filters.add(MaterialProductMapping::createdOn lt endInstant)
        if (products.isNotEmpty()) filters.add(MaterialProductMapping::product `in` products)
        if (materials.isNotEmpty()) filters.add(MaterialProductMapping::material `in` materials)
        val s = c.deleteMany(Filters.and(filters))
        val deletedCount = s.deletedCount
        logger.debug("remove $deletedCount records of material-product-mappings: ${params}.")
        return deletedCount
    }
}