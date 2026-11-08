package org.flowlang.frontend.outcomes

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.io.File
import java.security.MessageDigest
import java.security.KeyFactory
import java.security.interfaces.EdECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.time.Instant
import org.flowlang.frontend.ai.RequirementSelector
import org.flowlang.io.BoundedIo
import org.flowlang.serialization.ContractReadPolicy

class OutcomeInputException(val code: String) : IllegalArgumentException("$code: invalid operation outcome input.")

/** Private immutable bytes; every use is decoded afresh rather than trusting a mutable DTO. */
class CapturedOutcomeInput<T : Any> internal constructor(bytes: ByteArray, val source: OutcomeInputSource,
                                                        private val decoder: (ByteArray) -> T) {
    private val captured = bytes.copyOf()
    internal fun document(): T = decoder(captured)
}

object OperationOutcomeReader {
    internal const val MAX_RECORDS = 256
    internal const val MAX_PAYLOAD = 65536
    internal val digestPattern = Regex("[a-f0-9]{64}")
    private val id = Regex("[A-Za-z][A-Za-z0-9_.-]{0,127}")
    private val mapper = ContractReadPolicy.configure(ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(ContractReadPolicy.constraints()).build()).registerModule(
        KotlinModule.Builder().enable(KotlinFeature.StrictNullChecks).build())).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    fun requirements(file: File): CapturedOutcomeInput<OperationOutcomeRequirements> = capture(file, ::requirements)
    fun observations(file: File): CapturedOutcomeInput<OperationObservationBundle> = capture(file, ::observations)
    fun trust(file: File): CapturedOutcomeInput<OperationObservationTrust> = capture(file, ::trust)

    private fun <T : Any> capture(file: File, decode: (ByteArray) -> T): CapturedOutcomeInput<T> = guarded {
        val bytes = BoundedIo.readBytes(file)
        decode(bytes)
        CapturedOutcomeInput(bytes, OutcomeInputSource(file.absoluteFile.toPath().normalize().toString(), sha256(bytes), bytes.size), decode)
    }
    internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun text(value: String) = require(value.isNotBlank() && value.length <= 1000)
    private fun identity(value: ResourceIdentity) { text(value.scope); text(value.type); text(value.key) }
    private fun path(value: List<String>) = require(value.size <= 8 && value.all { it.isNotBlank() && it.length <= 128 })
    private fun selector(value: RequirementSelector) {
        value.workflow?.let(::text)
        require(value.parameters.size <= 16)
        value.parameters.forEach { (k, v) -> require(id.matches(k)); text(v) }
    }
    internal fun scalar(value: JsonNode): Boolean = value.isBoolean || value.isTextual && value.textValue().length <= 1000 ||
        value.isNumber && value.asText().length <= 100 && value.doubleValue().isFinite()
    private fun facts(value: Map<String, JsonNode>) {
        require(value.size <= 32 && value.all { (k, v) -> id.matches(k) && scalar(v) })
    }
    internal fun requirements(bytes: ByteArray): OperationOutcomeRequirements = guarded {
        decode(bytes, OperationOutcomeRequirements::class.java).also { d ->
            require(d.formatVersion == 1 && digestPattern.matches(d.graphDigest))
            require(id.matches(d.runId) && Regex("[A-Za-z0-9_-]{32,128}").matches(d.challenge))
            Instant.parse(d.startedAt); text(d.reviewerId)
            require(d.resources.size in 1..64 && d.operations.size in 1..64)
            require(d.resources.map { it.id }.distinct().size == d.resources.size)
            require(d.resources.map { it.identity }.distinct().size == d.resources.size)
            require(d.operations.map { it.id }.distinct().size == d.operations.size)
            d.resources.forEach { r ->
                require(id.matches(r.id)); identity(r.identity)
                require((r.disposition == ResourceDisposition.CREATED_IN_RUN) == (r.creator != null))
                require((r.disposition == ResourceDisposition.MANAGED) == (r.owner != null))
                r.owner?.let(::text)
                r.creator?.let(::selector)
            }
            val resources = d.resources.map { it.id }.toSet()
            d.operations.forEach { op ->
                require(id.matches(op.id) && op.resource in resources && id.matches(op.parameter))
                selector(op.selector); path(op.path); facts(op.postconditions)
                require(op.postconditions.isNotEmpty() && op.maxAgeSeconds in 1..86400)
            }
        }
    }
    private fun observations(bytes: ByteArray): OperationObservationBundle = guarded {
        decode(bytes, OperationObservationBundle::class.java).also { d ->
            require(d.formatVersion == 1 && d.observations.size <= MAX_RECORDS)
            d.observations.forEach { o ->
                require(id.matches(o.keyId) && o.payload.length in 1..90000 && o.source.length in 1..90000 && o.signature.length == 88)
            }
        }
    }
    private fun trust(bytes: ByteArray): OperationObservationTrust = guarded {
        decode(bytes, OperationObservationTrust::class.java).also { d ->
            require(d.formatVersion == 1 && d.grants.size in 1..64 && d.grants.map { it.keyId }.distinct().size == d.grants.size)
            d.grants.forEach { g ->
                require(id.matches(g.keyId) && g.publicKey.length in 1..256)
                val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(g.publicKey)))
                require(key is EdECPublicKey && key.params.name == "Ed25519")
                require(g.scopes.size in 1..64 && g.resourceTypes.size in 1..64 && g.kinds.isNotEmpty())
                require(g.scopes.distinct().size == g.scopes.size && g.resourceTypes.distinct().size == g.resourceTypes.size && g.kinds.distinct().size == g.kinds.size)
                (g.scopes + g.resourceTypes).forEach(::text)
            }
        }
    }
    internal fun observation(bytes: ByteArray): OperationObservation = guarded {
        require(bytes.size <= MAX_PAYLOAD)
        decode(bytes, OperationObservation::class.java).also { o ->
            require(o.formatVersion == 1 && id.matches(o.id) && id.matches(o.runId))
            require(digestPattern.matches(o.graphDigest) && digestPattern.matches(o.contractSha256) && digestPattern.matches(o.sourceSha256))
            require(Regex("[A-Za-z0-9_-]{32,128}").matches(o.challenge))
            require(o.sourceSizeBytes in 1..MAX_PAYLOAD)
            identity(o.resource); Instant.parse(o.observedAt); facts(o.facts); path(o.path)
            o.nodeId?.let(::text)
            require(o.nodeId != null || o.kind == ObservationKind.BINDING)
            require((o.kind == ObservationKind.BINDING) == (o.binding != null))
            o.binding?.let { require(id.matches(it)) }
            require(o.path.isEmpty() || o.kind == ObservationKind.BINDING)
            require(o.kind == ObservationKind.STATE || o.facts.isEmpty())
            require((o.kind == ObservationKind.CREATION) == (o.created != null))
            require((o.kind == ObservationKind.OWNERSHIP) == (o.owner != null))
            o.owner?.let(::text)
        }
    }
    private fun <T : Any> decode(bytes: ByteArray, type: Class<T>): T {
        val text = BoundedIo.decodeUtf8(bytes)
        ContractReadPolicy.validate(text, mapper.factory)
        return mapper.readValue(text, type)
    }
    private inline fun <T> guarded(action: () -> T): T = try { action() }
        catch (failure: OutcomeInputException) { throw failure }
        catch (failure: Exception) { throw OutcomeInputException(BoundedIo.limitFailure(failure)?.limitCode ?: "OUTCOME_INVALID_INPUT") }
}
