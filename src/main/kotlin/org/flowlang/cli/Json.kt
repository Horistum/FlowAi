package org.flowlang.cli

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

object Json {
    val mapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .enable(SerializationFeature.INDENT_OUTPUT)
}
