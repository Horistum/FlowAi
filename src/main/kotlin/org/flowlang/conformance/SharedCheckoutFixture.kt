package org.flowlang.conformance

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/** Independent immutable public-repository oracles shared by both native-provider experiments. */
internal object SharedCheckoutFixture {
    const val SOURCE = "flow-conformance-kit/src/runtimeTest/shared-checkout.intent.yaml"
    const val REPOSITORY = "https://github.com/Horistum/FlowAi.git"
    const val SELECTED = "c6149ff9ba87f88e25cdc0905cfb86cb935f7756"
    const val ALTERNATE = "9232061af22922af33ff3a65ada81d1b0d4eedd6"
    const val MARKER = ".flow-agent/work-packages/behavioral-adapter-certification.yaml"
    const val SELECTED_MARKER = "fb8263618cf3a4ad5cf5704a66b746512edc2643bc2c03a211168d41663fc8fb"
    const val ALTERNATE_MARKER = "ad91a8bf3c636f9123c83d19da1a6c55e16055e866f800630a88b4e2d08c6897"
    val runIds = listOf("baseline", "omitted-checkout", "substituted-revision")
    private val mapper = jacksonObjectMapper()

    fun expected(id: String): ByteArray = when (id) {
        "baseline" -> observationBytes(SELECTED, SELECTED_MARKER)
        "omitted-checkout" -> observationBytes(null, null)
        "substituted-revision" -> observationBytes(ALTERNATE, ALTERNATE_MARKER)
        else -> error("Unknown shared checkout run: $id")
    }

    fun observationBytes(revision: String?, markerSha256: String?): ByteArray {
        require(revision == null || Regex("[0-9a-f]{40}").matches(revision))
        require(markerSha256 == null || Regex("[0-9a-f]{64}").matches(markerSha256))
        require((revision == null) == (markerSha256 == null)) { "Revision and marker must both exist or both be absent." }
        return (mapper.writeValueAsString(linkedMapOf("revision" to revision, "markerSha256" to markerSha256)) + "\n").toByteArray(Charsets.UTF_8)
    }

    fun observationBytes(row: JsonNode): ByteArray {
        fun nullable(name: String): String? {
            val value = row[name]
            require(value != null && (value.isNull || value.isTextual)) { "Missing or malformed shared observation: $name" }
            return if (value.isNull) null else value.asText()
        }
        return observationBytes(nullable("revision"), nullable("markerSha256"))
    }
}
