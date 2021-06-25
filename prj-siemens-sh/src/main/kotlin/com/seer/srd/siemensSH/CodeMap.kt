package com.seer.srd.siemensSH

import com.mongodb.client.model.Sorts
import com.seer.srd.BusinessError
import com.seer.srd.db.MongoDBManager
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.eq
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneOffset

data class CodeMapResponse(
    @BsonId val id: ObjectId = ObjectId(),
    val createdOn: Instant = Instant.now(),
    val error: Boolean = false,
    val message: String = "no message.",
    val codeMap: List<CodeMap> = listOf()
)

data class CodeMap(
    val codeZ8: String = "",
    val code800: String = ""
)

object CodeMapService {

    private val logger = LoggerFactory.getLogger(CodeMapService::class.java)

    private val c = MongoDBManager.collection<CodeMapResponse>()

    @Volatile
    private var latestCodeMap: CodeMapResponse = CodeMapResponse()

    init {
        updateLatestCodeMap()
    }

    private fun getDate(instant: Instant): String {
        return instant.atOffset(ZoneOffset.ofHours(8)).toString().substring(0, 10)
    }

    fun getLatestCodeMap(): CodeMapResponse {
        if (latestCodeMap.codeMap.isEmpty()) updateLatestCodeMap()

        return latestCodeMap
    }

    @Synchronized
    fun persistIntraDayUniqueCodeMap(map: CodeMapResponse) {
        val list = c.find(CodeMapResponse::error eq false)
            .sort(Sorts.orderBy(Sorts.descending("createdOn"))).toList()
        if (list.isEmpty()) c.insertOne(map)
        else {
            val latest = list.first()
            // 只记录当天未被记录的数据
            if (getDate(latest.createdOn) != getDate(map.createdOn)
                && latest.codeMap != map.codeMap) {
                c.insertOne(map)
                // 记录成功之后，更新内存数据
                updateLatestCodeMap()
                return
            }
            logger.info("放弃记录相同的数据")
        }
    }

    private fun updateLatestCodeMap() {
        val validMap = c.find(CodeMapResponse::error eq false)
            .sort(Sorts.orderBy(Sorts.descending("createdOn"))).toList()
        val message = "${getDate(Instant.now())}没有可用的 Z8码 和 800码 的映射表，请在PDA上刷新！"
        if (validMap.isEmpty()) throw BusinessError(message)

        val latest = validMap.first()

        // 判断记录的时效性
        logger.info("${getDate(latest.createdOn)}, ${getDate(Instant.now())}")
        if (getDate(latest.createdOn) != getDate(Instant.now())) throw BusinessError(message)

        latestCodeMap = latest
    }
}