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
class JenkinsParallelWorkspaceRuntimeTests {
    companion object {
        private val scenarios = listOf(JenkinsCheckoutRuntimeCertification.Scenario.PARALLEL_WORKSPACES_APPROVE,
            JenkinsCheckoutRuntimeCertification.Scenario.PARALLEL_WORKSPACES_REJECT)
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
        fun workspace(events: List<Map<String, Any?>>): Map<String, Any?> {
            // The first root clone deletes pre-existing children; this fixture exports no branch workspace.
            val remaining = events.drop(events.indexOf(root) + 1)
            fun marker(index: Int) = remaining.lastOrNull { it["directory"] == ".flow-parallel/parallel_1_branch_$index" }?.get("branch")?.let { "$it\n" }
            return linkedMapOf("leftMarker" to marker(1), "rightMarker" to marker(2), "checkouts" to events)
        }
        fun pending(messages: List<String>, events: List<Map<String, Any?>>) = linkedMapOf("messages" to messages,
            "paused" to true, "checkoutCount" to events.size, "marker" to if (root in events) "selected\n" else null,
            "building" to true, "complete" to false, "workspaces" to workspace(events))
        val rejection = linkedMapOf("type" to "org.jenkinsci.plugins.workflow.steps.FlowInterruptedException", "result" to "ABORTED",
            "causes" to listOf("org.jenkinsci.plugins.workflow.support.steps.input.Rejection"), "inputMessage" to left)
        val reject = p.scenario == scenarios[1]
        val runs = p.artifacts.map { (id, bytes) ->
            val initial = when (id) { "early-successor" -> listOf(root); "early-branch-work" -> listOf(first); else -> emptyList() }
            val after = if (reject) initial else when (id) {
                "omitted-left-checkout" -> emptyList()
                "early-successor" -> listOf(root, first)
                "swapped-workspaces" -> listOf(checkout("selected", 1, 2))
                else -> listOf(first)
            }
            val failed = reject && id != "suppressed-rejection"
            val rightWork = when (id) { "aliased-workspaces", "swapped-workspaces" -> checkout("alternate", 2, 1); else -> second }
            val final = after + rightWork + if (failed || id == "early-successor") emptyList() else listOf(root)
            linkedMapOf("id" to id, "result" to if (failed) "ABORTED" else "SUCCESS", "finished" to true,
                "buildNumber" to 1, "artifactSha256" to sha(bytes), "marker" to if (root in final) "selected\n" else null,
                "checkoutCount" to final.size, "checkoutErrors" to emptyList<Any>(),
                "parallel" to linkedMapOf("inputMessages" to listOf(left, right),
                    "initialPending" to pending(listOf(left, right), initial), "afterFirst" to pending(listOf(right), after),
                    "decisions" to listOf(mapOf("message" to left, "action" to if (reject) "reject" else "approve"), mapOf("message" to right, "action" to "approve")),
                    "inputErrors" to if (reject) listOf(rejection) else emptyList(), "terminalError" to if (failed) rejection else null),
                "workspaces" to workspace(final))
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

    @Test fun branchDirectoriesPreserveApprovalWorkJoinAndRejection() {
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

    @Test fun equalSuccessCannotHideAliasedSwappedOrOmittedBranchWork() {
        val p = prepare(scenarios[0])
        for (index in 1..3) {
            val raw = record(p)
            assertEquals(raw["runs"][0]["result"], raw["runs"][index]["result"])
            assertEquals(raw["runs"][0]["marker"], raw["runs"][index]["marker"])
            (raw["runs"][0] as ObjectNode).set<JsonNode>("workspaces", raw["runs"][index]["workspaces"])
            if (index == 3) (raw["runs"][0] as ObjectNode).put("checkoutCount", 2)
            assertFalse(assess(p, raw).admission.valid)
        }
    }

    @Test fun branchWorkMustFollowApprovalAndRejectionMustSkipOwnCheckout() {
        for (scenario in scenarios) {
            val p = prepare(scenario)
            for (id in listOf("early-branch-work", "early-successor")) {
                val raw = record(p); val mutant = raw["runs"].single { it["id"].asText() == id }
                (raw["runs"][0] as ObjectNode).set<JsonNode>("parallel", mutant["parallel"])
                assertFalse(assess(p, raw).admission.valid)
            }
        }
        val p = prepare(scenarios[1]); val raw = record(p)
        assertEquals(1, raw["runs"][0]["checkoutCount"].asInt())
        assertTrue(raw["runs"][0]["workspaces"]["leftMarker"].isNull)
        assertEquals("alternate\n", raw["runs"][0]["workspaces"]["rightMarker"].asText())
        (raw["runs"][0]["parallel"]["terminalError"] as ObjectNode).put("inputMessage", right)
        assertEquals(CertificationRunOutcome.INFRASTRUCTURE_FAILURE, runs(p, raw)[0].outcome)
        assertFalse(assess(p, raw).admission.valid)
    }

    @Test fun malformedWorkspaceFramesBindingsAndPrerequisitesFailClosed() {
        val p = prepare(scenarios[0])
        for (change in listOf<(ObjectNode) -> Unit>(
            { it.remove("leftMarker") }, { it.put("invented", true) },
            { (it["checkouts"][0] as ObjectNode).remove("parallelBranch") },
            { (it["checkouts"][0] as ObjectNode).put("directory", "x".repeat(257)) })) {
            val raw = record(p); change(raw["runs"][0]["workspaces"] as ObjectNode)
            assertFails { runs(p, raw) }
        }
        val count = record(p); (count["runs"][0]["parallel"]["afterFirst"] as ObjectNode).put("checkoutCount", 4294967297L)
        assertFails { runs(p, count) }
        val plugin = record(p); (plugin["plugins"] as ObjectNode).put("workflow-durable-task-step", "unknown")
        assertFails { runs(p, plugin) }
        val bytes = record(p); (bytes["runs"][0] as ObjectNode).put("artifactSha256", "0".repeat(64))
        assertFails { runs(p, bytes) }
        val text = p.artifacts.getValue("baseline").toString(Charsets.UTF_8)
        for (changed in listOf(text + text, text.replace("failFast: false", "failFast: true"), text.replace("parallel_1_branch_2", "parallel_1_branch_1")))
            assertFails { JenkinsParallelWorkspaceCertification.artifacts(changed, changed.toByteArray(), false) }
    }
}
