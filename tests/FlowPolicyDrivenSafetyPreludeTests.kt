import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.SupportLevel
import org.flowlang.generators.manifest.TargetEnvironmentSafetyEvidenceResolver
import org.flowlang.generators.manifest.TargetJob
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetMaterialization
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.safety.EnvironmentPolicyRule
import org.flowlang.safety.EnvironmentSafetyPolicy
import org.flowlang.safety.EnvironmentSafetyPolicyNotes
import org.flowlang.safety.EnvironmentSensitivity
import org.flowlang.safety.StandardEnvironmentSafetyPolicyNotes

class FlowPolicyDrivenSafetyPreludeTests {
    @Test
    fun baselinePolicyClassifiesOnlyDeclaredEnvironmentEvidence() {
        val policy = StandardEnvironmentSafetyPolicyNotes.policy()

        val sensitive = policy.classify(mapOf("namespace" to "prod"))
        assertEquals(EnvironmentSensitivity.SENSITIVE, sensitive.sensitivity)
        assertEquals("environment.sensitive.production-like", sensitive.ruleId)
        assertEquals("production", sensitive.approvalEnvironment)
        assertTrue(sensitive.evidenceAvailable)

        val nonSensitive = policy.classify(mapOf("environment" to "dev"))
        assertEquals(EnvironmentSensitivity.NON_SENSITIVE, nonSensitive.sensitivity)
        assertNull(nonSensitive.approvalEnvironment)

        val unknown = policy.classify(mapOf("namespace" to "customer-a"))
        assertEquals(EnvironmentSensitivity.UNKNOWN, unknown.sensitivity)
        assertFalse(unknown.evidenceAvailable)
        assertNull(unknown.approvalEnvironment)
    }

    @Test
    fun customSafetyNotesCanClassifyNonstandardSensitiveEnvironment() {
        val policy = EnvironmentSafetyPolicy(
            EnvironmentSafetyPolicyNotes(
                packageId = "example.safety.environment",
                packageVersion = "1.0",
                rules = listOf(
                    EnvironmentPolicyRule(
                        ruleId = "environment.sensitive.customer-critical",
                        parameterNames = setOf("tenant"),
                        values = setOf("critical-a"),
                        sensitivity = EnvironmentSensitivity.SENSITIVE,
                        approvalEnvironment = "customer-critical",
                        reason = "Customer policy marks this tenant as sensitive."
                    )
                ),
                unknownReason = "No customer safety rule matched."
            )
        )

        val evidence = policy.classify(mapOf("tenant" to "critical-a"))

        assertEquals(EnvironmentSensitivity.SENSITIVE, evidence.sensitivity)
        assertEquals("customer-critical", evidence.approvalEnvironment)
        assertEquals("example.safety.environment", evidence.policyPackageId)
    }

    @Test
    fun githubApprovalEnvironmentRequiresSensitiveDownstreamEvidence() {
        val manifest = manifestWithEnvironment("namespace", "prod")
        val approval = manifest.jobs.first { it.metadata["approval"] == "true" }

        val evidence = TargetEnvironmentSafetyEvidenceResolver().resolve(manifest, approval)

        assertEquals(EnvironmentSensitivity.SENSITIVE, evidence.sensitivity)
        assertEquals("production", evidence.approvalEnvironment)
        assertEquals(setOf("namespace=prod"), evidence.parameterEvidence)
    }

    @Test
    fun githubApprovalEnvironmentIsOmittedForNonSensitiveOrUnknownEvidence() {
        listOf("dev", "customer-a").forEach { environment ->
            val manifest = manifestWithEnvironment("namespace", environment)
            val approval = manifest.jobs.first { it.metadata["approval"] == "true" }

            val evidence = TargetEnvironmentSafetyEvidenceResolver().resolve(manifest, approval)

            assertNull(evidence.approvalEnvironment, "Approval environment must not be guessed for '$environment'.")
        }
    }

    @Test
    fun rendererSourceDoesNotForceProductionForEveryApproval() {
        val source = File(
            "src/main/kotlin/org/flowlang/targets/builtin/GitHubActionsManifestRenderer.kt"
        ).readText()
        val validatorSource = File("src/main/kotlin/org/flowlang/validator/SafetyBoundaryValidator.kt").readText()

        assertFalse(source.contains(
            "if (job.metadata[\"approval\"] == \"true\") sb.appendLine(\"    environment: production\")"
        ))
        assertFalse(validatorSource.contains("productionBoundaryParamNames"))
        assertFalse(validatorSource.contains("productionValues"))
        assertTrue(source.contains("environmentEvidenceResolver.resolve(manifest, job)"))
        assertFalse(
            File("src/main/kotlin/org/flowlang/generators/manifest/GitHubActionsManifestRenderer.kt").exists(),
            "GitHub Actions rendering policy must not return to Core."
        )
    }

    private fun manifestWithEnvironment(parameter: String, value: String): TargetManifest {
        val approval = TargetJob(
            id = "approve",
            name = "approve",
            steps = listOf(
                TargetStep(
                    id = "approve-step",
                    type = "approval",
                    materialization = TargetMaterialization.native("approval.require", "Test approval evidence.")
                )
            ),
            metadata = mapOf("approval" to "true")
        )
        val deployment = TargetJob(
            id = "deploy",
            name = "deploy",
            dependsOn = listOf("approve"),
            steps = listOf(
                TargetStep(
                    id = "deploy-step",
                    type = "action",
                    module = "kubernetes",
                    action = "deploy",
                    params = mapOf(parameter to value),
                    materialization = TargetMaterialization.adapterRequired(
                        "deployment.apply",
                        "Test adapter evidence."
                    )
                )
            )
        )
        return TargetManifest(
            target = "github-actions",
            flowName = "policy-evidence",
            compatibility = CompatibilityReport("github-actions", SupportLevel.PARTIAL),
            jobs = listOf(approval, deployment)
        )
    }
}
