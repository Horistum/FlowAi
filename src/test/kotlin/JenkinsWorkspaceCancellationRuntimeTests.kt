package org.flowlang.conformance

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import kotlin.test.*
import org.flowlang.adapters.certification.*
import org.flowlang.distribution.reference.ReferenceTargetProjections
import org.flowlang.generators.manifest.TargetStructuralProjectionKind

/** Synthetic protocol regressions; only the dedicated CI producer supplies runtime evidence. */
class JenkinsWorkspaceCancellationRuntimeTests {
    companion object {
        private val scenarios = listOf(JenkinsCheckoutRuntimeCertification.Scenario.PARALLEL_WORKSPACES_FAIL_FAST_APPROVE,
            JenkinsCheckoutRuntimeCertification.Scenario.PARALLEL_WORKSPACES_CANCEL,
            JenkinsCheckoutRuntimeCertification.Scenario.PARALLEL_WORKSPACES_FAILURE)
        private val captured by lazy { scenarios.associateWith {
            JenkinsCheckoutRuntimeCertification.prepare(File("."), "sha256:" + "a".repeat(64), it)
        } }
    }
    private val mapper = jacksonObjectMapper()
    private val left = "Approve left branch?"
    private val right = "Approve right branch?"
    private fun prepare(s: JenkinsCheckoutRuntimeCertification.Scenario) = captured.getValue(s).let { it.copy(evidence = it.evidence.toMutableMap()) }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun record(p: JenkinsCheckoutRuntimeCertification.Prepared): ObjectNode {
        fun checkout(branch: String, owner: Int? = null, directory: Int? = owner): Map<String, Any?> = linkedMapOf(
            "branch" to branch, "parallelBranch" to owner?.let { "parallel_1_branch_$it" },
            "directory" to directory?.let { ".flow-parallel/parallel_1_branch_$it" })
        val first = checkout("selected", 1); val second = checkout("alternate", 2); val root = checkout("selected")
        val broken = checkout("missing-revision", 1)
        fun workspace(events: List<Map<String, Any?>>): Map<String, Any?> {
            val remaining = events.drop(events.indexOf(root) + 1)
            fun marker(index: Int) = remaining.lastOrNull {
                it["directory"] == ".flow-parallel/parallel_1_branch_$index" && it["branch"] != "missing-revision"
            }?.get("branch")?.let { "$it\n" }
            return linkedMapOf("leftMarker" to marker(1), "rightMarker" to marker(2), "checkouts" to events)
        }
        fun pending(messages: List<String>, events: List<Map<String, Any?>>) = linkedMapOf("messages" to messages,
            "paused" to true, "checkoutCount" to events.size, "marker" to if (root in events) "selected\n" else null,
            "building" to true, "complete" to false, "workspaces" to workspace(events))
        val rejection = linkedMapOf("type" to "org.jenkinsci.plugins.workflow.steps.FlowInterruptedException", "result" to "ABORTED",
            "causes" to listOf("org.jenkinsci.plugins.workflow.support.steps.input.Rejection"), "inputMessage" to left)
        val cancellation = rejection + mapOf("causes" to listOf("org.jenkinsci.plugins.workflow.cps.steps.ParallelStep\$FailFastCause"), "inputMessage" to right)
        val nativeError = linkedMapOf("type" to "hudson.AbortException", "message" to
            "Couldn't find any revision to build. Verify the repository and branch configuration for this job.")
        val nativeTerminal = linkedMapOf("type" to "hudson.AbortException", "result" to null, "causes" to emptyList<String>(), "inputMessage" to null)
        val approve = p.scenario == scenarios[0]; val reject = p.scenario == scenarios[1]
        val runs = p.artifacts.map { (id, bytes) ->
            val initial = when (id) { "early-successor" -> listOf(root); "early-branch-work" -> listOf(first); else -> emptyList() }
            val stopped = !approve && id !in listOf("disabled-cancellation", "omitted-failure")
            val failed = !approve && id !in listOf("suppressed-rejection", "suppressed-failure", "omitted-failure")
            val after = if (reject) initial else if (!approve) {
                if (id == "omitted-failure") emptyList() else initial + broken
            } else when (id) {
                "omitted-left-checkout" -> emptyList()
                "early-successor" -> listOf(root, first)
                "swapped-workspaces" -> listOf(checkout("selected", 1, 2))
                else -> listOf(first)
            }
            val rightWork = when (id) { "aliased-workspaces", "swapped-workspaces" -> checkout("alternate", 2, 1); else -> second }
            val final = after + (if (stopped) emptyList() else listOf(rightWork)) +
                (if (failed || id == "early-successor") emptyList() else listOf(root))
            val errors = if (!approve && !reject && id != "omitted-failure") listOf(nativeError) else emptyList()
            linkedMapOf<String, Any?>("id" to id, "result" to if (failed) (if (reject) "ABORTED" else "FAILURE") else "SUCCESS", "finished" to true,
                "buildNumber" to 1, "artifactSha256" to sha(bytes), "marker" to if (root in final) "selected\n" else null,
                "checkoutCount" to final.size, "checkoutErrors" to errors,
                "parallel" to linkedMapOf("inputMessages" to listOf(left, right),
                    "initialPending" to pending(listOf(left, right), initial), "afterFirst" to if (stopped) null else pending(listOf(right), after),
                    "decisions" to listOf(mapOf("message" to left, "action" to if (reject) "reject" else "approve")) +
                        (if (stopped) emptyList() else listOf(mapOf("message" to right, "action" to "approve"))),
                    "inputErrors" to (if (reject) listOf(rejection) else emptyList()) + (if (stopped) listOf(cancellation) else emptyList()),
                    "terminalError" to if (failed) (if (reject) rejection else nativeTerminal) else null),
                "workspaces" to workspace(final)).apply {
                if (p.scenario.workspaceFailure) {
                    val index = final.indexOf(broken) + 1
                    put("checkoutErrorIndices", if (errors.isEmpty()) emptyList<Int>() else listOf(index))
                    put("terminalError", if (failed) nativeError + ("checkoutIndex" to index) else null)
                }
            }
        }
        return mapper.valueToTree(mapOf("status" to "completed", "jenkins" to "2.580.1", "java" to "25",
            "plugins" to p.runtime.filter { it.id.startsWith("plugin:") }.associate { it.id.removePrefix("plugin:") to it.version }, "runs" to runs))
    }
    private fun runs(p: JenkinsCheckoutRuntimeCertification.Prepared, raw: ObjectNode) = JenkinsCheckoutRuntimeCertification.observations(p, mapper.writeValueAsBytes(raw))
    private fun assess(p: JenkinsCheckoutRuntimeCertification.Prepared, raw: ObjectNode): CertificationEvidenceViewResult {
        val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val trust = CertificationObservationTrust("f".repeat(32), mapOf("unit-test" to key.public),
            p.scenario.runIds.map { CertificationAuthorizedRun(it, p.bound.id, it.takeUnless { id -> id == "baseline" }, "unit-test") })
        val signed = runs(p, raw).map { observation ->
            val signer = Signature.getInstance("Ed25519"); signer.initSign(key.private)
            signer.update(CertificationObservationAuthentication.signingBytes("unit-test", trust.challenge, observation))
            SignedCertificationObservation("unit-test", trust.challenge, observation, signer.sign())
        }
        return CertificationEvidenceViews.assess(p.bundle, p.bundle.adapter, listOf(p.bound),
            ReferenceTargetProjections.nativeCatalogs.getValue("jenkins"), signed, trust, CertificationEvidenceResolver { p.evidence[it.id] })
    }


    @Test fun workspacePoliciesPreserveJoinCancellationAndOriginalFailure() {
        for (scenario in scenarios) {
            val p = prepare(scenario)
            assertContentEquals(p.bound.resolve(p.bound.artifact), p.artifacts.getValue("baseline"))
            assertContains(p.bound.subjects, CertificationSubject.Structural(TargetStructuralProjectionKind.PARALLEL))
            assertEquals(p.artifacts.size, p.artifacts.values.map(::sha).distinct().size)
            val result = assess(p, record(p))
            assertTrue(result.admission.valid, result.admission.findings.toString())
            assertFalse(assertNotNull(result.view).publicSupportPromoted)
            assertFalse(assertNotNull(result.view).portableExecution)
            val matrix = mapper.readTree(AdapterBehaviorMatrix.derive(result, scenario, CertificationEvidenceResolver { p.evidence[it.id] }).json())
            assertEquals(listOf("ARTIFACTS_WORKSPACES"), matrix["rows"].filter { !it["behavior"].isNull }.map { it["construct"].asText() })
        }
    }

    @Test fun disabledCancellationCannotMasqueradeAsSiblingStop() {
        for (scenario in scenarios.drop(1)) {
            val p = prepare(scenario); val raw = record(p)
            assertEquals(raw["runs"][0]["result"], raw["runs"][1]["result"])
            assertEquals(raw["runs"][0]["marker"], raw["runs"][1]["marker"])
            (raw["runs"][0] as ObjectNode).set<JsonNode>("parallel", raw["runs"][1]["parallel"])
            assertTrue(runs(p, raw).all { it.outcome == CertificationRunOutcome.COMPLETED })
            assertFalse(assess(p, raw).admission.valid)
            val decided = record(p)
            (decided["runs"][0]["parallel"]["decisions"] as com.fasterxml.jackson.databind.node.ArrayNode)
                .add(mapper.valueToTree<JsonNode>(mapOf("message" to right, "action" to "approve")))
            assertFalse(assess(p, decided).admission.valid)
        }
    }

    @Test fun originalCheckoutFailureCannotBeReplacedByCancellation() {
        val p = prepare(scenarios[2])
        for (change in listOf<(ObjectNode) -> Unit>(
            { (it["terminalError"] as ObjectNode).putNull("checkoutIndex") },
            { (it["checkoutErrors"][0] as ObjectNode).put("message", "Different failure") },
            { (it["parallel"]["terminalError"] as ObjectNode).put("inputMessage", right) },
            { (it["parallel"]["inputErrors"][0] as ObjectNode).putArray("causes").add("org.jenkinsci.plugins.workflow.support.steps.input.Rejection") })) {
            val raw = record(p); change(raw["runs"][0] as ObjectNode)
            assertEquals(CertificationRunOutcome.INFRASTRUCTURE_FAILURE, runs(p, raw)[0].outcome)
            assertFalse(assess(p, raw).admission.valid)
        }
        val wrong = record(p); val early = wrong["runs"].last() as ObjectNode
        (early["checkoutErrorIndices"] as com.fasterxml.jackson.databind.node.ArrayNode).removeAll().add(1)
        (early["terminalError"] as ObjectNode).put("checkoutIndex", 1)
        assertEquals(CertificationRunOutcome.INFRASTRUCTURE_FAILURE, runs(p, wrong).last().outcome)
        assertFalse(assess(p, wrong).admission.valid)
    }

    @Test fun approvalAndJoinOrderingCannotBeHidden() {
        for (scenario in scenarios) {
            val p = prepare(scenario)
            for (id in p.scenario.runIds.filter { it.startsWith("early-") }) {
                val raw = record(p); val mutant = raw["runs"].single { it["id"].asText() == id }
                (raw["runs"][0] as ObjectNode).set<JsonNode>("parallel", mutant["parallel"])
                assertFalse(assess(p, raw).admission.valid)
            }
        }
        val p = prepare(scenarios[0])
        for (id in listOf("aliased-workspaces", "swapped-workspaces", "omitted-left-checkout")) {
            val raw = record(p); val mutant = raw["runs"].single { it["id"].asText() == id }
            (raw["runs"][0] as ObjectNode).set<JsonNode>("workspaces", mutant["workspaces"])
            (raw["runs"][0] as ObjectNode).put("checkoutCount", mutant["checkoutCount"].asInt())
            assertFalse(assess(p, raw).admission.valid)
        }
    }

    @Test fun malformedRuntimeBindingsAndArtifactMutantsFailClosed() {
        for (scenario in scenarios) {
            val p = prepare(scenario); val original = p.artifacts.getValue("baseline").toString(Charsets.UTF_8)
            assertContains(original, "failFast: true")
            if (scenario != scenarios[0]) assertEquals(original.replace("failFast: true", "failFast: false"),
                p.artifacts.getValue("disabled-cancellation").toString(Charsets.UTF_8))
            for (changed in listOf(original + original, original.replace("failFast: true", "failFast: false"), original.replace(left, "Changed?")))
                assertFails { JenkinsParallelWorkspaceCertification.artifacts(changed, changed.toByteArray(), scenario == scenarios[1], true, scenario.workspaceFailure) }
            val raw = record(p); (raw["runs"][0] as ObjectNode).put("artifactSha256", "0".repeat(64))
            assertFails { runs(p, raw) }
            val plugins = record(p); (plugins["plugins"] as ObjectNode).put("workflow-durable-task-step", "unknown")
            assertFails { runs(p, plugins) }
            val fields = record(p); (fields["runs"][0]["parallel"]["initialPending"]["workspaces"] as ObjectNode).remove("checkouts")
            assertFails { runs(p, fields) }
        }
    }
}
