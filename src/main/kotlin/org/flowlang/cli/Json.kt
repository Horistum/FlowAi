package org.flowlang.cli

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.flowlang.planner.PlanDependencyRelation
import org.flowlang.serialization.FlowJson

// JVM reflection can return the derived continuity and blocking getters in
// either order. Published plan bytes are compared across compiler processes.
@JsonPropertyOrder(
    "sourceNodeId", "targetNodeId", "kind", "channel", "stateLifetime", "evidence",
    "resolution", "path", "candidates", "evidenceReference", "continuity", "blocking"
)
private abstract class PublishedDependencyOrder

object Json {
    fun bytes(value: Any, maximum: Int = org.flowlang.io.InputLimits.MAX_ARTIFACT_BYTES): ByteArray =
        org.flowlang.io.BoundedIo.encode(maximum) { output -> mapper.writeValue(output, value) }

    val mapper: ObjectMapper = FlowJson.newMapper()
        .addMixIn(PlanDependencyRelation::class.java, PublishedDependencyOrder::class.java)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .enable(SerializationFeature.INDENT_OUTPUT)
}
