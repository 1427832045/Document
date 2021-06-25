package com.seer.srd.db

import com.mongodb.client.model.ReplaceOptions
import com.seer.srd.SystemError
import com.seer.srd.db.MongoDBManager.collection
import com.seer.srd.db.MongoDBManager.getGridFSBucket
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.eq
import org.litote.kmongo.findOne
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * 版本化的文件。每写一次创建一个新文件，版本加一，老版本文件保留。自动删除过老的版本。
 *
 * 有并发争用风险！！特别是多个进程/程序管理同一个目录时。
 */

data class VersionedFileIndex(
    @BsonId val id: ObjectId = ObjectId(),
    val name: String,
    var no: Int = -1,
    var activeNo: Int = -1,
    val meta: MutableMap<String, Map<String, Any?>> = HashMap()
)

fun addVersionedFile(name: String, content: String, extra: Map<String, Any?>?): Int {
    val index = readIndex(name)
    val no = index.no + 1

    val filename = "$name-$no"
    val bucket = getGridFSBucket()
    val bytes = content.toByteArray(Charset.forName("UTF-8"))
    val fileId = bucket.uploadFromStream(filename, ByteArrayInputStream(bytes))

    val meta = if (extra != null) HashMap(extra) else HashMap()
    meta["_fileId"] = fileId
    meta["_fileSize"] = bytes.size
    index.meta[no.toString()] = meta

    index.no++
    index.activeNo = no
    updateIndex(index)

    return no
}

fun loadVersionedFile(name: String, version: Int): String {
    val index = readIndex(name)
    val meta = index.meta[version.toString()] ?: throw SystemError("No Meta")
    val fileId = meta["_fileId"] as? ObjectId? ?: throw SystemError("No FileId")
    val fileSize = meta["_fileSize"] as? Int? ?: throw SystemError("No FileId")

    val bucket = getGridFSBucket()
    val s = ByteArrayOutputStream(fileSize)
    bucket.downloadToStream(fileId, s)
    return String(s.toByteArray(), Charset.forName("UTF-8"))
}

fun activateVersion(name: String, no: Int): VersionedFileIndex {
    val index = readIndex(name)
    index.activeNo = no
    updateIndex(index)
    return index
}

fun remove(name: String, no: Int) {
    val index = readIndex(name)
    if (index.activeNo == no) index.activeNo = -1 // todo

    val meta = index.meta[no.toString()] ?: throw SystemError("No Meta")
    val fileId = meta["_fileId"] as? ObjectId? ?: throw SystemError("No FileId")

    val bucket = getGridFSBucket()
    bucket.delete(fileId)

    index.meta.remove(no.toString())
    updateIndex(index)
}

fun readIndex(name: String): VersionedFileIndex {
    return collection<VersionedFileIndex>().findOne(VersionedFileIndex::name eq name)
        ?: VersionedFileIndex(name = name)
}

private fun updateIndex(index: VersionedFileIndex) {
    collection<VersionedFileIndex>()
        .replaceOne(VersionedFileIndex::id eq index.id, index, ReplaceOptions().upsert(true))
}