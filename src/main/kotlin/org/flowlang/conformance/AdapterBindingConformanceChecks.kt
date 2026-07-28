package org.flowlang.conformance

import java.io.File
import org.flowlang.adapters.binding.AdapterBindingRoadmapLifecycleAuthority
import org.flowlang.adapters.binding.AdapterCapabilityBindingAuthority
import org.flowlang.ast.ActionNode
import org.flowlang.intent.CanonicalIntentMeaningAuthority
import org.flowlang.intent.IntentBindingParameterSource
import org.flowlang.intent.IntentBindingStatus
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentSecretRef
import org.flowlang.intent.IntentString
import org.flowlang.intent.IntentSystem
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.IntentYamlLoader
import org.flowlang.intent.StandardCapability
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.FlowPlanner

class AdapterBindingConformanceChecks(private val rootDir: File) {
    private val registry = ModuleRegistry.fromDirectory(File(rootDir, "modules"))

    fun checks(): List<ConformanceCheck> {
        val lifecycleResult = runCatching { AdapterBindingRoadmapLifecycleAuthority(rootDir).analyze() }
        val lifecycle = lifecycleResult.getOrNull()
        val authorityResult = runCatching { AdapterCapabilityBindingAuthority(rootDir, registry).analyze() }
        val authority = authorityResult.getOrNull()
        val runtimeErrors = resultErrors(runCatching(::runtimeBindingErrors))
        val effectErrors = resultErrors(runCatching(::semanticEffectAndProvenanceErrors))
        val polarityErrors = resultErrors(runCatching(::bindingPolarityErrors))

        val lifecycleErrors = buildList {
            lifecycleResult.exceptionOrNull()?.let { add(it.message ?: it.javaClass.simpleName) }
            lifecycle?.failedChecks?.forEach { id ->
                val failed = lifecycle.checks.first { it.id == id }
                add("$id:${failed.evidence.joinToString()}:${failed.message}")
            }
        }
        val authorityErrors = buildList {
            authorityResult.exceptionOrNull()?.let { add(it.message ?: it.javaClass.simpleName) }
            authority?.findings?.forEach { add("${it.code}:${it.binding}:${it.message}") }
        }
        return listOf(
            ConformanceCheck(
                name = LIFECYCLE_CHECK,
                passed = lifecycle?.status == "PASS",
                message = lifecycleErrors.takeIf(List<String>::isNotEmpty)?.joinToString(" | ")
            ),
            ConformanceCheck(
                name = EVIDENCE_CHECK,
                passed = authority?.status == "PASS",
                message = authorityErrors.takeIf(List<String>::isNotEmpty)?.joinToString(" | ")
            ),
            ConformanceCheck(
                name = RUNTIME_AUTHORITY_CHECK,
                passed = runtimeErrors.isEmpty(),
                message = runtimeErrors.takeIf(List<String>::isNotEmpty)?.joinToString(" | ")
            ),
            ConformanceCheck(
                name = EFFECT_PROVENANCE_CHECK,
                passed = effectErrors.isEmpty(),
                message = effectErrors.takeIf(List<String>::isNotEmpty)?.joinToString(" | ")
            ),
            ConformanceCheck(
                name = POLARITY_CHECK,
                passed = polarityErrors.isEmpty(),
                message = polarityErrors.takeIf(List<String>::isNotEmpty)?.joinToString(" | ")
            )
        )
    }

    private fun runtimeBindingErrors(): List<String> = buildList {
        val intent = IntentDocument(
            name = "a0-3-runtime-binding",
            systems = listOf(
                IntentSystem(
                    name = "argo",
                    type = "argocd",
                    config = mapOf(
                        "url" to IntentString("https://argo.example"),
                        "token" to IntentSecretRef("ARGO_TOKEN")
                    )
                )
            ),
            workflows = listOf(
                IntentWorkflow(
                    "delivery",
                    IntentWorkflowKind.DEPLOY,
                    listOf(
                        IntentStep(
                            id = "deploy",
                            capability = StandardCapability.DEPLOY,
                            uses = "argocd.sync",
                            params = mapOf(
                                "system" to IntentString("argo"),
                                "app" to IntentString("billing")
                            )
                        )
                    )
                )
            )
        )
        val resolution = CanonicalIntentMeaningAuthority(registry).resolve(intent)
        val binding = resolution.bindings.single()
        if (binding.status != IntentBindingStatus.RESOLVED) add("argocd.sync DEPLOY binding did not resolve: ${binding.issues}")
        if (binding.bindingContractId != "argocd.sync#DEPLOY") add("Unexpected binding contract id: ${binding.bindingContractId}")
        if (binding.resolvedParameters.keys != setOf("app", "wait", "timeout")) {
            add("Resolved parameters must contain authored app and descriptor defaults exactly once: ${binding.resolvedParameters.keys}")
        }
        if (binding.parameterSources["app"] != IntentBindingParameterSource.BINDING) add("app must retain BINDING provenance.")
        if (binding.parameterSources["wait"] != IntentBindingParameterSource.DEFAULT) add("wait must retain DEFAULT provenance.")
        if (binding.parameterSources["timeout"] != IntentBindingParameterSource.DEFAULT) add("timeout must retain DEFAULT provenance.")

        val action = IntentToAstPlanner(registry).plan(intent).flow.steps.single() as? ActionNode
        if (action == null) {
            add("Resolved explicit binding did not lower to ActionNode.")
        } else if (action.params.keys != binding.resolvedParameters.keys) {
            add("AST action parameters diverge from the resolved binding contract: ast=${action.params.keys} binding=${binding.resolvedParameters.keys}")
        }
    }

    private fun semanticEffectAndProvenanceErrors(): List<String> = buildList {
        val intentFile = File(rootDir, "examples/intent/checkout-build-image.intent.yaml")
        val intent = IntentYamlLoader.load(intentFile)
        val resolution = CanonicalIntentMeaningAuthority(registry).resolve(intent)
        val plan = FlowPlanner(registry).plan(IntentToAstPlanner(registry).plan(intent))
        val meaningByStep = resolution.meaning.workflows.flatMap { it.steps }.associateBy { it.id }
        if (resolution.bindings.any { it.status != IntentBindingStatus.RESOLVED }) {
            add("Executable reference contains a non-resolved explicit binding: ${resolution.bindings}")
        }
        for (task in plan.tasks) {
            val sourceId = task.sourceId
            val meaning = sourceId?.let(meaningByStep::get)
            if (meaning == null) {
                add("Task '${task.id}' lost source binding provenance.")
                continue
            }
            if (task.semanticCapability != meaning.capability.name) {
                add("Task '${task.id}' changed semantic capability ${meaning.capability.name} to ${task.semanticCapability}.")
            }
            if (task.effectModel != meaning.effects) {
                add("Task '${task.id}' replaced canonical effects during binding.")
            }
            if (task.module.isBlank() || task.action.isBlank() || task.target.isBlank()) {
                add("Task '${task.id}' lost explicit implementation selection provenance.")
            }
        }
    }

    private fun bindingPolarityErrors(): List<String> = buildList {
        val unboundIntent = IntentDocument(
            name = "unbound",
            workflows = listOf(
                IntentWorkflow(
                    "main",
                    IntentWorkflowKind.BUILD,
                    listOf(IntentStep("checkout", StandardCapability.CHECKOUT))
                )
            )
        )
        val unbound = CanonicalIntentMeaningAuthority(registry).resolve(unboundIntent).bindings.single()
        if (unbound.status != IntentBindingStatus.UNBOUND) add("Missing uses must remain UNBOUND, got ${unbound.status}.")

        val argoAction = registry.findAction("argocd", "sync")
        if (argoAction == null) {
            add("argocd.sync action is missing.")
        } else if (StandardCapability.SYNC.name in argoAction.implementedCapabilities) {
            add("argocd.sync still claims canonical SYNC without source/destination semantics.")
        }

        val invalid = IntentDocument(
            name = "unsupported-namespace",
            systems = listOf(
                IntentSystem(
                    "argo",
                    "argocd",
                    config = mapOf(
                        "url" to IntentString("https://argo.example"),
                        "token" to IntentSecretRef("ARGO_TOKEN")
                    )
                )
            ),
            workflows = listOf(
                IntentWorkflow(
                    "delivery",
                    IntentWorkflowKind.DEPLOY,
                    listOf(
                        IntentStep(
                            "deploy",
                            StandardCapability.DEPLOY,
                            uses = "argocd.sync",
                            params = mapOf(
                                "system" to IntentString("argo"),
                                "app" to IntentString("billing"),
                                "namespace" to IntentString("production")
                            )
                        )
                    )
                )
            )
        )
        val report = IntentCapabilityValidator(registry).validate(invalid)
        if (report.valid || report.issues.none { it.code == "BINDING_SEMANTIC_PARAM_UNSUPPORTED" }) {
            add("Unsupported canonical namespace did not fail explicit argocd.sync binding.")
        }
    }

    private fun resultErrors(result: Result<List<String>>): List<String> =
        result.getOrElse { listOf(it.message ?: it.javaClass.simpleName) }

    companion object {
        const val LIFECYCLE_CHECK = "adapters.a0.3.lifecycle-integrity"
        const val EVIDENCE_CHECK = "adapters.a0.3.binding-evidence-integrity"
        const val RUNTIME_AUTHORITY_CHECK = "adapters.a0.3.runtime-binding-authority"
        const val EFFECT_PROVENANCE_CHECK = "adapters.a0.3.semantic-effect-provenance-preservation"
        const val POLARITY_CHECK = "adapters.a0.3.unresolved-unsupported-polarity"
    }
}
