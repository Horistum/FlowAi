package org.flowlang.conformance

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.*
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
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
import org.flowlang.generators.manifest.TargetNativeProjectionCatalog
import org.flowlang.materialization.TargetMaterializationRequest
import org.flowlang.materialization.TargetSelectionAuthority
import org.flowlang.modules.ModuleRegistry
import org.flowlang.safety.StandardEnvironmentSafetyPolicyNotes
import org.flowlang.serialization.FlowYaml

/** Opt-in conformance harness. The provider executes native leaves; this is not a product runtime. */
internal object GitHubActionsCheckoutRuntimeCertification {
    const val SCENARIO = "github-actions-checkout-runtime"
    const val WORKFLOW = ".github/workflows/adapter-runtime-check.yml"
    const val SELECTED = "c6149ff9ba87f88e25cdc0905cfb86cb935f7756"
    const val ALTERNATE = "9232061af22922af33ff3a65ada81d1b0d4eedd6"
    const val MARKER = ".flow-agent/work-packages/behavioral-adapter-certification.yaml"
    const val SELECTED_MARKER = "fb8263618cf3a4ad5cf5704a66b746512edc2643bc2c03a211168d41663fc8fb"
    const val ALTERNATE_MARKER = "ad91a8bf3c636f9123c83d19da1a6c55e16055e866f800630a88b4e2d08c6897"
    const val RUNNER = "github-hosted-checkout-observer"
    val runIds = listOf("baseline", "omitted-checkout", "substituted-revision")
    private val mapper = jacksonObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    data class Prepared(val bound: BoundCertificationScenario, val bundle: AdapterCertificationBundle,
        val artifacts: Map<String, ByteArray>, val leaves: Map<String, String>,
        val evidence: MutableMap<String, ByteArray>, val runtime: List<CertificationRuntimePrerequisite>,
        val provider: TargetNativeProjectionCatalog)

    fun prepare(root: File, sourceRevision: String, workflowRevision: String, runnerImage: String, envelope: ByteArray): Prepared {
        require(listOf(sourceRevision, workflowRevision).all { Regex("[0-9a-f]{40}").matches(it) })
        require(Regex("[A-Za-z0-9._-]{1,128}").matches(runnerImage))
        require(envelope.contentEquals(File(root, WORKFLOW).readBytes())) { "Executed workflow differs from the selected source envelope." }
        val sourceFile = File(root, "flow-adapter-github-actions/src/runtimeTest/checkout.intent.yaml")
        val source = sourceFile.readBytes()
        val modules = ModuleRegistry.fromDirectory(File(root, "modules"))
        val targets = TargetRegistryYamlLoader.loadDirectory(File(root, "targets"))
        val compilation = IntentYamlFrontend(FrontendCompilerComposition.compiler(modules, StandardEnvironmentSafetyPolicyNotes.policy(root)))
            .compileText(source.toString(Charsets.UTF_8), sourceFile.name).requireAccepted()
        val projections = ReferenceTargetProjections.fromContracts(root)
        val provider = projections.requireProvider("github-actions").nativeProjectionCatalog
        val selection = TargetSelectionAuthority.requireSelected(TargetSelectionAuthority.fromCliOption("github-actions", targets), "runtime certification")
        val bound = BoundCertificationScenario.capture(SCENARIO, source, TargetMaterializationRequest.fromCompilation(compilation, selection),
            ReferenceTargetProjections.pipeline(targets, rootDir = root, projections = projections, modules = modules),
            ReferenceAdapterEvidence.rendering(rootDir = root, projections = projections), provider)
        val original = requireNotNull(bound.resolve(bound.artifact))
        val rendered = original.toString(Charsets.UTF_8)
        val leaf = nativeLeaf(rendered)
        require(leaf.contains("ref: \"$SELECTED\""))
        val leaves = linkedMapOf("baseline" to leaf, "omitted-checkout" to "", "substituted-revision" to leaf.replace(SELECTED, ALTERNATE))
        validateEnvelope(envelope.toString(Charsets.UTF_8), leaves)
        val artifacts = leaves.mapValues { (_, value) -> rendered.replace(leaf, value).toByteArray() }
        val evidence = linkedMapOf<String, ByteArray>()
        fun ref(id: String, bytes: ByteArray): CertificationEvidenceReference {
            evidence[id] = bytes.copyOf(); return CertificationEvidenceReference(id, sha256(bytes), bytes.size)
        }
        val expected = linkedMapOf("baseline" to observationBytes(SELECTED, SELECTED_MARKER),
            "omitted-checkout" to observationBytes(null, null), "substituted-revision" to observationBytes(ALTERNATE, ALTERNATE_MARKER))
            .mapValues { (id, bytes) -> ref("expected:$id", bytes) }
        val runtime = listOf(CertificationRuntimePrerequisite("provider", "github-actions"),
            CertificationRuntimePrerequisite("action-reference", "actions/checkout@v4"),
            CertificationRuntimePrerequisite("runner-image", runnerImage),
            CertificationRuntimePrerequisite("source-revision", sourceRevision),
            CertificationRuntimePrerequisite("workflow-revision", workflowRevision),
            CertificationRuntimePrerequisite("execution-envelope-sha256", sha256(envelope)))
        val specification = bound.scenario(expected.getValue("baseline"), runtime, runIds.drop(1).map { id ->
            CertificationNegativeMutant(id, ref("artifact:$id", artifacts.getValue(id)), expected.getValue(id)) })
        val implementation = File(root, "gradle/adapter-github-actions-sources.txt").readLines().filter(String::isNotBlank)
            .joinToString("\n") { "$it:${sha256(File(root, "src/main/kotlin/$it").readBytes())}" }.toByteArray()
        val adapter = CertificationAdapterIdentity("github-actions", "github-actions-native-checkout", "AR-06M", sha256(implementation))
        val subjects = bound.subjects + TargetStructuralProjectionKind.entries.map { CertificationSubject.Structural(it) } +
            provider.definitions.map { CertificationSubject.Leaf(it.kind, it.reference) } + provider.approvalDefinitions.map { CertificationSubject.Leaf(it.kind, it.reference) }
        val limitations = listOf("Only the single rendered native checkout leaf executes inside a checked observation envelope; the full generated workflow is not executed.",
            "No trigger, scheduling, credentials, workspace transfer, structural equivalence, general support or portable execution claim.",
            "Public immutable repository revisions are the oracle. The hosted runner image and action reference are recorded, not pinned provider binaries.",
            "The observation owner and native action share a trusted hosted runner. Signatures do not defend against a compromised runner or action.",
            "This fixture differs from the Jenkins checkout fixture; a shared construct label does not establish cross-target equivalence.")
        val bundle = AdapterCertificationBundle(adapter, subjects.map { CertificationCoverage(it,
            if (it in bound.subjects) listOf(bound.id) else emptyList(), "Native checkout leaf only; no whole-workflow credit.") }, listOf(specification), limitations)
        return Prepared(bound, bundle, artifacts, leaves, evidence, runtime, provider)
    }

    /** Fail closed if the renderer grows another job, step, binding, guard or workspace mechanism. */
    fun nativeLeaf(rendered: String): String {
        val tree = FlowYaml.readStrict(rendered, JsonNode::class.java, "generated-github-actions.yml")
        val jobs = tree["jobs"]
        require(jobs?.isObject == true && jobs.size() == 1)
        val job = jobs.elements().next()
        require(job.fieldNames().asSequence().toSet() == setOf("name", "runs-on", "steps"))
        require(job["runs-on"].asText() == "ubuntu-latest" && job["steps"].size() == 1)
        val step = job["steps"][0]
        require(step.fieldNames().asSequence().toSet() == setOf("name", "uses", "with"))
        require(step["uses"].asText() == "actions/checkout@v4")
        require(step["with"].fieldNames().asSequence().toSet() == setOf("repository", "ref", "fetch-depth"))
        require(step["with"]["repository"].asText() == "Horistum/FlowAi" && step["with"]["ref"].asText() == SELECTED && step["with"]["fetch-depth"].asText() == "0")
        val start = "      - name: "
        require(rendered.lineSequence().count { it.startsWith(start) } == 1)
        return rendered.substring(rendered.indexOf(start)).also { require(it.endsWith("\n")) }
    }

    fun validateEnvelope(envelope: String, leaves: Map<String, String>) {
        require(leaves.keys.toList() == runIds)
        runIds.forEach { id ->
            val start = "      # FLOW NATIVE BEGIN $id\n"; val end = "      # FLOW NATIVE END $id\n"
            require(envelope.split(start).size == 2 && envelope.split(end).size == 2)
            require(envelope.indexOf(start) < envelope.indexOf(end))
            require(envelope.substringAfter(start).substringBefore(end) == leaves.getValue(id)) { "Native $id envelope differs from compiler rendering." }
        }
    }

    fun observationBytes(revision: String?, markerSha256: String?): ByteArray {
        require(revision == null || Regex("[0-9a-f]{40}").matches(revision))
        require(markerSha256 == null || Regex("[0-9a-f]{64}").matches(markerSha256))
        return (mapper.writeValueAsString(linkedMapOf("revision" to revision, "markerSha256" to markerSha256)) + "\n").toByteArray()
    }

    fun observation(p: Prepared, id: String, raw: ByteArray): CertificationExecutionObservation {
        require(id in runIds && raw.size in 1..4096)
        val row = mapper.readTree(raw)
        require(row.isObject && row.fieldNames().asSequence().toSet() == setOf("revision", "markerSha256"))
        fun nullable(name: String): String? = row[name].let { require(it.isNull || it.isTextual); if (it.isNull) null else it.asText() }
        val normalized = observationBytes(nullable("revision"), nullable("markerSha256"))
        require(raw.contentEquals(normalized)) { "Observation is not canonical." }
        val ref = CertificationEvidenceReference("observed:$id", sha256(raw), raw.size)
        p.evidence[ref.id] = raw.copyOf()
        return CertificationExecutionObservation(id, p.bundle.adapter, p.bound.id, id.takeUnless { it == "baseline" },
            p.bound.canonicalGraph.sha256, p.bound.fixture.sha256,
            if (id == "baseline") p.bound.artifact else p.bundle.scenarios.single().negativeMutants.single { it.id == id }.artifact,
            ref, p.runtime, CertificationRunOutcome.COMPLETED)
    }

    /** Only children of an explicitly authorized real directory are removed. Symlinks are never followed. */
    fun clearWorkspace(workspace: Path, authorizedWorkspace: Path) {
        val path = workspace.toAbsolutePath().normalize()
        require(path == authorizedWorkspace.toAbsolutePath().normalize() && path.nameCount >= 3)
        require(path == path.toRealPath() && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
        require(path != Path.of(System.getProperty("user.home")).toAbsolutePath().normalize())
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                if (dir != path) Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    fun observeWorkspace(workspace: File): ByteArray {
        val git = File(workspace, ".git"); val marker = File(workspace, MARKER)
        if (!git.exists()) {
            require(workspace.listFiles()?.isEmpty() == true) { "Omitted checkout did not leave an empty workspace." }
            return observationBytes(null, null)
        }
        require(git.isDirectory && !Files.isSymbolicLink(git.toPath()))
        val process = ProcessBuilder("git", "-C", workspace.absolutePath, "rev-parse", "HEAD").redirectErrorStream(true).start()
        require(process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); "Git observation timed out." }
        val revision = process.inputStream.bufferedReader().readText().trim()
        require(process.exitValue() == 0 && marker.isFile && marker.length() in 1..65536)
        require(marker.canonicalFile.toPath().startsWith(workspace.canonicalFile.toPath()))
        return observationBytes(revision, sha256(marker.readBytes()))
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun cli(args: Array<String>) {
        require(args.size in 2..3) { "Expected initialize|observe|verify, staged directory, and optional run identity." }
        val stage = File(args[1]).canonicalFile
        val workspace = Path.of(requireNotNull(System.getenv("GITHUB_WORKSPACE"))).toAbsolutePath().normalize()
        require(System.getenv("GITHUB_ACTIONS") == "true" && System.getenv("RUNNER_ENVIRONMENT") == "github-hosted")
        require(workspace.fileName == workspace.parent.fileName && workspace.nameCount >= 4)
        val runnerTemp = File(requireNotNull(System.getenv("RUNNER_TEMP"))).canonicalFile.toPath()
        require(stage.toPath().startsWith(runnerTemp) && !stage.toPath().startsWith(workspace) && !workspace.startsWith(stage.toPath()))
        val output = File(stage, "evidence")
        val stateFile = File(stage, "owner.json")
        val privateFile = File(stage, "owner.pk8")
        fun write(file: File, value: Any) = file.writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n")
        fun requiredEnv(name: String) = requireNotNull(System.getenv(name)).also { require(it.isNotBlank()) }
        if (args[0] == "initialize") {
            require(!stateFile.exists() && !output.exists())
            val metadata = linkedMapOf("sourceRevision" to requiredEnv("EXPECTED_SHA"), "workflowRevision" to requiredEnv("WORKFLOW_REVISION"),
                "runnerImage" to requiredEnv("ImageVersion"), "runId" to requiredEnv("GITHUB_RUN_ID"), "attempt" to requiredEnv("GITHUB_RUN_ATTEMPT"))
            prepare(File(stage, "source"), metadata.getValue("sourceRevision"), metadata.getValue("workflowRevision"), metadata.getValue("runnerImage"), File(stage, "executed-workflow.yml").readBytes())
            val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            Files.createFile(privateFile.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            privateFile.writeBytes(key.private.encoded)
            val owner = metadata + mapOf("challenge" to UUID.randomUUID().toString().replace("-", ""), "publicKey" to Base64.getEncoder().encodeToString(key.public.encoded))
            write(stateFile, owner)
            output.mkdirs(); write(File(output, "trust.json"), owner)
            clearWorkspace(workspace, workspace)
            return
        }
        val owner = mapper.readTree(stateFile)
        require(owner["runId"].asText() == requiredEnv("GITHUB_RUN_ID") && owner["attempt"].asText() == requiredEnv("GITHUB_RUN_ATTEMPT"))
        val p = prepare(File(stage, "source"), owner["sourceRevision"].asText(), owner["workflowRevision"].asText(), owner["runnerImage"].asText(), File(stage, "executed-workflow.yml").readBytes())
        val challenge = owner["challenge"].asText()
        val trust = CertificationObservationTrust(challenge,
            mapOf(RUNNER to KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(owner["publicKey"].asText())))),
            runIds.map { CertificationAuthorizedRun(it, SCENARIO, it.takeUnless { value -> value == "baseline" }, RUNNER) })
        if (args[0] == "observe") {
            require(args.size == 3 && args[2] in runIds)
            val id = args[2]; val index = runIds.indexOf(id)
            require(runIds.map { File(output, "$it.json").exists() } == runIds.indices.map { it < index }) { "Observation inventory or ordering changed." }
            val raw = observeWorkspace(workspace.toFile())
            val observation = observation(p, id, raw)
            val signer = Signature.getInstance("Ed25519")
            signer.initSign(KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(privateFile.readBytes())))
            signer.update(CertificationObservationAuthentication.signingBytes(RUNNER, challenge, observation))
            write(File(output, "$id.json"), mapOf("raw" to raw.toString(Charsets.UTF_8), "signature" to Base64.getEncoder().encodeToString(signer.sign())))
            if (index < runIds.lastIndex) clearWorkspace(workspace, workspace)
            return
        }
        require(args[0] == "verify" && args.size == 2)
        try {
            val signed = runIds.map { id ->
                val file = File(output, "$id.json"); require(file.length() in 1..8192)
                val record = mapper.readTree(file)
                SignedCertificationObservation(RUNNER, challenge, observation(p, id, record["raw"].asText().toByteArray()), Base64.getDecoder().decode(record["signature"].asText()))
            }
            val assessment = CertificationEvidenceViews.assess(p.bundle, p.bundle.adapter, listOf(p.bound),
                p.provider, signed, trust, CertificationEvidenceResolver { p.evidence[it.id] })
            require(assessment.admission.valid) { "GitHub Actions certification rejected: ${assessment.admission.findings}" }
            val view = requireNotNull(assessment.view)
            val matrix = AdapterBehaviorMatrix.deriveGitHubCheckout(assessment, CertificationEvidenceResolver { p.evidence[it.id] })
            File(output, "behavior-matrix.json").writeText(matrix.json()); File(output, "behavior-matrix.md").writeText(matrix.markdown())
            File(output, "evidence-view.json").writeText(view.json()); File(output, "evidence-view.md").writeText(view.markdown())
            val artifacts = File(output, "artifacts").apply { mkdirs() }
            p.artifacts.forEach { (id, bytes) -> File(artifacts, "$id.yml").writeBytes(bytes) }
            p.evidence.forEach { (id, bytes) -> File(artifacts, id.replace(':', '-') + ".evidence").writeBytes(bytes) }
            File(artifacts, "source.intent.yaml").writeBytes(requireNotNull(p.bound.resolve(p.bound.fixture)))
            File(artifacts, "canonical-graph.json").writeBytes(requireNotNull(p.bound.resolve(p.bound.canonicalGraph)))
            File(stage, "executed-workflow.yml").copyTo(File(artifacts, "execution-envelope.yml"))
            write(File(output, "proof.json"), linkedMapOf("status" to "passed", "claim" to "native-leaf-only",
                "executionMode" to "native-leaf-in-checked-envelope", "sourceRevision" to owner["sourceRevision"].asText(),
                "workflowRevision" to owner["workflowRevision"].asText(), "runId" to owner["runId"].asText(), "attempt" to owner["attempt"].asText(),
                "bundle" to p.bundle, "admission" to assessment.admission, "publicSupportPromoted" to false, "portableExecution" to false,
                "observations" to signed.map { mapOf("runnerId" to it.runnerId, "challenge" to it.challenge, "observation" to it.observation, "signature" to Base64.getEncoder().encodeToString(it.signature())) },
                "behaviorMatrix" to listOf("behavior-matrix.json", "behavior-matrix.md").associateWith { sha256(File(output, it).readBytes()) },
                "evidenceViews" to listOf("evidence-view.json", "evidence-view.md").associateWith { sha256(File(output, it).readBytes()) }))
            println("PASS: $SCENARIO; baseline, omitted checkout and substituted revision authenticated; native leaf only")
        } finally { Files.deleteIfExists(privateFile.toPath()) }
    }
}

object GitHubActionsCheckoutRuntimeCertificationCli {
    @JvmStatic fun main(args: Array<String>) = GitHubActionsCheckoutRuntimeCertification.cli(args)
}
