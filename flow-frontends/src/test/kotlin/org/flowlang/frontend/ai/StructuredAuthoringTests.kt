package org.flowlang.frontend.ai

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import org.flowlang.cli.Json
import org.flowlang.compiler.*
import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.frontend.intent.IntentYamlFrontend
import org.flowlang.frontend.source.FlowSourceFrontend
import org.flowlang.intent.*
import org.flowlang.lowering.IntentLoweringAuthority
import org.flowlang.modules.*
import org.flowlang.planner.ConditionNode
import org.flowlang.planner.ControlNode

class StructuredAuthoringTests {
    private val corpus = File("conformance/structured-authoring")
    private val compiler = FrontendCompilerComposition.compiler(ModuleRegistry.fromDirectory(File("modules")))
    private fun tree(id: String, suffix: String = "proposal") = Json.mapper.readTree(File(corpus, "$id.$suffix.json")) as ObjectNode
    private fun body(p: ObjectNode) = p.path("response").path("normalizedIntent").path("workflows")[0].path("body") as ArrayNode
    private fun intent(p: ObjectNode) = p.path("response").path("normalizedIntent") as ObjectNode
    private fun obj(value: com.fasterxml.jackson.databind.JsonNode) = value as ObjectNode
    private fun workspace(action: (File) -> Unit) {
        val root = createTempDirectory("structured-authoring-").toFile()
        try { action(root) } finally { root.deleteRecursively() }
    }
    private fun compile(root: File, proposal: ObjectNode): CompilationResult {
        val file = File(root, "proposal.json").apply { writeText(proposal.toString()) }
        return ReviewedAiProposalFrontend(compiler).compile(ExternalAiProposalReader.read(file).proposal)
    }
    private fun review(root: File, id: String, proposal: ObjectNode = tree(id), requirements: ObjectNode = tree(id, "requirements")): RequestFidelityReview {
        val unit = assertIs<CompilationResult.Accepted>(compile(root, proposal)).unit
        val file = File(root, "requirements.json").apply { writeText(requirements.toString()) }
        return RequestFidelityEvaluator.evaluate(RequestRequirementsReader.read(file), unit)
    }
    @Test fun crossDomainPortfolioCompilesAndMatchesIndependentRequirements() = workspace { root ->
        for (id in listOf("infrastructure-choice", "maintenance-window", "recovery-selection", "generic-composition")) {
            val proposal = tree(id)
            val unit = assertIs<CompilationResult.Accepted>(compile(root, proposal), id).unit
            assertEquals(RequestFidelityState.REQUIREMENTS_SATISFIED, review(root, id).state, id)
            val fromIntent = IntentYamlFrontend(compiler).compile(File(corpus, "$id.intent.json")).requireAccepted()
            assertEquals(fromIntent.graphDigest, unit.graphDigest, id)
            assertNotNull(unit.executionPlan.loweringReport)
            assertTrue(unit.executionPlan.loweringReport!!.evidence.any { it.valueKind == "structured-body" })
        }
    }
    @Test fun missingWorkNarrowedGuardWrongPolarityAndUnguardedDuplicateCannotPass() = workspace { root ->
        val id = "maintenance-window"
        val missing = tree(id).also { (body(it)[0].path("then") as ArrayNode).removeAll() }
        assertEquals(FidelityRequirementState.SATISFIED, review(root, id, missing).requirements[0].state)
        assertEquals(FidelityRequirementState.VIOLATED, review(root, id, missing).requirements[1].state)
        val narrower = tree(id).also {
            val arm = body(it)[0].path("then") as ArrayNode
            val work = arm[0].deepCopy<ObjectNode>(); arm.removeAll()
            arm.addObject().put("kind", "branch").put("id", "extraGuard").put("when", "ref:extra").putArray("then").add(work)
        }
        val duplicate = tree(id).also {
            body(it).add(body(it)[0].path("then")[0].deepCopy<ObjectNode>().put("id", "outside"))
        }
        val inverted = tree(id).also { obj(body(it)[0]).put("when", "expr:not window") }
        val constant = tree(id).also { obj(body(it)[0]).put("when", true) }
        val swapped = tree(id).also {
            val branch = obj(body(it)[0]); val yes = branch["then"]; val no = branch["otherwise"]
            branch.set<ArrayNode>("then", no); branch.set<ArrayNode>("otherwise", yes)
        }
        for (mutation in listOf(narrower, duplicate, inverted, constant, swapped))
            assertEquals(RequestFidelityState.VIOLATED, review(root, id, mutation).state)
    }
    @Test fun nestedJoinsRejectLiteralAndWrongOriginIncludingExtraLiteralPaths() = workspace { root ->
        val id = "recovery-selection"
        val wrong = tree(id).also { obj(body(it)[0].path("then")[0]).put("value", "ref:coldHandle") }
        val literal = tree(id).also { obj(body(it)[0].path("then")[0]).put("value", "archiveHandle") }
        for (mutation in listOf(wrong, literal)) assertEquals(RequestFidelityState.VIOLATED, review(root, id, mutation).state)
        val noJoin = tree(id).also { obj(body(it).last().path("params")).put("target", "ref:archiveHandle") }
        assertEquals(RequestFidelityState.REVIEW_REQUIRED, review(root, id, noJoin).state)
    }
    @Test fun booleanAndIdentityAdmissionNeverCoercesOrRepairs() = workspace { root ->
        for (condition in listOf(Json.mapper.nullNode(), Json.mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>("false"), Json.mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>("ref:unknown"))) {
            val proposal = tree("maintenance-window"); obj(body(proposal)[0]).set<com.fasterxml.jackson.databind.JsonNode>("when", condition)
            assertIs<CompilationResult.Rejected>(compile(root, proposal))
        }
        val optional = tree("maintenance-window").also { obj(intent(it).path("inputs")[0]).put("required", false) }
        assertIs<CompilationResult.Rejected>(compile(root, optional))
        val duplicated = tree("maintenance-window").also { obj(body(it)[0].path("otherwise")[0]).put("id", "maintenance") }
        assertIs<CompilationResult.Rejected>(compile(root, duplicated))
        val collision = tree("infrastructure-choice").also { obj(body(it)[0].path("then")[0]).put("id", "existingHandle") }
        assertIs<CompilationResult.Rejected>(compile(root, collision))
    }
    @Test fun siblingForwardAndMaybeValueDependenciesAreRejected() = workspace { root ->
        val sibling = tree("maintenance-window").also { obj(body(it)[0].path("otherwise")[0]).putArray("requires").add("maintenance") }
        val forward = tree("generic-composition").also { obj(body(it)[0].path("then")[0]).putArray("requires").add("done") }
        val maybe = tree("infrastructure-choice").also { obj(body(it).last().path("params")).put("target", "ref:create") }
        for (mutation in listOf(sibling, forward, maybe)) assertIs<CompilationResult.Rejected>(compile(root, mutation))
    }
    @Test fun actualPlanMutationCannotRetainStructurePreservationEvidence() = workspace { root ->
        val unit = assertIs<CompilationResult.Accepted>(compile(root, tree("maintenance-window"))).unit
        val plan = unit.executionPlan
        val branch = assertIs<ConditionNode>(plan.nodes.single())
        assertFailsWith<IllegalArgumentException> { IntentLoweringAuthority.report(plan.copy(nodes = listOf(branch.copy(condition = "false")))) }
        assertFailsWith<IllegalArgumentException> { IntentLoweringAuthority.report(plan.copy(nodes = listOf(branch.copy(then = branch.otherwise, otherwise = branch.then)))) }
    }
    @Test fun versionsDoNotGuessFromPayloadAndLegacyDefaultsRemainStable() = workspace { root ->
        val mixed = tree("maintenance-window").also { obj(intent(it).path("workflows")[0]).putArray("steps") }
        assertFailsWith<ProposalInputException> { compile(root, mixed) }
        val omitted = tree("maintenance-window").also { intent(it).remove("intentVersion") }
        assertFailsWith<ProposalInputException> { compile(root, omitted) }
        assertEquals("2.0", IntentYamlLoader.normalize(mapOf("name" to "legacy"), "legacy").intentVersion)
        for (version in listOf(1, 2)) {
            val req = tree("maintenance-window", "requirements").put("formatVersion", version)
            val file = File(root, "requirements.json").apply { writeText(req.toString()) }
            assertFailsWith<RequestRequirementsException> { RequestRequirementsReader.read(file) }
        }
    }
    @Test fun sourceAndStructuredAuthoringUseTheSameCanonicalJoin() = workspace { root ->
        val p = tree("infrastructure-choice")
        val i = intent(p); i.put("name", "same")
        val b = body(p); b.removeAll()
        b.add(Json.mapper.readTree("""{"kind":"branch","id":"choice","when":"ref:reuse","then":[{"kind":"value","id":"left","value":"ref:existingHandle"}],"otherwise":[{"kind":"value","id":"right","value":"fallback"}]}"""))
        b.add(Json.mapper.readTree("""{"kind":"merge","id":"joined","sources":["ref:left","ref:right"]}"""))
        val structured = assertIs<CompilationResult.Accepted>(compile(root, p)).unit
        val source = File(root, "same.flow").apply { writeText("""
            flow "same" {
              input { reuse: boolean required existingHandle: text required }
              steps {
                if reuse { set left = existingHandle } else { set right = "fallback" }
                set joined = merge(left, right)
              }
            }
        """.trimIndent()) }
        val flow = FlowSourceFrontend(compiler).compile(source).requireAccepted()
        assertEquals(flow.graphDigest, structured.graphDigest)
    }
    @Test fun localReferenceAliasesRetainCanonicalEdgesAndJoinedOrigins() = workspace { root ->
        val id = "infrastructure-choice"
        val candidate = tree(id)
        val arm = body(candidate)[0].path("otherwise") as ArrayNode
        arm.addObject().put("kind", "value").put("id", "createdAlias").put("value", "ref:create")
        (body(candidate)[1].path("sources") as ArrayNode).set(1, Json.mapper.valueToTree("ref:createdAlias"))
        val unit = assertIs<CompilationResult.Accepted>(compile(root, candidate)).unit
        assertTrue(unit.graph.dependencyEdges.any { it.kind == CanonicalDependencyKind.VALUE && it.channel == "create" })
        assertEquals(RequestFidelityState.REQUIREMENTS_SATISFIED, review(root, id, candidate).state)
    }
    @Test fun consistentInternalRenamingAndDescriptionChangesLeaveIndependentDecisionsUnchanged() = workspace { root ->
        val id = "infrastructure-choice"
        val candidate = tree(id)
        val branch = obj(body(candidate)[0]); branch.put("id", "renamedChoice")
        obj(branch.path("then")[0]).put("id", "renamedExisting")
        obj(branch.path("otherwise")[0]).put("id", "renamedCreate").put("description", "Different display wording")
        val merge = obj(body(candidate)[1]); merge.put("id", "renamedSelected")
        merge.putArray("sources").add("ref:renamedExisting").add("ref:renamedCreate")
        obj(body(candidate)[2].path("params")).put("target", "ref:renamedSelected")
        assertEquals(RequestFidelityState.REQUIREMENTS_SATISFIED, review(root, id, candidate).state)
    }
    @Test fun approvalInAnotherAlternativeCannotCoverProtectedWork() = workspace { root ->
        val candidate = tree("maintenance-window")
        val branch = obj(body(candidate)[0])
        val approval = Json.mapper.readTree("""{"kind":"operation","id":"gate","capability":"APPROVE"}""")
        val deletion = Json.mapper.readTree("""{"kind":"operation","id":"remove","capability":"DEPROVISION","requires":["gate"],"params":{"target":"retired-resource"}}""")
        branch.putArray("then").add(approval).add(deletion)
        branch.putArray("otherwise")
        assertIs<CompilationResult.Accepted>(compile(root, candidate))
        branch.putArray("then").add(approval)
        branch.putArray("otherwise").add(deletion)
        assertIs<CompilationResult.Rejected>(compile(root, candidate))
        // Also catches a downstream operation that could be reached without its conditional approval.
        branch.putArray("otherwise")
        body(candidate).add(deletion)
        assertIs<CompilationResult.Rejected>(compile(root, candidate))
    }
    @Test fun invalidMergeCoverageDuplicateSourcesAndTypesAreRejected() = workspace { root ->
        val base = tree("recovery-selection")
        val missing = base.deepCopy().also { obj(body(it)[0]).putArray("then") }
        val duplicate = base.deepCopy().also { obj(body(it)[1]).putArray("sources").add("ref:archiveValue").add("ref:archiveValue") }
        val incompatible = base.deepCopy().also { obj(body(it)[0].path("then")[0]).put("value", 42) }
        for (p in listOf(missing, duplicate, incompatible)) assertIs<CompilationResult.Rejected>(compile(root, p))
    }
    @Test fun typedRecursiveInputsAndCumulativeStatementBudgetsAreBounded() {
        var body: List<IntentStatement> = emptyList()
        repeat(66) { body = listOf(IntentBranch("branch$it", IntentBoolean(true), body)) }
        assertFailsWith<IllegalArgumentException> { IntentStructure.visits(IntentWorkflow("main", IntentWorkflowKind.CUSTOM, body = body)) }
        val intent = IntentDocument(intentVersion = "3.0", name = "budget", workflows = listOf(
            IntentWorkflow("a", IntentWorkflowKind.CUSTOM, body = (1..1100).map { IntentLocalValue("a$it", IntentBoolean(true)) }),
            IntentWorkflow("b", IntentWorkflowKind.CUSTOM, body = (1..1100).map { IntentLocalValue("b$it", IntentBoolean(true)) })))
        assertFailsWith<IllegalArgumentException> { IntentStructure.issues(intent) }
    }
    @Test fun conjunctionAndNegationUseTypedBooleanOperands() = workspace { root ->
        val candidate = tree("maintenance-window")
        obj(body(candidate)[0]).put("when", "expr:window and not extra")
        assertIs<CompilationResult.Accepted>(compile(root, candidate))
        obj(intent(candidate).path("inputs")[1]).put("type", "text")
        assertIs<CompilationResult.Rejected>(compile(root, candidate))
    }
    @Test fun reviewedStructuredSnapshotIsDetachedAndIntegrityChecked() = workspace { root ->
        val file = File(root, "proposal.json").apply { writeText(tree("maintenance-window").toString()) }
        val source = ExternalAiProposalReader.read(file).proposal
        val mutable = source.response.normalizedIntent.workflows.single().body!!.toMutableList()
        val proposal = source.copy(response = source.response.copy(normalizedIntent = source.response.normalizedIntent.copy(
            workflows = listOf(source.response.normalizedIntent.workflows.single().copy(body = mutable)))))
        val unit = ReviewedAiProposalFrontend(compiler).compile(proposal).requireAccepted()
        mutable.clear()
        val captured = RequestRequirementsReader.read(File(corpus, "maintenance-window.requirements.json"))
        assertEquals(RequestFidelityState.REQUIREMENTS_SATISFIED, RequestFidelityEvaluator.evaluate(captured, unit).state)
        val evidenceBody = unit.requireReviewedAiProposalEvidence().response.normalizedIntent.workflows.single().body!!
        (evidenceBody as MutableList<IntentStatement>).clear()
        assertFailsWith<IllegalArgumentException> { RequestFidelityEvaluator.evaluate(captured, unit) }
    }

    @Test fun scopedParametersEffectsAndOrderingInspectEachRelevantPath() = workspace { root ->
        val id = "maintenance-window"
        val p = tree(id)
        (body(p)[0].path("then") as ArrayNode).add(Json.mapper.readTree("""{"kind":"operation","id":"checkAfter","capability":"VERIFY","requires":["maintenance"],"params":{"resource":"service"}}"""))
        val req = tree(id, "requirements")
        val checks = req.putArray("requirements")
        val nested = listOf(
            """{"kind":"literal-parameter","selector":{"capability":"VERIFY"},"parameter":"resource","expected":"service"}""",
            """{"kind":"require-effect","selector":{"capability":"RUNBOOK"},"domain":"INFRASTRUCTURE_STATE","operation":"EXECUTE","resource":"operations.runbook"}""",
            """{"kind":"before","before":{"capability":"RUNBOOK"},"after":{"capability":"VERIFY"}}""")
        nested.forEachIndexed { index, value ->
            val item = checks.addObject().put("id", "check-$index").put("sourceQuote", p.path("request").path("userText").asText())
            val check = item.putObject("check").put("kind", "on-path")
            check.putArray("scope").addObject().put("predicate", "window")
            check.set<com.fasterxml.jackson.databind.JsonNode>("check", Json.mapper.readTree(value))
        }
        assertEquals(RequestFidelityState.REQUIREMENTS_SATISFIED, review(root, id, p, req).state)
        obj(body(p)[0].path("then")[1]).putArray("requires")
        assertEquals(FidelityRequirementState.VIOLATED, review(root, id, p, req).requirements[2].state)
        obj(body(p)[0].path("then")[1].path("params")).put("resource", "another-service")
        assertEquals(FidelityRequirementState.VIOLATED, review(root, id, p, req).requirements[0].state)
    }
    @Test fun operationPredicateRequiresAnActualRequiredBooleanOutputContract() = workspace { root ->
        fun registry(required: Boolean, type: SchemaType) = ModuleRegistry(ModuleRegistry.loadCanonical().associateBy { it.name } +
            ("probe" to FlowModule("probe", "1.0", systemTypes = mapOf("probe" to SystemTypeContract("probe")), actions = mapOf(
                "inspect" to ModuleActionContract(name = "inspect", targetTypes = setOf("probe"), implementedCapabilities = setOf("VERIFY"),
                    output = mapOf("ready" to SchemaField(type, required = required)))))))
        val p = tree("maintenance-window")
        intent(p).putArray("systems").addObject().put("name", "probe").put("type", "probe")
        val original = body(p)[0].deepCopy<ObjectNode>().put("when", "ref:observed.ready")
        body(p).removeAll().add(Json.mapper.readTree("""{"kind":"operation","id":"observed","capability":"VERIFY","uses":"probe.inspect","params":{"system":"probe"}}""")).add(original)
        val file = File(root, "proposal.json").apply { writeText(p.toString()) }
        val proposal = ExternalAiProposalReader.read(file).proposal
        for ((required, type) in listOf(true to SchemaType.BOOLEAN, false to SchemaType.BOOLEAN, true to SchemaType.TEXT)) {
            val compiled = ReviewedAiProposalFrontend(FrontendCompilerComposition.compiler(registry(required, type))).compile(proposal)
            if (required && type == SchemaType.BOOLEAN) {
                val unit = assertIs<CompilationResult.Accepted>(compiled, compiled.toString()).unit
                // Authoring type support does not pretend the independent public-input predicate oracle understands outputs.
                assertEquals(RequestFidelityState.REVIEW_REQUIRED, RequestFidelityEvaluator.evaluate(
                    RequestRequirementsReader.read(File(corpus, "maintenance-window.requirements.json")), unit).state)
            } else assertIs<CompilationResult.Rejected>(compiled)
        }
    }
    @Test fun extraLiteralMergeArmCannotHideBehindOtherValidOrigins() = workspace { root ->
        val id = "recovery-selection"
        val p = tree(id)
        intent(p).withArray("inputs").addObject().put("name", "extra").put("type", "boolean").put("required", true)
        val arm = obj(body(p)[0]).putArray("then")
        arm.add(Json.mapper.readTree("""{"kind":"branch","id":"extraChoice","when":"ref:extra","then":[{"kind":"value","id":"archiveActual","value":"ref:archiveHandle"}],"otherwise":[{"kind":"value","id":"archiveLiteral","value":"archive-placeholder"}]}"""))
        arm.add(Json.mapper.readTree("""{"kind":"merge","id":"archiveValue","sources":["ref:archiveActual","ref:archiveLiteral"]}"""))
        assertEquals(RequestFidelityState.VIOLATED, review(root, id, p).state)
    }
    @Test fun pathExplosionFailsWithoutAPartiallyAuthorizedPlan() = workspace { root ->
        val p = tree("generic-composition"); body(p).removeAll(); val inputs = intent(p).putArray("inputs")
        repeat(13) { n ->
            inputs.addObject().put("name", "flag$n").put("type", "boolean").put("required", true)
            val branch = body(p).addObject().put("kind", "branch").put("id", "choice$n").put("when", "ref:flag$n")
            branch.putArray("then").addObject().put("kind", "value").put("id", "yes$n").put("value", true)
            branch.putArray("otherwise").addObject().put("kind", "value").put("id", "no$n").put("value", false)
        }
        assertIs<CompilationResult.Rejected>(compile(root, p))
    }
    @Test fun yamlAndJsonHaveTheSameStructuredMeaning() = workspace { root ->
        val id = "infrastructure-choice"
        val json = File(corpus, "$id.intent.json")
        val yaml = File(root, "structured.yaml").apply {
            writeText(com.fasterxml.jackson.databind.ObjectMapper(com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
                .writeValueAsString(Json.mapper.readTree(json)))
        }
        assertEquals(IntentYamlFrontend(compiler).compile(json).requireAccepted().graphDigest,
            IntentYamlFrontend(compiler).compile(yaml).requireAccepted().graphDigest)
    }

    @Test fun structuredVersionAndBodyReceiptsCannotBeStrippedOrAddedToLegacyProvenance() = workspace { root ->
        val plan = assertIs<CompilationResult.Accepted>(compile(root, tree("infrastructure-choice"))).unit.executionPlan
        val source = requireNotNull(plan.sourceIntent)
        assertEquals("3.0", source.intentVersion)
        for (modified in listOf(source.copy(intentVersion = null),
            source.copy(fields = source.fields.filterNot { it.identity == "intent/version" }),
            source.copy(fields = source.fields.filterNot { it.valueKind == "structured-body" }))) {
            assertFailsWith<IllegalArgumentException> { IntentLoweringAuthority.report(plan.copy(sourceIntent = modified)) }
        }
        val legacyFile = File("conformance/authoring/backup-specified.proposal.json")
        val legacy = ReviewedAiProposalFrontend(compiler).compile(ExternalAiProposalReader.read(legacyFile).proposal).requireAccepted().executionPlan
        assertNull(legacy.sourceIntent!!.intentVersion)
        assertFailsWith<IllegalArgumentException> { IntentLoweringAuthority.report(legacy.copy(sourceIntent = legacy.sourceIntent!!.copy(intentVersion = "3.0"))) }
    }

}
