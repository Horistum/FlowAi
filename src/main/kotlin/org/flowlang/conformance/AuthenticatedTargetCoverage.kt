package org.flowlang.conformance

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.security.MessageDigest
import org.flowlang.capabilities.TargetCapability
import org.flowlang.adapters.certification.CertificationScenarioShape
import org.flowlang.generators.manifest.TargetNativeProjectionCatalog

/** Publication consumes an opaque, reauthenticated portfolio, never caller-supplied JSON or PASS flags. */
internal class AuthenticatedTargetCoverage private constructor(private val encoded: String, private val rendered: String) {
    fun json(): String = encoded
    fun markdown(): String = rendered

    companion object {
        private val mapper = jacksonObjectMapper()

        fun derive(portfolio: AdapterCertificationPortfolio, declarations: Map<String, TargetCapability>,
            providers: Map<String, TargetNativeProjectionCatalog>): AuthenticatedTargetCoverage {
            require(declarations.isNotEmpty() && declarations.all { (id, value) -> id.isNotBlank() && id == value.target }) {
                "Coverage requires uniquely keyed target declarations."
            }
            require(providers.all { (id, value) -> id == value.target && id in declarations }) {
                "Every composed provider must have its own target declaration."
            }
            val portfolioBytes = portfolio.json().toByteArray(Charsets.UTF_8)
            val source = mapper.readTree(portfolioBytes)
            require(source["referenceTargets"].map { it.asText() }.toSet() == providers.keys) {
                "Publication composition differs from the reauthenticated portfolio."
            }
            val assessments = source["assessments"].toList()
            require(assessments.all { it["matrix"]["adapter"]["target"].asText() in providers })
            val scenarios = assessments.sortedBy { it["scenarioId"].asText() }.map { assessment ->
                val matrix = assessment["matrix"]
                val row = matrix["rows"].single { it["status"].asText() == "BOUNDED_SCENARIO_EVIDENCE" }
                linkedMapOf<String, Any>("scenarioId" to assessment["scenarioId"].asText(),
                    "target" to matrix["adapter"]["target"].asText(), "construct" to row["construct"].asText(),
                    "proofSha256" to assessment["proofSha256"].asText(), "trustSha256" to assessment["trustSha256"].asText(),
                    "adapter" to matrix["adapter"], "claim" to assessment["claim"].asText(),
                    "executionMode" to assessment["executionMode"].asText(), "behavior" to row["behavior"])
            }
            val rows = declarations.keys.sorted().flatMap { target -> CertificationConstruct.entries.map { construct ->
                val ids = scenarios.filter { it["target"] == target && it["construct"] == construct.name }
                    .map { it.getValue("scenarioId") as String }
                linkedMapOf<String, Any>("target" to target, "construct" to construct.name,
                    "status" to if (ids.isEmpty()) "NO_BEHAVIORAL_EVIDENCE_IN_PORTFOLIO" else "BOUNDED_SCENARIO_EVIDENCE",
                    "scenarioIds" to ids)
            } }
            val targets = declarations.toSortedMap().map { (id, declared) ->
                val catalog = providers[id]
                val observed = scenarios.filter { it["target"] == id }
                val missing = rows.filter { it["target"] == id && (it["scenarioIds"] as List<*>).isEmpty() }
                    .map { it.getValue("construct") }
                val declarationsOnly = linkedMapOf("sequentialTasks" to declared.sequentialTasks, "parallel" to declared.parallel,
                    "conditions" to declared.conditions, "dynamicLoops" to declared.dynamicLoops, "match" to declared.match,
                    "retry" to declared.retry, "approvals" to declared.approvals, "errorHandlers" to declared.errorHandlers,
                    "artifacts" to declared.artifacts, "secrets" to declared.secrets, "nativeRuntime" to declared.nativeRuntime)
                val maturity = when {
                    observed.isEmpty() -> "NO_RUNTIME_EVIDENCE_IN_PORTFOLIO"
                    observed.all { (it.getValue("behavior") as JsonNode)["shape"].asText() == CertificationScenarioShape.NATIVE_LEAF_ONLY.name } -> "BOUNDED_NATIVE_LEAF_OBSERVED"
                    else -> "BOUNDED_SCENARIOS_OBSERVED"
                }
                linkedMapOf<String, Any>("target" to id, "providerAvailable" to (catalog != null),
                    "composition" to if (catalog == null) "NO_COMPOSED_PROVIDER" else "COMPOSED_PROVIDER",
                    "declaredCapabilities" to declarationsOnly.mapValues { it.value.name },
                    "declaredFeatures" to declared.features.toSortedMap().mapValues { it.value.name },
                    "nativeActionDefinitions" to catalog?.definitions.orEmpty().sortedWith(compareBy({ it.kind }, { it.reference }))
                        .map { mapOf("kind" to it.kind, "reference" to it.reference) },
                    "structuralProjectionDefinitions" to catalog?.structuralDefinitions.orEmpty().sortedBy { it.structure.name }
                        .map { mapOf("construct" to it.structure.name, "scope" to it.supportScope.name,
                            "kind" to it.kind, "reference" to it.reference) },
                    "evidenceMaturity" to maturity, "boundedScenarioCount" to observed.size,
                    "boundedConstructCount" to (CertificationConstruct.entries.size - missing.size), "missingConstructs" to missing)
            }
            val output = linkedMapOf<String, Any>("formatVersion" to 1,
                "scope" to "authenticated-declared-target-coverage", "sourceRevision" to source["sourceRevision"].asText(),
                "portfolioSha256" to sha(portfolioBytes), "publicSupportPromoted" to false, "portableExecution" to false,
                "targetCount" to targets.size, "constructCount" to CertificationConstruct.entries.size,
                "assessmentCount" to assessments.size, "targets" to targets, "rows" to rows, "scenarios" to scenarios,
                "sharedCanonicalCheckout" to source["sharedCanonicalCheckout"], "sharedCanonicalConditions" to source["sharedCanonicalConditions"])
            val markdown = buildString {
                append("# Authenticated target coverage\n\nSource revision: ${source["sourceRevision"].asText()}. ")
                append("Portfolio SHA-256: ${sha(portfolioBytes)}.\n\n")
                append("This publication covers every declared target, including profiles without a composed adapter. ")
                append("Registry declarations and projection definitions are separate from observed behavior. ")
                append("A bounded scenario does not certify a target-wide capability; missing evidence does not mean unsupported. ")
                append("No public support or portable execution is promoted.\n\n")
                append("| Target | Provider available | Evidence maturity | Scenarios | Constructs with evidence |\n| --- | --- | --- | --- | --- |\n")
                targets.forEach { append("| ${cell(it.getValue("target").toString())} | ${it["providerAvailable"]} | ${it["evidenceMaturity"]} | ${it["boundedScenarioCount"]} | ${it["boundedConstructCount"]}/${CertificationConstruct.entries.size} |\n") }
                append("\n## Construct coverage\n\n| Target | Construct | Evidence | Scenario identities |\n| --- | --- | --- | --- |\n")
                rows.forEach { append("| ${cell(it.getValue("target").toString())} | ${it["construct"]} | ${it["status"]} | ${cell((it["scenarioIds"] as List<*>).joinToString(", "))} |\n") }
                append("\n## Evidence and limits\n\n")
                append("The JSON publication preserves each scenario's proof/trust digests, adapter identity, source and canonical graph, execution mode, ")
                append("expected and observed baseline/mutant bytes, native artifact references, runtime prerequisites and limitations. ")
                append("Shared comparisons are inherited only from the reauthenticated portfolio; a shared construct label never implies portability. ")
                append("Secrets and general value/state continuity require their own behavioral oracles; checkout markers cannot fill those gaps. ")
                append("Runner hosts and same-workflow transport remain trusted. Signatures authenticate observations, not runner honesty.\n")
            }
            return AuthenticatedTargetCoverage(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(output) + "\n", markdown)
        }

        internal fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }

        private fun cell(value: String): String = buildString { value.forEach { c -> when {
            c.isISOControl() -> append(' ')
            c in "\\`*_{}[]<>()#+-.!|&\"'" -> append("&#${c.code};")
            else -> append(c)
        } } }
    }
}
