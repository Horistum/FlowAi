package org.flowlang.tests

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.flowlang.standard.StandardCapabilityContracts
import org.flowlang.intent.StandardCapability
import org.flowlang.generators.manifest.TargetExpressionTranslator
import org.flowlang.generators.manifest.TargetExpressionTranslationException
import org.flowlang.generators.manifest.TargetInput
import org.flowlang.tests.TargetExpressionTestEvidence

class FlowRc5RegressionTests {
    @Test
    fun standardCapabilityContractsCoverDeployAndBackup() {
        assertEquals(StandardCapability.DEPLOY, StandardCapabilityContracts.requireContract(StandardCapability.DEPLOY).capability)
        assertEquals(StandardCapability.BACKUP, StandardCapabilityContracts.requireContract(StandardCapability.BACKUP).capability)
    }

    @Test
    fun githubConditionTranslatorUsesWorkflowInputs() {
        val expr = TargetExpressionTranslator.github("environment == 'prod'", listOf(TargetInput("environment")), TargetExpressionTestEvidence.declaration("github-actions"))
        assertTrue(expr.contains("inputs.environment"))
        assertTrue(expr.contains("'prod'"))
    }

    @Test
    fun tektonConditionTranslatorMapsSimpleInputEquality() {
        val yaml = TargetExpressionTranslator.tektonWhen("environment == 'prod'", listOf(TargetInput("environment")), TargetExpressionTestEvidence.declaration("tekton"))
        assertNotNull(yaml)
        assertTrue(yaml.contains("$(params.environment)"))
        assertTrue(yaml.contains("operator: in"))
        assertTrue(yaml.contains("prod"))
    }

    @Test
    fun githubConditionTranslatorRejectsUnsupportedRegexInsteadOfFailOpen() {
        assertFailsWith<TargetExpressionTranslationException> {
            TargetExpressionTranslator.github("environment matches 'prod.*'", listOf(TargetInput("environment")), TargetExpressionTestEvidence.declaration("github-actions"))
        }
    }
}
