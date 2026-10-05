package org.flowlang.conformance

import com.fasterxml.jackson.databind.JsonNode
import org.flowlang.artifacts.ArtifactSchemaValidator

/** Compatibility entry point; syntax validation is shared with actual-byte publication. */
object JsonSchemaSmokeValidator {
    fun validate(instance: JsonNode, schema: JsonNode) = ArtifactSchemaValidator.validate(instance, schema)
}
