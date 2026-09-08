package org.flowlang.conformance

internal enum class WorkflowTargetScenario { EXPLICIT_MERGE, MULTI_WORKFLOW, WORKFLOW_FAILURE }
internal enum class WorkflowTargetOutcome { EXECUTABLE, NON_EXECUTABLE, BLOCKED, NO_PROVIDER }

/** Observations of real compilation/projection calls, not executable authorization. */
internal data class WorkflowTargetObservation(
    val target: String,
    val scenario: WorkflowTargetScenario,
    val graphDigest: String,
    val outcome: WorkflowTargetOutcome,
    val executableBlocked: Boolean = false,
    val diagnosticBlocked: Boolean = false,
    val mergePreserved: Boolean = false,
    val renderBlocked: Boolean = false,
    val renderedText: String? = null,
    val failurePolicyPreserved: Boolean = false,
    val error: String? = null
)

internal object WorkflowTargetGatingMatrix {
    fun errors(
        observations: List<WorkflowTargetObservation>,
        targets: Set<String>,
        providers: Set<String>
    ): List<String> = buildList {
        val expected = targets.flatMap { target -> WorkflowTargetScenario.entries.map { target to it } }.toSet()
        val actual = observations.map { it.target to it.scenario }
        if (actual.size != expected.size || actual.toSet() != expected) {
            add("Target matrix must observe every registered target and scenario exactly once.")
        }
        if (targets.isEmpty() || "jenkins" !in providers || !targets.containsAll(providers)) {
            add("Target matrix registry/provider boundary is incomplete.")
        }
        observations.forEach { row ->
            val context = "${row.target}/${row.scenario}"
            if (!row.graphDigest.matches(Regex("[0-9a-f]{64}"))) add("$context lacks a graph digest.")
            if (row.error != null) add("$context failed unexpectedly: ${row.error}")
            when {
                row.scenario == WorkflowTargetScenario.MULTI_WORKFLOW -> {
                    if (row.outcome != WorkflowTargetOutcome.BLOCKED ||
                        !row.executableBlocked || !row.diagnosticBlocked || row.renderedText != null
                    ) add("$context did not reject both non-flattening request boundaries.")
                }
                row.target !in providers -> {
                    if (row.outcome != WorkflowTargetOutcome.NO_PROVIDER || row.renderedText != null) {
                        add("$context invented a projection provider.")
                    }
                }
                row.scenario == WorkflowTargetScenario.EXPLICIT_MERGE -> {
                    if (row.outcome != WorkflowTargetOutcome.NON_EXECUTABLE ||
                        !row.mergePreserved || !row.failurePolicyPreserved ||
                        !row.executableBlocked || row.diagnosticBlocked ||
                        !row.renderBlocked || row.renderedText != null
                    ) add("$context lost merge/failure meaning or authorized unsupported executable syntax.")
                }
                row.target == "jenkins" -> {
                    if (row.outcome != WorkflowTargetOutcome.EXECUTABLE || !row.failurePolicyPreserved ||
                        row.executableBlocked || row.renderBlocked
                    ) add("$context lost its typed native failure projection.")
                    addAll(jenkinsPropagationErrors(row.renderedText).map { "$context: $it" })
                }
                else -> {
                    if (row.outcome != WorkflowTargetOutcome.NON_EXECUTABLE ||
                        !row.executableBlocked || row.diagnosticBlocked ||
                        !row.failurePolicyPreserved || !row.renderBlocked || row.renderedText != null
                    ) add("$context promoted unsupported failure execution or lost diagnostic policy evidence.")
                }
            }
        }
    }

    fun jenkinsPropagationErrors(text: String?): List<String> = buildList {
        if (text == null) {
            add("No native Jenkins rendering was produced.")
            return@buildList
        }
        val body = text.indexOf("Proceed with work")
        val caught = text.indexOf("catch (flowError)")
        val binding = text.indexOf("def error = flowError")
        val handler = text.indexOf("Handle workflow failure")
        val rethrow = text.indexOf("throw flowError")
        if (!(body >= 0 && body < caught && caught < binding && binding < handler && handler < rethrow)) {
            add("Rendering must retain body, catch, error binding, handler and PROPAGATE rethrow in order.")
        }
        if (Regex("\\bthrow flowError\\b").findAll(text).count() != 1) {
            add("The workflow must propagate the caught error exactly once.")
        }
    }
}
