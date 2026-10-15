package org.flowlang.targets.builtin

import kotlin.test.*
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.SupportLevel
import org.flowlang.generators.manifest.*

class JenkinsConditionValueScopeTests {
    private fun condition(source: String, id: String = "guard") = TargetStep(id = id, type = "condition",
        params = mapOf("condition" to source), materialization = TargetMaterialization.native("condition", "fixture"))
    private fun manifest(steps: List<TargetStep>, inputs: List<TargetInput> = emptyList()) = TargetManifest(
        target = "jenkins", flowName = "value-scope", compatibility = CompatibilityReport("jenkins", SupportLevel.SUPPORTED),
        jobs = listOf(TargetJob(id = "job", steps = steps)), inputs = inputs)
    private fun missing(source: String, roots: Set<String> = emptySet()) =
        JenkinsConditionValueScope.reconcile(listOf(condition(source)), roots).single()

    @Test fun nestedReferencesCannotHideUnboundValues() {
        for (expression in listOf("result.ok == true", "result?.ok exists", "allowed[result.ok] exists",
            "result in [allowed]", "not (result == null)", "allowed == true and result != null")) {
            val step = missing(expression, setOf("allowed"))
            assertEquals(TargetMaterializationStatus.ADAPTER_REQUIRED, step.materialization.status, expression)
            assertContains(step.materialization.reason, "result")
            assertNull(step.rendererPayload)
            assertEquals(expression, step.params["condition"])
            assertEquals("condition.value-binding", step.mappingNotes.single().feature)
        }
        for (name in listOf("env", "params", "currentBuild", "flowError"))
            assertFails { JenkinsConditionValueScope.requireBound(manifest(listOf(condition("$name != null")))) }
    }

    @Test fun literalSecretInputAndNamedPatternValuesNeedNoInventedBinding() {
        for (expression in listOf("true", "secret(\"token\") != null", "name matches email",
            "name in [\"result.ok\"]", "\"result.ok\" == \"result.ok\"")) {
            val original = condition(expression)
            assertEquals(listOf(original), JenkinsConditionValueScope.reconcile(listOf(original), setOf("name")))
        }
    }

    @Test fun loopBindingsAreLexicalAndCannotLeakToSiblingsOrJobs() {
        val guard = condition("item == \"selected\"")
        val loop = TargetStep(id = "loop", type = "loop", params = mapOf("item" to "item"),
            children = listOf(TargetStep(id = "wrapper", type = "try-body", children = listOf(guard))))
        assertEquals(listOf(loop), JenkinsConditionValueScope.reconcile(listOf(loop), emptySet()))
        JenkinsConditionValueScope.requireBound(manifest(listOf(loop)))
        assertEquals(TargetMaterializationStatus.ADAPTER_REQUIRED,
            JenkinsConditionValueScope.reconcile(listOf(loop, guard), emptySet()).last().materialization.status)
        assertFails { JenkinsConditionValueScope.requireBound(manifest(listOf(loop)).copy(jobs = listOf(
            TargetJob(id = "first", steps = listOf(loop)), TargetJob(id = "second", steps = listOf(guard))))) }
    }

    @Test fun workflowErrorBindingExistsOnlyInsideItsHandler() {
        val guard = condition("failure.message != null")
        val body = TargetStep(id = "body", type = "try-body", children = listOf(guard))
        val handler = TargetStep(id = "handler", type = "error-handler", children = listOf(guard))
        val boundary = TargetStep(id = "failure-policy", type = "try", children = listOf(body, handler),
            metadata = mapOf("workflowFailurePolicy" to "true", "workflowFailureErrorBinding" to "failure"))
        val result = JenkinsConditionValueScope.reconcile(listOf(boundary, guard), emptySet())
        assertEquals(TargetMaterializationStatus.ADAPTER_REQUIRED, result[0].children[0].children.single().materialization.status)
        assertEquals(handler, result[0].children[1])
        assertEquals(TargetMaterializationStatus.ADAPTER_REQUIRED, result[1].materialization.status)
        assertFails { JenkinsConditionValueScope.requireBound(manifest(listOf(boundary.copy(
            metadata = emptyMap(), children = listOf(handler))))) }
    }

    @Test fun missingOrMalformedConditionsCannotBeRelabeledAsBound() {
        for (step in listOf(condition("("), condition("true").copy(params = emptyMap()))) {
            assertEquals(TargetMaterializationStatus.ADAPTER_REQUIRED,
                JenkinsConditionValueScope.reconcile(listOf(step), emptySet()).single().materialization.status)
            assertFails { JenkinsConditionValueScope.requireBound(manifest(listOf(step))) }
        }
    }
}
