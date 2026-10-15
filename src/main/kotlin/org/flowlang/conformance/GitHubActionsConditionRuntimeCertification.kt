package org.flowlang.conformance

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.UUID
import org.flowlang.adapters.certification.*
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.compiler.requireAccepted
import org.flowlang.distribution.reference.ReferenceAdapterEvidence
import org.flowlang.distribution.reference.ReferenceTargetProjections
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.source.FlowSourceFrontend
import org.flowlang.generators.manifest.TargetStructuralProjectionKind
import org.flowlang.materialization.TargetMaterializationRequest
import org.flowlang.materialization.TargetSelectionAuthority
import org.flowlang.modules.ModuleRegistry
import org.flowlang.safety.StandardEnvironmentSafetyPolicyNotes
import org.flowlang.serialization.FlowYaml
import org.flowlang.conformance.GitHubActionsCheckoutRuntimeCertification.Prepared
import org.flowlang.conformance.GitHubActionsCheckoutRuntimeCertification.sha256

/** External, bounded job-condition proof. Native job results are observed by a separate trusted collector. */
internal object GitHubActionsConditionRuntimeCertification {
    const val RUNNER = "github-hosted-condition-collector"
    const val CLAIM = "bounded-structural-occurrence"
    const val MODE = "native-job-condition-in-checked-envelope"
    private val mapper = jacksonObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun prepare(root: File, sourceRevision: String, workflowRevision: String, runnerImage: String,
        envelope: ByteArray, value: Boolean, nativeResults: ByteArray = "{}".toByteArray()): Prepared {
        require(listOf(sourceRevision, workflowRevision).all { Regex("[0-9a-f]{40}").matches(it) })
        require(Regex("[A-Za-z0-9._-]{1,128}").matches(runnerImage))
        require(envelope.contentEquals(File(root, GitHubActionsCheckoutRuntimeCertification.WORKFLOW).readBytes()))
        val sourceFile = File(root, SharedConditionFixture.source(value))
        val source = sourceFile.readBytes()
        val modules = ModuleRegistry.fromDirectory(File(root, "modules"))
        val targets = TargetRegistryYamlLoader.loadDirectory(File(root, "targets"))
        val compilation = FlowSourceFrontend(FrontendCompilerComposition.compiler(modules, StandardEnvironmentSafetyPolicyNotes.policy(root)))
            .compile(sourceFile).requireAccepted()
        val projections = ReferenceTargetProjections.fromContracts(root)
        val provider = projections.requireProvider("github-actions").nativeProjectionCatalog
        val selection = TargetSelectionAuthority.requireSelected(TargetSelectionAuthority.fromCliOption("github-actions", targets), "runtime certification")
        val bound = BoundCertificationScenario.capture(SharedConditionFixture.scenario("github-actions", value), source,
            TargetMaterializationRequest.fromCompilation(compilation, selection),
            ReferenceTargetProjections.pipeline(targets, rootDir = root, projections = projections, modules = modules),
            ReferenceAdapterEvidence.rendering(rootDir = root, projections = projections), provider)
        val original = requireNotNull(bound.resolve(bound.artifact))
        val rendered = original.toString(Charsets.UTF_8)
        val guard = rendered.lineSequence().single { it.startsWith("    if: ") } + "\n"
        require(guard.trim() == "if: \${{ ($value) }}") { "Expected one literal job guard, got $guard" }
        val leaf = GitHubActionsCheckoutRuntimeCertification.nativeLeaf(rendered.replace(guard, ""))
        val artifacts = linkedMapOf("baseline" to original)
        if (value) artifacts["omitted-checkout"] = rendered.replace(leaf, "").toByteArray()
        else artifacts["flattened-condition"] = rendered.replace(guard, "").toByteArray()
        artifacts["inverted-condition"] = rendered.replace(guard, guard.replace("($value)", "(${!value})")).toByteArray()
        validateEnvelope(root, envelope, artifacts, value)
        val evidence = linkedMapOf<String, ByteArray>()
        fun ref(id: String, bytes: ByteArray): CertificationEvidenceReference {
            evidence[id] = bytes.copyOf(); return CertificationEvidenceReference(id, sha256(bytes), bytes.size)
        }
        val runIds = SharedConditionFixture.runIds(value)
        val expected = runIds.associateWith { ref("expected:$it", SharedConditionFixture.expected(value, it)) }
        val runtime = listOf(CertificationRuntimePrerequisite("provider", "github-actions"),
            CertificationRuntimePrerequisite("action-reference", "actions/checkout@v4"),
            CertificationRuntimePrerequisite("runner-image", runnerImage),
            CertificationRuntimePrerequisite("source-revision", sourceRevision),
            CertificationRuntimePrerequisite("workflow-revision", workflowRevision),
            CertificationRuntimePrerequisite("execution-envelope-sha256", sha256(envelope)),
            CertificationRuntimePrerequisite("native-job-results-sha256", sha256(nativeResults)))
        val specification = bound.scenario(expected.getValue("baseline"), runtime, runIds.drop(1).map { id ->
            CertificationNegativeMutant(id, ref("artifact:$id", artifacts.getValue(id)), expected.getValue(id)) })
        val implementation = File(root, "gradle/adapter-github-actions-sources.txt").readLines().filter(String::isNotBlank)
            .joinToString("\n") { "$it:${sha256(File(root, "src/main/kotlin/$it").readBytes())}" }.toByteArray()
        val adapter = CertificationAdapterIdentity("github-actions", "github-actions-literal-condition", "AR-06Z", sha256(implementation))
        val subjects = bound.subjects + TargetStructuralProjectionKind.entries.map { CertificationSubject.Structural(it) } +
            provider.definitions.map { CertificationSubject.Leaf(it.kind, it.reference) } + provider.approvalDefinitions.map { CertificationSubject.Leaf(it.kind, it.reference) }
        val bundle = AdapterCertificationBundle(adapter, subjects.map { CertificationCoverage(it,
            if (it in bound.subjects) listOf(bound.id) else emptyList(), "Single literal job guard only; no general condition or scheduling credit.") },
            listOf(specification), SharedConditionFixture.limitations + listOf(
                "Preflight binds the execution envelope before native jobs run. The collector creates its private key before admitting same-workflow job outputs; the key is never available to native jobs or archived.",
                "The preflight challenge and source/workflow revisions, native job results and runner images are bound to every signature. Skipped jobs have no runner image or workspace observation."))
        return Prepared(bound, bundle, artifacts, emptyMap(), evidence, runtime, provider)
    }

    /** Check the whole native job: guard, leaf, observation code, outputs and its sole prerequisite. */
    fun validateEnvelope(root: File, envelope: ByteArray, artifacts: Map<String, ByteArray>, value: Boolean) {
        val jobs = FlowYaml.readStrict(envelope.toString(Charsets.UTF_8), JsonNode::class.java, "condition-envelope.yml")["jobs"]
        val observer = File(root, SharedConditionFixture.OBSERVER).readText()
        require(artifacts.keys.toList() == SharedConditionFixture.runIds(value))
        artifacts.forEach { (id, bytes) ->
            val native = FlowYaml.readStrict(bytes.toString(Charsets.UTF_8), JsonNode::class.java, "$id.yml")["jobs"].single()
            val job = requireNotNull(jobs[SharedConditionFixture.job(value, id)])
            val fields = setOf("needs", "runs-on", "timeout-minutes", "outputs", "steps") + if (native.has("if")) setOf("if") else emptySet()
            require(job.fieldNames().asSequence().toSet() == fields) { "Native job $id envelope fields changed." }
            require(job["if"] == native["if"] && job["runs-on"] == native["runs-on"])
            require(job["needs"].asText() == SharedConditionFixture.PREPARE_JOB && job["timeout-minutes"].asInt() == 10)
            val expectedOutputs = mapper.valueToTree<JsonNode>(mapOf("observation" to "\${{ steps.observe.outputs.observation }}",
                "runner-image" to "\${{ steps.observe.outputs.runner-image }}"))
            require(job["outputs"] == expectedOutputs)
            val nativeSteps = native["steps"]?.takeUnless { it.isNull }?.toList().orEmpty()
            val steps = job["steps"].toList()
            require(steps.size == nativeSteps.size + 1 && steps.dropLast(1) == nativeSteps) { "Native checkout bytes or inventory changed for $id." }
            require(steps.last() == mapper.valueToTree<JsonNode>(mapOf("name" to "Observe Native Workspace", "id" to "observe",
                "shell" to "python", "run" to observer))) { "Native observer envelope changed." }
        }
    }

    /** Preserve provider results and fail closed on skipped, failed or missing execution evidence. */
    fun observations(p: Prepared, value: Boolean, raw: ByteArray): Map<String, ByteArray> {
        require(raw.size in 1..65536)
        require(p.runtime.single { it.id == "native-job-results-sha256" }.version == sha256(raw))
        val jobs = mapper.readTree(raw)
        val names = setOf(SharedConditionFixture.PREPARE_JOB) + SharedConditionFixture.values.flatMap { v -> SharedConditionFixture.runIds(v).map { SharedConditionFixture.job(v, it) } }
        require(jobs.isObject && jobs.fieldNames().asSequence().toSet() == names)
        val preflight = jobs[SharedConditionFixture.PREPARE_JOB]
        require(preflight["result"]?.asText() == "success")
        val metadata = preflight["outputs"]
        require(metadata?.isObject == true && metadata.fieldNames().asSequence().toSet() == setOf("challenge", "source-revision", "workflow-revision"))
        require(metadata["challenge"]?.isTextual == true && Regex("[0-9a-f]{32}").matches(metadata["challenge"].asText()))
        for (field in listOf("source-revision", "workflow-revision"))
            require(metadata[field]?.asText() == p.runtime.single { it.id == field }.version)
        // Validate both values' inventory, including peers used by the same collector.
        val normalized = SharedConditionFixture.values.flatMap { v -> SharedConditionFixture.runIds(v).map { id ->
            val job = jobs[SharedConditionFixture.job(v, id)]
            require(job.fieldNames().asSequence().toSet() == setOf("result", "outputs"))
            val executed = SharedConditionFixture.executes(v, id)
            require(job["result"]?.asText() == if (executed) "success" else "skipped") { "Unexpected native job result for $v/$id." }
            val outputs = job["outputs"]
            require(outputs?.isObject == true)
            val bytes = if (!executed) {
                require(outputs.isEmpty) { "Skipped job supplied observations." }
                SharedCheckoutFixture.observationBytes(null, null)
            } else {
                require(outputs.fieldNames().asSequence().toSet() == setOf("observation", "runner-image"))
                require(outputs["runner-image"]?.isTextual == true && Regex("[A-Za-z0-9._-]{1,128}").matches(outputs["runner-image"].asText()))
                val observed = outputs["observation"]
                require(observed?.isTextual == true && observed.asText().length in 1..4096)
                val bytes = (observed.asText() + "\n").toByteArray()
                val node = mapper.readTree(bytes)
                require(node.isObject && node.fieldNames().asSequence().toSet() == setOf("revision", "markerSha256"))
                require(bytes.contentEquals(SharedCheckoutFixture.observationBytes(node)))
                bytes
            }
            (v to id) to bytes
        } }.toMap()
        return SharedConditionFixture.runIds(value).associateWith { normalized.getValue(value to it) }
    }

    fun cli(args: Array<String>) {
        require(args.size == 4 && args[0] in setOf("prepare", "verify"))
        require(System.getenv("GITHUB_ACTIONS") == "true" && System.getenv("RUNNER_ENVIRONMENT") == "github-hosted")
        fun env(name: String) = requireNotNull(System.getenv(name)).also { require(it.isNotBlank()) }
        val root = File(args[1]).canonicalFile
        val envelope = File(args[2]).readBytes()
        val revision = env("EXPECTED_SHA"); val workflow = env("WORKFLOW_REVISION"); val image = env("ImageVersion")
        if (args[0] == "prepare") {
            SharedConditionFixture.values.forEach { prepare(root, revision, workflow, image, envelope, it) }
            File(env("GITHUB_OUTPUT")).appendText("challenge=${UUID.randomUUID().toString().replace("-", "")}\nsource-revision=$revision\nworkflow-revision=$workflow\n")
            return
        }
        // Caller-owned key is created before job outputs are admitted, and remains in this process only.
        val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val raw = env("FLOW_CONDITION_JOB_RESULTS").toByteArray()
        val challenge = mapper.readTree(raw)[SharedConditionFixture.PREPARE_JOB]["outputs"]["challenge"].asText()
        val outputRoot = File(args[3]); require(!outputRoot.exists())
        fun write(file: File, data: Any) = file.writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data) + "\n")
        SharedConditionFixture.values.forEach { value ->
            val p = prepare(root, revision, workflow, image, envelope, value, raw)
            val observed = observations(p, value, raw)
            val runIds = SharedConditionFixture.runIds(value)
            val trust = CertificationObservationTrust(challenge, mapOf(RUNNER to key.public), runIds.map {
                CertificationAuthorizedRun(it, p.bound.id, it.takeUnless { id -> id == "baseline" }, RUNNER) })
            val output = File(outputRoot, value.toString()).apply { mkdirs() }
            val owner = linkedMapOf("sourceRevision" to revision, "workflowRevision" to workflow, "runnerImage" to image,
                "runId" to env("GITHUB_RUN_ID"), "attempt" to env("GITHUB_RUN_ATTEMPT"), "challenge" to challenge,
                "publicKey" to Base64.getEncoder().encodeToString(key.public.encoded))
            write(File(output, "trust.json"), owner)
            File(output, "native-job-results.json").writeBytes(raw)
            val signed = runIds.map { id ->
                val observation = GitHubActionsCheckoutRuntimeCertification.observation(p, id, observed.getValue(id))
                val signer = Signature.getInstance("Ed25519"); signer.initSign(key.private)
                signer.update(CertificationObservationAuthentication.signingBytes(RUNNER, challenge, observation))
                val signature = signer.sign()
                write(File(output, "$id.json"), mapOf("raw" to observed.getValue(id).toString(Charsets.UTF_8), "signature" to Base64.getEncoder().encodeToString(signature)))
                SignedCertificationObservation(RUNNER, challenge, observation, signature)
            }
            val resolver = CertificationEvidenceResolver { p.evidence[it.id] }
            val assessment = CertificationEvidenceViews.assess(p.bundle, p.bundle.adapter, listOf(p.bound), p.provider, signed, trust, resolver)
            require(assessment.admission.valid) { "Condition admission failed: ${assessment.admission.findings}" }
            val view = requireNotNull(assessment.view)
            val matrix = AdapterBehaviorMatrix.deriveGitHubCondition(assessment, value, resolver)
            File(output, "evidence-view.json").writeText(view.json()); File(output, "evidence-view.md").writeText(view.markdown())
            File(output, "behavior-matrix.json").writeText(matrix.json()); File(output, "behavior-matrix.md").writeText(matrix.markdown())
            val artifacts = File(output, "artifacts").apply { mkdirs() }
            p.artifacts.forEach { (id, bytes) -> File(artifacts, "$id.yml").writeBytes(bytes) }
            p.evidence.forEach { (id, bytes) -> File(artifacts, id.replace(':', '-') + ".evidence").writeBytes(bytes) }
            File(artifacts, "source.flow").writeBytes(requireNotNull(p.bound.resolve(p.bound.fixture)))
            File(artifacts, "canonical-graph.json").writeBytes(requireNotNull(p.bound.resolve(p.bound.canonicalGraph)))
            File(artifacts, "execution-envelope.yml").writeBytes(envelope)
            write(File(output, "proof.json"), owner.filterKeys { it in setOf("sourceRevision", "workflowRevision", "runId", "attempt") } + linkedMapOf(
                "status" to "passed", "claim" to CLAIM, "executionMode" to MODE, "bundle" to p.bundle, "admission" to assessment.admission,
                "publicSupportPromoted" to false, "portableExecution" to false,
                "observations" to signed.map { mapOf("runnerId" to it.runnerId, "challenge" to it.challenge, "observation" to it.observation, "signature" to Base64.getEncoder().encodeToString(it.signature())) },
                "behaviorMatrix" to listOf("behavior-matrix.json", "behavior-matrix.md").associateWith { sha256(File(output, it).readBytes()) },
                "evidenceViews" to listOf("evidence-view.json", "evidence-view.md").associateWith { sha256(File(output, it).readBytes()) }))
            println("PASS: ${p.bound.id}; native job condition and falsifying mutants authenticated")
        }
    }
}

object GitHubActionsConditionRuntimeCertificationCli {
    @JvmStatic fun main(args: Array<String>) = GitHubActionsConditionRuntimeCertification.cli(args)
}
