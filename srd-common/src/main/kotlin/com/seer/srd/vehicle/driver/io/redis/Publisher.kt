package com.seer.srd.vehicle.driver.io.redis

import redis.clients.jedis.JedisPool

class Publisher(private val jedisPool: JedisPool, private val vehicleName: String) {

    fun publish(data: String?) {
        val jedis = jedisPool.resource
        jedis.publish(vehicleName, data)
        jedis.close()
    }

}