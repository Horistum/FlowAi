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
        require(document.formatVersion == 1 && document.request.userText.isNotBlank())
        require(document.requirements.isNotEmpty() && document.requirements.size <= 256)
        require(document.requirements.map { it.id }.distinct().size == document.requirements.size)
        require(document.reviewerId == null || document.reviewerId.isNotBlank())
        require(document.confirmation != RequirementConfirmation.CONFIRMED || !document.reviewerId.isNullOrBlank())
        fun selector(s: RequirementSelector) { require(s.workflow == null || s.workflow.isNotBlank()) }
        document.requirements.forEach { requirement ->
            require(idPattern.matches(requirement.id))
            require(requirement.sourceQuote.isNotBlank() && requirement.sourceQuote in document.request.userText)
            when (val check = requirement.check) {
                is CapabilityCount -> {
                    selector(check.selector)
                    require(check.minimum in 1..10000 && (check.maximum == null || check.maximum in check.minimum..10000))
                }
                is ForbiddenCapability -> Unit
                is ForbiddenEffect -> require(check.resource == null || check.resource.isNotBlank())
                is RequiredEffect -> { selector(check.selector); require(check.resource == null || check.resource.isNotBlank()) }
                is LiteralParameter -> { selector(check.selector); require(check.parameter.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}"))) }
                is RequiredOrdering -> { selector(check.before); selector(check.after) }
                is RequirementClarification -> require(check.question.isNotBlank())
                is UnsupportedRequirement -> Unit
            }
        }
        document
    }

    private inline fun <T> guarded(block: () -> T): T = try { block() }
        catch (failure: RequestRequirementsException) { throw failure }
        catch (failure: Exception) { throw RequestRequirementsException(BoundedIo.limitFailure(failure)?.limitCode ?: "FIDELITY_INVALID_REQUIREMENTS") }
}
