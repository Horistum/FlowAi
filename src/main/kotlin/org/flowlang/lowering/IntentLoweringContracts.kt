package org.flowlang.lowering

import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentPolicyType
import org.flowlang.intent.IntentValue

/** How an accepted intent value is represented after lowering. */
enum class IntentLoweringDisposition { PRESERVED, TRANSFORMED }

data class IntentLoweringEvidence(
    val sourcePath: String,
    val targetPath: String,
    val disposition: IntentLoweringDisposition,
    val valueKind: String,
    val note: String? = null
)

data class IntentLoweringReport(
    val contractVersion: String = "1.0",
    val evidence: List<IntentLoweringEvidence> = emptyList()
)

data class IntentSourceMetadata(
    val description: String? = null,
    val workflows: List<IntentWorkflowMetadata> = emptyList(),
    val policies: List<IntentPolicyMetadata> = emptyList(),
    val systemPurposes: Map<String, String> = emptyMap(),
    val failure: IntentFailureMetadata = IntentFailureMetadata()
)

data class IntentWorkflowMetadata(
    val name: String,
    val kind: String,
    val stepIds: List<String> = emptyList()
)

data class IntentPolicyMetadata(
    val name: String,
    val type: String,
    val condition: String? = null,
    val message: String? = null
)

data class IntentFailureMetadata(
    val notify: Boolean = false,
    val rollback: Boolean = false,
    val stopOnError: Boolean = true
)

/**
 * Deterministic coverage evidence for accepted Standard Intent input.
 *
 * This report is not a substitute for semantic fields. It points to the real AST
 * representation so a consumer can prove that an accepted value was not silently
 * discarded between the intent and canonical artifacts.
 */
object IntentLoweringAuthority {
    fun sourceMetadata(intent: IntentDocument): IntentSourceMetadata = IntentSourceMetadata(
        description = intent.description,
        workflows = intent.workflows.map { workflow ->
            IntentWorkflowMetadata(workflow.name, workflow.kind.name, workflow.steps.map { it.id })
        },
        policies = intent.policies.map { policy ->
            IntentPolicyMetadata(policy.name, policy.type.name, policy.condition, policy.message)
        },
        systemPurposes = intent.systems.mapNotNull { system -> system.purpose?.let { system.name to it } }.toMap(),
        failure = IntentFailureMetadata(intent.failure.notify, intent.failure.rollback, intent.failure.stopOnError)
    )

    fun report(intent: IntentDocument): IntentLoweringReport = IntentLoweringReport(evidence = buildList {
        add(preserved("$.name", "$.flow.name", "string"))
        intent.description?.let { add(preserved("$.description", "$.metadata.sourceIntent.description", "string")) }
        intent.inputs.forEachIndexed { index, input ->
            add(preserved("$.inputs[$index].name", "$.flow.input[$index].name", "string"))
            add(preserved("$.inputs[$index].type", "$.flow.input[$index].valueType", "string"))
            add(preserved("$.inputs[$index].required", "$.flow.input[$index].required", "boolean"))
            input.default?.let { add(preserved("$.inputs[$index].default", "$.flow.input[$index].default", it.valueKind())) }
        }
        intent.systems.forEachIndexed { index, system ->
            add(preserved("$.systems[$index].name", "$.flow.systems[$index].name", "string"))
            add(transformed("$.systems[$index].type", "$.flow.systems[$index].systemType", "string", "Canonical system type normalization."))
            system.purpose?.let { add(preserved("$.systems[$index].purpose", "$.metadata.sourceIntent.systemPurposes.${system.name}", "string")) }
            system.config.forEach { (key, value) -> add(preserved("$.systems[$index].config.$key", "$.flow.systems[$index].config.$key", value.valueKind())) }
        }
        intent.triggers.forEachIndexed { index, trigger ->
            add(preserved("$.triggers[$index]", "$.flow.triggers[$index]", "trigger"))
        }
        intent.workflows.forEachIndexed { workflowIndex, workflow ->
            add(preserved("$.workflows[$workflowIndex].name", "$.metadata.sourceIntent.workflows[$workflowIndex].name", "string"))
            add(preserved("$.workflows[$workflowIndex].kind", "$.metadata.sourceIntent.workflows[$workflowIndex].kind", "enum"))
            workflow.steps.forEachIndexed { stepIndex, step ->
                val source = "$.workflows[$workflowIndex].steps[$stepIndex]"
                val target = "$.flow.steps[$stepIndex]"
                add(transformed("$source.id", "$target.result", "string", "Step id becomes a stable result binding and source id."))
                add(preserved("$source.capability", "$target.semanticCapability", "enum"))
                step.description?.let { add(preserved("$source.description", "$target.sourceDescription", "string")) }
                step.uses?.let { add(transformed("$source.uses", "$target.module/$target.action", "binding", "Explicit binding resolves to module and action.")) }
                if (step.requires.isNotEmpty()) add(transformed("$source.requires", "$target.dependsOn", "list", "Source step ids become canonical result dependencies."))
                if (step.produces.isNotEmpty()) add(preserved("$source.produces", "$target.declaredOutputs", "list"))
                step.params.forEach { (key, value) ->
                    val targetField = if (key in setOf("system", "tool", "engine")) "bindingMetadata" else "params"
                    add(preserved("$source.params.$key", "$target.$targetField.$key", value.valueKind()))
                }
            }
        }
        intent.policies.forEachIndexed { index, policy ->
            val target = if (policy.type in setOf(IntentPolicyType.APPROVAL, IntentPolicyType.SAFETY, IntentPolicyType.CUSTOM)) {
                "$.flow.controlRequirements"
            } else {
                "$.metadata.sourceIntent.policies[$index]"
            }
            add(transformed("$.policies[$index]", target, "policy", "Policy source is preserved and supported policy meaning becomes a control requirement."))
        }
        add(transformed("$.failure", "$.flow.errorHandler", "failure-policy", "Failure notification and rollback lower to canonical error-handler actions; stopOnError=true is the canonical default."))
    })

    private fun preserved(source: String, target: String, kind: String) =
        IntentLoweringEvidence(source, target, IntentLoweringDisposition.PRESERVED, kind)

    private fun transformed(source: String, target: String, kind: String, note: String) =
        IntentLoweringEvidence(source, target, IntentLoweringDisposition.TRANSFORMED, kind, note)

    private fun IntentValue.valueKind(): String = kind
}
