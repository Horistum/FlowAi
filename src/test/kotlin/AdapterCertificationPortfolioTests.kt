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

/** Synthetic signed protocol fixtures; no unit test claims provider execution. */
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
                        if (scenario == JenkinsCheckoutRuntimeCertification.Scenario.SHARED_CHECKOUT) {
                            put("result", "SUCCESS"); put("checkoutCount", if (id == "omitted-checkout") 0 else 1); putArray("checkoutErrors")
                        }
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
            files.putAll(githubFiles())
            files
        }
        private fun githubFiles(): Map<String, ByteArray> {
            val files = linkedMapOf<String, ByteArray>()
            val id = GitHubActionsCheckoutRuntimeCertification.SCENARIO
            fun write(name: String, bytes: ByteArray) { files["$id/$name"] = bytes }
            fun json(name: String, value: Any) = write(name, mapper.writeValueAsBytes(value))
            val envelope = File(GitHubActionsCheckoutRuntimeCertification.WORKFLOW).readBytes()
            val p = GitHubActionsCheckoutRuntimeCertification.prepare(File("."), revision, "d".repeat(40), "20261010.1", envelope)
            val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val runner = GitHubActionsCheckoutRuntimeCertification.RUNNER
            val runIds = SharedCheckoutFixture.runIds
            val challenge = "c".repeat(32)
            val trust = CertificationObservationTrust(challenge, mapOf(runner to key.public), runIds.map {
                CertificationAuthorizedRun(it, id, it.takeUnless { value -> value == "baseline" }, runner) })
            val owner = mapOf("sourceRevision" to revision, "workflowRevision" to "d".repeat(40), "runnerImage" to "20261010.1",
                "runId" to "123", "attempt" to "1", "challenge" to challenge, "publicKey" to Base64.getEncoder().encodeToString(key.public.encoded))
            json("trust.json", owner)
            val spec = p.bundle.scenarios.single()
            val signed = runIds.map { runId ->
                val expected = spec.negativeMutants.singleOrNull { it.id == runId }?.expectedObservation ?: spec.expectedObservation
                val raw = p.evidence.getValue(expected.id)
                val observation = GitHubActionsCheckoutRuntimeCertification.observation(p, runId, raw)
                val signer = Signature.getInstance("Ed25519"); signer.initSign(key.private)
                signer.update(CertificationObservationAuthentication.signingBytes(runner, challenge, observation))
                val signature = signer.sign()
                json("$runId.json", mapOf("raw" to raw.toString(Charsets.UTF_8), "signature" to Base64.getEncoder().encodeToString(signature)))
                SignedCertificationObservation(runner, challenge, observation, signature)
            }
            val resolver = CertificationEvidenceResolver { p.evidence[it.id] }
            val assessment = CertificationEvidenceViews.assess(p.bundle, p.bundle.adapter, listOf(p.bound), p.provider, signed, trust, resolver)
            check(assessment.admission.valid)
            val view = requireNotNull(assessment.view)
            val matrix = AdapterBehaviorMatrix.deriveGitHubCheckout(assessment, resolver)
            val outputs = mapOf("evidence-view.json" to view.json(), "evidence-view.md" to view.markdown(),
                "behavior-matrix.json" to matrix.json(), "behavior-matrix.md" to matrix.markdown())
            outputs.forEach { (name, text) -> write(name, text.toByteArray()) }
            p.artifacts.forEach { (runId, bytes) -> write("artifacts/$runId.yml", bytes) }
            p.evidence.forEach { (name, bytes) -> write("artifacts/" + name.replace(':', '-') + ".evidence", bytes) }
            write("artifacts/source.intent.yaml", requireNotNull(p.bound.resolve(p.bound.fixture)))
            write("artifacts/canonical-graph.json", requireNotNull(p.bound.resolve(p.bound.canonicalGraph)))
            write("artifacts/execution-envelope.yml", envelope)
            json("proof.json", mapOf("status" to "passed", "claim" to "native-leaf-only", "executionMode" to "native-leaf-in-checked-envelope",
                "sourceRevision" to revision, "workflowRevision" to owner.getValue("workflowRevision"), "runId" to "123", "attempt" to "1",
                "bundle" to p.bundle, "admission" to assessment.admission, "publicSupportPromoted" to false, "portableExecution" to false,
                "evidenceViews" to outputs.filterKeys { it.startsWith("evidence-") }.mapValues { sha(it.value.toByteArray()) },
                "behaviorMatrix" to outputs.filterKeys { it.startsWith("behavior-") }.mapValues { sha(it.value.toByteArray()) },
                "observations" to signed.map { mapOf("runnerId" to it.runnerId, "challenge" to it.challenge,
                    "observation" to it.observation, "signature" to Base64.getEncoder().encodeToString(it.signature())) }))
            return files
        }

    }

    private class Fixture(val directory: File) {
        val first = scenarios.first()
        fun file(name: String) = File(directory, AdapterCertificationPortfolio.relativeDirectory(first) + "/" + name)
        fun githubFile(name: String) = File(directory, GitHubActionsCheckoutRuntimeCertification.SCENARIO + "/" + name)
        fun receipts() = (scenarios.map { it.id to AdapterCertificationPortfolio.relativeDirectory(it) } +
            (GitHubActionsCheckoutRuntimeCertification.SCENARIO to GitHubActionsCheckoutRuntimeCertification.SCENARIO)).map { (id, path) ->
            val dir = File(directory, path)
            PortfolioAssessmentReceipt(id, sha(File(dir, "proof.json").readBytes()), sha(File(dir, "trust.json").readBytes()))
        }
        fun derive(receipts: List<PortfolioAssessmentReceipt> = receipts(), selectedRevision: String = revision) =
            AdapterCertificationPortfolio.derive(File("."), selectedRevision, directory, receipts)
        fun edit(name: String, change: (JsonNode) -> Unit) {
            val node = mapper.readTree(file(name)); change(node); file(name).writeBytes(mapper.writeValueAsBytes(node))
        }
        fun editGithub(name: String, change: (JsonNode) -> Unit) {
            val node = mapper.readTree(githubFile(name)); change(node); githubFile(name).writeBytes(mapper.writeValueAsBytes(node))
        }
        fun jobs(): ObjectNode {
            val rows = receipts()
            return mapper.createObjectNode().apply {
                val groups = scenarios.groupBy { AdapterCertificationPortfolio.relativeDirectory(it).substringBefore('/') }
                    .mapValues { (_, entries) -> entries.map { it.id } } +
                    (GitHubActionsCheckoutRuntimeCertification.SCENARIO to listOf(GitHubActionsCheckoutRuntimeCertification.SCENARIO))
                groups.forEach { (job, entries) ->
                    val receipt = mapOf("artifact" to job, "sourceRevision" to revision, "assessments" to rows.filter { row -> row.scenarioId in entries })
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
        assertEquals(12, doc["assessmentCount"].asInt())
        assertEquals(36, doc["rows"].size())
        assertFalse(doc["publicSupportPromoted"].asBoolean()); assertFalse(doc["portableExecution"].asBoolean())
        assertEquals(6, doc["rows"].count { it["status"].asText() == "BOUNDED_SCENARIO_EVIDENCE" })
        assertTrue(doc["rows"].filter { it["target"].asText() !in setOf("jenkins", "github-actions") }.all {
            it["scenarioIds"].isEmpty && it["status"].asText() == "NO_BEHAVIORAL_EVIDENCE_IN_PORTFOLIO" })
        val matrices = doc["assessments"].map { it["matrix"] }
        assertEquals(38, matrices.sumOf { matrix -> val row = matrix["rows"].single { !it["behavior"].isNull }; 1 + row["behavior"]["negativeMutants"].size() })
        val github = doc["assessments"].single { it["scenarioId"].asText() == GitHubActionsCheckoutRuntimeCertification.SCENARIO }
        assertEquals("native-leaf-only", github["claim"].asText())
        assertEquals("native-leaf-in-checked-envelope", github["executionMode"].asText())
        val covered = github["matrix"]["rows"].filter { !it["behavior"].isNull }
        assertEquals(listOf("NATIVE_CHECKOUT"), covered.map { it["construct"].asText() })
        val jenkins = doc["assessments"].single { it["scenarioId"].asText() == scenarios.first().id }
        assertNotEquals(jenkins["matrix"]["rows"].single { !it["behavior"].isNull }["behavior"]["canonicalGraph"], covered.single()["behavior"]["canonicalGraph"])
        val shared = doc["sharedCanonicalCheckout"]
        assertTrue(shared["observableEquivalence"].asBoolean())
        assertEquals("bounded-native-checkout-observable-equivalence", shared["scope"].asText())
        assertEquals(SharedCheckoutFixture.runIds, shared["runs"].map { it["runId"].asText() })
        assertEquals(2, shared["scenarioIds"].size())
        assertTrue(result.markdown().contains("not general construct support"))
        assertTrue(result.markdown().contains("does not establish cross-target equivalence"))
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

    @Test fun secondProducerMustBePresentSuccessfulAndHaveTheExactScenario() = fixture { f ->
        val job = GitHubActionsCheckoutRuntimeCertification.SCENARIO
        fun read(jobs: JsonNode) = AdapterCertificationPortfolio.receiptsFromJobs(mapper.writeValueAsBytes(jobs), revision)
        assertFailsWith<IllegalArgumentException> { read(f.jobs().apply { remove(job) }) }
        for (status in listOf("failure", "skipped", "cancelled"))
            assertFailsWith<IllegalArgumentException> { read(f.jobs().apply { (get(job) as ObjectNode).put("result", status) }) }
        val jobs = f.jobs()
        (jobs[job]["outputs"] as ObjectNode).put("receipt", f.jobs()["jenkins-checkout-runtime"]["outputs"]["receipt"].asText())
        assertFailsWith<IllegalArgumentException> { read(jobs) }
        assertFailsWith<IllegalArgumentException> { f.derive(f.receipts().filter { it.scenarioId != job }) }
    }

    @Test fun secondAdapterProofAndTrustStayPinnedOutsideCandidateFiles() = fixture { f ->
        for (name in listOf("proof.json", "trust.json")) {
            val receipts = f.receipts(); val file = f.githubFile(name); val original = file.readBytes()
            file.appendText("\n")
            assertFailsWith<IllegalArgumentException> { f.derive(receipts) }
            file.writeBytes(original)
        }
    }

    @Test fun secondAdapterCannotReplaceItsKeyOrChallengeEvenWhenRepinned() = fixture { f ->
        val file = f.githubFile("trust.json"); val original = file.readBytes()
        val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        for ((field, value) in listOf("publicKey" to Base64.getEncoder().encodeToString(key.public.encoded), "challenge" to "e".repeat(32), "runnerImage" to "forged-image")) {
            f.editGithub("trust.json") { (it as ObjectNode).put(field, value) }
            assertFailsWith<IllegalArgumentException> { f.derive() }
            file.writeBytes(original)
        }
    }

    @Test fun secondAdapterForgedSignaturesAndRawResultsFailReplay() = fixture { f ->
        val signature = Base64.getEncoder().encodeToString(ByteArray(64))
        val proof = f.githubFile("proof.json").readBytes(); val record = f.githubFile("baseline.json").readBytes()
        f.editGithub("proof.json") { (it["observations"][0] as ObjectNode).put("signature", signature) }
        f.editGithub("baseline.json") { (it as ObjectNode).put("signature", signature) }
        assertFailsWith<IllegalArgumentException> { f.derive() }
        f.githubFile("proof.json").writeBytes(proof); f.githubFile("baseline.json").writeBytes(record)
        f.editGithub("baseline.json") { (it as ObjectNode).put("raw", SharedCheckoutFixture.observationBytes(null, null).toString(Charsets.UTF_8)) }
        assertFailsWith<IllegalArgumentException> { f.derive() }
    }

    @Test fun secondAdapterCannotBroadenItsClaimOrMixOwnerRevisions() = fixture { f ->
        val original = f.githubFile("proof.json").readBytes()
        for ((field, value) in listOf("claim" to "whole-workflow", "executionMode" to "whole-workflow", "sourceRevision" to "b".repeat(40),
            "workflowRevision" to "b".repeat(40), "runId" to "456", "attempt" to "2")) {
            f.editGithub("proof.json") { (it as ObjectNode).put(field, value) }
            assertFailsWith<IllegalArgumentException> { f.derive() }
            f.githubFile("proof.json").writeBytes(original)
        }
        for (field in listOf("publicSupportPromoted", "portableExecution")) {
            f.editGithub("proof.json") { (it as ObjectNode).put(field, true) }
            assertFailsWith<IllegalArgumentException> { f.derive() }
            f.githubFile("proof.json").writeBytes(original)
        }
    }

    @Test fun secondAdapterArtifactsEnvelopeAndDerivedViewsMustMatchCurrentCompiler() = fixture { f ->
        for (name in listOf("artifacts/source.intent.yaml", "artifacts/canonical-graph.json", "artifacts/execution-envelope.yml", "artifacts/baseline.yml",
            "artifacts/omitted-checkout.yml", "artifacts/substituted-revision.yml", "artifacts/observed-baseline.evidence", "evidence-view.json", "behavior-matrix.md")) {
            val file = f.githubFile(name); val original = file.readBytes(); file.writeText("forged")
            assertFailsWith<IllegalArgumentException>(name) { f.derive() }
            file.writeBytes(original)
        }
    }

    @Test fun secondAdapterMissingDuplicateOrSubstitutedRunCannotBorrowAnAdmission() = fixture { f ->
        val file = f.githubFile("proof.json"); val original = file.readBytes()
        for (mutation in 0..2) {
            f.editGithub("proof.json") {
                val rows = it["observations"] as com.fasterxml.jackson.databind.node.ArrayNode
                when (mutation) { 0 -> rows.remove(0); 1 -> rows.set(0, rows[1]); else -> (rows[0]["observation"] as ObjectNode).put("runId", "invented") }
            }
            assertFailsWith<IllegalArgumentException> { f.derive() }
            file.writeBytes(original)
        }
    }

    @Test fun secondAdapterRuntimeRecordsObeyFileBudgetsAndSymlinkConfinement() = fixture { f ->
        val file = f.githubFile("baseline.json"); val original = file.readBytes()
        file.writeBytes(ByteArray(8193))
        assertFailsWith<IllegalArgumentException> { f.derive() }
        file.delete()
        assertFailsWith<IllegalArgumentException> { f.derive() }
        val target = File(f.directory, "outside-github.json").apply { writeBytes(original) }
        java.nio.file.Files.createSymbolicLink(file.toPath(), target.toPath())
        assertFailsWith<IllegalArgumentException> { f.derive() }
    }


    @Test fun sharedComparisonRequiresBothProvidersAndCannotUseTheOlderLocalFixture() = fixture { f ->
        val doc = mapper.readTree(f.derive().json())
        val sharedIds = doc["sharedCanonicalCheckout"]["scenarioIds"].map { it.asText() }.toSet()
        val pair = doc["assessments"].filter { it["scenarioId"].asText() in sharedIds }.map { it["matrix"] }
        assertEquals(doc["sharedCanonicalCheckout"], mapper.valueToTree<JsonNode>(AdapterCertificationPortfolio.sharedCheckoutComparison(pair.reversed())))
        for (bad in listOf(pair.take(1), pair + pair.first(), listOf(pair.first(), pair.first())))
            assertFailsWith<IllegalArgumentException> { AdapterCertificationPortfolio.sharedCheckoutComparison(bad) }
        val old = doc["assessments"].single { it["scenarioId"].asText() == "jenkins-checkout-runtime" }["matrix"]
        assertFailsWith<IllegalArgumentException> { AdapterCertificationPortfolio.sharedCheckoutComparison(listOf(pair.first { it["adapter"]["target"].asText() == "github-actions" }, old)) }
    }

    @Test fun sharedComparisonRejectsDifferentSourcesGraphsAndEquallyWrongObservations() = fixture { f ->
        val doc = mapper.readTree(f.derive().json())
        val ids = doc["sharedCanonicalCheckout"]["scenarioIds"].map { it.asText() }.toSet()
        val original = doc["assessments"].filter { it["scenarioId"].asText() in ids }.map { it["matrix"] }
        fun behavior(node: JsonNode) = node["rows"].single { !it["behavior"].isNull }["behavior"]
        for (field in listOf("source", "canonicalGraph")) {
            val pair = original.map { it.deepCopy<JsonNode>() }
            (behavior(pair.first())[field] as ObjectNode).put("sha256", "f".repeat(64))
            assertFailsWith<IllegalArgumentException> { AdapterCertificationPortfolio.sharedCheckoutComparison(pair) }
        }
        val pair = original.map { it.deepCopy<JsonNode>() }
        pair.forEach { (behavior(it)["baseline"] as ObjectNode).put("observedUtf8", "same forged observation") }
        assertFailsWith<IllegalArgumentException> { AdapterCertificationPortfolio.sharedCheckoutComparison(pair) }
        val widened = original.map { it.deepCopy<JsonNode>() }
        (behavior(widened.first()) as ObjectNode).put("shape", "STRUCTURAL")
        assertFailsWith<IllegalArgumentException> { AdapterCertificationPortfolio.sharedCheckoutComparison(widened) }
    }

}
