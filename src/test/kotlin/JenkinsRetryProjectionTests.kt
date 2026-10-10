import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.SupportLevel
import org.flowlang.generators.manifest.TargetJob
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetMaterialization
import org.flowlang.generators.manifest.TargetNativeProjectionCatalog
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.generators.manifest.TargetStructuralProjectionKind
import org.flowlang.generators.manifest.TargetInput
import org.flowlang.planner.TaskNode
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.targets.TargetRegistryYamlLoader
import org.flowlang.targets.builtin.BuiltInNativeProjectionCatalogs
import org.flowlang.targets.builtin.JenkinsManifestRenderer
import kotlin.test.assertFails
import org.flowlang.compiler.requireAccepted
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.source.FlowSourceFrontend
import org.flowlang.modules.ModuleRegistry
import org.flowlang.safety.StandardEnvironmentSafetyPolicyNotes
import org.flowlang.targets.builtin.JenkinsRetryProjectionScope
import org.flowlang.topology.ExecutionTopologyKind
import org.flowlang.topology.ExecutionTopologySupportStatus

class JenkinsRetryProjectionTests {
    private val targets by lazy { TargetRegistryYamlLoader.loadDirectory(File("targets")) }
    private val declared get() = targets.getValue("jenkins")
    private val source = File("flow-adapter-jenkins/src/runtimeTest/retry-success.flow").readText()
    private fun authorization(text: String = source): org.flowlang.compiler.CompilationAuthorization {
        val file = kotlin.io.path.createTempFile(suffix = ".flow").toFile()
        try {
            file.writeText(text)
            return FlowSourceFrontend(FrontendCompilerComposition.compiler(ModuleRegistry.fromDirectory(File("modules")),
                StandardEnvironmentSafetyPolicyNotes.policy(File(".")))).compile(file).requireAccepted().authorization
        } finally { file.delete() }
    }
    private fun scoped(text: String = source, catalog: TargetNativeProjectionCatalog? = BuiltInNativeProjectionCatalogs.jenkins) =
        JenkinsRetryProjectionScope(catalog).resolve(authorization(text), "jenkins", declared)

    @Test fun boundedAuthorizationChangesOnlyThePlanSpecificRetryFeature() {
        assertEquals(declared.copy(features = declared.features + ("retry.task" to SupportLevel.SUPPORTED),
            topologyProfile = scoped().topologyProfile, notes = scoped().notes), scoped())
        val original = declared.topologyProfile!!
        val effective = scoped().topologyProfile!!
        assertEquals(original.declarations.filter { it.kind != ExecutionTopologyKind.ATTEMPT_ISOLATION },
            effective.declarations.filter { it.kind != ExecutionTopologyKind.ATTEMPT_ISOLATION })
        assertEquals(ExecutionTopologySupportStatus.UNKNOWN, original.declarations.single { it.kind == ExecutionTopologyKind.ATTEMPT_ISOLATION }.status)
        assertEquals(ExecutionTopologySupportStatus.SUPPORTED, effective.declarations.single { it.kind == ExecutionTopologyKind.ATTEMPT_ISOLATION }.status)
        assertEquals(SupportLevel.UNSUPPORTED, declared.retry)
        assertEquals(SupportLevel.UNSUPPORTED, declared.features["retry.task"])
    }

    @Test fun delayedOrVariableBackoffPoliciesRemainUnsupported() {
        for (text in listOf(source.replace("0s", "1s"), source.replace("backoff: fixed", "backoff: exponential")))
            assertEquals(declared, scoped(text))
    }

    @Test fun absentOrWrongProviderCannotAuthorizeRetry() {
        val wrong = TargetNativeProjectionCatalog.of("jenkins", emptyList(), structuralDefinitions =
            BuiltInNativeProjectionCatalogs.jenkins.structuralDefinitions.filter { it.structure == TargetStructuralProjectionKind.RETRY }.map { it.copy(reference = "if") })
        for (catalog in listOf(null, wrong, TargetNativeProjectionCatalog.empty("jenkins"), TargetNativeProjectionCatalog.empty("github-actions")))
            assertEquals(declared, scoped(catalog = catalog))
        val github = targets.getValue("github-actions")
        assertEquals(github, JenkinsRetryProjectionScope(BuiltInNativeProjectionCatalogs.jenkins).resolve(authorization(), "github-actions", github))
    }

    @Test fun plansWithoutRetryKeepDeclaredCapabilities() {
        val text = File("flow-adapter-jenkins/src/runtimeTest/recovery-success.flow").readText()
        assertEquals(declared, scoped(text))
    }

    @Test fun unprovenBodyAndIncompleteTopologyRemainBlocked() {
        val nested = source.replace("retry { max: 3 delay: \"0s\" backoff: fixed } {",
            "retry { max: 3 delay: \"0s\" backoff: fixed } { retry { max: 2 delay: \"0s\" backoff: fixed } {")
            .replace("} -> attemptedCheckout", "} -> attemptedCheckout }")
        assertEquals(declared, scoped(nested))
        val profile = declared.topologyProfile!!
        for (candidate in listOf(declared.copy(topologyProfile = null),
            declared.copy(topologyProfile = profile.copy(declarations = profile.declarations.filter { it.kind != ExecutionTopologyKind.ATTEMPT_ISOLATION })),
            declared.copy(topologyProfile = profile.copy(declarations = profile.declarations + profile.declarations.single { it.kind == ExecutionTopologyKind.ATTEMPT_ISOLATION }))))
            assertEquals(candidate, JenkinsRetryProjectionScope(BuiltInNativeProjectionCatalogs.jenkins).resolve(authorization(), "jenkins", candidate))
    }

    @Test fun rendererRejectsIncompleteAndUnsupportedPolicies() {
        val valid = mapOf("max" to "3", "delay" to "0s", "backoff" to "fixed")
        val resolved = BuiltInNativeProjectionCatalogs.jenkins.resolveStructure(TargetStructuralProjectionKind.RETRY, "retry")
        fun render(params: Map<String, String>) = JenkinsManifestRenderer().render(manifest(TargetStep(
            id = "retry", type = "retry", params = params, children = listOf(jenkinsCheckoutStep("body")),
            materialization = resolved.materialization, rendererPayload = resolved.rendererPayload), supportedJenkinsCompatibility(), true))
        assertContains(render(valid), "retry(3) {")
        assertContains(render(valid + ("max" to "1")), "retry(1) {")
        for (params in listOf(valid - "delay", valid + ("extra" to "ignored"), valid + ("max" to "0"),
            valid + ("max" to "-1"), valid + ("max" to "4294967297"), valid + ("delay" to "1s"), valid + ("backoff" to "exponential")))
            assertFails { render(params) }
    }

    private fun supportedJenkinsCompatibility(): CompatibilityReport {
        val target = targets.getValue("jenkins")
        return CompatibilityReport(
            target = target.target,
            status = SupportLevel.SUPPORTED,
            capabilityStatus = SupportLevel.SUPPORTED,
            expressionSupport = target.expressionSupport,
            projectionRules = target.projectionRules
        )
    }

    private fun jenkinsCheckoutStep(id: String): TargetStep {
        val target = targets.getValue("jenkins")
        val rule = target.projectionRules.single { it.module == "git" && it.action == "checkout" }
        val task = TaskNode(
            id = id,
            module = "git",
            action = "checkout",
            target = "source",
            params = mapOf(
                "url" to "https://example.invalid/source.git",
                "branch" to "main"
            )
        )
        val payload = BuiltInNativeProjectionCatalogs.jenkins.compile(rule, task)
        return TargetStep(
            id = id,
            name = id,
            type = "action",
            module = "git",
            action = "checkout",
            target = "source",
            materialization = TargetMaterialization.native(
                capability = "git.checkout",
                reason = "Native checkout fixture."
            ),
            rendererPayload = payload,
            params = task.params
        )
    }

    private fun manifest(
        step: TargetStep,
        compatibility: CompatibilityReport,
        executableMetadata: Boolean,
        inputs: List<TargetInput> = emptyList()
    ): TargetManifest = TargetManifest(
        target = compatibility.target,
        flowName = "structural-honesty",
        compatibility = compatibility,
        inputs = inputs,
        jobs = listOf(TargetJob(id = "structural-honesty", steps = listOf(step))),
        metadata = mapOf(
            "sourcePlanVersion" to FlowStandardVersions.EXECUTION_PLAN_VERSION,
            "generator" to "JenkinsRetryProjectionTests",
            "standardVersion" to FlowStandardVersions.FLOW_STANDARD_VERSION,
            "capabilityCompatibility" to compatibility.capabilityStatus.name,
            "effectiveCompatibility" to compatibility.status.name,
            "materializationReadiness" to if (executableMetadata) "COMPLETE" else "REVIEW_REQUIRED",
            "projectionReadiness" to if (executableMetadata) "EXECUTABLE" else "REVIEW_ONLY",
            "executable" to executableMetadata.toString()
        )
    )
}
