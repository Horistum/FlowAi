package org.flowlang.frontend.ai

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.io.File
import java.security.MessageDigest
import org.flowlang.ai.normalization.AiIntentRequest
import org.flowlang.io.BoundedIo
import org.flowlang.serialization.ContractReadPolicy
import org.flowlang.serialization.FlowJson

/** Keeps the reviewed bytes private; each evaluation receives a detached, validated snapshot. */
class CapturedRequestRequirements internal constructor(bytes: ByteArray, val source: RequestRequirementsSource) {
    private val captured = bytes.copyOf()
    internal fun document(): RequestRequirementsDocument = RequestRequirementsReader.decode(captured)
    fun requireRequest(request: AiIntentRequest) {
        if (document().request != request) throw RequestRequirementsException("FIDELITY_REQUEST_MISMATCH")
    }
}

class RequestRequirementsException(val code: String) : IllegalArgumentException(
    "$code: independent request requirements are invalid or do not match the captured request."
)

object RequestRequirementsReader {
    private val mapper = ContractReadPolicy.configure(ObjectMapper().registerModule(
        KotlinModule.Builder().enable(KotlinFeature.StrictNullChecks).build()
    ))
    private val idPattern = Regex("[a-z][a-z0-9-]{0,63}")

    fun read(file: File): CapturedRequestRequirements = guarded {
        val bytes = BoundedIo.readBytes(file)
        decode(bytes)
        CapturedRequestRequirements(bytes, RequestRequirementsSource(
            file.absoluteFile.toPath().normalize().toString(),
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }, bytes.size
        ))
    }

    internal fun decode(bytes: ByteArray): RequestRequirementsDocument = guarded {
        val tree = FlowJson.readTree(BoundedIo.decodeUtf8(bytes), "<request-requirements>")
        val document = mapper.treeToValue(tree, RequestRequirementsDocument::class.java)
        require(document.formatVersion in 1..3 && document.request.userText.isNotBlank())
        // Version 1 remains frozen, including explicit empty/null uses of new fields.
        if (document.formatVersion == 1) require(tree.path("requirements").findValues("parameters").isEmpty())
        require(document.requirements.isNotEmpty() && document.requirements.size <= 256)
        require(document.requirements.map { it.id }.distinct().size == document.requirements.size)
        require(document.reviewerId == null || document.reviewerId.isNotBlank())
        require(document.confirmation != RequirementConfirmation.CONFIRMED || !document.reviewerId.isNullOrBlank())
        val parameterPattern = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
        fun selector(s: RequirementSelector) {
            require(s.workflow == null || s.workflow.isNotBlank())
            require(s.parameters.size <= 16)
            require(s.parameters.all { (key, value) -> parameterPattern.matches(key) && value.length <= 1000 })
        }
        fun scope(guards: List<RequirementGuard>) {
            require(guards.isNotEmpty() && guards.size <= 32)
            require(guards.all { it.predicate.isNotBlank() && it.predicate.length <= 1000 })
            require(guards.map { it.predicate }.distinct().size == guards.size)
        }
        fun validate(check: FidelityCheck, nested: Boolean = false) {
            when (check) {
                is CapabilityCount -> {
                    selector(check.selector)
                    val lowerBound = if (document.formatVersion >= 2) 0 else 1
                    require(check.minimum in lowerBound..10000 && (check.maximum == null || check.maximum in check.minimum..10000))
                }
                is ForbiddenCapability -> Unit
                is ForbiddenEffect -> require(check.resource == null || check.resource.isNotBlank())
                is RequiredEffect -> { selector(check.selector); require(check.resource == null || check.resource.isNotBlank()) }
                is LiteralParameter -> { selector(check.selector); require(check.parameter.matches(parameterPattern)) }
                is RequiredOrdering -> { selector(check.before); selector(check.after) }
                is RequiredValueFrom -> {
                    require(document.formatVersion >= 2)
                    selector(check.producer); selector(check.consumer)
                    require(check.parameter.matches(parameterPattern))
                    require(check.path.size <= 8 && check.path.all { it.isNotBlank() && it.length <= 128 })
                }
                is OnlyUnder -> { require(document.formatVersion == 3 && !nested); selector(check.selector); scope(check.scope) }
                is OnPath -> {
                    require(document.formatVersion == 3 && !nested); scope(check.scope)
                    require(check.check is CapabilityCount || check.check is RequiredEffect || check.check is LiteralParameter || check.check is RequiredOrdering)
                    validate(check.check, true)
                }
                is JoinedValueFrom -> {
                    require(document.formatVersion == 3 && !nested); selector(check.consumer)
                    require(check.parameter.matches(parameterPattern) && check.origins.size in 2..32)
                    check.origins.forEach { origin ->
                        scope(origin.scope)
                        require((origin.input == null) != (origin.producer == null))
                        origin.input?.let { require(parameterPattern.matches(it)) }
                        origin.producer?.let(::selector)
                    }
                }
                is RequirementClarification -> require(check.question.isNotBlank())
                is UnsupportedRequirement -> Unit
            }
        }
        document.requirements.forEach { requirement ->
            require(idPattern.matches(requirement.id))
            require(requirement.sourceQuote.isNotBlank() && requirement.sourceQuote in document.request.userText)
            validate(requirement.check)
        }
        document
    }

    private inline fun <T> guarded(block: () -> T): T = try { block() }
        catch (failure: RequestRequirementsException) { throw failure }
        catch (failure: Exception) { throw RequestRequirementsException(BoundedIo.limitFailure(failure)?.limitCode ?: "FIDELITY_INVALID_REQUIREMENTS") }
}
