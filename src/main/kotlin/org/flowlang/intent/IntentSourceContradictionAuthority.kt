package org.flowlang.intent

/**
 * Detects contradictions between retained source language and normalized intent.
 *
 * Scenario normalizers are heuristic by definition. Their output must therefore
 * never override an explicit negative statement merely because the same noun is
 * present in the request. This authority is intentionally independent from any
 * scenario pack so every normalization entrypoint receives the same fail-closed
 * protection before AST lowering.
 */
object IntentSourceContradictionAuthority {
    fun validationIssues(intent: IntentDocument): List<IntentValidationIssue> {
        val source = intent.description?.trim().orEmpty()
        if (source.isBlank()) return emptyList()

        return buildList {
            if (explicitlyDeniesBackup(source) && declaresPositiveBackupEvidence(intent)) {
                add(
                    IntentValidationIssue(
                        level = "error",
                        code = "CONTRADICTORY_BACKUP_EVIDENCE",
                        message = "The retained source explicitly denies an available backup, but the normalized intent declares positive backup evidence. The contradiction must be clarified; a BACKUP step or backup=required value cannot be synthesized from the word 'backup' alone."
                    )
                )
            }
        }
    }

    internal fun explicitlyDeniesBackup(source: String): Boolean =
        IntentSourceDirectiveAuthority
            .analyze(source, IntentSourceDirectiveConcept.BACKUP)
            .hasNegatedMention

    private fun declaresPositiveBackupEvidence(intent: IntentDocument): Boolean {
        val steps = intent.workflows.flatMap(IntentWorkflow::steps)
        if (steps.any { it.capability == StandardCapability.BACKUP }) return true

        return steps.any { step ->
            when (val value = step.params["backup"]) {
                is IntentBoolean -> value.value
                is IntentString -> value.value.trim().lowercase() in POSITIVE_CONFIRMATIONS
                else -> false
            }
        }
    }

    private val POSITIVE_CONFIRMATIONS = setOf("true", "yes", "required", "confirmed", "available", "created")


}
