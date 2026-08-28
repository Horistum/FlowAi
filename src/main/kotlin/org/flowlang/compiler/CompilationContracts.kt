package org.flowlang.compiler

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.flowlang.ai.normalization.AiIntentRequest
import org.flowlang.ai.normalization.AiIntentResponse
import org.flowlang.ai.normalization.IntentProposalDecision
import org.flowlang.ai.normalization.IntentProposalReviewEvidence
import org.flowlang.ast.FlowDocument
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentValidationReport
import org.flowlang.planner.CanonicalExecutionPlan
import org.flowlang.planner.ExecutionPlan
import org.flowlang.validator.ValidationReport

/** Authored representation accepted by the shared compiler boundary. */
enum class CompilationFrontend {
    FLOW_SOURCE,
    INTENT_YAML,
    REVIEWED_AI_PROPOSAL
}

/** Immutable provenance for the exact source bytes accepted by a frontend. */
data class CompilationSource(
    val frontend: CompilationFrontend,
    val identity: String,
    val sourceName: String,
    val mediaType: String,
    val sha256: String,
    val byteCount: Long
) {
    init {
        require(identity.isNotBlank()) { "Compilation source identity must not be blank." }
        require(sourceName.isNotBlank()) { "Compilation source name must not be blank." }
        require(mediaType.isNotBlank()) { "Compilation source media type must not be blank." }
        require(SHA_256.matches(sha256)) { "Compilation source digest must be lowercase SHA-256 text." }
        require(byteCount >= 0) { "Compilation source byte count must not be negative." }
    }

    companion object {
        private val SHA_256 = Regex("[0-9a-f]{64}")

        fun fromBytes(
            frontend: CompilationFrontend,
            identity: String,
            bytes: ByteArray,
            sourceName: String = identity,
            mediaType: String = defaultMediaType(frontend)
        ): CompilationSource = CompilationSource(
            frontend = frontend,
            identity = identity,
            sourceName = sourceName,
            mediaType = mediaType,
            sha256 = MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') },
            byteCount = bytes.size.toLong()
        )

        private fun defaultMediaType(frontend: CompilationFrontend): String = when (frontend) {
            CompilationFrontend.FLOW_SOURCE -> "text/x-flow"
            CompilationFrontend.INTENT_YAML -> "application/vnd.flow.intent+yaml"
            CompilationFrontend.REVIEWED_AI_PROPOSAL -> "application/vnd.flow.intent-proposal+json"
        }
    }
}

internal class CapturedCompilationSource<T> internal constructor(
    val source: CompilationSource,
    val value: T
)

/**
 * Captures source bytes once, decodes them strictly, and gives the parser the
 * exact text whose bytes are bound by the recorded digest. A later filesystem
 * change cannot alter the already captured compilation input.
 */
internal object CompilationSourceCapture {
    fun <T> capture(
        file: File,
        frontend: CompilationFrontend,
        parse: (String, String) -> T
    ): CapturedCompilationSource<T> {
        require(file.isFile) { "Compilation source does not exist: ${file.path}" }
        val bytes = file.readBytes()
        val sourceName = file.path
        val identity = file.absoluteFile.toPath().normalize().toString()
        val text = decodeUtf8(bytes, sourceName)
        return CapturedCompilationSource(
            source = CompilationSource.fromBytes(
                frontend = frontend,
                identity = identity,
                bytes = bytes,
                sourceName = sourceName
            ),
            value = parse(text, sourceName)
        )
    }

    fun <T> captureText(
        text: String,
        identity: String,
        frontend: CompilationFrontend,
        parse: (String, String) -> T
    ): CapturedCompilationSource<T> {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        return CapturedCompilationSource(
            source = CompilationSource.fromBytes(
                frontend = frontend,
                identity = identity,
                bytes = bytes,
                sourceName = identity
            ),
            value = parse(text, identity)
        )
    }

    /** Captures a frontend-owned immutable value together with its exact serialized source view. */
    fun <T> captureBytes(
        bytes: ByteArray,
        identity: String,
        sourceName: String,
        frontend: CompilationFrontend,
        value: T
    ): CapturedCompilationSource<T> = CapturedCompilationSource(
        source = CompilationSource.fromBytes(
            frontend = frontend,
            identity = identity,
            bytes = bytes,
            sourceName = sourceName
        ),
        value = value
    )

    private fun decodeUtf8(bytes: ByteArray, identity: String): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { throw IllegalArgumentException("Compilation source must be valid UTF-8: $identity", it) }
    }
}

internal sealed interface CompilationInput {
    val source: CompilationSource
}

internal data class FlowSourceCompilationInput(
    override val source: CompilationSource,
    val ast: FlowDocument
) : CompilationInput {
    init {
        require(source.frontend == CompilationFrontend.FLOW_SOURCE) {
            "Flow Source input requires FLOW_SOURCE provenance, found ${source.frontend}."
        }
    }
}

internal data class IntentCompilationInput(
    override val source: CompilationSource,
    val intent: IntentDocument
) : CompilationInput {
    init {
        require(source.frontend == CompilationFrontend.INTENT_YAML) {
            "Intent YAML input requires INTENT_YAML provenance, found ${source.frontend}."
        }
    }
}

internal data class ReviewedAiProposalCompilationInput(
    override val source: CompilationSource,
    val providerId: String,
    val request: AiIntentRequest,
    val response: AiIntentResponse
) : CompilationInput {
    init {
        require(source.frontend == CompilationFrontend.REVIEWED_AI_PROPOSAL) {
            "Reviewed AI proposal input requires REVIEWED_AI_PROPOSAL provenance, found ${source.frontend}."
        }
        require(providerId.isNotBlank()) { "Reviewed AI proposal provider id must not be blank." }
        require(request.userText.isNotBlank()) { "Reviewed AI proposal request text must not be blank." }
    }
}

sealed interface FrontendCompilationEvidence {
    val source: CompilationSource
}

data class FlowSourceCompilationEvidence(
    override val source: CompilationSource
) : FrontendCompilationEvidence {
    init {
        require(source.frontend == CompilationFrontend.FLOW_SOURCE) {
            "Flow Source evidence requires FLOW_SOURCE provenance."
        }
    }
}

sealed interface IntentCompilationEvidence : FrontendCompilationEvidence {
    val intent: IntentDocument
    val validation: IntentValidationReport
}

data class IntentFrontendCompilationEvidence(
    override val source: CompilationSource,
    override val intent: IntentDocument,
    override val validation: IntentValidationReport
) : IntentCompilationEvidence {
    init {
        require(source.frontend == CompilationFrontend.INTENT_YAML) {
            "Intent YAML evidence requires INTENT_YAML provenance."
        }
        require(validation.valid) { "Accepted Intent YAML evidence must contain a valid Intent report." }
    }
}

data class ReviewedAiProposalCompilationEvidence(
    override val source: CompilationSource,
    val providerId: String,
    val request: AiIntentRequest,
    val response: AiIntentResponse,
    val review: IntentProposalReviewEvidence,
    override val validation: IntentValidationReport
) : IntentCompilationEvidence {
    override val intent: IntentDocument get() = response.normalizedIntent

    init {
        require(source.frontend == CompilationFrontend.REVIEWED_AI_PROPOSAL) {
            "Reviewed AI proposal evidence requires REVIEWED_AI_PROPOSAL provenance."
        }
        require(providerId.isNotBlank()) { "Reviewed AI proposal evidence must name its provider." }
        require(request.userText.isNotBlank()) { "Reviewed AI proposal evidence must retain its request text." }
        require(review.accepted && review.decision is IntentProposalDecision.Accepted) {
            "Accepted compilation cannot carry rejected AI proposal review evidence."
        }
        require(review.intent == intent) {
            "AI proposal review evidence does not describe the normalized intent being compiled."
        }
        require(review.validation == validation) {
            "AI proposal review and shared compiler validation reports differ."
        }
        require(validation.valid) { "Accepted reviewed AI proposal evidence must contain a valid Intent report." }
    }
}

enum class CompilationStage {
    INTENT_VALIDATION,
    INTENT_LOWERING,
    FLOW_VALIDATION,
    PLANNING,
    CANONICALIZATION,
    PROPOSAL_REVIEW
}

enum class CompilationDiagnosticSeverity {
    ERROR,
    WARNING;

    companion object {
        fun fromWire(level: String): CompilationDiagnosticSeverity = when (level.lowercase()) {
            "error" -> ERROR
            "warning" -> WARNING
            else -> error("Unknown compilation diagnostic severity '$level'.")
        }
    }
}

data class CompilationDiagnostic(
    val code: String,
    val message: String,
    val severity: CompilationDiagnosticSeverity = CompilationDiagnosticSeverity.ERROR
) {
    init {
        require(code.isNotBlank()) { "Compilation diagnostic code must not be blank." }
        require(message.isNotBlank()) { "Compilation diagnostic message must not be blank." }
    }
}

data class CompilationRejection(
    val source: CompilationSource,
    val stage: CompilationStage,
    val diagnostics: List<CompilationDiagnostic>,
    val intent: IntentDocument? = null,
    val intentValidation: IntentValidationReport? = null,
    val ast: FlowDocument? = null,
    val flowValidation: ValidationReport? = null,
    val proposal: AiIntentResponse? = null,
    val proposalReview: IntentProposalReviewEvidence? = null
) {
    init {
        require(diagnostics.isNotEmpty()) { "Rejected compilation must expose at least one diagnostic." }
        require(diagnostics.any { it.severity == CompilationDiagnosticSeverity.ERROR }) {
            "Rejected compilation must expose at least one error diagnostic."
        }
        when (source.frontend) {
            CompilationFrontend.REVIEWED_AI_PROPOSAL -> {
                require(proposal != null && proposalReview != null && intent != null && intentValidation != null) {
                    "Reviewed AI proposal rejection must retain proposal, review, Intent and validation evidence."
                }
                require(proposal.normalizedIntent == intent && proposalReview.intent == intent) {
                    "AI proposal rejection evidence must describe the rejected intent exactly."
                }
            }
            CompilationFrontend.FLOW_SOURCE,
            CompilationFrontend.INTENT_YAML -> require(proposal == null && proposalReview == null) {
                "Non-AI compilation rejection cannot carry AI proposal-review evidence."
            }
        }
    }
}

/**
 * Accepted compilation envelope after the graph-authority cutover.
 *
 * ExecutionPlan and CanonicalExecutionPlan remain public compatibility views,
 * but both are reconstructed from the exact validated CanonicalExecutionGraph
 * and its separate binding envelope. They cannot authorize projection on their own.
 */
class CompilationUnit private constructor(
    val source: CompilationSource,
    val frontendEvidence: FrontendCompilationEvidence,
    val ast: FlowDocument,
    val validation: ValidationReport,
    val authorization: CompilationAuthorization
) {
    val graph: CanonicalExecutionGraph get() = authorization.graph
    val graphDigest: CanonicalExecutionGraphDigest get() = authorization.graphDigest
    val validationBinding: CompilationValidationBinding get() = authorization.validationBinding
    val executionPlan: ExecutionPlan get() = authorization.executionPlan
    val canonicalPlan: CanonicalExecutionPlan get() = authorization.canonicalPlan

    init {
        require(frontendEvidence.source == source) {
            "Frontend evidence provenance must match the compilation source exactly."
        }
        when (source.frontend) {
            CompilationFrontend.FLOW_SOURCE -> require(frontendEvidence is FlowSourceCompilationEvidence) {
                "FLOW_SOURCE compilation requires Flow Source evidence."
            }
            CompilationFrontend.INTENT_YAML -> require(frontendEvidence is IntentFrontendCompilationEvidence) {
                "INTENT_YAML compilation requires Intent YAML evidence."
            }
            CompilationFrontend.REVIEWED_AI_PROPOSAL -> {
                require(frontendEvidence is ReviewedAiProposalCompilationEvidence) {
                    "REVIEWED_AI_PROPOSAL compilation requires reviewed proposal evidence."
                }
                require(validationBinding.proposalReviewValid == true) {
                    "Reviewed AI proposal authorization must bind successful proposal review."
                }
            }
        }
        require(validation.valid) { "CompilationUnit cannot contain an invalid Flow validation report." }
        require(validationBinding.origin == CompilationAuthorizationOrigin.COMPILATION_UNIT) {
            "CompilationUnit must carry source-bound graph authorization."
        }
        require(validationBinding.sourceSha256 == source.sha256) {
            "Compilation authorization source digest does not match the captured frontend bytes."
        }
        require(ast.flow.name == executionPlan.flowName) {
            "Flow AST name '${ast.flow.name}' does not match graph-derived execution plan '${executionPlan.flowName}'."
        }
        authorization.requireIntegrity()
    }

    /**
     * Source-compatible strict Intent YAML accessor retained for existing product
     * and conformance consumers. Reviewed AI proposals deliberately use their
     * own typed accessor so proposal-review evidence cannot be erased by an
     * overly broad cast at the trust boundary.
     */
    fun requireIntentEvidence(): IntentFrontendCompilationEvidence =
        frontendEvidence as? IntentFrontendCompilationEvidence
            ?: error("Compilation source ${source.frontend} does not carry strict Intent YAML evidence.")

    fun requireIntentCompilationEvidence(): IntentCompilationEvidence =
        frontendEvidence as? IntentCompilationEvidence
            ?: error("Compilation source ${source.frontend} does not carry Intent compilation evidence.")

    fun requireReviewedAiProposalEvidence(): ReviewedAiProposalCompilationEvidence =
        frontendEvidence as? ReviewedAiProposalCompilationEvidence
            ?: error("Compilation source ${source.frontend} does not carry reviewed AI proposal evidence.")

    companion object {
        internal fun from(
            source: CompilationSource,
            frontendEvidence: FrontendCompilationEvidence,
            ast: FlowDocument,
            validation: ValidationReport,
            plannerPlan: ExecutionPlan
        ): CompilationUnit {
            val intentValidation = (frontendEvidence as? IntentCompilationEvidence)?.validation
            val proposalReview = (frontendEvidence as? ReviewedAiProposalCompilationEvidence)?.review
            val authorization = CanonicalExecutionGraphGate.authorizeCompilation(
                source = source,
                intentValidation = intentValidation,
                proposalReview = proposalReview,
                flowValidation = validation,
                plannerPlan = plannerPlan
            )
            return CompilationUnit(
                source = source,
                frontendEvidence = frontendEvidence,
                ast = ast,
                validation = validation,
                authorization = authorization
            )
        }
    }
}

sealed interface CompilationResult {
    val source: CompilationSource

    data class Accepted(val unit: CompilationUnit) : CompilationResult {
        override val source: CompilationSource = unit.source
    }

    data class Rejected(val rejection: CompilationRejection) : CompilationResult {
        override val source: CompilationSource = rejection.source
    }
}

private fun CompilationRejection.message(): String =
    "Compilation failed during $stage: " +
        diagnostics.joinToString { diagnostic -> "${diagnostic.code}: ${diagnostic.message}" }

fun CompilationResult.requireAccepted(): CompilationUnit = when (this) {
    is CompilationResult.Accepted -> unit
    is CompilationResult.Rejected -> when (rejection.stage) {
        CompilationStage.PROPOSAL_REVIEW,
        CompilationStage.FLOW_VALIDATION -> throw IllegalArgumentException(rejection.message())
        CompilationStage.INTENT_VALIDATION,
        CompilationStage.INTENT_LOWERING,
        CompilationStage.PLANNING,
        CompilationStage.CANONICALIZATION -> throw IllegalStateException(rejection.message())
    }
}
