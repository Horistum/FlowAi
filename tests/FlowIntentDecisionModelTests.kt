package org.flowlang.tests

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.ai.normalization.AiIntentRequest
import org.flowlang.ai.normalization.ScenarioPackIntentNormalizer
import org.flowlang.adapters.yaml.IntentYamlLoader
import org.flowlang.intent.IntentDecisionAnalyzer
import org.flowlang.modules.ModuleRegistry
import java.io.File

class FlowIntentDecisionModelTests {
    private val registry = ModuleRegistry.fromDirectory(File("modules"), includeDefaults = true)
    private val analyzer = IntentDecisionAnalyzer(registry)

    @Test
    fun cleanupWithoutRetentionProducesBlockingDecision() {
        val intent = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Cleanup old docker images.")).normalizedIntent
        val report = analyzer.analyze(intent)
        assertFalse(report.validForLowering)
        assertTrue(report.missingDecisions.any { it.field == "safety.cleanup.retention" && it.blocksLowering })
    }

    @Test
    fun backupWithoutTimezoneProducesRecommendedDecision() {
        val intent = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Back up PostgreSQL database every night, keep backups for 14 days and notify the team on failure.")).normalizedIntent
        val report = analyzer.analyze(intent)
        assertTrue(report.missingDecisions.any { it.field == "schedule.timezone" && it.severity == "recommended" && !it.blocksLowering })
    }

    @Test
    fun databaseMigrationWithoutBackupProducesBlockingDecision() {
        val intent = ScenarioPackIntentNormalizer().normalize(AiIntentRequest("Run database migration for orders database to version 2026.06, require approval and verify schema after migration.")).normalizedIntent
        val report = analyzer.analyze(intent)
        assertFalse(report.validForLowering)
        assertTrue(report.missingDecisions.any { it.field == "safety.backup" && it.blocksLowering })
    }

    @Test
    fun productionDeployWithoutApprovalProducesBlockingSafetyGate() {
        val intent = IntentYamlLoader.loadText("""
            kind: FlowIntentDocument
            name: prod-deploy-without-approval
            workflows:
              - name: cd
                kind: DEPLOY
                steps:
                  - id: deploy
                    capability: DEPLOY
                    params:
                      environment: prod
                      namespace: prod
                      image: billing-api:1.0
        """.trimIndent())
        val report = analyzer.analyze(intent)
        assertFalse(report.validForLowering)
        assertTrue(report.safetyGates.any { it.policy == "forbidProductionWithoutApproval" && it.blocksLowering })
        assertTrue(report.missingDecisions.any { it.field == "safety.approval" && it.blocksLowering })
    }
}
