package org.flowlang.intent

import kotlin.test.*

class SemanticIdentityIndexTests {
    private fun step(id: String, outputs: List<String> = emptyList()) = IntentStep(id = id,
        capability = StandardCapability.CUSTOM, produces = outputs, params = mapOf("operation" to IntentString("inspect")))
    private fun intent(vararg steps: IntentStep) = IntentDocument(name = "identity",
        workflows = listOf(IntentWorkflow(name = "main", kind = IntentWorkflowKind.CUSTOM, steps = steps.toList())))

    @Test fun resultSymbolsNeverSelectBetweenDistinctAuthoredSteps() {
        for (steps in listOf(listOf(step("audit-step"), step("audit_step")), listOf(step("audit_step"), step("audit-step")))) {
            val value = intent(*steps.toTypedArray())
            assertTrue(IntentIdentityIndex.issues(value).any { it.code == "INTENT_SYMBOL_COLLISION" })
            assertFailsWith<IllegalArgumentException> { IntentIdentityIndex.capture(value) }
        }
    }
    @Test fun displayLabelsAreNotStepIdentityOrResultBinding() {
        val first = step("stable-id").copy(description = "Visible name")
        val second = first.copy(description = "Different visible name")
        val a = IntentIdentityIndex.capture(intent(first)).step(first.id)
        val b = IntentIdentityIndex.capture(intent(second)).step(second.id)
        assertEquals(a.semanticId, b.semanticId)
        assertEquals("stable_id", a.resultName)
        assertEquals(a.resultName, b.resultName)
        assertNotEquals(a.displayName, b.displayName)
    }
    @Test fun inputsOutputsAndResultSymbolsShareOnlyTheirActualWorkflowScope() {
        val cases = listOf(intent(step("audit-step")).copy(inputs = listOf(IntentInput("audit_step"))),
            intent(step("audit-step"), step("other", listOf("audit_step"))),
            intent(step("work", listOf("work"))))
        cases.forEach { assertTrue(IntentIdentityIndex.issues(it).any { issue -> issue.code == "INTENT_SYMBOL_COLLISION" }) }
        val separate = IntentDocument(name = "separate", workflows = listOf(
            IntentWorkflow("one", IntentWorkflowKind.CUSTOM, listOf(step("audit-step", listOf("output")))),
            IntentWorkflow("two", IntentWorkflowKind.CUSTOM, listOf(step("audit_step", listOf("output"))))))
        assertTrue(IntentIdentityIndex.issues(separate).isEmpty())
    }
    @Test fun duplicateOrBlankDeclarationsCannotReachAnAssociativeCollection() {
        val values = listOf(intent(step("same"), step("same")), intent(step(" ")),
            intent().copy(inputs = listOf(IntentInput("same"), IntentInput("same"))),
            intent().copy(systems = listOf(IntentSystem("same", "standard"), IntentSystem("same", "standard"))))
        values.forEach { assertTrue(IntentIdentityIndex.issues(it).isNotEmpty()) }
    }
    @Test fun anImplicitStandardSystemCannotReplaceAnAuthoredIdentity() {
        assertTrue(IntentIdentityIndex.issues(intent(step("standard"))).any { it.code == "INTENT_SYMBOL_COLLISION" })
        assertTrue(IntentIdentityIndex.issues(intent(step("work")).copy(systems = listOf(IntentSystem("standard", "remote"))))
            .any { it.code == "INTENT_SYSTEM_COLLISION" })
        assertTrue(IntentIdentityIndex.issues(intent(step("work")).copy(systems = listOf(IntentSystem("standard", "standard")))).isEmpty())
    }
}
