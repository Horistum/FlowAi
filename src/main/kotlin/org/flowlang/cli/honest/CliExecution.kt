package org.flowlang.cli.honest

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
        override val exitCode: Int = 0
        override val artifacts: List<CliArtifact> = emptyList()
        override val diagnostics: List<CliExecutionDiagnostic> = emptyList()
    }

    data class Completed(
        override val presentation: CliPresentation,
        override val artifacts: List<CliArtifact> = emptyList(),
        val selectionDecision: TargetSelectionDecision = TargetSelectionDecision.NotSelected,
        override val exitCode: Int = 0
    ) : CliExecutionResult {
        override val diagnostics: List<CliExecutionDiagnostic> = emptyList()

        init {
            require(exitCode >= 0) { "CLI completed result cannot use a negative exit code." }
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
        override val exitCode: Int = 0
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
        override val exitCode: Int =
            if ((strict || renderRequested) && evidence.outcome != CliTargetEvidenceOutcome.EXECUTABLE) 3 else 0
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
            require(artifacts.any { it.role == CliArtifactRole.RENDERED_TARGET } == (evidence.renderedArtifact != null)) {
                "Rendered artifact role must agree with concrete rendered evidence."
            }
            require(evidence.renderedArtifact == null || renderRequested) {
                "CLI cannot expose rendered target syntax without an explicit render request."
            }
        }
    }

    data class Rejected(
        val command: String,
        val diagnostic: CliExecutionDiagnostic,
        override val presentation: CliPresentation
    ) : CliExecutionResult {
        override val exitCode: Int = 2
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
