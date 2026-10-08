package org.flowlang.conformance

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.flowlang.adapters.certification.*
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.compiler.requireAccepted
import org.flowlang.distribution.reference.ReferenceAdapterEvidence
import org.flowlang.distribution.reference.ReferenceTargetProjections
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.intent.IntentYamlFrontend
import org.flowlang.generators.manifest.TargetStructuralProjectionKind
import org.flowlang.materialization.TargetMaterializationRequest
import org.flowlang.materialization.TargetSelectionAuthority
import org.flowlang.modules.ModuleRegistry

/** External conformance runner only. No product entry point or runtime dependency invokes this program. */
internal object JenkinsCheckoutRuntimeCertification {
    const val FIXTURE = "flow-adapter-jenkins/src/runtimeTest"
    private val mapper = jacksonObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    private val plugins = linkedMapOf("git" to "5.10.1", "pipeline-model-definition" to "2.2293.v6e7193cec599", "timestamper" to "1.30")
    private const val MISSING_REVISION = "Couldn't find any revision to build. Verify the repository and branch configuration for this job."

    internal enum class Scenario(val id: String, val source: String, val matrix: String, val runIds: List<String>, val version: String) {
        CHECKOUT("jenkins-checkout-runtime", "checkout.intent.yaml", "behavior-matrix.json", listOf("baseline", "omitted-checkout", "substituted-branch"), "AR-06D"),
        FAILURE("jenkins-failure-runtime", "failure.intent.yaml", "failure-matrix.json", listOf("baseline", "omitted-failure", "suppressed-failure"), "AR-06E")
    }

    internal data class Prepared(
        val bound: BoundCertificationScenario,
        val bundle: AdapterCertificationBundle,
        val artifacts: Map<String, ByteArray>,
        val evidence: MutableMap<String, ByteArray>,
        val runtime: List<CertificationRuntimePrerequisite>,
        val scenario: Scenario
    )

    fun prepare(root: File, imageId: String, scenario: Scenario = Scenario.CHECKOUT): Prepared {
        require(Regex("sha256:[0-9a-f]{64}").matches(imageId))
        val source = File(root, "$FIXTURE/${scenario.source}").readBytes()
        val modules = ModuleRegistry.fromDirectory(File(root, "modules"))
        val targets = TargetRegistryYamlLoader.loadDirectory(File(root, "targets"))
        val compilation = IntentYamlFrontend(FrontendCompilerComposition.compiler(modules))
            .compileText(source.toString(Charsets.UTF_8), scenario.source).requireAccepted()
        val selection = TargetSelectionAuthority.requireSelected(TargetSelectionAuthority.fromCliOption("jenkins", targets), "runtime certification")
        val provider = ReferenceTargetProjections.nativeCatalogs.getValue("jenkins")
        val bound = BoundCertificationScenario.capture(scenario.id, source, TargetMaterializationRequest.fromCompilation(compilation, selection),
            ReferenceTargetProjections.pipeline(targets, rootDir = root, modules = modules),
            ReferenceAdapterEvidence.rendering(rootDir = root), provider)
        val original = requireNotNull(bound.resolve(bound.artifact))
        val text = original.toString(Charsets.UTF_8)
        val branch = if (scenario == Scenario.CHECKOUT) "selected" else "missing-revision"
        val checkout = text.lineSequence().single { it.trimStart().startsWith("git branch: '$branch'") }
        require(checkout.trim() == "git branch: '$branch', url: 'git://127.0.0.1:9418/repository.git'")
        val indent = checkout.takeWhile(Char::isWhitespace)
        if (scenario == Scenario.FAILURE) {
            require(text.lineSequence().count { it.trimStart().startsWith("git branch:") } == 2)
            require(text.indexOf(checkout) < text.indexOf("git branch: 'selected'"))
        }
        val runIds = scenario.runIds
        val artifacts = linkedMapOf("baseline" to original,
            runIds[1] to replaceOnce(text, checkout, indent + "echo 'Checkout intentionally omitted by certification mutant'").toByteArray(),
            runIds[2] to replaceOnce(text, checkout, if (scenario == Scenario.CHECKOUT) checkout.replace("branch: 'selected'", "branch: 'alternate'")
                else "${indent}catchError(buildResult: 'SUCCESS', stageResult: 'SUCCESS') {\n$indent    ${checkout.trim()}\n$indent}").toByteArray())
        val matrix = mapper.readTree(File(root, "$FIXTURE/${scenario.matrix}"))
        require(matrix["version"].asInt() == 1 && matrix["scenario"].asText() == scenario.id && matrix["target"].asText() == "jenkins")
        require(matrix["claim"].asText() == "native-leaf-only" && matrix["construct"].asText() == "git.checkout")
        val rows = listOf(matrix["positive"]) + matrix["mutants"].toList()
        require(rows.map { it["id"].asText() } == runIds)
        val evidence = linkedMapOf<String, ByteArray>()
        fun ref(id: String, bytes: ByteArray): CertificationEvidenceReference {
            evidence[id] = bytes.copyOf(); return CertificationEvidenceReference(id, sha256(bytes), bytes.size)
        }
        val expected = rows.associate { row ->
            val expectedResult = if (scenario == Scenario.FAILURE && row["id"].asText() == "baseline") "FAILURE" else "SUCCESS"
            require(row["result"].asText() == expectedResult)
            row["id"].asText() to ref("expected:${row["id"].asText()}", observationBytes(row, scenario))
        }
        val runtime = listOf(CertificationRuntimePrerequisite("jenkins", "2.580.1"),
            CertificationRuntimePrerequisite("container-image", imageId)) + plugins.map { (id, version) -> CertificationRuntimePrerequisite("plugin:$id", version) }
        val specification = bound.scenario(expected.getValue("baseline"), runtime, runIds.drop(1).map { id ->
            CertificationNegativeMutant(id, ref("artifact:$id", artifacts.getValue(id)), expected.getValue(id))
        })
        val implementation = File(root, "gradle/adapter-jenkins-sources.txt").readLines().filter(String::isNotBlank)
            .joinToString("\n") { path -> "$path:${sha256(File(root, "src/main/kotlin/$path").readBytes())}" }.toByteArray()
        val adapter = CertificationAdapterIdentity("jenkins", "jenkins-native-checkout", scenario.version, sha256(implementation))
        val subjects = bound.subjects + TargetStructuralProjectionKind.entries.map { CertificationSubject.Structural(it) } +
            provider.definitions.map { CertificationSubject.Leaf(it.kind, it.reference) }
        val limitations = matrix["limitations"].map { it.asText() }
        require(limitations.isNotEmpty() && limitations.none(String::isBlank))
        val bundle = AdapterCertificationBundle(adapter, subjects.map { subject -> CertificationCoverage(subject,
            if (subject in bound.subjects) listOf(bound.id) else emptyList(), "Native checkout occurrences only; see bounded runtime limitations.") },
            listOf(specification), limitations)
        return Prepared(bound, bundle, artifacts, evidence, runtime, scenario)
    }

    fun observations(prepared: Prepared, raw: ByteArray): List<CertificationExecutionObservation> {
        require(raw.size in 1..65536) { "Runtime record exceeds its byte budget." }
        val result = mapper.readTree(raw)
        require(result["status"]?.asText() == "completed") { "Runtime did not complete." }
        require(result["jenkins"]?.asText() == "2.580.1") { "Unexpected Jenkins version." }
        require(result["java"]?.asText()?.let { it == "25" || it.startsWith("25.") || it.startsWith("25+") } == true)
        require(plugins.all { (id, version) -> result["plugins"]?.get(id)?.asText() == version }) { "Unexpected runtime plugins." }
        val runIds = prepared.scenario.runIds
        require(result["runs"]?.isArray == true && result["runs"].size() == runIds.size)
        val rows = result["runs"].toList()
        require(rows.map { it["id"]?.asText() }.toSet() == runIds.toSet()) { "Runtime run inventory is incomplete or duplicated." }
        val scenario = prepared.bundle.scenarios.single()
        return rows.map { row ->
            val id = row["id"].asText()
            val bytes = prepared.artifacts.getValue(id)
            require(row["artifactSha256"]?.asText() == sha256(bytes)) { "Executed script differs from the bound artifact." }
            require(row["buildNumber"]?.isIntegralNumber == true && row["buildNumber"].canConvertToInt() && row["buildNumber"].asInt() == 1)
            if (prepared.scenario == Scenario.FAILURE) require(row["finished"]?.isBoolean == true && row["finished"].booleanValue())
            val observed = observationBytes(row, prepared.scenario)
            val ref = CertificationEvidenceReference("observed:$id", sha256(observed), observed.size)
            prepared.evidence[ref.id] = observed
            CertificationExecutionObservation(id, prepared.bundle.adapter, prepared.bound.id, id.takeUnless { it == "baseline" },
                prepared.bound.canonicalGraph.sha256, prepared.bound.fixture.sha256,
                if (id == "baseline") prepared.bound.artifact else scenario.negativeMutants.single { it.id == id }.artifact,
                ref, prepared.runtime, if (completed(row, prepared.scenario)) CertificationRunOutcome.COMPLETED else CertificationRunOutcome.INFRASTRUCTURE_FAILURE)
        }
    }

    private fun completed(row: JsonNode, scenario: Scenario): Boolean = row["result"].asText() == "SUCCESS" ||
        scenario == Scenario.FAILURE && row["result"].asText() == "FAILURE" && row["checkoutErrors"].size() == 1 &&
        row["checkoutErrors"][0]["type"].asText() == "hudson.AbortException" && row["checkoutErrors"][0]["message"].asText() == MISSING_REVISION

    private fun observationBytes(row: JsonNode, scenario: Scenario): ByteArray {
        require(row["result"]?.isTextual == true && row.has("marker"))
        val marker = row["marker"]
        require(marker.isNull || marker.isTextual && marker.asText().length <= 1024)
        val observation = linkedMapOf<String, Any?>("result" to row["result"].asText(), "marker" to if (marker.isNull) null else marker.asText())
        if (scenario == Scenario.FAILURE) {
            require(row["checkoutCount"]?.isIntegralNumber == true && row["checkoutCount"].canConvertToInt() && row["checkoutCount"].asInt() in 0..2)
            val errors = row["checkoutErrors"]
            require(errors?.isArray == true && errors.size() <= row["checkoutCount"].asInt())
            observation["checkoutCount"] = row["checkoutCount"].asInt()
            observation["checkoutErrors"] = errors.map { error ->
                require(error.isObject && error.fieldNames().asSequence().toSet() == setOf("type", "message"))
                require(error["type"].isTextual && error["type"].asText().length in 1..256)
                require(error["message"].isTextual && error["message"].asText().length in 1..1024)
                linkedMapOf("type" to error["type"].asText(), "message" to error["message"].asText())
            }
        }
        return (mapper.writeValueAsString(observation) + "\n").toByteArray()
    }

    internal fun replaceOnce(source: String, old: String, replacement: String): String {
        require(old.isNotEmpty() && source.indexOf(old) >= 0 && source.indexOf(old) == source.lastIndexOf(old))
        return source.replace(old, replacement)
    }

    fun run(root: File, output: File, scenario: Scenario = Scenario.CHECKOUT) {
        require(!output.exists()) { "Runtime evidence output must be fresh." }
        output.mkdirs()
        val container = "flow-certification-${UUID.randomUUID()}"
        val imageTag = "$container:local"
        fun process(label: String, command: List<String>, seconds: Long = 120, directory: File = root): String {
            val log = File(output, "$label.log")
            val child = ProcessBuilder(command).directory(directory).redirectErrorStream(true).redirectOutput(log).start()
            if (!child.waitFor(seconds, TimeUnit.SECONDS)) { child.destroyForcibly(); error("$label timed out") }
            require(log.length() <= 8 * 1024 * 1024) { "$label log exceeds the evidence budget." }
            val text = log.readText()
            check(child.exitValue() == 0) { "$label failed (${child.exitValue()}): ${text.takeLast(4000)}" }
            return text
        }
        // The assessment owner creates trust before the external runtime starts; the private key never enters its container.
        val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val challenge = UUID.randomUUID().toString().replace("-", "")
        val runAuthorization = scenario.runIds.map { CertificationAuthorizedRun(it, scenario.id, it.takeUnless { id -> id == "baseline" }, "isolated-jenkins-runner") }
        val trust = CertificationObservationTrust(challenge, mapOf("isolated-jenkins-runner" to key.public), runAuthorization)
        File(output, "trust.json").writeText(mapper.writeValueAsString(mapOf("challenge" to challenge,
            "publicKey" to Base64.getEncoder().encodeToString(key.public.encoded), "authorizedRuns" to runAuthorization)) + "\n")
        try {
            println("Building the pinned Jenkins test runtime")
            process("image-build", listOf("docker", "build", "--tag", imageTag, File(root, FIXTURE).absolutePath), 600)
            val imageId = process("image-identity", listOf("docker", "image", "inspect", "--format", "{{.Id}}", imageTag)).trim()
            val prepared = prepare(root, imageId, scenario)
            val artifacts = File(output, "artifacts").apply { mkdirs() }
            prepared.artifacts.forEach { (id, bytes) -> File(artifacts, "$id.Jenkinsfile").writeBytes(bytes) }
            File(artifacts, "source.intent.yaml").writeBytes(requireNotNull(prepared.bound.resolve(prepared.bound.fixture)))
            File(artifacts, "canonical-graph.json").writeBytes(requireNotNull(prepared.bound.resolve(prepared.bound.canonicalGraph)))
            val fixture = File(output, "fixture").apply { mkdirs() }
            val working = File(fixture, "working").apply { mkdirs() }
            fun git(label: String, vararg args: String) = process(label, listOf("git", "-C", working.absolutePath) + args)
            git("fixture-init", "init", "--initial-branch=selected")
            git("fixture-name", "config", "user.name", "Flow Certification Fixture")
            git("fixture-email", "config", "user.email", "fixture@example.invalid")
            File(working, "marker.txt").writeText("selected\n")
            git("fixture-add-selected", "add", "marker.txt"); git("fixture-commit-selected", "commit", "-m", "Selected branch fixture")
            git("fixture-alternate", "checkout", "-b", "alternate")
            File(working, "marker.txt").writeText("alternate\n")
            git("fixture-add-alternate", "add", "marker.txt"); git("fixture-commit-alternate", "commit", "-m", "Alternate branch fixture")
            git("fixture-selected", "checkout", "selected")
            process("fixture-bare", listOf("git", "clone", "--bare", working.absolutePath, File(fixture, "repository.git").absolutePath))
            val evidence = File(output, "runtime").apply { mkdirs() }
            Files.setPosixFilePermissions(evidence.toPath(), PosixFilePermissions.fromString("rwxrwxrwx"))
            println("Executing the original artifact and both mutants on isolated Jenkins")
            process("controller", listOf("docker", "run", "--rm", "--name", container, "--network", "none", "--memory", "3g", "--cpus", "2",
                "--env", "FLOW_CERTIFICATION_SCENARIO=${scenario.id}",
                "--mount", "type=bind,src=${fixture.absolutePath},dst=/fixture,readonly",
                "--mount", "type=bind,src=${artifacts.absolutePath},dst=/artifacts,readonly",
                "--mount", "type=bind,src=${evidence.absolutePath},dst=/evidence",
                "--mount", "type=bind,src=${File(root, "$FIXTURE/run.groovy").absolutePath},dst=/usr/share/jenkins/ref/init.groovy.d/certification.groovy,readonly",
                imageId), 600)
            val runtimeFile = File(evidence, "runtime.json")
            require(runtimeFile.length() in 1..65536)
            val runs = observations(prepared, runtimeFile.readBytes())
            val signed = runs.map { observation ->
                val signer = Signature.getInstance("Ed25519"); signer.initSign(key.private)
                signer.update(CertificationObservationAuthentication.signingBytes("isolated-jenkins-runner", challenge, observation))
                SignedCertificationObservation("isolated-jenkins-runner", challenge, observation, signer.sign())
            }
            val report = AuthenticatedAdapterCertificationAdmission.evaluate(prepared.bundle, prepared.bundle.adapter, listOf(prepared.bound),
                ReferenceTargetProjections.nativeCatalogs.getValue("jenkins"), signed, trust,
                CertificationEvidenceResolver { prepared.evidence[it.id] })
            require(report.valid) { "Runtime certification rejected: ${report.findings}" }
            val sourceRevision = process("source-revision", listOf("git", "rev-parse", "HEAD")).trim()
            val proof = linkedMapOf<String, Any>("status" to "passed", "claim" to "native-leaf-only", "sourceRevision" to sourceRevision,
                "imageId" to imageId, "bundle" to prepared.bundle, "admission" to report, "publicSupportPromoted" to false,
                "observations" to signed.map { mapOf("runnerId" to it.runnerId, "challenge" to it.challenge,
                    "observation" to it.observation, "signature" to Base64.getEncoder().encodeToString(it.signature())) })
            File(output, "proof.json").writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(proof) + "\n")
            prepared.evidence.forEach { (id, bytes) -> File(artifacts, id.replace(':', '-') + ".evidence").writeBytes(bytes) }
            println("PASS: ${scenario.id}, ${scenario.runIds.joinToString()}; authenticated bounded admission")
        } finally {
            // Only this invocation's disposable resources are removed, including on timeout.
            ProcessBuilder("docker", "rm", "--force", container).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor(30, TimeUnit.SECONDS)
            ProcessBuilder("docker", "image", "rm", imageTag).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor(30, TimeUnit.SECONDS)
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

fun main(args: Array<String>) {
    require(args.size in 2..3) { "Expected repository root, fresh evidence directory and optional scenario identity." }
    val scenario = if (args.size == 2) JenkinsCheckoutRuntimeCertification.Scenario.CHECKOUT
        else JenkinsCheckoutRuntimeCertification.Scenario.entries.single { it.id == args[2] }
    JenkinsCheckoutRuntimeCertification.run(File(args[0]).canonicalFile, File(args[1]).canonicalFile, scenario)
}
