package org.flowlang.tests

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentPolicy
import org.flowlang.intent.IntentPolicyType
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentString
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.StandardCapability

class FlowSafetyPolicyValidationTests {
    private fun validate(vararg steps: IntentStep, policies: List<IntentPolicy> = emptyList()) =
        IntentCapabilityValidator().validate(
            IntentDocument(
                name = "safety-test",
                workflows = listOf(IntentWorkflow("main", IntentWorkflowKind.CUSTOM, steps.toList())),
                policies = policies
            )
        )

    @Test
    fun databaseMigrationRequiresBackupWhenPolicySaysSo() {
        val report = validate(
            IntentStep("migrate", StandardCapability.DATABASE_MIGRATE, params = mapOf("database" to IntentString("orders"))),
            policies = listOf(IntentPolicy("backup-required", IntentPolicyType.SAFETY, "requiresBackup"))
        )
        assertFalse(report.valid)
        assertTrue(report.issues.any { it.code == "SAFETY_REQUIRES_BACKUP" })
    }

    @Test
    fun kubernetesMaintenanceRequiresDryRunWhenPolicySaysSo() {
        val report = validate(
            IntentStep("maintain", StandardCapability.KUBERNETES_MAINTENANCE, params = mapOf("scope" to IntentString("payments"))),
            policies = listOf(IntentPolicy("dry-run-required", IntentPolicyType.SAFETY, "requiresDryRun"))
        )
        assertFalse(report.valid)
        assertTrue(report.issues.any { it.code == "SAFETY_REQUIRES_DRY_RUN" })
    }

    @Test
    fun cleanupRequiresRetentionOrSafetyRule() {
        val report = validate(
            IntentStep("cleanup", StandardCapability.CLEANUP, params = mapOf("resource" to IntentString("docker-images")))
        )
        assertFalse(report.valid)
        assertTrue(report.issues.any { it.code == "SAFETY_CLEANUP_REQUIRES_RETENTION" })
    }

    @Test
    fun cleanupWithRetentionIsAllowed() {
        val report = validate(
            IntentStep("cleanup", StandardCapability.CLEANUP, params = mapOf("resource" to IntentString("docker-images"), "retention" to IntentString("14d")))
        )
        assertTrue(report.valid, report.issues.joinToString { it.code + ": " + it.message })
    }
}
