package org.flowlang.compiler

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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
        require(source.frontend in INTENT_FRONTENDS) {
            "Intent input requires INTENT_YAML or REVIEWED_AI_PROPOSAL provenance, found ${source.frontend}."
        }
    }

    private companion object {
        val INTENT_FRONTENDS = setOf(
            CompilationFrontend.INTENT_YAML,
            CompilationFrontend.REVIEWED_AI_PROPOSAL
        )
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

data class IntentFrontendCompilationEvidence(
    override val source: CompilationSource,
    val intent: IntentDocument,
    val validation: IntentValidationReport
) : FrontendCompilationEvidence {
    init {
        require(source.frontend in setOf(CompilationFrontend.INTENT_YAML, CompilationFrontend.REVIEWED_AI_PROPOSAL)) {
            "Intent evidence requires Intent frontend provenance."
        }
        require(validation.valid) { "Accepted Intent frontend evidence must contain a valid Intent report." }
    }
}

enum class CompilationStage {
    INTENT_VALIDATION,
    INTENT_LOWERING,
    FLOW_VALIDATION,
    PLANNING,
    CANONICALIZATION
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
    val flowValidation: ValidationReport? = null
) {
    init {
        require(diagnostics.isNotEmpty()) { "Rejected compilation must expose at least one diagnostic." }
        require(diagnostics.any { it.severity == CompilationDiagnosticSeverity.ERROR }) {
            "Rejected compilation must expose at least one error diagnostic."
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
            CompilationFrontend.INTENT_YAML,
            CompilationFrontend.REVIEWED_AI_PROPOSAL -> require(frontendEvidence is IntentFrontendCompilationEvidence) {
                "Intent compilation requires Intent frontend evidence."
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

    fun requireIntentEvidence(): IntentFrontendCompilationEvidence =
        frontendEvidence as? IntentFrontendCompilationEvidence
            ?: error("Compilation source ${source.frontend} does not carry Intent frontend evidence.")

    companion object {
        internal fun from(
            source: CompilationSource,
            frontendEvidence: FrontendCompilationEvidence,
            ast: FlowDocument,
            validation: ValidationReport,
            plannerPlan: ExecutionPlan
        ): CompilationUnit {
            val intentValidation = (frontendEvidence as? IntentFrontendCompilationEvidence)?.validation
            val authorization = CanonicalExecutionGraphAuthority.authorizeCompilation(
                source = source,
                intentValidation = intentValidation,
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
        CompilationStage.FLOW_VALIDATION -> throw IllegalArgumentException(rejection.message())
        CompilationStage.INTENT_VALIDATION,
        CompilationStage.INTENT_LOWERING,
        CompilationStage.PLANNING,
        CompilationStage.CANONICALIZATION -> throw IllegalStateException(rejection.message())
    }
}
