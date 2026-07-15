import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.MaterializationReadinessStatus
import org.flowlang.capabilities.ProjectionReadinessStatus
import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetProjectionMode
import org.flowlang.capabilities.TargetProjectionRule
import org.flowlang.capabilities.TargetRendererPayloadTemplate
import org.flowlang.targets.builtin.GitHubActionsManifestRenderer
import org.flowlang.generators.manifest.TargetJob
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetManifestContractValidator
import org.flowlang.generators.manifest.TargetMaterialization
import org.flowlang.generators.manifest.TargetMaterializationResolver
import org.flowlang.generators.manifest.TargetMaterializationStatus
import org.flowlang.generators.manifest.TargetRendererPayload
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetRenderPolicy
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.targets.builtin.TektonManifestRenderer
import org.flowlang.planner.TaskNode
import org.flowlang.projection.ProjectionBinding
import org.flowlang.projection.ProjectionBindingContract
import org.flowlang.projection.ProjectionBindingKind
import org.flowlang.projection.ProjectionBindingResolutionStatus
import org.flowlang.projection.TaskMetadataField
import org.flowlang.targets.TargetProjectionPayloadDescriptor

class ProjectionBindingContractTests {
    @Test
    fun registryAcceptsEveryTargetNeutralBindingKind() {
        val descriptor = TargetProjectionPayloadDescriptor(
            kind = "future-task",
            reference = "catalog/task@sha256:1234",
            bindings = mapOf(
                "literal" to ProjectionBinding.literal("stable"),
                "parameter" to ProjectionBinding.taskParameter("repository"),
                "input" to ProjectionBinding.taskInput("workspace"),
                "metadata" to ProjectionBinding.taskMetadata(TaskMetadataField.ID),
                "flowInput" to ProjectionBinding.flowInput("environment"),
                "secret" to ProjectionBinding.secret("registry-token"),
                "artifact" to ProjectionBinding.artifact("image-bundle"),
                "taskOutput" to ProjectionBinding.taskOutput("build", "image-reference"),
                "expression" to ProjectionBinding.targetExpression("future", "native.expression")
            )
        )

        val template = descriptor.toTemplate("future")

        assertEquals("FUTURE_TASK", template.kind)
        assertEquals(9, template.bindings.size)
        assertTrue(template.bindings.values.all { it.resolutionStatus == null })
    }

    @Test
    fun registryRejectsInvalidFieldCombinationsAndPreResolvedSources() {
        assertFailsWith<IllegalArgumentException> {
            ProjectionBindingContract.requireTemplate(
                ProjectionBinding(
                    kind = ProjectionBindingKind.SECRET,
                    name = "token",
                    value = "forbidden"
                ),
                "test.secret"
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProjectionBindingContract.requireTemplate(
                ProjectionBinding(
                    kind = ProjectionBindingKind.TASK_PARAMETER,
                    name = "url",
                    value = "already-resolved"
                ),
                "test.parameter"
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProjectionBindingContract.requireTemplate(
                ProjectionBinding.taskParameter("url").copy(
                    resolutionStatus = ProjectionBindingResolutionStatus.UNRESOLVED,
                    reason = "Registry must not decide runtime resolution."
                ),
                "test.parameter-status"
            )
        }
    }

    @Test
    fun resolverPreservesBindingKindsAndResolvesCompileTimeSources() {
        val resolution = TargetMaterializationResolver.resolve(
            task = TaskNode(
                id = "checkout_source",
                module = "git",
                action = "checkout",
                target = "source",
                params = mapOf("url" to "https://example.invalid/repository.git"),
                inputs = mapOf("workspace" to "source-workspace")
            ),
            targetName = "jenkins",
            projectionRules = listOf(nativeRule(
                targetKind = "JENKINS_STEP",
                bindings = mapOf(
                    "url" to ProjectionBinding.taskParameter("url"),
                    "branch" to ProjectionBinding.taskParameter("branch", defaultValue = "main"),
                    "workspace" to ProjectionBinding.taskInput("workspace"),
                    "taskId" to ProjectionBinding.taskMetadata(TaskMetadataField.ID),
                    "flowInput" to ProjectionBinding.flowInput("environment")
                )
            ))
        )

        val bindings = assertNotNull(resolution.rendererPayload).bindings
        assertEquals(ProjectionBindingKind.TASK_PARAMETER, bindings.getValue("url").kind)
        assertEquals("url", bindings.getValue("url").name)
        assertEquals("https://example.invalid/repository.git", bindings.getValue("url").value)
        assertEquals(ProjectionBindingResolutionStatus.RESOLVED, bindings.getValue("url").resolutionStatus)
        assertEquals("main", bindings.getValue("branch").value)
        assertEquals(ProjectionBindingResolutionStatus.RESOLVED, bindings.getValue("branch").resolutionStatus)
        assertEquals("source-workspace", bindings.getValue("workspace").value)
        assertEquals("checkout_source", bindings.getValue("taskId").value)
        assertEquals(ProjectionBindingKind.FLOW_INPUT, bindings.getValue("flowInput").kind)
        assertEquals(ProjectionBindingResolutionStatus.SYMBOLIC, bindings.getValue("flowInput").resolutionStatus)
        assertEquals(null, bindings.getValue("flowInput").value)
    }

    @Test
    fun missingCompileTimeSourceRemainsAuditableAndReviewOnly() {
        val resolution = TargetMaterializationResolver.resolve(
            task = TaskNode(
                id = "checkout_source",
                module = "git",
                action = "checkout",
                target = "source"
            ),
            targetName = "jenkins",
            projectionRules = listOf(nativeRule(
                targetKind = "JENKINS_STEP",
                bindings = mapOf("url" to ProjectionBinding.taskParameter("url"))
            ))
        )
        val payload = assertNotNull(resolution.rendererPayload)
        val binding = payload.bindings.getValue("url")
        val manifest = manifestWithPayload("jenkins", payload)

        assertEquals(ProjectionBindingResolutionStatus.UNRESOLVED, binding.resolutionStatus)
        assertEquals(null, binding.value)
        assertTrue(binding.reason.orEmpty().contains("does not provide required parameter 'url'"))
        assertTrue(TargetManifestContractValidator.validate(manifest).valid)

        val readiness = TargetRenderPolicy.evaluate(manifest)
        assertEquals(TargetRenderMode.REVIEW_ONLY, readiness.mode)
        assertTrue(readiness.findings.any { finding ->
            finding.status == "TARGET_BINDING_UNRESOLVED" && finding.reason.contains("url")
        })
    }

    @Test
    fun invalidTargetExpressionPreventsExecutableReadiness() {
        val manifest = executableManifest(
            target = "jenkins",
            payloadKind = "JENKINS_STEP",
            reference = "git",
            bindings = mapOf(
                "url" to ProjectionBinding.targetExpression(
                    target = "github-actions",
                    expression = "\${{ github.repository }}"
                )
            )
        )

        val contract = TargetManifestContractValidator.validate(manifest)
        val readiness = TargetRenderPolicy.evaluate(manifest)

        assertFalse(contract.valid)
        assertTrue(contract.issues.any { it.code == "RENDERER_PAYLOAD_BINDING_INVALID" })
        assertEquals(TargetRenderMode.REVIEW_ONLY, readiness.mode)
        assertTrue(readiness.findings.any { it.status == "TARGET_BINDING_INVALID" })
    }

    @Test
    fun edgeRenderersTranslateSupportedSymbolicBindings() {
        val github = GitHubActionsManifestRenderer().render(executableManifest(
            target = "github-actions",
            payloadKind = "GITHUB_ACTION",
            reference = "example/action@sha256:1234",
            bindings = mapOf(
                "environment" to ProjectionBinding.flowInput("environment"),
                "token" to ProjectionBinding.secret("registry-token"),
                "image" to ProjectionBinding.taskOutput("build", "image-reference")
            )
        ))
        assertTrue(github.contains("\${{ inputs.environment }}"))
        assertTrue(github.contains("\${{ secrets.registry-token }}"))
        assertTrue(github.contains("\${{ needs.build.outputs.image-reference }}"))

        val tekton = TektonManifestRenderer().render(executableManifest(
            target = "tekton",
            payloadKind = "TEKTON_TASK",
            reference = "deploy-task",
            bindings = mapOf(
                "environment" to ProjectionBinding.flowInput("environment"),
                "image" to ProjectionBinding.taskOutput("build", "image-reference")
            )
        ))
        assertTrue(tekton.contains("\$(params.environment)"))
        assertTrue(tekton.contains("\$(tasks.build.results.image-reference)"))
    }

    @Test
    fun edgeRendererRejectsBindingWithoutConcreteTargetContract() {
        val failure = assertFailsWith<IllegalStateException> {
            TektonManifestRenderer().render(executableManifest(
                target = "tekton",
                payloadKind = "TEKTON_TASK",
                reference = "deploy-task",
                bindings = mapOf("token" to ProjectionBinding.secret("registry-token"))
            ))
        }

        assertTrue(failure.message.orEmpty().contains(
            "Tekton secret binding requires explicit workspace or secretKeyRef evidence"
        ))
    }

    @Test
    fun serializationOmitsFieldsThatDoNotBelongToBindingKind() {
        val registryJson = ObjectMapper().registerKotlinModule().writeValueAsString(
            ProjectionBinding.secret("registry-token")
        )
        val manifestJson = ObjectMapper().registerKotlinModule().writeValueAsString(
            ProjectionBinding.secret("registry-token").asManifestBinding()
        )

        assertTrue(registryJson.contains("\"kind\":\"SECRET\""))
        assertTrue(registryJson.contains("\"name\":\"registry-token\""))
        assertFalse(registryJson.contains("\"resolutionStatus\""))
        assertFalse(registryJson.contains("\"value\""))
        assertTrue(manifestJson.contains("\"resolutionStatus\":\"SYMBOLIC\""))
        assertFalse(manifestJson.contains("\"reason\""))
    }

    @Test
    fun productionSourcesContainNoPrefixEncodedPayloadBindingParser() {
        val sources = listOf(
            "src/main/kotlin/org/flowlang/generators/manifest/TargetMaterializationResolverEngine.kt",
            "src/main/kotlin/org/flowlang/targets/TargetRegistryModels.kt",
            "targets/builtin-targets.yaml"
        ).associateWith { File(it).readText() }

        val forbidden = listOf(
            "startsWith(\"param:\")",
            "startsWith(\"input:\")",
            "literal:",
            "task:id",
            "task:target"
        )
        sources.forEach { (path, source) ->
            forbidden.forEach { token ->
                assertFalse(source.contains(token), "$path must not contain prefix-encoded binding token '$token'.")
            }
        }
    }

    private fun nativeRule(
        targetKind: String,
        bindings: Map<String, ProjectionBinding>
    ): TargetProjectionRule = TargetProjectionRule(
        module = "git",
        action = "checkout",
        mode = TargetProjectionMode.NATIVE,
        reason = "Concrete native projection evidence exists.",
        evidenceReference = "test:projection-rule",
        payload = TargetRendererPayloadTemplate(
            kind = targetKind,
            reference = "git",
            bindings = bindings
        )
    )

    private fun executableManifest(
        target: String,
        payloadKind: String,
        reference: String,
        bindings: Map<String, ProjectionBinding>
    ): TargetManifest = manifestWithPayload(
        target = target,
        payload = TargetRendererPayload(
            kind = payloadKind,
            target = target,
            reference = reference,
            bindings = bindings.mapValues { (_, binding) -> binding.asManifestBinding() },
            evidenceReference = "test:$target#typed-bindings"
        )
    )

    private fun manifestWithPayload(
        target: String,
        payload: TargetRendererPayload
    ): TargetManifest = TargetManifest(
        target = target,
        flowName = "typed-binding-test",
        compatibility = CompatibilityReport(
            target = target,
            status = SupportLevel.SUPPORTED,
            capabilityStatus = SupportLevel.SUPPORTED,
            materializationReadiness = MaterializationReadinessStatus.COMPLETE,
            projectionReadiness = ProjectionReadinessStatus.EXECUTABLE,
            executable = true,
            readinessEvidenceAvailable = true
        ),
        jobs = listOf(TargetJob(
            id = "projection",
            steps = listOf(TargetStep(
                id = "typed_projection",
                type = "action",
                module = "example",
                action = "project",
                target = "artifact",
                materialization = TargetMaterialization(
                    status = TargetMaterializationStatus.NATIVE,
                    capability = "example.project",
                    reason = "Complete declarative projection evidence is available."
                ),
                rendererPayload = payload
            ))
        )),
        metadata = mapOf(
            "sourcePlanVersion" to "2.0",
            "generator" to "ProjectionBindingContractTests",
            "standardVersion" to "0.8.0",
            "capabilityCompatibility" to "SUPPORTED",
            "effectiveCompatibility" to "SUPPORTED",
            "materializationReadiness" to "COMPLETE",
            "projectionReadiness" to "EXECUTABLE",
            "executable" to "true"
        )
    )

    private fun ProjectionBinding.asManifestBinding(): ProjectionBinding = when (kind) {
        ProjectionBindingKind.LITERAL -> copy(
            resolutionStatus = ProjectionBindingResolutionStatus.RESOLVED
        )
        ProjectionBindingKind.TASK_PARAMETER,
        ProjectionBindingKind.TASK_INPUT,
        ProjectionBindingKind.TASK_METADATA -> if (value != null) {
            copy(resolutionStatus = ProjectionBindingResolutionStatus.RESOLVED)
        } else {
            copy(
                resolutionStatus = ProjectionBindingResolutionStatus.UNRESOLVED,
                reason = "Test binding intentionally lacks a compile-time value."
            )
        }
        ProjectionBindingKind.FLOW_INPUT,
        ProjectionBindingKind.SECRET,
        ProjectionBindingKind.ARTIFACT,
        ProjectionBindingKind.TASK_OUTPUT,
        ProjectionBindingKind.TARGET_EXPRESSION -> copy(
            resolutionStatus = ProjectionBindingResolutionStatus.SYMBOLIC
        )
    }
}
