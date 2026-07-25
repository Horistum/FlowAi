import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.ast.ActionNode
import org.flowlang.ast.ApproveNode
import org.flowlang.ast.FlowDocument
import org.flowlang.ast.FlowNode
import org.flowlang.ast.ReferenceNode
import org.flowlang.ast.ResultBindingNode
import org.flowlang.ast.SafetyNode
import org.flowlang.ast.StringLiteralNode
import org.flowlang.controls.CanonicalControlRequirementAuthority
import org.flowlang.controls.ControlDecisionStatus
import org.flowlang.controls.ControlEvidenceStatus
import org.flowlang.controls.ControlRequirementKind
import org.flowlang.effects.CanonicalIntentEffectAuthority
import org.flowlang.effects.SemanticEffect
import org.flowlang.generators.manifest.InvalidPlanningEvidenceException
import org.flowlang.generators.manifest.MandatoryMaterializationAuthority
import org.flowlang.generators.manifest.UnresolvedPlanningControlException
import org.flowlang.intent.CanonicalIntentMeaningAuthority
import org.flowlang.intent.IntentCapabilityValidator
import org.flowlang.intent.IntentDocument
import org.flowlang.intent.IntentPolicy
import org.flowlang.intent.IntentPolicyType
import org.flowlang.intent.IntentStep
import org.flowlang.intent.IntentString
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.intent.IntentWorkflow
import org.flowlang.intent.IntentWorkflowKind
import org.flowlang.intent.StandardCapability
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.FlowPlanner
import org.flowlang.planner.TaskNode

class UniversalControlPolicyRequirementsTests {
    private val modules by lazy { ModuleRegistry.fromDirectory(File("modules")) }
    private val targets by lazy { TargetRegistryYamlLoader.loadDirectory(File("targets")) }

    @Test
    fun canonicalControlMeaningIsInventoryIndependent() {
        val intent = migrationIntent(includeBackup = true)
        val alternativeInventory = ModuleRegistry.fromDescriptors(listOf(File("modules/notify.yaml").readText()))

        val canonical = CanonicalIntentMeaningAuthority(modules).resolve(intent).meaning
        val alternative = CanonicalIntentMeaningAuthority(alternativeInventory).resolve(intent).meaning

        assertEquals(canonical.controlRequirements, alternative.controlRequirements)
        assertEquals(canonical, alternative)
    }

    @Test
    fun knownBackupEvidenceAllowsCanonicalRequirement() {
        val assessment = CanonicalControlRequirementAuthority.assess(migrationIntent(includeBackup = true))

        assertEquals(ControlDecisionStatus.ALLOWED, assessment.decision.status)
        assertEquals(ControlRequirementKind.BACKUP, assessment.requirements.single().kind)
        assertEquals(ControlEvidenceStatus.SATISFIED, assessment.evidence.single().status)
    }

    @Test
    fun missingEvidenceIsExplicitlyUnknownAndBlocksValidation() {
        val intent = migrationIntent(includeBackup = false)
        val assessment = CanonicalControlRequirementAuthority.assess(intent)
        val validation = IntentCapabilityValidator(modules).validate(intent)

        assertEquals(ControlDecisionStatus.BLOCKED, assessment.decision.status)
        assertEquals(ControlEvidenceStatus.UNKNOWN, assessment.evidence.single().status)
        assertFalse(validation.valid)
        assertTrue(validation.issues.any { it.code == "SAFETY_REQUIRES_BACKUP" })
    }

    @Test
    fun explicitNegativeEvidenceIsUnsatisfiedRatherThanUnknown() {
        val intent = IntentDocument(
            name = "migration",
            workflows = listOf(IntentWorkflow(
                name = "migration",
                kind = IntentWorkflowKind.CUSTOM,
                steps = listOf(IntentStep(
                    id = "migrate",
                    capability = StandardCapability.DATABASE_MIGRATE,
                    params = mapOf("backup" to org.flowlang.intent.IntentBoolean(false))
                ))
            ))
        )

        val evidence = CanonicalControlRequirementAuthority.assess(intent).evidence.single()
        assertEquals(ControlEvidenceStatus.UNSATISFIED, evidence.status)
    }

    @Test
    fun conditionalApprovalRemainsPendingAtIntentButFailsClosedInExecutionPlan() {
        val intent = dynamicApprovalIntent()
        val validation = IntentCapabilityValidator(modules).validate(intent)
        val plan = FlowPlanner(modules).plan(IntentToAstPlanner(modules).plan(intent))

        assertTrue(validation.valid, validation.issues.toString())
        assertEquals(ControlDecisionStatus.PENDING, validation.controlAssessment.decision.status)
        assertTrue(validation.controlAssessment.evidence.any { it.status == ControlEvidenceStatus.DYNAMIC })

        assertEquals(ControlDecisionStatus.BLOCKED, plan.controlDecision.status)
        assertTrue(plan.controlEvidence.any {
            it.status == ControlEvidenceStatus.UNKNOWN &&
                it.detail.orEmpty().contains("no provider enforcement evidence")
        })
        assertTrue("approval.manual" in plan.requiredCapabilities)
        assertTrue("condition.evaluate" in plan.requiredCapabilities)
        assertFailsWith<UnresolvedPlanningControlException> {
            MandatoryMaterializationAuthority(targets, modules).authorize(testMaterializationRequest(plan, "jenkins", targets))
        }
        assertTrue(
            MandatoryMaterializationAuthority(targets, modules)
                .authorizeDiagnosticEvidence(testDiagnosticMaterializationRequest(plan, "jenkins", targets))
                .compatibility.hasErrors
        )
    }

    @Test
    fun customPolicyWithConditionIsPendingAtIntentButUnknownAtExecutionPlanning() {
        val dynamic = customPolicyIntent("risk.score <= 3")
        val unknown = customPolicyIntent("")

        val dynamicValidation = IntentCapabilityValidator(modules).validate(dynamic)
        val unknownValidation = IntentCapabilityValidator(modules).validate(unknown)
        val dynamicPlan = FlowPlanner(modules).plan(IntentToAstPlanner(modules).plan(dynamic))

        assertTrue(dynamicValidation.valid)
        assertEquals(ControlDecisionStatus.PENDING, dynamicValidation.controlAssessment.decision.status)
        assertTrue(dynamicValidation.issues.any { it.code == "CONTROL_EVIDENCE_DYNAMIC" })
        assertEquals(ControlDecisionStatus.BLOCKED, dynamicPlan.controlDecision.status)
        assertFalse(unknownValidation.valid)
        assertTrue(unknownValidation.issues.any { it.code == "SAFETY_POLICY_EVIDENCE_UNKNOWN" })
    }

    @Test
    fun destructiveModuleWithoutControlEvidenceBlocksExecutionButKeepsDiagnosticEvidence() {
        val plan = FlowPlanner(modules).plan(deleteFlow())
        val authority = MandatoryMaterializationAuthority(targets, modules)

        assertEquals(ControlDecisionStatus.BLOCKED, plan.controlDecision.status)
        assertFailsWith<UnresolvedPlanningControlException> { authority.authorize(testMaterializationRequest(plan, "jenkins", targets)) }
        val diagnostic = authority.authorizeDiagnosticEvidence(testDiagnosticMaterializationRequest(plan, "jenkins", targets))
        assertTrue(diagnostic.compatibility.hasErrors)
        assertTrue(diagnostic.compatibility.issues.any { it.feature.startsWith("control.") })
    }

    @Test
    fun unrelatedApprovalDoesNotAuthorizeDestructiveTask() {
        val plan = FlowPlanner(modules).plan(
            deleteFlow(
                approval = ApproveNode(result = ResultBindingNode(name = "approval")),
                dependsOnApproval = false,
                safety = SafetyNode(rule = "requiresApproval")
            )
        )

        assertEquals(ControlDecisionStatus.BLOCKED, plan.controlDecision.status)
        assertTrue(plan.controlEvidence.any {
            it.requirementId.contains("control.approval.task") && it.status == ControlEvidenceStatus.UNKNOWN
        })
    }

    @Test
    fun reachableApprovalAncestorAuthorizesTheTaskControl() {
        val plan = FlowPlanner(modules).plan(
            deleteFlow(
                approval = ApproveNode(result = ResultBindingNode(name = "approval")),
                dependsOnApproval = true,
                safety = SafetyNode(rule = "requiresApproval")
            )
        )

        assertEquals(ControlDecisionStatus.ALLOWED, plan.controlDecision.status)
        assertTrue(plan.controlEvidence.all { it.status == ControlEvidenceStatus.SATISFIED })
    }

    @Test
    fun safetyDeclarationAloneIsRequirementMetadataNotApprovalImplementation() {
        val plan = FlowPlanner(modules).plan(
            deleteFlow(safety = SafetyNode(rule = "requiresApproval"))
        )

        assertEquals(ControlDecisionStatus.BLOCKED, plan.controlDecision.status)
        assertTrue(plan.controlEvidence.any {
            it.requirementId.contains("control.approval.task") &&
                it.status == ControlEvidenceStatus.UNKNOWN &&
                it.detail.orEmpty().contains("does not provide a reachable approval mechanism")
        })
    }

    @Test
    fun materializationRejectsOmittedOrForgedControlAssessment() {
        val effects = CanonicalIntentEffectAuthority.effectsFor(StandardCapability.DATABASE_MIGRATE)
        val task = TaskNode(
            id = "migration",
            module = "standard",
            action = "execute",
            target = "standard",
            semanticCapability = StandardCapability.DATABASE_MIGRATE.name,
            effectModel = effects,
            effects = effects.map(SemanticEffect::resource),
            params = mapOf("operation" to "database-migrate", "capability" to "database-migrate")
        )
        val forged = ExecutionPlan(flowName = "forged", nodes = listOf(task))

        val failure = assertFailsWith<InvalidPlanningEvidenceException> {
            MandatoryMaterializationAuthority(targets, modules).authorizeDiagnosticEvidence(testDiagnosticMaterializationRequest(forged, "jenkins", targets))
        }
        assertTrue(failure.issues.any { it.code == "planning.control.requirement.invalid" })
    }

    @Test
    fun semanticEffectsDoNotInventControlRequirements() {
        val intent = IntentDocument(
            name = "build",
            workflows = listOf(IntentWorkflow(
                name = "build",
                kind = IntentWorkflowKind.BUILD,
                steps = listOf(IntentStep(
                    id = "image",
                    capability = StandardCapability.BUILD_IMAGE,
                    params = mapOf("image" to IntentString("example/app:1"))
                ))
            ))
        )
        val meaning = CanonicalIntentMeaningAuthority(modules).resolve(intent).meaning

        assertTrue(meaning.workflows.single().steps.single().effects.isNotEmpty())
        assertTrue(meaning.controlRequirements.isEmpty())
    }

    private fun dynamicApprovalIntent(): IntentDocument = IntentDocument(
        name = "dynamic-approval",
        workflows = listOf(IntentWorkflow(
            name = "delivery",
            kind = IntentWorkflowKind.DEPLOY,
            steps = listOf(IntentStep(
                id = "approve",
                capability = StandardCapability.APPROVE
            ))
        )),
        policies = listOf(IntentPolicy(
            name = "conditional-approval",
            type = IntentPolicyType.APPROVAL,
            condition = "environment == 'prod'",
            message = "Approval is required only when the runtime environment is production."
        ))
    )

    private fun deleteFlow(
        approval: ApproveNode? = null,
        dependsOnApproval: Boolean = false,
        safety: SafetyNode? = null
    ): FlowDocument {
        val steps = buildList {
            approval?.let(::add)
            add(ActionNode(
                module = "kubernetes",
                action = "delete",
                target = ReferenceNode(path = listOf("cluster")),
                params = mapOf(
                    "resource" to StringLiteralNode(value = "namespace"),
                    "name" to StringLiteralNode(value = "demo")
                ),
                dependsOn = if (dependsOnApproval) listOf("approval") else emptyList(),
                safety = safety
            ))
        }
        return FlowDocument(flow = FlowNode(name = "delete", steps = steps))
    }

    private fun migrationIntent(includeBackup: Boolean): IntentDocument {
        val steps = buildList {
            if (includeBackup) add(IntentStep(id = "backup", capability = StandardCapability.BACKUP))
            add(IntentStep(id = "migrate", capability = StandardCapability.DATABASE_MIGRATE))
        }
        return IntentDocument(
            name = "migration",
            workflows = listOf(IntentWorkflow("migration", IntentWorkflowKind.CUSTOM, steps))
        )
    }

    private fun customPolicyIntent(condition: String): IntentDocument = IntentDocument(
        name = "custom-policy",
        workflows = listOf(IntentWorkflow(
            name = "build",
            kind = IntentWorkflowKind.BUILD,
            steps = listOf(IntentStep(id = "build", capability = StandardCapability.BUILD))
        )),
        policies = listOf(IntentPolicy(
            name = "risk-policy",
            type = IntentPolicyType.CUSTOM,
            condition = condition
        ))
    )
}
