import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.flowlang.adapters.continuity.AdapterContinuityDecision
import org.flowlang.adapters.continuity.AdapterContinuityEvidenceStatus
import org.flowlang.adapters.continuity.AdapterContinuityFamily
import org.flowlang.adapters.continuity.AdapterContinuitySatisfactionAuthority
import org.flowlang.adapters.continuity.AdapterContinuityScopedSupportIntegrityAuthority
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.SupportLevel
import org.flowlang.generators.manifest.TargetRendererPayload
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.FlowPlanner
import org.flowlang.planner.PlanDependencyKind
import org.flowlang.projection.ProjectionBindingKind
import org.flowlang.targets.builtin.BuiltInTargetProjections
import org.flowlang.targets.builtin.GitHubActionsWorkspaceContinuityPlanner
import org.flowlang.targets.builtin.UnsupportedGitHubActionsWorkspaceContinuityException

class AdapterContinuityProviderBehaviorTests {
    private val root = File(".")
    private val modules = ModuleRegistry.fromDirectory(File(root, "modules"))
    private val targets = TargetRegistryYamlLoader.loadDirectory(File(root, "targets"))
    private val authority = AdapterContinuitySatisfactionAuthority(rootDir = root, targets = targets)

    @Test
    fun jenkinsCheckoutBuildImageReplaysOneSharedWorkspacePath() {
        val plan = referencePlan()
        val workspace = plan.dependencyRelations.single { it.kind == PlanDependencyKind.WORKSPACE }

        assertEquals("git_checkout_1", workspace.sourceNodeId)
        assertEquals("docker_build_1", workspace.targetNodeId)
        assertEquals(listOf("git_checkout_1", "docker_build_1"), workspace.path)

        val assessment = authority.requireMatched(plan, "jenkins")
        assertEquals(AdapterContinuityDecision.MATCHED, assessment.decision)
        assertEquals(AdapterContinuityFamily.ARTIFACT, assessment.requirements.single().family)
        assertEquals(AdapterContinuityEvidenceStatus.SATISFIED, assessment.evidence.single().status)

        val manifest = BuiltInTargetProjections.pipeline(targets).generate(
            testMaterializationRequest(plan, "jenkins", targets)
        )
        assertEquals(1, manifest.jobs.size)
        val steps = manifest.jobs.single().steps
        assertEquals(listOf("git", "docker"), steps.mapNotNull { it.module })
        assertEquals(listOf("checkout", "build"), steps.mapNotNull { it.action })

        val rendered = BuiltInTargetProjections.registry.requireProvider("jenkins").render(manifest)
        assertTrue(rendered.contains("agent any"))
        assertTrue(rendered.contains("git branch:"))
        assertTrue(rendered.contains("docker.build("))
        assertFalse(rendered.contains("stash "))
        assertFalse(rendered.contains("unstash "))
    }

    @Test
    fun githubActionsCheckoutBuildUsesBoundedArtifactWorkspaceTransfer() {
        val plan = referencePlan()
        val assessment = authority.requireMatched(plan, "github-actions")
        assertEquals(AdapterContinuityDecision.MATCHED, assessment.decision)
        assertEquals(AdapterContinuityEvidenceStatus.SATISFIED, assessment.evidence.single().status)
        assertTrue(assessment.evidence.single().detail.contains("Bounded adapter continuity support"))

        val provider = BuiltInTargetProjections.registry.requireProvider("github-actions")
        val target = targets.getValue("github-actions")
        val manifest = provider.generate(
            plan,
            CompatibilityReport(
                target = target.target,
                status = SupportLevel.SUPPORTED,
                capabilityStatus = SupportLevel.SUPPORTED,
                expressionSupport = target.expressionSupport,
                projectionRules = target.projectionRules
            )
        )

        assertTrue(manifest.compatibility.executable)
        assertEquals("1", manifest.metadata["workspaceContinuityTransferCount"])
        assertEquals(2, manifest.jobs.size)
        val producer = manifest.jobs.single { it.id == "git_checkout_1" }
        val consumer = manifest.jobs.single { it.id == "docker_build_1" }
        assertEquals(listOf("git_checkout_1"), consumer.dependsOn)
        assertEquals(
            listOf("actions/checkout@v4", GitHubActionsWorkspaceContinuityPlanner.UPLOAD_REFERENCE),
            producer.steps.map { requireNotNull(it.rendererPayload).reference }
        )
        assertEquals(
            listOf(GitHubActionsWorkspaceContinuityPlanner.DOWNLOAD_REFERENCE, "docker/build-push-action@v7"),
            consumer.steps.map { requireNotNull(it.rendererPayload).reference }
        )

        val upload = requireNotNull(producer.steps.last().rendererPayload)
        val download = requireNotNull(consumer.steps.first().rendererPayload)
        assertEquals(artifactName(upload), artifactName(download))
        assertEquals(".", literal(upload, "path"))
        assertEquals(".", literal(download, "path"))
        assertEquals("error", literal(upload, "if-no-files-found"))
        assertEquals("true", literal(upload, "include-hidden-files"))

        val rendered = provider.render(manifest)
        val checkoutIndex = rendered.indexOf("actions/checkout@v4")
        val uploadIndex = rendered.indexOf(GitHubActionsWorkspaceContinuityPlanner.UPLOAD_REFERENCE)
        val downloadIndex = rendered.indexOf(GitHubActionsWorkspaceContinuityPlanner.DOWNLOAD_REFERENCE)
        val buildIndex = rendered.indexOf("docker/build-push-action@v7")
        assertTrue(checkoutIndex >= 0, rendered)
        assertTrue(uploadIndex > checkoutIndex, rendered)
        assertTrue(downloadIndex > uploadIndex, rendered)
        assertTrue(buildIndex > downloadIndex, rendered)
        assertTrue(rendered.contains("needs: [git_checkout_1]"), rendered)
        assertTrue(rendered.contains("if-no-files-found: \"error\""), rendered)
        assertTrue(rendered.contains("include-hidden-files: \"true\""), rendered)
    }

    @Test
    fun githubActionsTransferContractReconstructsExactProducerBytes() {
        val plan = referencePlan()
        val provider = BuiltInTargetProjections.registry.requireProvider("github-actions")
        val target = targets.getValue("github-actions")
        val manifest = provider.generate(
            plan,
            CompatibilityReport(
                target = target.target,
                status = SupportLevel.SUPPORTED,
                capabilityStatus = SupportLevel.SUPPORTED,
                expressionSupport = target.expressionSupport,
                projectionRules = target.projectionRules
            )
        )
        val upload = requireNotNull(manifest.jobs.single { it.id == "git_checkout_1" }.steps.last().rendererPayload)
        val download = requireNotNull(manifest.jobs.single { it.id == "docker_build_1" }.steps.first().rendererPayload)

        val producer = Files.createTempDirectory("flow-a1-producer").toFile()
        val artifactStore = Files.createTempDirectory("flow-a1-artifact").toFile()
        val consumer = Files.createTempDirectory("flow-a1-consumer").toFile()
        try {
            File(producer, "Dockerfile").writeBytes("FROM scratch\n".toByteArray())
            File(producer, ".dockerignore").writeBytes("build/\n".toByteArray())
            File(producer, "src/payload.bin").apply {
                parentFile.mkdirs()
                writeBytes(byteArrayOf(0, 1, 2, 3, 127, -1))
            }

            replayUpload(producer, artifactStore, upload)
            replayDownload(artifactStore, consumer, download)

            listOf("Dockerfile", ".dockerignore", "src/payload.bin").forEach { relative ->
                assertContentEquals(
                    File(producer, relative).readBytes(),
                    File(consumer, relative).readBytes(),
                    relative
                )
            }
        } finally {
            producer.deleteRecursively()
            artifactStore.deleteRecursively()
            consumer.deleteRecursively()
        }
    }

    @Test
    fun githubActionsRejectsWorkspaceRelationsOutsideDeclaredScope() {
        val plan = referencePlan()
        val unsupported = plan.copy(
            dependencyRelations = plan.dependencyRelations.map { relation ->
                if (relation.kind == PlanDependencyKind.WORKSPACE) relation.copy(channel = "unrelated") else relation
            }
        )
        val assessment = authority.assess(unsupported, "github-actions")
        assertEquals(AdapterContinuityDecision.BLOCKED, assessment.decision)
        assertEquals(AdapterContinuityEvidenceStatus.UNSUPPORTED, assessment.evidence.single().status)

        val target = targets.getValue("github-actions")
        assertFailsWith<UnsupportedGitHubActionsWorkspaceContinuityException> {
            BuiltInTargetProjections.registry.requireProvider("github-actions").generate(
                unsupported,
                CompatibilityReport(
                    target = target.target,
                    status = SupportLevel.SUPPORTED,
                    capabilityStatus = SupportLevel.SUPPORTED,
                    expressionSupport = target.expressionSupport,
                    projectionRules = target.projectionRules
                )
            )
        }
    }

    @Test
    fun scopedSupportEvidenceIsRepositoryBackedAndTektonRemainsBlocked() {
        val report = AdapterContinuityScopedSupportIntegrityAuthority(root).analyze()
        assertEquals("PASS", report.status, report.findings.joinToString { "${it.code}:${it.message}" })
        assertEquals(1, report.declarationCount)

        val assessment = authority.assess(referencePlan(), "tekton")
        assertEquals(AdapterContinuityDecision.BLOCKED, assessment.decision)
        assertEquals(AdapterContinuityEvidenceStatus.UNSUPPORTED, assessment.evidence.single().status)
    }

    private fun replayUpload(producer: File, store: File, payload: TargetRendererPayload) {
        assertEquals(".", literal(payload, "path"))
        val destination = File(store, artifactName(payload))
        copyTree(producer, destination)
    }

    private fun replayDownload(store: File, consumer: File, payload: TargetRendererPayload) {
        assertEquals(".", literal(payload, "path"))
        val source = File(store, artifactName(payload))
        assertTrue(source.isDirectory, "Artifact identity did not resolve to uploaded bytes.")
        copyTree(source, consumer)
    }

    private fun copyTree(source: File, destination: File) {
        source.walkTopDown().forEach { file ->
            val relative = file.relativeTo(source)
            val target = File(destination, relative.path)
            if (file.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile.mkdirs()
                file.copyTo(target, overwrite = true)
            }
        }
    }

    private fun artifactName(payload: TargetRendererPayload): String {
        val binding = assertNotNull(payload.bindings["name"])
        assertEquals(ProjectionBindingKind.ARTIFACT, binding.kind)
        return assertNotNull(binding.name)
    }

    private fun literal(payload: TargetRendererPayload, name: String): String {
        val binding = assertNotNull(payload.bindings[name])
        assertEquals(ProjectionBindingKind.LITERAL, binding.kind)
        return assertNotNull(binding.value)
    }

    private fun referencePlan() = IntentYamlLoader.load(
        File(root, "examples/intent/checkout-build-image.intent.yaml")
    ).let { intent ->
        IntentCapabilityValidator(modules).validate(intent).assertValid()
        FlowPlanner(modules).plan(IntentToAstPlanner(modules).plan(intent))
    }
}
