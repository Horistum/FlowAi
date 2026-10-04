package org.flowlang.cli

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.SerializationFeature
import org.flowlang.serialization.FlowJson

object Json {
    fun bytes(value: Any, maximum: Int = org.flowlang.io.InputLimits.MAX_ARTIFACT_BYTES): ByteArray =
        org.flowlang.io.BoundedIo.encode(maximum) { output -> mapper.writeValue(output, value) }

    val mapper: ObjectMapper = FlowJson.newMapper()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .enable(SerializationFeature.INDENT_OUTPUT)
}
