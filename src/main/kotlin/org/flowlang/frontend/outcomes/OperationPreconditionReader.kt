package org.flowlang.frontend.outcomes

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.io.File
import java.security.KeyFactory
import java.security.interfaces.EdECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import org.flowlang.frontend.ai.RequirementSelector
import org.flowlang.io.BoundedIo
import org.flowlang.serialization.ContractReadPolicy

class PreconditionInputException(val code: String) : IllegalArgumentException("$code: invalid operation precondition input.")

/** Strict, bounded input capture. No mutable caller DTO becomes assessment authority. */
object OperationPreconditionReader {
    private val id = Regex("[A-Za-z][A-Za-z0-9_.-]{0,127}")
    private val challenge = Regex("[A-Za-z0-9_-]{32,128}")
    private val mapper = ContractReadPolicy.configure(ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(ContractReadPolicy.constraints()).build())
        .registerModule(KotlinModule.Builder().enable(KotlinFeature.StrictNullChecks).build()))
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    fun requirements(file: File): CapturedOutcomeInput<OperationPreconditionRequirements> = capture(file, ::requirements)
    fun observations(file: File): CapturedOutcomeInput<PreconditionObservationBundle> = capture(file, ::observations)
    fun trust(file: File): CapturedOutcomeInput<PreconditionObservationTrust> = capture(file, ::trust)

    private fun <T : Any> capture(file: File, decoder: (ByteArray) -> T): CapturedOutcomeInput<T> = guarded {
        val bytes = BoundedIo.readBytes(file)
        decoder(bytes)
        CapturedOutcomeInput(bytes, OutcomeInputSource(file.absoluteFile.toPath().normalize().toString(),
            OperationOutcomeReader.sha256(bytes), bytes.size), decoder)
    }
    private fun text(value: String) = require(value.isNotBlank() && value.length <= 1000)
    private fun digest(value: String) = require(OperationOutcomeReader.digestPattern.matches(value))
    private fun identity(value: ResourceIdentity) { text(value.scope); text(value.type); text(value.key) }
    private fun facts(value: Map<String, JsonNode>) = require(value.size <= 32 &&
        value.all { (key, fact) -> id.matches(key) && OperationOutcomeReader.scalar(fact) })
    private fun selector(value: RequirementSelector) {
        value.workflow?.let(::text)
        require(value.parameters.size <= 16)
        value.parameters.forEach { (key, value) -> require(id.matches(key)); text(value) }
    }
    internal fun requirements(bytes: ByteArray): OperationPreconditionRequirements = guarded {
        decode(bytes, OperationPreconditionRequirements::class.java).also { d ->
            require(d.formatVersion == 1); digest(d.graphDigest)
            require(id.matches(d.assessmentId) && challenge.matches(d.challenge)); text(d.reviewerId)
            val lifetime = Duration.between(Instant.parse(d.issuedAt), Instant.parse(d.expiresAt))
            require(!lifetime.isNegative && !lifetime.isZero && lifetime <= Duration.ofHours(1))
            require(d.resources.size in 1..64 && d.operations.size in 1..64)
            require(d.resources.map { it.id }.distinct().size == d.resources.size)
            require(d.resources.map { it.identity }.distinct().size == d.resources.size)
            require(d.resources.map { Triple(it.identity.scope, it.identity.type, it.instanceId) }.distinct().size == d.resources.size)
            d.resources.forEach { r ->
                require(id.matches(r.id)); identity(r.identity); text(r.instanceId)
                require((r.disposition == PreconditionResourceDisposition.MANAGED) == (r.owner != null))
                r.owner?.let(::text)
            }
            val resources = d.resources.map { it.id }.toSet()
            require(d.operations.map { it.id }.distinct().size == d.operations.size)
            require(resources.all { resource -> d.operations.any { it.resource == resource } })
            d.operations.forEach { op ->
                require(id.matches(op.id) && op.resource in resources && id.matches(op.parameter))
                selector(op.selector); facts(op.facts)
                require(op.path.size <= 8 && op.path.all { it.isNotBlank() && it.length <= 128 })
                require(op.maxAgeSeconds in 1..3600)
            }
        }
    }
    private fun observations(bytes: ByteArray): PreconditionObservationBundle = guarded {
        decode(bytes, PreconditionObservationBundle::class.java).also { d ->
            require(d.formatVersion == 1 && d.observations.size <= 128)
            d.observations.forEach { o ->
                require(id.matches(o.keyId) && o.payload.length in 1..90000 && o.source.length in 1..90000 && o.signature.length == 88)
            }
        }
    }
    private fun trust(bytes: ByteArray): PreconditionObservationTrust = guarded {
        decode(bytes, PreconditionObservationTrust::class.java).also { d ->
            require(d.formatVersion == 1 && d.grants.size in 1..64 && d.grants.map { it.keyId }.distinct().size == d.grants.size)
            d.grants.forEach { g ->
                require(id.matches(g.keyId) && g.publicKey.length in 1..256)
                val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(g.publicKey)))
                require(key is EdECPublicKey && key.params.name == "Ed25519")
                for (values in listOf(g.scopes, g.resourceTypes)) {
                    require(values.size in 1..64 && values.distinct().size == values.size)
                    values.forEach(::text)
                }
            }
        }
    }
    internal fun observation(bytes: ByteArray): PreconditionObservation = guarded {
        require(bytes.size <= OperationOutcomeReader.MAX_PAYLOAD)
        decode(bytes, PreconditionObservation::class.java).also { o ->
            require(o.formatVersion == 1 && id.matches(o.id) && id.matches(o.assessmentId) && id.matches(o.requirementId))
            require(challenge.matches(o.challenge)); text(o.nodeId); identity(o.resource)
            listOf(o.contractSha256, o.graphDigest, o.sourceSha256).forEach(::digest)
            require(o.sourceSizeBytes in 1..OperationOutcomeReader.MAX_PAYLOAD)
            Instant.parse(o.observedAt); facts(o.facts)
            o.instanceId?.let(::text); o.owner?.let(::text)
        }
    }
    private fun <T : Any> decode(bytes: ByteArray, type: Class<T>): T {
        val text = BoundedIo.decodeUtf8(bytes)
        ContractReadPolicy.validate(text, mapper.factory)
        return mapper.readValue(text, type)
    }
    private inline fun <T> guarded(action: () -> T): T = try { action() }
        catch (failure: PreconditionInputException) { throw failure }
        catch (failure: Exception) { throw PreconditionInputException(BoundedIo.limitFailure(failure)?.limitCode ?: "PRECONDITION_INVALID_INPUT") }
}
