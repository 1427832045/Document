package com.seer.srd.db

import com.mongodb.ConnectionString
import com.mongodb.MongoClient
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.gridfs.GridFSBucket
import com.mongodb.client.gridfs.GridFSBuckets
import com.seer.srd.CONFIG
import com.seer.srd.SystemError
import org.litote.kmongo.KMongo
import org.slf4j.LoggerFactory

object MongoDBManager {

    private val logger = LoggerFactory.getLogger(MongoDBManager::class.java)

    private var mongoClient: MongoClient? = null

    private var mongoMainDatabase: MongoDatabase? = null

    // Create a gridFSBucket with a custom bucket name "files"
    private var gridFSFilesBucket: GridFSBucket? = null

    fun initMongoDB() {
        val client = KMongo.createClient(ConnectionString(CONFIG.mongodbUrl))
        val db = client.getDatabase(CONFIG.mongoMainDatabase)
        mongoClient = client
        mongoMainDatabase = db
        gridFSFilesBucket = GridFSBuckets.create(db, "files")
    }

    fun getDatabase(): MongoDatabase {
        return mongoMainDatabase ?: throw SystemError("No MongoDB main database")
    }

    inline fun <reified T : Any> collection(): MongoCollection<T> =
        getDatabase().getCollection(T::class.java.simpleName, T::class.java)

    fun getGridFSBucket(): GridFSBucket {
        return gridFSFilesBucket ?: throw SystemError("No MongoDB gridFSFilesBucket")
    }

    fun disposeMongoDB() {
        try {
            mongoClient?.close()
        } catch (e: Exception) {
            logger.error("Close MongoDB", e)
        }
    }
}
