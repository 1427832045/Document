package com.seer.srd.util

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.Version
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule

import org.bson.types.ObjectId

val mapper = ObjectMapper().apply {
    val simpleModule = SimpleModule("SimpleModule", Version(1, 0, 0, null, "", ""))
    simpleModule.addSerializer(ObjectId::class.java, ObjectIdSerializer())
    simpleModule.addDeserializer(ObjectId::class.java, ObjectIdDeserializer())
    registerModule(simpleModule)

    configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    registerModule(JavaTimeModule())

    registerModule(KotlinModule())

    disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
}

class ObjectIdSerializer : JsonSerializer<ObjectId>() {

    override fun serialize(value: ObjectId, gen: JsonGenerator, serializers: SerializerProvider) {
        gen.writeString(value.toString())
    }

}

class ObjectIdDeserializer : JsonDeserializer<ObjectId>() {

    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): ObjectId {
        val oid = p.readValueAs(String::class.java)
        return ObjectId(oid)
    }

}