package org.flowlang.conformance

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.flowlang.adapters.certification.*
import org.flowlang.distribution.reference.ReferenceTargetProjections
import org.flowlang.targets.TargetRegistryYamlLoader

/** Digests are supplied by the assessment owner, separately from downloaded candidate files. */
internal data class PortfolioAssessmentReceipt(val scenarioId: String, val proofSha256: String, val trustSha256: String)

/** Replays existing assessments; it neither starts runtimes nor grants public support. */
internal class AdapterCertificationPortfolio private constructor(private val encoded: String, private val rendered: String) {
    fun json(): String = encoded
    fun markdown(): String = rendered

    companion object {
        private val mapper = jacksonObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        private val scenarios = JenkinsCheckoutRuntimeCertification.Scenario.entries
        private val githubScenario = GitHubActionsCheckoutRuntimeCertification.SCENARIO
        private val githubConditions = SharedConditionFixture.values.map { SharedConditionFixture.scenario("github-actions", it) }
        private val scenarioIds = scenarios.map { it.id } + githubScenario + githubConditions
        private val jobScenarios = scenarios.groupBy { relativeDirectory(it).substringBefore('/') }
            .mapValues { (_, values) -> values.map { it.id }.toSet() } + (githubScenario to setOf(githubScenario)) + (SharedConditionFixture.PRODUCER_JOB to githubConditions.toSet())

        internal fun relativeDirectory(s: JenkinsCheckoutRuntimeCertification.Scenario): String = when (s) {
            JenkinsCheckoutRuntimeCertification.Scenario.SECRET_CONDITION -> "jenkins-secret-condition-runtime"
            JenkinsCheckoutRuntimeCertification.Scenario.SHARED_CONDITION_TRUE -> "jenkins-shared-condition-runtime/true"
            JenkinsCheckoutRuntimeCertification.Scenario.SHARED_CONDITION_FALSE -> "jenkins-shared-condition-runtime/false"
            JenkinsCheckoutRuntimeCertification.Scenario.CHECKOUT -> "jenkins-checkout-runtime"
            JenkinsCheckoutRuntimeCertification.Scenario.PARALLEL_APPROVE -> "jenkins-parallel-runtime/approve"
            JenkinsCheckoutRuntimeCertification.Scenario.PARALLEL_REJECT -> "jenkins-parallel-runtime/reject"
            JenkinsCheckoutRuntimeCertification.Scenario.PARALLEL_FAIL_FAST_APPROVE -> "jenkins-parallel-runtime/fail-fast-approve"
            JenkinsCheckoutRuntimeCertification.Scenario.PARALLEL_CANCEL -> "jenkins-parallel-runtime/cancel"
            JenkinsCheckoutRuntimeCertification.Scenario.PARALLEL_WORKSPACES_APPROVE -> "jenkins-parallel-runtime/workspaces-approve"
            JenkinsCheckoutRuntimeCertification.Scenario.PARALLEL_WORKSPACES_REJECT -> "jenkins-parallel-runtime/workspaces-reject"
            JenkinsCheckoutRuntimeCertification.Scenario.PARALLEL_WORKSPACES_FAIL_FAST_APPROVE -> "jenkins-parallel-runtime/workspaces-fail-fast-approve"
            JenkinsCheckoutRuntimeCertification.Scenario.PARALLEL_WORKSPACES_CANCEL -> "jenkins-parallel-runtime/workspaces-cancel"
            JenkinsCheckoutRuntimeCertification.Scenario.PARALLEL_WORKSPACES_FAILURE -> "jenkins-parallel-runtime/workspaces-failure"
            JenkinsCheckoutRuntimeCertification.Scenario.MATCH_SELECTED -> "jenkins-match-runtime/selected"
            JenkinsCheckoutRuntimeCertification.Scenario.MATCH_DEFAULT -> "jenkins-match-runtime/default"
            JenkinsCheckoutRuntimeCertification.Scenario.MATCH_EMPTY -> "jenkins-match-runtime/empty"
            JenkinsCheckoutRuntimeCertification.Scenario.MATCH_FAILURE -> "jenkins-match-runtime/failure"
            JenkinsCheckoutRuntimeCertification.Scenario.LOOP_ORDER -> "jenkins-loop-runtime/order"
            JenkinsCheckoutRuntimeCertification.Scenario.LOOP_EMPTY -> "jenkins-loop-runtime/empty"
            JenkinsCheckoutRuntimeCertification.Scenario.LOOP_FAILURE -> "jenkins-loop-runtime/failure"
            JenkinsCheckoutRuntimeCertification.Scenario.RETRY_FAILURE -> "jenkins-retry-runtime/failure"
            JenkinsCheckoutRuntimeCertification.Scenario.RETRY_SUCCESS -> "jenkins-retry-runtime/success"
            JenkinsCheckoutRuntimeCertification.Scenario.SHARED_CHECKOUT -> "jenkins-shared-checkout-runtime"
            JenkinsCheckoutRuntimeCertification.Scenario.FAILURE -> "jenkins-failure-runtime"
            JenkinsCheckoutRuntimeCertification.Scenario.CONDITION_TRUE -> "jenkins-condition-runtime/true"
            JenkinsCheckoutRuntimeCertification.Scenario.CONDITION_FALSE -> "jenkins-condition-runtime/false"
            JenkinsCheckoutRuntimeCertification.Scenario.ERROR_FAILURE -> "jenkins-error-boundary-runtime/failure"
            JenkinsCheckoutRuntimeCertification.Scenario.ERROR_SUCCESS -> "jenkins-error-boundary-runtime/success"
            JenkinsCheckoutRuntimeCertification.Scenario.RECOVERY_FAILURE -> "jenkins-local-recovery-runtime/failure"
            JenkinsCheckoutRuntimeCertification.Scenario.RECOVERY_SUCCESS -> "jenkins-local-recovery-runtime/success"
            JenkinsCheckoutRuntimeCertification.Scenario.APPROVAL_APPROVE -> "jenkins-approval-runtime/approve"
            JenkinsCheckoutRuntimeCertification.Scenario.APPROVAL_REJECT -> "jenkins-approval-runtime/reject"
        }

        /** Only same-workflow successful producer job outputs may pin CI input trust. */
        fun receiptsFromJobs(bytes: ByteArray, revision: String): List<PortfolioAssessmentReceipt> {
            require(bytes.size in 1..65536) { "Producer job outputs exceed the input budget." }
            val jobs = mapper.readTree(bytes)
            val expectedJobs = jobScenarios.keys
            require(jobs.isObject && jobs.fieldNames().asSequence().toSet() == expectedJobs) { "Producer job inventory differs." }
            return expectedJobs.sorted().flatMap { job ->
                require(jobs[job]["result"]?.asText() == "success") { "Producer $job did not succeed." }
                val raw = jobs[job]["outputs"]?.get("receipt")
                require(raw?.isTextual == true && raw.asText().length in 1..16384)
                val receipt = mapper.readTree(raw.asText())
                require(receipt["sourceRevision"]?.asText() == revision && receipt["artifact"]?.asText() == job)
                val rows = receipt["assessments"]
                val expected = jobScenarios.getValue(job)
                require(rows?.isArray == true && rows.size() == expected.size && rows.map { it["scenarioId"]?.asText() }.toSet() == expected)
                rows.map { row -> PortfolioAssessmentReceipt(row["scenarioId"].asText(),
                    row["proofSha256"]?.asText().orEmpty(), row["trustSha256"]?.asText().orEmpty()) }
            }
        }

        fun derive(root: File, revision: String, evidenceRoot: File, receipts: List<PortfolioAssessmentReceipt>): AdapterCertificationPortfolio {
            require(Regex("[0-9a-f]{40}").matches(revision)) { "Expected an exact source revision." }
            val inputs = receipts.toList()
            require(inputs.size == scenarioIds.size && inputs.map { it.scenarioId }.toSet() == scenarioIds.toSet()) {
                "Portfolio requires the complete, unique scenario inventory."
            }
            require(inputs.all { Regex("[0-9a-f]{64}").matches(it.proofSha256) && Regex("[0-9a-f]{64}").matches(it.trustSha256) })
            val githubAssessment = replayGitHub(root, revision, evidenceRoot, inputs.single { it.scenarioId == githubScenario })
            val assessments = scenarios.sortedBy { it.id }.map { scenario ->
                val receipt = inputs.single { it.scenarioId == scenario.id }
                val directory = File(evidenceRoot, relativeDirectory(scenario))
                val proofBytes = read(directory, "proof.json", 1024 * 1024)
                val trustBytes = read(directory, "trust.json", 16384)
                require(sha(proofBytes) == receipt.proofSha256 && sha(trustBytes) == receipt.trustSha256) {
                    "Assessment ${scenario.id} differs from the owner-pinned proof or trust."
                }
                val proof = mapper.readTree(proofBytes)
                require(proof["sourceRevision"]?.asText() == revision && proof["status"]?.asText() == "passed") {
                    "Assessment ${scenario.id} is stale or incomplete."
                }
                val prepared = JenkinsCheckoutRuntimeCertification.prepare(root, proof["imageId"]?.asText().orEmpty(), scenario)
                require(proof["bundle"] == mapper.valueToTree<JsonNode>(prepared.bundle)) { "Compiler-bound scenario or adapter implementation changed." }
                require(proof["claim"]?.asText() == scenario.claim && proof["publicSupportPromoted"]?.isBoolean == true && !proof["publicSupportPromoted"].booleanValue())
                prepared.artifacts.forEach { (id, bytes) ->
                    require(read(directory, "artifacts/$id.Jenkinsfile", 1024 * 1024).contentEquals(bytes)) { "Artifact $id differs from compiler output or declared mutant." }
                }
                require(read(directory, "artifacts/" + if (scenario.flowSource) "source.flow" else "source.intent.yaml", 1024 * 1024)
                    .contentEquals(prepared.bound.resolve(prepared.bound.fixture))) { "Source bytes differ." }
                require(read(directory, "artifacts/canonical-graph.json", 1024 * 1024)
                    .contentEquals(prepared.bound.resolve(prepared.bound.canonicalGraph))) { "Canonical graph differs." }
                // Rebuild observations from native runtime records, never deserialize an asserted PASS.
                val observed = JenkinsCheckoutRuntimeCertification.observations(prepared, read(directory, "runtime/runtime.json", 65536))
                prepared.evidence.forEach { (id, bytes) ->
                    require(read(directory, "artifacts/" + id.replace(':', '-') + ".evidence", 1024 * 1024).contentEquals(bytes)) {
                        "Archived evidence $id differs from reconstructed bytes."
                    }
                }
                val trustNode = mapper.readTree(trustBytes)
                val challenge = trustNode["challenge"]?.asText().orEmpty()
                val authorized = scenario.runIds.map { CertificationAuthorizedRun(it, scenario.id,
                    it.takeUnless { id -> id == "baseline" }, "isolated-jenkins-runner") }
                require(trustNode["authorizedRuns"] == mapper.valueToTree<JsonNode>(authorized)) { "Run authorization differs from the scenario inventory." }
                val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(
                    Base64.getDecoder().decode(trustNode["publicKey"]?.asText().orEmpty())))
                val trust = CertificationObservationTrust(challenge, mapOf("isolated-jenkins-runner" to key), authorized)
                val statements = proof["observations"]
                require(statements?.isArray == true && statements.size() == observed.size)
                require(statements.map { it["observation"]?.get("runId")?.asText() }.toSet() == scenario.runIds.toSet())
                val signed = observed.map { observation ->
                    val statement = statements.single { it["observation"]["runId"].asText() == observation.runId }
                    require(statement["observation"] == mapper.valueToTree<JsonNode>(observation)) { "Signed observation differs from native results." }
                    require(statement["runnerId"]?.asText() == "isolated-jenkins-runner" && statement["challenge"]?.asText() == challenge)
                    SignedCertificationObservation("isolated-jenkins-runner", challenge, observation,
                        Base64.getDecoder().decode(statement["signature"]?.asText().orEmpty()))
                }
                val resolver = CertificationEvidenceResolver { prepared.evidence[it.id] }
                val assessment = CertificationEvidenceViews.assess(prepared.bundle, prepared.bundle.adapter, listOf(prepared.bound),
                    ReferenceTargetProjections.nativeCatalogs.getValue(prepared.bundle.adapter.target), signed, trust, resolver)
                require(assessment.admission.valid) { "Replayed admission failed for ${scenario.id}: ${assessment.admission.findings}" }
                require(proof["admission"] == mapper.valueToTree<JsonNode>(assessment.admission)) { "Archived admission differs from replay." }
                val view = requireNotNull(assessment.view)
                val matrix = AdapterBehaviorMatrix.derive(assessment, scenario, resolver)
                val outputs = mapOf("evidence-view.json" to view.json(), "evidence-view.md" to view.markdown(),
                    "behavior-matrix.json" to matrix.json(), "behavior-matrix.md" to matrix.markdown())
                outputs.forEach { (name, value) ->
                    val bytes = value.toByteArray(Charsets.UTF_8)
                    val field = if (name.startsWith("behavior-")) "behaviorMatrix" else "evidenceViews"
                    require(proof[field]?.get(name)?.asText() == sha(bytes) && read(directory, name, 2 * 1024 * 1024).contentEquals(bytes)) {
                        "Published $name differs from authenticated replay."
                    }
                }
                linkedMapOf<String, Any>("scenarioId" to scenario.id, "proofSha256" to receipt.proofSha256,
                    "trustSha256" to receipt.trustSha256, "claim" to scenario.claim, "executionMode" to "generated-jenkinsfile",
                    "matrix" to mapper.readTree(matrix.json()))
            }
            val githubConditionAssessments = SharedConditionFixture.values.map { value ->
                replayGitHub(root, revision, evidenceRoot, inputs.single { it.scenarioId == SharedConditionFixture.scenario("github-actions", value) }, value)
            }
            val allAssessments = (assessments + githubAssessment + githubConditionAssessments)
                .sortedBy { it.getValue("scenarioId") as String }
            val sharedIds = setOf(githubScenario, JenkinsCheckoutRuntimeCertification.Scenario.SHARED_CHECKOUT.id)
            val shared = sharedCheckoutComparison(allAssessments.filter { it["scenarioId"] in sharedIds }.map { it.getValue("matrix") as JsonNode })
            // The reference catalog supplies target identities only, never behavioral support.
            val targets = ReferenceTargetProjections.nativeCatalogs.keys.sorted()
            val rows = CertificationConstruct.entries.flatMap { construct -> targets.map { target ->
                val ids = allAssessments.filter { entry ->
                    val matrix = entry.getValue("matrix") as JsonNode
                    matrix["adapter"]["target"].asText() == target && matrix["rows"].any {
                        it["construct"].asText() == construct.name && it["status"].asText() == "BOUNDED_SCENARIO_EVIDENCE"
                    }
                }.map { it.getValue("scenarioId") as String }
                linkedMapOf<String, Any>("construct" to construct.name, "target" to target,
                    "status" to if (ids.isEmpty()) "NO_BEHAVIORAL_EVIDENCE_IN_PORTFOLIO" else "BOUNDED_SCENARIO_EVIDENCE", "scenarioIds" to ids)
            } }
            val output = linkedMapOf<String, Any>("formatVersion" to 1, "scope" to "authenticated-reference-adapter-portfolio",
                "sourceRevision" to revision, "referenceTargets" to targets, "publicSupportPromoted" to false,
                "portableExecution" to false, "assessmentCount" to allAssessments.size, "sharedCanonicalCheckout" to shared,
                "sharedCanonicalConditions" to SharedConditionFixture.values.map { value ->
                    sharedConditionComparison(allAssessments.filter { it["scenarioId"] in setOf(SharedConditionFixture.scenario("jenkins", value),
                        SharedConditionFixture.scenario("github-actions", value)) }.map { it.getValue("matrix") as JsonNode }, value)
                }, "rows" to rows, "assessments" to allAssessments)
            val markdown = buildString {
                append("# Adapter certification portfolio\n\nSource revision: $revision. Reauthenticated assessments: ${allAssessments.size}.\n\n")
                append("Scope: the current reference adapter catalog and the complete declared runtime scenario inventory. ")
                append("Evidence covers bounded scenarios, not general construct support. Missing evidence is not an unsupported-feature declaration. ")
                append("Public support is not promoted; portable execution is not established.\n\n")
                append("| Construct | Target | Evidence | Scenarios |\n| --- | --- | --- | --- |\n")
                rows.forEach { row -> append("| ${row["construct"]} | ${cell(row.getValue("target") as String)} | ${row["status"]} | ${cell((row.getValue("scenarioIds") as List<*>).joinToString(", "))} |\n") }
                append("\n## Evidence boundaries\n\nEvery scenario retains its adapter identity/version/digest, source and graph digests, exact baseline and mutant observations, runtime prerequisites, runner key and limitations in the JSON portfolio. ")
                append("Different adapter versions and runtime images are never merged into a general certification. Checkout observations do not establish artifact transfer, secrets or value/state continuity.\n\n")
                append("Owner-pinned producer receipts bind input proof and trust bytes. CI job outputs and the assessment host remain part of the trust boundary; a signature does not prove runner honesty. ")
                append("Only targets with admitted scenario IDs have execution evidence. GitHub Actions assessments execute native leaves or literal job guards inside checked envelopes, not complete generated workflows. ")
                append("The shared checkout pair has identical source and canonical graph hashes, and identical authenticated revision/file observations for the baseline and both mutants. ")
                append("The shared true/false conditions also compare identical source/graph hashes and authenticated revision/file observations while preserving provider-specific job results. These comparisons are bounded to the declared fixtures. The older local Jenkins fixture remains distinct; a shared construct label alone does not establish cross-target equivalence.\n")
            }
            return AdapterCertificationPortfolio(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(output) + "\n", markdown)
        }

        /** Diagnostic comparison only; derive() supplies matrices reconstructed by authenticated replay. */
        internal fun sharedCheckoutComparison(matrices: List<JsonNode>): Map<String, Any> = sharedComparison(matrices, null)
        internal fun sharedConditionComparison(matrices: List<JsonNode>, value: Boolean): Map<String, Any> = sharedComparison(matrices, value)
        private fun sharedComparison(matrices: List<JsonNode>, value: Boolean?): Map<String, Any> {
            val scenarios = if (value == null) mapOf("github-actions" to githubScenario, "jenkins" to JenkinsCheckoutRuntimeCertification.Scenario.SHARED_CHECKOUT.id)
                else listOf("github-actions", "jenkins").associateWith { SharedConditionFixture.scenario(it, value) }
            val runIds = if (value == null) SharedCheckoutFixture.runIds else SharedConditionFixture.runIds(value)
            require(matrices.size == 2 && matrices.map { it["adapter"]?.get("target")?.asText() }.toSet() == scenarios.keys) {
                "The shared checkout comparison requires both distinct providers."
            }
            val behaviors = matrices.associate { matrix ->
                require(matrix["scope"]?.asText() == "authenticated-single-scenario-behavior-matrix")
                require(listOf("publicSupportPromoted", "portableExecution").all { matrix[it]?.isBoolean == true && !matrix[it].booleanValue() })
                val target = matrix["adapter"]["target"].asText()
                val rows = matrix["rows"]
                require(rows?.isArray == true && rows.map { it["construct"]?.asText() } == CertificationConstruct.entries.map { it.name })
                val covered = rows.filter { !it["behavior"].isNull }
                require(covered.size == 1 && covered.single()["construct"].asText() == if (value == null) "NATIVE_CHECKOUT" else "CONDITIONS")
                val b = covered.single()["behavior"]
                require(b["scenarioId"]?.asText() == scenarios.getValue(target) && b["shape"]?.asText() == if (value == null) "NATIVE_LEAF_ONLY" else "STRUCTURAL_OCCURRENCES")
                target to b
            }
            val ordered = scenarios.keys.sorted()
            val first = behaviors.getValue(ordered.first())
            for (field in listOf("source", "canonicalGraph")) {
                val reference = first[field]
                require(reference["sha256"]?.asText()?.matches(Regex("[0-9a-f]{64}")) == true && reference["sizeBytes"]?.asInt()?.let { it > 0 } == true)
                require(behaviors.values.all { it[field]["sha256"] == reference["sha256"] && it[field]["sizeBytes"] == reference["sizeBytes"] }) {
                    "Shared checkout $field differs between providers."
                }
            }
            val inventories = behaviors.mapValues { (_, behavior) ->
                val runs = listOf(behavior["baseline"]) + behavior["negativeMutants"].toList()
                require(runs.size == runIds.size && runs.map { it["evidence"]["runId"].asText() }.toSet() == runIds.toSet())
                runs.associateBy { it["evidence"]["runId"].asText() }
            }
            val runs = runIds.map { id ->
                val expected = if (value == null) SharedCheckoutFixture.expected(id) else SharedConditionFixture.expected(value, id)
                val digest = sha(expected)
                val artifacts = ordered.associateWith { target ->
                    val run = inventories.getValue(target).getValue(id)
                    require(run["expectedObservationUtf8"]?.asText() == expected.toString(Charsets.UTF_8) &&
                        run["observedUtf8"]?.asText() == expected.toString(Charsets.UTF_8) &&
                        run["evidence"]["observed"]["sha256"]?.asText() == digest && run["evidence"]["observed"]["sizeBytes"]?.asInt() == expected.size) {
                        "Shared checkout $id does not match the independent immutable oracle on $target."
                    }
                    run["evidence"]["artifact"]
                }
                linkedMapOf<String, Any>("runId" to id, "observationSha256" to digest,
                    "observation" to mapper.readTree(expected), "artifacts" to artifacts)
            }
            return linkedMapOf("scope" to (if (value == null) "bounded-native-checkout-observable-equivalence" else "bounded-literal-condition-observable-equivalence"), "observableEquivalence" to true,
                "publicSupportPromoted" to false, "portableExecution" to false, "scenarioIds" to ordered.map { scenarios.getValue(it) },
                "sourceSha256" to first["source"]["sha256"].asText(), "canonicalGraphSha256" to first["canonicalGraph"]["sha256"].asText(),
                "runs" to runs, "limitations" to if (value != null) SharedConditionFixture.limitations else listOf(
                    "Only the shared native checkout's revision and fixture-file bytes are compared; no general portability or structural equivalence.",
                    "Jenkins executes the generated Jenkinsfile; GitHub Actions executes its rendered native leaf inside the checked observation envelope.",
                    "Both runtime hosts remain trusted; provider credentials, triggers, scheduling and workspace transfer are outside this comparison."))
        }

        private fun replayGitHub(root: File, revision: String, evidenceRoot: File,
            receipt: PortfolioAssessmentReceipt, condition: Boolean? = null): Map<String, Any> {
            val scenarioId = if (condition == null) githubScenario else SharedConditionFixture.scenario("github-actions", condition)
            val claim = if (condition == null) "native-leaf-only" else GitHubActionsConditionRuntimeCertification.CLAIM
            val mode = if (condition == null) "native-leaf-in-checked-envelope" else GitHubActionsConditionRuntimeCertification.MODE
            val directory = File(evidenceRoot, if (condition == null) githubScenario else "${SharedConditionFixture.PRODUCER_JOB}/$condition")
            val proofBytes = read(directory, "proof.json", 1024 * 1024)
            val trustBytes = read(directory, "trust.json", 16384)
            require(sha(proofBytes) == receipt.proofSha256 && sha(trustBytes) == receipt.trustSha256) {
                "GitHub Actions assessment differs from the owner-pinned proof or trust."
            }
            val proof = mapper.readTree(proofBytes)
            val owner = mapper.readTree(trustBytes)
            require(proof["status"]?.asText() == "passed" && proof["sourceRevision"]?.asText() == revision)
            for (name in listOf("sourceRevision", "workflowRevision", "runId", "attempt")) {
                require(proof[name]?.isTextual == true && proof[name] == owner[name]) { "GitHub Actions owner metadata differs: $name" }
            }
            require(listOf("runId", "attempt").all { Regex("[1-9][0-9]{0,19}").matches(owner[it].asText()) })
            require(proof["claim"]?.asText() == claim &&
                proof["executionMode"]?.asText() == mode) { "GitHub Actions execution scope changed." }
            for (name in listOf("publicSupportPromoted", "portableExecution"))
                require(proof[name]?.isBoolean == true && !proof[name].booleanValue()) { "Portfolio cannot promote support or portability." }
            val nativeResults = if (condition == null) null else read(directory, "native-job-results.json", 65536)
            val envelope = read(directory, "artifacts/execution-envelope.yml", 1024 * 1024)
            val prepared = if (condition == null) GitHubActionsCheckoutRuntimeCertification.prepare(root, revision, owner["workflowRevision"].asText(),
                owner["runnerImage"]?.asText().orEmpty(), envelope) else GitHubActionsConditionRuntimeCertification.prepare(root, revision,
                owner["workflowRevision"].asText(), owner["runnerImage"]?.asText().orEmpty(), envelope, condition, requireNotNull(nativeResults))
            val nativeObservations = if (condition == null) null else GitHubActionsConditionRuntimeCertification.observations(prepared, condition, requireNotNull(nativeResults))
            if (condition != null) require(mapper.readTree(nativeResults)[SharedConditionFixture.PREPARE_JOB]["outputs"]["challenge"] == owner["challenge"])
            require(proof["bundle"] == mapper.valueToTree<JsonNode>(prepared.bundle)) { "GitHub Actions compiler or adapter binding changed." }
            prepared.artifacts.forEach { (id, bytes) ->
                require(read(directory, "artifacts/$id.yml", 1024 * 1024).contentEquals(bytes)) { "GitHub Actions artifact $id differs." }
            }
            require(read(directory, if (condition == null) "artifacts/source.intent.yaml" else "artifacts/source.flow", 1024 * 1024).contentEquals(prepared.bound.resolve(prepared.bound.fixture)))
            require(read(directory, "artifacts/canonical-graph.json", 1024 * 1024).contentEquals(prepared.bound.resolve(prepared.bound.canonicalGraph)))
            val runner = if (condition == null) GitHubActionsCheckoutRuntimeCertification.RUNNER else GitHubActionsConditionRuntimeCertification.RUNNER
            val runIds = if (condition == null) SharedCheckoutFixture.runIds else SharedConditionFixture.runIds(condition)
            val challenge = owner["challenge"]?.asText().orEmpty()
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(
                Base64.getDecoder().decode(owner["publicKey"]?.asText().orEmpty())))
            val trust = CertificationObservationTrust(challenge, mapOf(runner to key), runIds.map {
                CertificationAuthorizedRun(it, scenarioId, it.takeUnless { id -> id == "baseline" }, runner) })
            val statements = proof["observations"]
            require(statements?.isArray == true && statements.size() == runIds.size &&
                statements.map { it["observation"]?.get("runId")?.asText() }.toSet() == runIds.toSet())
            val signed = runIds.map { id ->
                val record = mapper.readTree(read(directory, "$id.json", 8192))
                require(record["raw"]?.isTextual == true && record["signature"]?.isTextual == true)
                val observed = record["raw"].asText().toByteArray(Charsets.UTF_8)
                if (nativeObservations != null) require(observed.contentEquals(nativeObservations.getValue(id)))
                val observation = GitHubActionsCheckoutRuntimeCertification.observation(prepared, id, observed)
                val statement = statements.single { it["observation"]["runId"].asText() == id }
                require(statement["observation"] == mapper.valueToTree<JsonNode>(observation)) { "GitHub Actions native result differs from its signed observation." }
                require(statement["runnerId"]?.asText() == runner && statement["challenge"]?.asText() == challenge &&
                    statement["signature"] == record["signature"]) { "GitHub Actions runner, challenge or signature differs." }
                SignedCertificationObservation(runner, challenge, observation, Base64.getDecoder().decode(record["signature"].asText()))
            }
            prepared.evidence.forEach { (id, bytes) ->
                require(read(directory, "artifacts/" + id.replace(':', '-') + ".evidence", 1024 * 1024).contentEquals(bytes)) {
                    "GitHub Actions archived evidence $id differs from reconstructed bytes."
                }
            }
            val resolver = CertificationEvidenceResolver { prepared.evidence[it.id] }
            val assessment = CertificationEvidenceViews.assess(prepared.bundle, prepared.bundle.adapter, listOf(prepared.bound),
                prepared.provider, signed, trust, resolver)
            require(assessment.admission.valid) { "GitHub Actions replayed admission failed: ${assessment.admission.findings}" }
            require(proof["admission"] == mapper.valueToTree<JsonNode>(assessment.admission)) { "GitHub Actions archived admission differs from replay." }
            val view = requireNotNull(assessment.view)
            val matrix = if (condition == null) AdapterBehaviorMatrix.deriveGitHubCheckout(assessment, resolver)
                else AdapterBehaviorMatrix.deriveGitHubCondition(assessment, condition, resolver)
            mapOf("evidence-view.json" to view.json(), "evidence-view.md" to view.markdown(),
                "behavior-matrix.json" to matrix.json(), "behavior-matrix.md" to matrix.markdown()).forEach { (name, value) ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                val field = if (name.startsWith("behavior-")) "behaviorMatrix" else "evidenceViews"
                require(proof[field]?.get(name)?.asText() == sha(bytes) && read(directory, name, 2 * 1024 * 1024).contentEquals(bytes)) {
                    "GitHub Actions published $name differs from authenticated replay."
                }
            }
            return linkedMapOf("scenarioId" to scenarioId, "proofSha256" to receipt.proofSha256,
                "trustSha256" to receipt.trustSha256, "claim" to claim, "executionMode" to mode,
                "matrix" to mapper.readTree(matrix.json()))
        }

        private fun read(directory: File, name: String, maxBytes: Int): ByteArray {
            val base = directory.toPath().toAbsolutePath().normalize()
            val path = base.resolve(name).normalize()
            require(path.startsWith(base) && Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Missing regular evidence file: $name" }
            require(path.toRealPath() == path) { "Evidence paths cannot contain symbolic links." }
            Files.newInputStream(path).use { input ->
                val bytes = input.readNBytes(maxBytes + 1)
                require(bytes.size in 1..maxBytes) { "Evidence file exceeds its byte budget: $name" }
                return bytes
            }
        }

        private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun cell(value: String): String = buildString { value.forEach { c -> when {
            c.isISOControl() -> append(' ')
            c in "\\`*_{}[]<>()#+-.!|&\"'" -> append("&#${c.code};")
            else -> append(c)
        } } }
    }
}

internal object AdapterCertificationPortfolioCli {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 4) { "Expected repository root, downloaded evidence directory, fresh output directory and exact revision." }
        val root = File(args[0]).canonicalFile
        val revision = args[3]
        val git = ProcessBuilder("git", "rev-parse", "HEAD").directory(root).redirectErrorStream(true).start()
        val actual = git.inputStream.bufferedReader().readText().trim()
        require(git.waitFor() == 0 && actual == revision) { "Checkout does not match the selected source revision." }
        val receipts = AdapterCertificationPortfolio.receiptsFromJobs(
            requireNotNull(System.getenv("FLOW_CERTIFICATION_JOB_RECEIPTS")) { "Caller-owned producer receipts are required." }.toByteArray(), revision)
        val output = File(args[2]).absoluteFile.toPath()
        require(!Files.exists(output)) { "Portfolio output must be fresh." }
        val portfolio = AdapterCertificationPortfolio.derive(root, revision, File(args[1]), receipts)
        val coverage = AuthenticatedTargetCoverage.derive(portfolio,
            TargetRegistryYamlLoader.loadDirectory(File(root, "targets")), ReferenceTargetProjections.nativeCatalogs)
        publish(output, portfolio, coverage)
        println("PASS: reauthenticated the complete runtime inventory and published all declared target coverage.")
    }

    internal fun publish(output: java.nio.file.Path, portfolio: AdapterCertificationPortfolio, coverage: AuthenticatedTargetCoverage) {
        require(!Files.exists(output)) { "Portfolio output must be fresh." }
        val mapper = jacksonObjectMapper()
        val parsed = mapper.readTree(coverage.json())
        require(parsed["portfolioSha256"].asText() == AuthenticatedTargetCoverage.sha(portfolio.json().toByteArray(Charsets.UTF_8))) {
            "Coverage publication belongs to a different portfolio."
        }
        val documents = linkedMapOf("portfolio.json" to portfolio.json(), "portfolio.md" to portfolio.markdown(),
            "target-coverage.json" to coverage.json(), "target-coverage.md" to coverage.markdown())
        Files.createDirectories(output.parent)
        val stage = Files.createTempDirectory(output.parent, ".portfolio-")
        try {
            val receipts = documents.mapValues { (name, text) ->
                val bytes = text.toByteArray(Charsets.UTF_8)
                Files.write(stage.resolve(name), bytes)
                val actual = Files.readAllBytes(stage.resolve(name))
                require(actual.contentEquals(bytes)) { "Published bytes differ: $name" }
                mapOf("sha256" to AuthenticatedTargetCoverage.sha(actual), "sizeBytes" to actual.size)
            }
            // Write the manifest last, from re-read bytes, before publishing the complete directory.
            Files.writeString(stage.resolve("publication.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                linkedMapOf("formatVersion" to 1, "sourceRevision" to parsed["sourceRevision"].asText(), "files" to receipts)) + "\n")
            Files.move(stage, output, ATOMIC_MOVE)
        } finally {
            stage.toFile().deleteRecursively()
        }
    }
}
