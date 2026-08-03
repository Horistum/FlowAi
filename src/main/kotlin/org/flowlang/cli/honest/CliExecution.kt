package org.flowlang.cli.honest

import org.flowlang.adapters.rendering.AdapterRenderedArtifactKind
import org.flowlang.materialization.ExplicitTargetSelection
import org.flowlang.materialization.TargetSelectionDecision

/** Stable semantic roles of artifacts exposed by the CLI. */
enum class CliArtifactRole {
    TARGET_NEUTRAL_PLANNING,
    TARGET_MANIFEST,
    RENDERED_TARGET,
    REVIEW_DOCUMENT,
    DIAGNOSTIC_EVIDENCE
}

data class CliArtifact(
    val name: String,
    val role: CliArtifactRole,
    val persisted: Boolean
) {
    init {
        require(name.isNotBlank()) { "CLI artifact name must not be blank." }
    }
}

sealed interface CliPresentationItem {
    data class Section(val title: String, val value: Any) : CliPresentationItem
    data class Text(val value: String) : CliPresentationItem
}

data class CliPresentation(val items: List<CliPresentationItem> = emptyList())

internal class CliOutputCollector {
    private val items = mutableListOf<CliPresentationItem>()

    fun section(title: String, value: Any) {
        require(title.isNotBlank()) { "CLI presentation section title must not be blank." }
        items += CliPresentationItem.Section(title, value)
    }

    fun text(value: String) {
        items += CliPresentationItem.Text(value)
    }

    fun snapshot(): CliPresentation = CliPresentation(items.toList())
}

enum class CliDiagnosticCode(val wireCode: String) {
    TARGET_REQUIRED_FOR_RENDER("CLI_TARGET_REQUIRED_FOR_RENDER"),
    UNKNOWN_COMMAND("CLI_UNKNOWN_COMMAND"),
    INVALID_INPUT("CLI_INVALID_INPUT"),
    INTEGRITY_BLOCKED("CLI_INTEGRITY_BLOCKED"),
    INTERNAL_ERROR("CLI_INTERNAL_ERROR")
}

/**
 * Stable process status contract derived from typed CLI outcomes.
 *
 * Review-required evidence is intentionally distinct from a blocking integrity
 * failure so automation can decide whether to inspect evidence or stop outright.
 */
enum class CliProcessExit(val code: Int) {
    SUCCESS(0),
    INVALID_INPUT(2),
    REVIEW_REQUIRED(3),
    BLOCKED(4),
    INTERNAL_ERROR(70)
}

data class CliExecutionDiagnostic(
    val code: CliDiagnosticCode,
    val message: String,
    val causeType: String? = null
) {
    init {
        require(message.isNotBlank()) { "CLI diagnostic message must not be blank." }
    }
}

sealed interface CliExecutionResult {
    val exitCode: Int
    val presentation: CliPresentation
    val artifacts: List<CliArtifact>
    val diagnostics: List<CliExecutionDiagnostic>

    data class Help(
        override val presentation: CliPresentation
    ) : CliExecutionResult {
        override val exitCode: Int = CliProcessExit.SUCCESS.code
        override val artifacts: List<CliArtifact> = emptyList()
        override val diagnostics: List<CliExecutionDiagnostic> = emptyList()
    }

    data class Completed(
        override val presentation: CliPresentation,
        override val artifacts: List<CliArtifact> = emptyList(),
        val selectionDecision: TargetSelectionDecision = TargetSelectionDecision.NotSelected
    ) : CliExecutionResult {
        override val exitCode: Int = CliProcessExit.SUCCESS.code
        override val diagnostics: List<CliExecutionDiagnostic> = emptyList()

        init {
            if (selectionDecision == TargetSelectionDecision.NotSelected) {
                require(artifacts.none { it.role == CliArtifactRole.TARGET_MANIFEST || it.role == CliArtifactRole.RENDERED_TARGET }) {
                    "A command without explicit target selection cannot expose target artifacts."
                }
            }
        }
    }

    data class TargetNeutral(
        val planning: CliTargetNeutralPlanningEvidence,
        override val presentation: CliPresentation,
        override val artifacts: List<CliArtifact>
    ) : CliExecutionResult {
        override val exitCode: Int = CliProcessExit.SUCCESS.code
        override val diagnostics: List<CliExecutionDiagnostic> = emptyList()

        init {
            require(!planning.targetSelected) { "Target-neutral CLI result cannot claim target selection." }
            require(artifacts.any { it.role == CliArtifactRole.TARGET_NEUTRAL_PLANNING }) {
                "Target-neutral CLI result must expose planning evidence."
            }
            require(artifacts.none { it.role == CliArtifactRole.TARGET_MANIFEST || it.role == CliArtifactRole.RENDERED_TARGET }) {
                "Target-neutral CLI result cannot contain target manifest or rendered target artifacts."
            }
        }
    }

    data class Targeted(
        val selection: ExplicitTargetSelection,
        val evidence: CliTargetEvidence,
        val strict: Boolean,
        val renderRequested: Boolean,
        override val presentation: CliPresentation,
        override val artifacts: List<CliArtifact>
    ) : CliExecutionResult {
        override val exitCode: Int = when (evidence.outcome) {
            CliTargetEvidenceOutcome.EXECUTABLE -> CliProcessExit.SUCCESS.code
            CliTargetEvidenceOutcome.REVIEW_ONLY ->
                if (strict || renderRequested) CliProcessExit.REVIEW_REQUIRED.code else CliProcessExit.SUCCESS.code
            CliTargetEvidenceOutcome.BLOCKED -> CliProcessExit.BLOCKED.code
        }
        override val diagnostics: List<CliExecutionDiagnostic> = emptyList()

        init {
            require(evidence.manifest.target == selection.target) {
                "CLI target evidence does not match the explicit target selection."
            }
            require(evidence.targetSelection.target == selection.target &&
                evidence.targetSelection.origin == selection.evidence.origin &&
                evidence.targetSelection.source == selection.evidence.source) {
                "CLI target-selection report was not derived from the explicit selection used for materialization."
            }
            require(artifacts.any { it.role == CliArtifactRole.TARGET_MANIFEST }) {
                "Targeted CLI result must expose target manifest evidence."
            }
            require(artifacts.any { it.name == "target-selection-evidence.json" && it.role == CliArtifactRole.DIAGNOSTIC_EVIDENCE }) {
                "Targeted CLI result must expose target-selection provenance evidence."
            }

            val rendered = evidence.renderedArtifact
            val executableArtifactPresent = artifacts.any { it.role == CliArtifactRole.RENDERED_TARGET }
            require(executableArtifactPresent == (rendered?.kind == AdapterRenderedArtifactKind.EXECUTABLE_TARGET)) {
                "Rendered target role must be reserved for executable target syntax."
            }
            if (rendered?.kind == AdapterRenderedArtifactKind.REVIEW_EVIDENCE) {
                require(artifacts.any { it.name == rendered.fileName && it.role == CliArtifactRole.REVIEW_DOCUMENT }) {
                    "Review rendering must expose a review-document artifact, not a rendered target."
                }
            }
            val receiptPresent = artifacts.any {
                it.name == rendered?.evidenceFileName && it.role == CliArtifactRole.DIAGNOSTIC_EVIDENCE
            }
            require(receiptPresent == (rendered != null)) {
                "Every produced adapter artifact must expose its rendering evidence receipt."
            }
            require(rendered == null || renderRequested) {
                "CLI cannot expose an adapter artifact without an explicit render request."
            }
            require(rendered == null || rendered.evidence != null) {
                "CLI adapter artifact must retain its rendering evidence receipt."
            }
        }
    }

    data class Rejected(
        val command: String,
        val diagnostic: CliExecutionDiagnostic,
        override val presentation: CliPresentation
    ) : CliExecutionResult {
        override val exitCode: Int = when (diagnostic.code) {
            CliDiagnosticCode.INTEGRITY_BLOCKED -> CliProcessExit.BLOCKED.code
            CliDiagnosticCode.INTERNAL_ERROR -> CliProcessExit.INTERNAL_ERROR.code
            CliDiagnosticCode.TARGET_REQUIRED_FOR_RENDER,
            CliDiagnosticCode.UNKNOWN_COMMAND,
            CliDiagnosticCode.INVALID_INPUT -> CliProcessExit.INVALID_INPUT.code
        }
        override val artifacts: List<CliArtifact> = emptyList()
        override val diagnostics: List<CliExecutionDiagnostic> = listOf(diagnostic)
    }
}

internal class CliTypedFailure(
    val diagnosticCode: CliDiagnosticCode,
    message: String,
    cause: Throwable? = null
) : IllegalArgumentException(message, cause)

object CliPresenter {
    fun present(result: CliExecutionResult) {
        result.presentation.items.forEach { item ->
            when (item) {
                is CliPresentationItem.Section -> {
                    println("===== ${item.title} =====")
                    println(org.flowlang.cli.Json.mapper.writeValueAsString(item.value))
                }
                is CliPresentationItem.Text -> println(item.value)
            }
        }
    }
}
