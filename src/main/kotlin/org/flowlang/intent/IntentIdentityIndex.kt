package org.flowlang.intent

import org.flowlang.identity.SemanticId

/** A description may change without changing either authored identity or its checked result symbol. */
internal data class IntentStepIdentity(val semanticId: SemanticId, val displayName: String?) {
    val resultName: String get() = resultSymbol(semanticId)
    companion object {
        fun from(step: IntentStep) = IntentStepIdentity(SemanticId.of(step.id), step.description)
        fun resultSymbol(id: SemanticId): String = id.authored.replace('-', '_')
    }
}

/** Per-compilation, exact-keyed inventory; no mutable global allocator and no collision repair. */
internal class IntentIdentityIndex private constructor(private val steps: Map<SemanticId, IntentStepIdentity>) {
    fun step(id: String): IntentStepIdentity = steps.getValue(SemanticId.of(id))

    companion object {
        fun capture(intent: IntentDocument): IntentIdentityIndex {
            val issues = issues(intent)
            require(issues.isEmpty()) { issues.joinToString("; ") { "${it.code}: ${it.message}" } }
            return IntentIdentityIndex(java.util.Collections.unmodifiableMap(
                intent.workflows.flatMap { it.steps }.associate { step ->
                    val identity = IntentStepIdentity.from(step)
                    identity.semanticId to identity
                }
            ))
        }

        fun issues(intent: IntentDocument): List<IntentValidationIssue> = buildList {
            fun error(code: String, message: String) { add(IntentValidationIssue("error", code, message)) }
            fun declarations(values: List<Pair<String, String>>, duplicateCode: String, blankCode: String = "INVALID_SEMANTIC_ID") {
                values.forEach { (name, path) ->
                    if (runCatching { SemanticId.of(name) }.isFailure) {
                        error(if (name.isBlank()) blankCode else "INVALID_SEMANTIC_ID", "$path must contain a nonblank, well-formed Unicode identity.")
                    }
                }
                values.groupBy { it.first }.toSortedMap().forEach { (name, owners) ->
                    if (owners.size > 1) error(duplicateCode,
                        "Identity '$name' is declared more than once at ${owners.map { it.second }.sorted()}.")
                }
            }
            declarations(listOf(intent.name to "name"), "DUPLICATE_INTENT_NAME", "INTENT_NAME_EMPTY")
            val inputs = intent.inputs.mapIndexed { index, item -> item.name to "inputs[$index].name" }
            val systems = intent.systems.mapIndexed { index, item -> item.name to "systems[$index].name" }
            declarations(inputs, "DUPLICATE_INTENT_INPUT")
            declarations(systems, "DUPLICATE_INTENT_SYSTEM")
            declarations(intent.workflows.mapIndexed { i, w -> w.name to "workflows[$i].name" }, "DUPLICATE_INTENT_WORKFLOW", "EMPTY_INTENT_WORKFLOW_NAME")
            declarations(intent.triggers.mapIndexed { i, t -> t.id to "triggers[$i].id" }, "DUPLICATE_INTENT_TRIGGER")
            declarations(intent.policies.mapIndexed { i, p -> p.name to "policies[$i].name" }, "DUPLICATE_INTENT_POLICY")
            declarations(intent.workflows.flatMapIndexed { wi, workflow ->
                workflow.steps.mapIndexed { si, step -> step.id to "workflows[$wi].steps[$si].id" }
            }, "DUPLICATE_INTENT_STEP")
            val needsStandard = intent.failure.notify || intent.failure.rollback || intent.workflows.any { workflow ->
                workflow.steps.any { it.capability != StandardCapability.APPROVE && it.uses.isNullOrBlank() }
            }
            if (needsStandard && intent.systems.any { it.name == "standard" && it.type != "standard" }) {
                error("INTENT_SYSTEM_COLLISION", "systems: authored system 'standard' conflicts with the required semantic standard system.")
            }
            val implicit = if (needsStandard && systems.none { it.first == "standard" })
                listOf("standard" to "implicit semantic system") else emptyList()
            intent.workflows.ifEmpty { listOf(IntentWorkflow(name = "main", kind = IntentWorkflowKind.CUSTOM)) }.forEachIndexed { wi, workflow ->
                val symbols = (inputs + systems + implicit).toMutableList()
                workflow.steps.forEachIndexed { si, step ->
                    val path = "workflows[$wi].steps[$si]"
                    runCatching { IntentStepIdentity.from(step) }.getOrNull()?.let { symbols += it.resultName to "$path.id" }
                    declarations(step.produces.mapIndexed { oi, output -> output to "$path.produces[$oi]" }, "DUPLICATE_STEP_OUTPUT", "EMPTY_STEP_OUTPUT")
                    symbols += step.produces.mapIndexed { oi, output -> output to "$path.produces[$oi]" }
                }
                symbols.groupBy { it.first }.toSortedMap().forEach { (symbol, owners) ->
                    if (owners.size > 1) error("INTENT_SYMBOL_COLLISION",
                        "workflows[$wi]: derived Flow binding '$symbol' has competing declarations at ${owners.map { it.second }.sorted()}. " +
                            "Choose distinct declared names; display text and punctuation normalization cannot select a producer.")
                }
            }
        }
    }
}
