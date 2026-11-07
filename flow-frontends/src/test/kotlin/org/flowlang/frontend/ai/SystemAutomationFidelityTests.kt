package org.flowlang.frontend.ai

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import org.flowlang.cli.Json
import org.flowlang.compiler.CompilationResult
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.modules.ModuleRegistry
import org.flowlang.safety.StandardEnvironmentSafetyPolicyNotes

class SystemAutomationFidelityTests {
    private val compiler = FrontendCompilerComposition.compiler(ModuleRegistry.fromDirectory(File("modules")),
        StandardEnvironmentSafetyPolicyNotes.policy(File(".")))
    private val cases = listOf("infrastructure-foundation", "data-recovery-rehearsal", "environment-retirement")
    private fun tree(name: String = cases.first(), suffix: String = "proposal") =
        Json.mapper.readTree(File("examples/system-automation/$name.$suffix.json")) as ObjectNode
    private fun steps(p: ObjectNode) = p.path("response").path("normalizedIntent").path("workflows")[0].path("steps") as ArrayNode
    private fun params(p: ObjectNode, id: String) = steps(p).single { it.path("id").asText() == id }.path("params") as ObjectNode
    private fun requirements(name: String = cases.first(), only: String? = null) = tree(name, "requirements").also { r ->
        if (only != null) {
            val check = r.path("requirements").single { it.path("id").asText() == only }.deepCopy<com.fasterxml.jackson.databind.JsonNode>()
            r.putArray("requirements").add(check)
        }
    }
    private fun evaluate(p: ObjectNode, r: ObjectNode): RequestFidelityReview {
        val root = createTempDirectory("system-automation-").toFile()
        try {
            val input = File(root, "proposal.json").apply { writeText(p.toString()) }
            val packet = File(root, "requirements.json").apply { writeText(r.toString()) }
            val compiled = ReviewedAiProposalFrontend(compiler).compile(ExternalAiProposalReader.read(input).proposal)
            val unit = assertIs<CompilationResult.Accepted>(compiled, compiled.toString()).unit
            return RequestFidelityEvaluator.evaluate(RequestRequirementsReader.read(packet), unit)
        } finally { root.deleteRecursively() }
    }

    @Test fun threeSystemLifecyclesPassWithoutApplicationOrRepositoryDefaults() {
        for (name in cases) {
            val p = tree(name)
            assertFalse(p.path("request").path("context").has("repositoryUrl"))
            assertFalse(p.path("request").path("context").has("defaultApplication"))
            val review = evaluate(p, requirements(name))
            assertEquals(RequestFidelityState.REQUIREMENTS_SATISFIED, review.state, "$name: ${review.requirements}")
            assertEquals("unassessed", review.naturalLanguageCompleteness)
        }
    }

    @Test fun resourceSubstitutionCannotPassUsingAnotherProvisionOperation() {
        val p = tree()
        params(p, "network").put("resource", "shared-network")
        val review = evaluate(p, requirements())
        assertEquals(RequestFidelityState.VIOLATED, review.state)
        assertEquals(FidelityRequirementState.VIOLATED, review.requirements.single { it.id == "protect-shared" }.state)
    }

    @Test fun correctOrderingCannotHideValueFromTheWrongResource() {
        val p = tree()
        (params(p, "compute").path("stack") as ObjectNode).put("network", "ref:identity_state")
        val review = evaluate(p, requirements())
        assertEquals(RequestFidelityState.VIOLATED, review.state)
        assertEquals(FidelityRequirementState.VIOLATED, review.requirements.single { it.id == "compute-network" }.state)
    }

    @Test fun literalPlaceholderAndMissingNestedValueDoNotCountAsContinuity() {
        for (replacement in listOf("network-placeholder", null)) {
            val p = tree()
            val stack = params(p, "compute").path("stack") as ObjectNode
            if (replacement == null) stack.remove("network") else stack.put("network", replacement)
            assertEquals(RequestFidelityState.VIOLATED, evaluate(p, requirements(only = "compute-network")).state)
        }
    }

    @Test fun renamingStepsAndOutputsDoesNotChangeResourceBasedRequirements() {
        val renamed = Json.mapper.readTree(tree().toString().replace("network_state", "discovered_network")
            .replace("\"network\"", "\"renamed-step\"")) as ObjectNode
        // Keep the semantic configuration field name; only author-local identifiers changed.
        val stack = params(renamed, "compute").path("stack") as ObjectNode
        stack.set<com.fasterxml.jackson.databind.JsonNode>("network", stack.remove("renamed-step"))
        assertEquals(RequestFidelityState.REQUIREMENTS_SATISFIED, evaluate(renamed, requirements()).state)
    }

    @Test fun dynamicSelectorCannotIgnoreAnOperationThatMightMatch() {
        val p = tree()
        val intent = p.path("response").path("normalizedIntent") as ObjectNode
        intent.putArray("inputs").addObject().put("name", "resource_name").put("type", "text").put("required", true)
        params(p, "identity").put("resource", "ref:resource_name")
        val review = evaluate(p, requirements(only = "compute-network"))
        assertEquals(RequestFidelityState.REVIEW_REQUIRED, review.state)
        assertEquals("FIDELITY_DYNAMIC_SELECTION", review.requirements.single().code)
    }

    @Test fun knownMismatchExcludesDynamicNodeRegardlessOfFilterOrder() {
        val p = tree()
        (p.path("response").path("normalizedIntent") as ObjectNode).putArray("inputs")
            .addObject().put("name", "resource_name").put("type", "text").put("required", true)
        params(p, "identity").put("resource", "ref:resource_name").put("mode", "import")
        for (reverse in listOf(false, true)) {
            val r = requirements(only = "dev-network")
            val selector = r.path("requirements")[0].path("check").path("selector") as ObjectNode
            val filters = selector.putObject("parameters")
            if (reverse) filters.put("mode", "create").put("resource", "dev-network")
            else filters.put("resource", "dev-network").put("mode", "create")
            assertEquals(RequestFidelityState.REQUIREMENTS_SATISFIED, evaluate(p, r).state)
        }
    }

    @Test fun duplicateResourceProducersRemainAmbiguous() {
        val p = tree()
        steps(p).addObject().put("id", "another-network").put("capability", "PROVISION")
            .putObject("params").put("resource", "dev-network")
        val review = evaluate(p, requirements(only = "compute-network"))
        assertEquals(RequestFidelityState.REVIEW_REQUIRED, review.state)
        assertEquals("FIDELITY_AMBIGUOUS_PRODUCER", review.requirements.single().code)
    }

    @Test fun retirementCannotDeleteThePreservedSharedNetwork() {
        val p = tree("environment-retirement")
        params(p, "remove-network").put("target", "shared-network")
        val review = evaluate(p, requirements("environment-retirement"))
        assertEquals(FidelityRequirementState.VIOLATED, review.requirements.single { it.id == "preserve-network" }.state)
    }

    @Test fun recoveryMustConsumeTheBackupNotAnAvailableRestorationResult() {
        val p = tree("data-recovery-rehearsal")
        params(p, "migrate").put("backup", "ref:restored_state")
        val review = evaluate(p, requirements("data-recovery-rehearsal"))
        assertEquals(FidelityRequirementState.VIOLATED, review.requirements.single { it.id == "migration-backup" }.state)
    }

    @Test fun versionOneCannotSilentlyAdoptVersionTwoSemantics() {
        for (id in listOf("dev-network", "compute-network", "protect-shared")) {
            val r = requirements(only = id).put("formatVersion", 1)
            assertFailsWith<RequestRequirementsException> { evaluate(tree(), r) }
        }
        val zero = requirements(only = "protect-shared").put("formatVersion", 1)
        (zero.path("requirements")[0].path("check").path("selector") as ObjectNode).remove("parameters")
        assertFailsWith<RequestRequirementsException> { evaluate(tree(), zero) }
        val emptyFilters = requirements(only = "dev-network").put("formatVersion", 1)
        (emptyFilters.path("requirements")[0].path("check").path("selector") as ObjectNode).putObject("parameters")
        assertFailsWith<RequestRequirementsException> { evaluate(tree(), emptyFilters) }
    }

    @Test fun selectorsAndValuePathsHaveStrictShapeAndBounds() {
        val initial = requirements(only = "compute-network")
        val invalid = listOf<(ObjectNode) -> Unit>(
            { it.put("formatVersion", 4) },
            { ((it.path("requirements")[0].path("check") as ObjectNode).putArray("path")).apply { repeat(9) { add("field") } } },
            { (it.path("requirements")[0].path("check").path("producer") as ObjectNode).putNull("parameters") },
            { (it.path("requirements")[0].path("check").path("producer").path("parameters") as ObjectNode).apply { repeat(17) { put("field$it", "x") } } }
        )
        for (mutation in invalid) {
            val r = initial.deepCopy().also(mutation)
            assertFailsWith<RequestRequirementsException> { evaluate(tree(), r) }
        }
    }
}
