package org.flowlang.conformance

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import org.flowlang.adapters.certification.*
import org.flowlang.distribution.reference.ReferenceTargetProjections

/** Synthetic signed protocol fixtures; no test claims to execute Jenkins. */
class AdapterCertificationPortfolioTests {
    companion object {
        private val mapper = jacksonObjectMapper()
        private val revision = "a".repeat(40)
        private val scenarios = JenkinsCheckoutRuntimeCertification.Scenario.entries
        private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        private val files: Map<String, ByteArray> by lazy {
            val files = linkedMapOf<String, ByteArray>()
            for (scenario in scenarios) {
                val directory = AdapterCertificationPortfolio.relativeDirectory(scenario)
                fun write(name: String, bytes: ByteArray) { files["$directory/$name"] = bytes }
                fun json(name: String, value: Any) = write(name, mapper.writeValueAsBytes(value))
                val p = JenkinsCheckoutRuntimeCertification.prepare(File("."), "sha256:" + "b".repeat(64), scenario)
                val spec = p.bundle.scenarios.single()
                val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
                val trust = CertificationObservationTrust("c".repeat(32), mapOf("isolated-jenkins-runner" to key.public),
                    scenario.runIds.map { CertificationAuthorizedRun(it, scenario.id, it.takeUnless { id -> id == "baseline" }, "isolated-jenkins-runner") })
                json("trust.json", mapOf("challenge" to trust.challenge, "publicKey" to Base64.getEncoder().encodeToString(key.public.encoded), "authorizedRuns" to trust.authorizedRuns))
                val runs = scenario.runIds.map { id ->
                    val expected = spec.negativeMutants.singleOrNull { it.id == id }?.expectedObservation ?: spec.expectedObservation
                    (mapper.readTree(p.evidence.getValue(expected.id)) as ObjectNode).apply {
                        put("id", id); put("artifactSha256", sha(p.artifacts.getValue(id))); put("buildNumber", 1); put("finished", true)
                    }
                }
                val raw = mapper.writeValueAsBytes(mapOf("status" to "completed", "jenkins" to "2.580.1", "java" to "25",
                    "plugins" to p.runtime.filter { it.id.startsWith("plugin:") }.associate { it.id.removePrefix("plugin:") to it.version }, "runs" to runs))
                write("runtime/runtime.json", raw)
                val signed = JenkinsCheckoutRuntimeCertification.observations(p, raw).map { observation ->
                    val signer = Signature.getInstance("Ed25519"); signer.initSign(key.private)
                    signer.update(CertificationObservationAuthentication.signingBytes("isolated-jenkins-runner", trust.challenge, observation))
                    SignedCertificationObservation("isolated-jenkins-runner", trust.challenge, observation, signer.sign())
                }
                p.evidence.forEach { (id, bytes) -> write("artifacts/" + id.replace(':', '-') + ".evidence", bytes) }
                val resolver = CertificationEvidenceResolver { p.evidence[it.id] }
                val assessment = CertificationEvidenceViews.assess(p.bundle, p.bundle.adapter, listOf(p.bound),
                    ReferenceTargetProjections.nativeCatalogs.getValue("jenkins"), signed, trust, resolver)
                check(assessment.admission.valid)
                val view = requireNotNull(assessment.view)
                val matrix = AdapterBehaviorMatrix.derive(assessment, scenario, resolver)
                val outputs = mapOf("evidence-view.json" to view.json(), "evidence-view.md" to view.markdown(),
                    "behavior-matrix.json" to matrix.json(), "behavior-matrix.md" to matrix.markdown())
                outputs.forEach { (name, text) -> write(name, text.toByteArray()) }
                p.artifacts.forEach { (id, bytes) -> write("artifacts/$id.Jenkinsfile", bytes) }
                write("artifacts/" + if (scenario.flowSource) "source.flow" else "source.intent.yaml", requireNotNull(p.bound.resolve(p.bound.fixture)))
                write("artifacts/canonical-graph.json", requireNotNull(p.bound.resolve(p.bound.canonicalGraph)))
                json("proof.json", mapOf("status" to "passed", "claim" to scenario.claim, "sourceRevision" to revision,
                    "imageId" to "sha256:" + "b".repeat(64), "bundle" to p.bundle, "admission" to assessment.admission, "publicSupportPromoted" to false,
                    "evidenceViews" to outputs.filterKeys { it.startsWith("evidence-") }.mapValues { sha(it.value.toByteArray()) },
                    "behaviorMatrix" to outputs.filterKeys { it.startsWith("behavior-") }.mapValues { sha(it.value.toByteArray()) },
                    "observations" to signed.map { mapOf("runnerId" to it.runnerId, "challenge" to it.challenge,
                        "observation" to it.observation, "signature" to Base64.getEncoder().encodeToString(it.signature())) }))
            }
            files
        }
    }

    private class Fixture(val directory: File) {
        val first = scenarios.first()
        fun file(name: String) = File(directory, AdapterCertificationPortfolio.relativeDirectory(first) + "/" + name)
        fun receipts() = scenarios.map { scenario ->
            val dir = File(directory, AdapterCertificationPortfolio.relativeDirectory(scenario))
            PortfolioAssessmentReceipt(scenario.id, sha(File(dir, "proof.json").readBytes()), sha(File(dir, "trust.json").readBytes()))
        }
        fun derive(receipts: List<PortfolioAssessmentReceipt> = receipts(), selectedRevision: String = revision) =
            AdapterCertificationPortfolio.derive(File("."), selectedRevision, directory, receipts)
        fun edit(name: String, change: (JsonNode) -> Unit) {
            val node = mapper.readTree(file(name)); change(node); file(name).writeBytes(mapper.writeValueAsBytes(node))
        }
        fun jobs(): ObjectNode {
            val rows = receipts()
            return mapper.createObjectNode().apply {
                scenarios.groupBy { AdapterCertificationPortfolio.relativeDirectory(it).substringBefore('/') }.forEach { (job, entries) ->
                    val receipt = mapOf("artifact" to job, "sourceRevision" to revision, "assessments" to rows.filter { row -> entries.any { it.id == row.scenarioId } })
                    set<JsonNode>(job, mapper.valueToTree(mapOf("result" to "success", "outputs" to mapOf("receipt" to mapper.writeValueAsString(receipt)))))
                }
            }
        }
    }

    private fun fixture(test: (Fixture) -> Unit) {
        val dir = createTempDirectory("portfolio-test-").toFile()
        try {
            files.forEach { (path, bytes) -> File(dir, path).apply { parentFile.mkdirs(); writeBytes(bytes) } }
            test(Fixture(dir))
        } finally { dir.deleteRecursively() }
    }

    @Test fun completePortfolioRetainsEveryScenarioAndMakesMissingTargetEvidenceExplicit() = fixture { f ->
        val result = f.derive(); val doc = mapper.readTree(result.json())
        assertEquals(10, doc["assessmentCount"].asInt())
        assertEquals(36, doc["rows"].size())
        assertFalse(doc["publicSupportPromoted"].asBoolean()); assertFalse(doc["portableExecution"].asBoolean())
        assertEquals(5, doc["rows"].count { it["status"].asText() == "BOUNDED_SCENARIO_EVIDENCE" })
        assertTrue(doc["rows"].filter { it["target"].asText() != "jenkins" }.all {
            it["scenarioIds"].isEmpty && it["status"].asText() == "NO_BEHAVIORAL_EVIDENCE_IN_PORTFOLIO" })
        val matrices = doc["assessments"].map { it["matrix"] }
        assertEquals(32, matrices.sumOf { matrix -> val row = matrix["rows"].single { !it["behavior"].isNull }; 1 + row["behavior"]["negativeMutants"].size() })
        assertTrue(result.markdown().contains("not general construct support"))
    }

    @Test fun receiptOrderDoesNotChangeOutputAndLaterFileChangesCannotMutateSnapshot() = fixture { f ->
        val value = f.derive(); val json = value.json(); val markdown = value.markdown()
        assertEquals(json, f.derive(f.receipts().reversed()).json())
        f.file("proof.json").writeText("{}")
        assertEquals(json, value.json()); assertEquals(markdown, value.markdown())
    }

    @Test fun missingDuplicateAndUnexpectedScenariosCannotProducePartialSuccess() = fixture { f ->
        val r = f.receipts()
        for (bad in listOf(emptyList(), r.dropLast(1), r + r.first(), r.dropLast(1) + r.first(), r.dropLast(1) + r.last().copy(scenarioId = "invented")))
            assertFailsWith<IllegalArgumentException> { f.derive(bad) }
    }

    @Test fun differentSourceRevisionFailsBeforeAggregation() = fixture { f ->
        assertFailsWith<IllegalArgumentException> { f.derive(selectedRevision = "b".repeat(40)) }
        f.edit("proof.json") { (it as ObjectNode).put("sourceRevision", "b".repeat(40)) }
        assertFailsWith<IllegalArgumentException> { f.derive() }
    }

    @Test fun ownerPinnedProofAndTrustCannotBeReplacedByCandidateFiles() = fixture { f ->
        val receipts = f.receipts()
        f.file("trust.json").appendText("\n")
        assertFailsWith<IllegalArgumentException> { f.derive(receipts) }
        val repinned = f.receipts()
        f.file("proof.json").appendText("\n")
        assertFailsWith<IllegalArgumentException> { f.derive(repinned) }
    }

    @Test fun aRepinnedForgedSignatureStillFailsAuthentication() = fixture { f ->
        f.edit("proof.json") { ((it["observations"][0]) as ObjectNode).put("signature", Base64.getEncoder().encodeToString(ByteArray(64))) }
        assertFailsWith<IllegalArgumentException> { f.derive() }
    }

    @Test fun aRepinnedReplacementKeyCannotValidateTheOriginalStatements() = fixture { f ->
        val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        f.edit("trust.json") { (it as ObjectNode).put("publicKey", Base64.getEncoder().encodeToString(key.public.encoded)) }
        assertFailsWith<IllegalArgumentException> { f.derive() }
    }

    @Test fun nativeResultsCannotDisagreeWithTheSignedObservation() = fixture { f ->
        f.edit("runtime/runtime.json") { (it["runs"][0] as ObjectNode).put("marker", "forged") }
        assertFailsWith<IllegalArgumentException> { f.derive() }
    }

    @Test fun changedImplementationOrInventedAdmissionCannotBorrowAnOldPass() = fixture { f ->
        val original = f.file("proof.json").readBytes()
        f.edit("proof.json") { (it["bundle"]["adapter"] as ObjectNode).put("implementationSha256", "f".repeat(64)) }
        assertFailsWith<IllegalArgumentException> { f.derive() }
        f.file("proof.json").writeBytes(original)
        f.edit("proof.json") { (it["admission"] as ObjectNode).put("valid", false) }
        assertFailsWith<IllegalArgumentException> { f.derive() }
    }

    @Test fun sourceGraphArtifactsAndPublishedViewsMustMatchReconstruction() = fixture { f ->
        for (name in listOf("artifacts/source.intent.yaml", "artifacts/canonical-graph.json", "artifacts/baseline.Jenkinsfile", "artifacts/observed-baseline.evidence", "behavior-matrix.json", "evidence-view.md")) {
            val file = f.file(name); val original = file.readBytes(); file.writeText("forged")
            assertFailsWith<IllegalArgumentException>(name) { f.derive() }
            file.writeBytes(original)
        }
    }

    @Test fun oversizedMissingAndSymlinkedEvidenceIsRejected() = fixture { f ->
        val file = f.file("runtime/runtime.json"); val original = file.readBytes()
        file.writeBytes(ByteArray(65537))
        assertFailsWith<IllegalArgumentException> { f.derive() }
        file.delete()
        assertFailsWith<IllegalArgumentException> { f.derive() }
        val target = File(f.directory, "outside.json").apply { writeBytes(original) }
        java.nio.file.Files.createSymbolicLink(file.toPath(), target.toPath())
        assertFailsWith<IllegalArgumentException> { f.derive() }
    }

    @Test fun producerReceiptsRequireAllSuccessfulSameRevisionJobsAndExactInventories() = fixture { f ->
        fun read(jobs: JsonNode) = AdapterCertificationPortfolio.receiptsFromJobs(mapper.writeValueAsBytes(jobs), revision)
        assertEquals(f.receipts().toSet(), read(f.jobs()).toSet())
        val missing = f.jobs().apply { remove("jenkins-checkout-runtime") }
        assertFailsWith<IllegalArgumentException> { read(missing) }
        val failed = f.jobs().apply { (get("jenkins-checkout-runtime") as ObjectNode).put("result", "failure") }
        assertFailsWith<IllegalArgumentException> { read(failed) }
        assertFailsWith<IllegalArgumentException> { AdapterCertificationPortfolio.receiptsFromJobs(mapper.writeValueAsBytes(f.jobs()), "b".repeat(40)) }
        val duplicate = f.jobs()
        val output = duplicate["jenkins-checkout-runtime"]["outputs"] as ObjectNode
        val receipt = mapper.readTree(output["receipt"].asText())
        (receipt["assessments"] as com.fasterxml.jackson.databind.node.ArrayNode).add(receipt["assessments"][0])
        output.put("receipt", mapper.writeValueAsString(receipt))
        assertFailsWith<IllegalArgumentException> { read(duplicate) }
    }
}
