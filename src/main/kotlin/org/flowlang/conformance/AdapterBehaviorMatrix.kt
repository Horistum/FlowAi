package org.flowlang.conformance

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.security.MessageDigest
import org.flowlang.adapters.certification.*
import org.flowlang.generators.manifest.TargetStructuralProjectionKind

/** AR-06 obligations, not capability declarations. Checkout is an additional bounded leaf case. */
internal enum class CertificationConstruct {
    CONDITIONS, PARALLELISM, LOOPS, MATCH, RETRY, ERROR_HANDLING, APPROVALS,
    ARTIFACTS_WORKSPACES, SECRETS, VALUE_STATE_CONTINUITY, FAILURE_PROPAGATION, NATIVE_CHECKOUT
}

/**
 * Conformance-owned diagnostic output. Only immutable serialized snapshots escape.
 * The runner selects the scenario's oracle; graph occurrence alone never assigns behavior.
 * Provider-specific entry points assign the bounded oracle; serialized views are never inputs.
 * A common construct label does not establish equivalence between different fixtures.
 */
internal class AdapterBehaviorMatrix private constructor(private val encoded: String, private val rendered: String) {
    fun json(): String = encoded
    fun markdown(): String = rendered

    companion object {
        private val mapper = jacksonObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

        fun derive(
            assessment: CertificationEvidenceViewResult,
            scenario: JenkinsCheckoutRuntimeCertification.Scenario,
            resolver: CertificationEvidenceResolver
        ): AdapterBehaviorMatrix {
            val construct = when (scenario) {
                JenkinsCheckoutRuntimeCertification.Scenario.CHECKOUT,
                JenkinsCheckoutRuntimeCertification.Scenario.SHARED_CHECKOUT -> CertificationConstruct.NATIVE_CHECKOUT
                JenkinsCheckoutRuntimeCertification.Scenario.RETRY_FAILURE,
                JenkinsCheckoutRuntimeCertification.Scenario.RETRY_SUCCESS -> CertificationConstruct.RETRY
                JenkinsCheckoutRuntimeCertification.Scenario.FAILURE -> CertificationConstruct.FAILURE_PROPAGATION
                JenkinsCheckoutRuntimeCertification.Scenario.CONDITION_TRUE,
                JenkinsCheckoutRuntimeCertification.Scenario.CONDITION_FALSE -> CertificationConstruct.CONDITIONS
                JenkinsCheckoutRuntimeCertification.Scenario.ERROR_FAILURE,
                JenkinsCheckoutRuntimeCertification.Scenario.ERROR_SUCCESS,
                JenkinsCheckoutRuntimeCertification.Scenario.RECOVERY_FAILURE,
                JenkinsCheckoutRuntimeCertification.Scenario.RECOVERY_SUCCESS -> CertificationConstruct.ERROR_HANDLING
                JenkinsCheckoutRuntimeCertification.Scenario.APPROVAL_APPROVE,
                JenkinsCheckoutRuntimeCertification.Scenario.APPROVAL_REJECT -> CertificationConstruct.APPROVALS
            }
            val requiredSubject = when (construct) {
                CertificationConstruct.RETRY -> CertificationSubject.Structural(TargetStructuralProjectionKind.RETRY)
                CertificationConstruct.CONDITIONS -> CertificationSubject.Structural(TargetStructuralProjectionKind.CONDITION)
                CertificationConstruct.ERROR_HANDLING -> CertificationSubject.Structural(TargetStructuralProjectionKind.ERROR_BOUNDARY)
                CertificationConstruct.APPROVALS -> CertificationSubject.Semantic("approval.manual")
                CertificationConstruct.NATIVE_CHECKOUT, CertificationConstruct.FAILURE_PROPAGATION -> CertificationSubject.Semantic("git.checkout")
                else -> error("No behavioral oracle is assigned to this construct.")
            }
            return derive(assessment, scenario.id, "jenkins", scenario.runIds, construct, requiredSubject, resolver)
        }

        fun deriveGitHubCheckout(assessment: CertificationEvidenceViewResult, resolver: CertificationEvidenceResolver): AdapterBehaviorMatrix =
            derive(assessment, GitHubActionsCheckoutRuntimeCertification.SCENARIO, "github-actions",
                SharedCheckoutFixture.runIds, CertificationConstruct.NATIVE_CHECKOUT,
                CertificationSubject.Semantic("git.checkout"), resolver)

        private fun derive(assessment: CertificationEvidenceViewResult, scenarioId: String, target: String,
            runIds: List<String>, construct: CertificationConstruct, requiredSubject: CertificationSubject,
            resolver: CertificationEvidenceResolver): AdapterBehaviorMatrix {
            require(assessment.admission.valid) { "A rejected assessment cannot publish a behavior matrix." }
            val view = requireNotNull(assessment.view)
            require(assessment.admission.admittedScenarioIds == listOf(scenarioId))
            val bound = view.scenarios.single()
            require(bound.id == scenarioId && view.adapter.target == target)
            require(view.coverage.any { it.subject == requiredSubject && scenarioId in it.scenarioIds &&
                it.status == CertificationOccurrenceStatus.OBSERVED_IN_SCENARIO })
            require(bound.runs.map { it.mutantId ?: "baseline" }.toSet() == runIds.toSet())
            require(bound.runs.size == runIds.size)
            val baseline = bound.runs.single { it.mutantId == null }
            val mutants = bound.runs.filter { it.mutantId != null }.sortedBy { it.mutantId }
            require(mutants.isNotEmpty())

            // Revalidate every byte after the authenticated assessment. A stateful resolver
            // cannot replace admitted observations between admission and display.
            fun observation(ref: CertificationEvidenceReference): String {
                require(ref.sizeBytes in 1..65536)
                val resolved = requireNotNull(resolver.resolve(ref))
                require(resolved.size == ref.sizeBytes)
                val bytes = resolved.copyOf()
                require(sha(bytes) == ref.sha256)
                require(mapper.readTree(bytes).isObject) { "Behavior observations must be JSON objects." }
                return bytes.toString(Charsets.UTF_8)
            }
            fun run(run: CertificationRunView): Map<String, Any?> {
                val expected = observation(run.expectedObservation)
                val observed = observation(run.observed)
                require(expected == observed)
                if (run.mutantId != null) require(run.observed.sha256 != baseline.observed.sha256)
                return linkedMapOf("evidence" to run, "expectedObservationUtf8" to expected,
                    "observedUtf8" to observed, "matchesOracle" to true,
                    "distinguishesBaseline" to (run.mutantId != null))
            }
            val positive = run(baseline)
            val negative = mutants.map(::run)
            val behavior = linkedMapOf<String, Any?>("scenarioId" to bound.id, "shape" to bound.shape,
                "source" to bound.source, "canonicalGraph" to bound.canonicalGraph,
                "runtimePrerequisites" to bound.runtimePrerequisites, "requiredSubject" to requiredSubject,
                "baseline" to positive, "negativeMutants" to negative, "limitations" to view.limitations)
            val rows = CertificationConstruct.entries.map { entry ->
                linkedMapOf<String, Any?>("construct" to entry.name, "target" to view.adapter.target,
                    "status" to if (entry == construct) "BOUNDED_SCENARIO_EVIDENCE" else "NO_BEHAVIORAL_EVIDENCE_IN_ASSESSMENT",
                    "behavior" to behavior.takeIf { entry == construct })
            }
            val output = linkedMapOf<String, Any?>("formatVersion" to 1, "scope" to "authenticated-single-scenario-behavior-matrix",
                "adapter" to view.adapter, "assessmentChallenge" to view.assessmentChallenge,
                "publicSupportPromoted" to false, "portableExecution" to false,
                "rows" to rows)
            val markdown = buildString {
                append("# Construct behavior matrix\n\n")
                append("Target: ${cell(view.adapter.target)}; scenario: ${cell(bound.id)}.\n\n")
                append("Scope: this authenticated assessment only. Missing evidence does not mean an unsupported target feature. ")
                append("A bounded scenario is not general construct support. Public support is not promoted; portable execution is not established.\n\n")
                append("| Construct | Target | Evidence in this assessment |\n| --- | --- | --- |\n")
                rows.forEach { append("| ${it["construct"]} | ${cell(view.adapter.target)} | ${it["status"]} |\n") }
                append("\n## Expected behavior and observed mutants\n\n")
                append("Source SHA-256: ${bound.source.sha256}. Canonical graph SHA-256: ${bound.canonicalGraph.sha256}.\n\n")
                append("The exact UTF-8 oracle and observation bytes are preserved as strings in the JSON matrix. ")
                append("The observations below are equal to their admitted oracles. Each mutant differs from the baseline observation.\n\n")
                append("| Run | Expected and observed behavior | Artifact SHA-256 | Observation SHA-256 |\n| --- | --- | --- | --- |\n")
                (listOf(positive) + negative).forEach { row ->
                    val evidence = row.getValue("evidence") as CertificationRunView
                    append("| ${cell(evidence.mutantId ?: "baseline")} | ${cell(row.getValue("observedUtf8") as String)} | ${evidence.artifact.sha256} | ${evidence.observed.sha256} |\n")
                }
                append("\n## Limits\n\n")
                view.limitations.forEach { append("- ${cell(it)}\n") }
                append("\nCheckout markers do not establish artifact transfer, secret handling or general value/state continuity. ")
                append("Scenario categorization and oracle adequacy belong to conformance; signatures authenticate observations, not runner honesty.\n")
            }
            return AdapterBehaviorMatrix(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(output) + "\n", markdown)
        }

        private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun cell(value: String): String = buildString {
            value.forEach { c -> when {
                c.isISOControl() -> append(' ')
                c in "\\`*_{}[]<>()#+-.!|&\"'" -> append("&#${c.code};")
                else -> append(c)
            } }
        }
    }
}
