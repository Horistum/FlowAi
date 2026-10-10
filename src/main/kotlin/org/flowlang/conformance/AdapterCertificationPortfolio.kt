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

        internal fun relativeDirectory(s: JenkinsCheckoutRuntimeCertification.Scenario): String = when (s) {
            JenkinsCheckoutRuntimeCertification.Scenario.CHECKOUT -> "jenkins-checkout-runtime"
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
            val expectedJobs = scenarios.map { relativeDirectory(it).substringBefore('/') }.toSet()
            require(jobs.isObject && jobs.fieldNames().asSequence().toSet() == expectedJobs) { "Producer job inventory differs." }
            return expectedJobs.sorted().flatMap { job ->
                require(jobs[job]["result"]?.asText() == "success") { "Producer $job did not succeed." }
                val raw = jobs[job]["outputs"]?.get("receipt")
                require(raw?.isTextual == true && raw.asText().length in 1..16384)
                val receipt = mapper.readTree(raw.asText())
                require(receipt["sourceRevision"]?.asText() == revision && receipt["artifact"]?.asText() == job)
                val rows = receipt["assessments"]
                val expected = scenarios.filter { relativeDirectory(it).substringBefore('/') == job }.map { it.id }.toSet()
                require(rows?.isArray == true && rows.size() == expected.size && rows.map { it["scenarioId"]?.asText() }.toSet() == expected)
                rows.map { row -> PortfolioAssessmentReceipt(row["scenarioId"].asText(),
                    row["proofSha256"]?.asText().orEmpty(), row["trustSha256"]?.asText().orEmpty()) }
            }
        }

        fun derive(root: File, revision: String, evidenceRoot: File, receipts: List<PortfolioAssessmentReceipt>): AdapterCertificationPortfolio {
            require(Regex("[0-9a-f]{40}").matches(revision)) { "Expected an exact source revision." }
            val inputs = receipts.toList()
            require(inputs.size == scenarios.size && inputs.map { it.scenarioId }.toSet() == scenarios.map { it.id }.toSet()) {
                "Portfolio requires the complete, unique scenario inventory."
            }
            require(inputs.all { Regex("[0-9a-f]{64}").matches(it.proofSha256) && Regex("[0-9a-f]{64}").matches(it.trustSha256) })
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
                    "trustSha256" to receipt.trustSha256, "matrix" to mapper.readTree(matrix.json()))
            }
            // The reference catalog supplies target identities only, never behavioral support.
            val targets = ReferenceTargetProjections.nativeCatalogs.keys.sorted()
            val rows = CertificationConstruct.entries.flatMap { construct -> targets.map { target ->
                val ids = assessments.filter { entry ->
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
                "portableExecution" to false, "assessmentCount" to assessments.size, "rows" to rows, "assessments" to assessments)
            val markdown = buildString {
                append("# Adapter certification portfolio\n\nSource revision: $revision. Reauthenticated assessments: ${assessments.size}.\n\n")
                append("Scope: the current reference adapter catalog and the complete declared runtime scenario inventory. ")
                append("Evidence covers bounded scenarios, not general construct support. Missing evidence is not an unsupported-feature declaration. ")
                append("Public support is not promoted; portable execution is not established.\n\n")
                append("| Construct | Target | Evidence | Scenarios |\n| --- | --- | --- | --- |\n")
                rows.forEach { row -> append("| ${row["construct"]} | ${cell(row.getValue("target") as String)} | ${row["status"]} | ${cell((row.getValue("scenarioIds") as List<*>).joinToString(", "))} |\n") }
                append("\n## Evidence boundaries\n\nEvery scenario retains its adapter identity/version/digest, source and graph digests, exact baseline and mutant observations, runtime prerequisites, runner key and limitations in the JSON portfolio. ")
                append("Different adapter versions and runtime images are never merged into a general certification. Checkout observations do not establish artifact transfer, secrets or value/state continuity.\n\n")
                append("Owner-pinned producer receipts bind input proof and trust bytes. CI job outputs and the assessment host remain part of the trust boundary; a signature does not prove runner honesty. ")
                append("Another target in this table does not imply that it executed any scenario.\n")
            }
            return AdapterCertificationPortfolio(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(output) + "\n", markdown)
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
        Files.createDirectories(output.parent)
        val stage = Files.createTempDirectory(output.parent, ".portfolio-")
        try {
            Files.writeString(stage.resolve("portfolio.json"), portfolio.json())
            Files.writeString(stage.resolve("portfolio.md"), portfolio.markdown())
            Files.move(stage, output, ATOMIC_MOVE)
        } finally {
            stage.toFile().deleteRecursively()
        }
        println("PASS: reauthenticated the complete runtime inventory and published the reference adapter portfolio.")
    }
}
