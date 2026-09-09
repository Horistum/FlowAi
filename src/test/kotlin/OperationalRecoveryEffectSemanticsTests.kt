import org.flowlang.frontend.FrontendCompilerComposition
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.flowlang.cli.Json
import org.flowlang.conformance.SemanticObservationAuthority
import org.flowlang.effects.CanonicalIntentEffectAuthority
import org.flowlang.effects.EffectDomain
import org.flowlang.effects.EffectOperation
import org.flowlang.effects.RecoveryEffectKind
import org.flowlang.effects.RecoveryEndpoint
import org.flowlang.effects.RecoveryEndpointKind
import org.flowlang.effects.RecoverySemantics
import org.flowlang.effects.ResourceState
import org.flowlang.effects.SemanticEffect
import org.flowlang.effects.canonicalObservationValue
import org.flowlang.generators.manifest.InvalidPlanningEvidenceException
import org.flowlang.generators.manifest.MandatoryMaterializationAuthority
import org.flowlang.intent.IntentString
import org.flowlang.intent.IntentToAstPlanner
import org.flowlang.intent.IntentYamlLoader
import org.flowlang.intent.StandardCapability
import org.flowlang.modules.ModuleRegistry
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.FlowPlanner
import org.flowlang.planner.TaskNode
import org.flowlang.standard.FlowStandardVersions
import org.flowlang.targets.TargetRegistryYamlLoader

class OperationalRecoveryEffectSemanticsTests {
    private val modules by lazy { ModuleRegistry.fromDirectory(File("modules")) }

    @Test
    fun dp01BackupPreservesAuthoredProtectedStateAndDestination() {
        val intent = IntentYamlLoader.load(
            File("conformance/corpus/operational/cases/DP01-backup/canonical.intent.yaml")
        )
        val task = FlowPlanner(modules)
            .plan(FrontendCompilerComposition.intentPlanner(modules).plan(intent))
            .tasks.single { it.semanticCapability == StandardCapability.BACKUP.name }
        val effect = task.effectModel.single { it.recovery != null }
        val recovery = requireNotNull(effect.recovery)

        assertEquals(EffectDomain.STATE_RECOVERY, effect.domain)
        assertEquals(EffectOperation.CREATE, effect.operation)
        assertEquals(ResourceState.ABSENT, effect.transition?.from)
        assertEquals(ResourceState.PRESENT, effect.transition?.to)
        assertEquals(RecoveryEffectKind.RECOVERY_POINT_CAPTURE, recovery.kind)
        assertEquals(
            RecoveryEndpoint(RecoveryEndpointKind.PROTECTED_STATE, "application-state"),
            recovery.source
        )
        assertEquals(
            RecoveryEndpoint(RecoveryEndpointKind.BACKUP_DESTINATION, "durable-backup-store"),
            recovery.target
        )
        assertNull(recovery.retention)
    }

    @Test
    fun dp02RestorePreservesRecoveryPointToProtectedStateRelationship() {
        val intent = IntentYamlLoader.load(
            File("conformance/corpus/operational/cases/DP02-restore/canonical.intent.yaml")
        )
        val task = FlowPlanner(modules)
            .plan(FrontendCompilerComposition.intentPlanner(modules).plan(intent))
            .tasks.single { it.semanticCapability == StandardCapability.RESTORE.name }
        val effect = task.effectModel.single { it.recovery != null }
        val recovery = requireNotNull(effect.recovery)

        assertEquals(EffectDomain.STATE_RECOVERY, effect.domain)
        assertEquals(EffectOperation.UPSERT, effect.operation)
        assertEquals(ResourceState.UNKNOWN, effect.transition?.from)
        assertEquals(ResourceState.PRESENT, effect.transition?.to)
        assertEquals(RecoveryEffectKind.STATE_RESTORE, recovery.kind)
        assertEquals(
            RecoveryEndpoint(RecoveryEndpointKind.RECOVERY_POINT, "nightly-recovery-point"),
            recovery.source
        )
        assertEquals(
            RecoveryEndpoint(RecoveryEndpointKind.PROTECTED_STATE, "application-state"),
            recovery.target
        )
        assertNull(recovery.retention)
    }

    @Test
    fun authoredRetentionIsOwnedOnlyByRecoveryPointCapture() {
        val effects = CanonicalIntentEffectAuthority.effectsFor(
            StandardCapability.BACKUP,
            mapOf(
                "subject" to IntentString("application-state"),
                "destination" to IntentString("durable-backup-store"),
                "retention" to IntentString("30d")
            )
        )

        assertEquals("30d", effects.single { it.recovery != null }.recovery?.retention)
    }

    @Test
    fun recoveryParameterDifferencesChangeSemanticObservations() {
        val backupA = taskFor(
            StandardCapability.BACKUP,
            mapOf("subject" to "state", "destination" to "store-a", "retention" to "7d")
        )
        val backupB = taskFor(
            StandardCapability.BACKUP,
            mapOf("subject" to "state", "destination" to "store-b", "retention" to "30d")
        )
        val restoreA = taskFor(
            StandardCapability.RESTORE,
            mapOf("subject" to "state", "recoveryPoint" to "point-a")
        )
        val restoreB = taskFor(
            StandardCapability.RESTORE,
            mapOf("subject" to "state", "recoveryPoint" to "point-b")
        )

        assertTrue(requirementsFor(backupA) != requirementsFor(backupB))
        assertTrue(requirementsFor(restoreA) != requirementsFor(restoreB))
    }

    @Test
    fun implementationLabelsCannotChangeRecoveryObservationMeaning() {
        val canonical = taskFor(
            StandardCapability.RESTORE,
            mapOf("subject" to "application-state", "recoveryPoint" to "point-a")
        )
        val relabeled = canonical.copy(
            module = "vendor-specific-module",
            action = "opaque-action-name",
            target = "some-platform"
        )

        assertEquals(requirementsFor(canonical), requirementsFor(relabeled))
    }

    @Test
    fun materializationRejectsForgedRecoveryRelationship() {
        val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))
        val authority = MandatoryMaterializationAuthority(targets, modules)
        val params = mapOf(
            "operation" to "backup",
            "capability" to "backup",
            "subject" to "application-state",
            "destination" to "durable-backup-store"
        )
        val expected = CanonicalIntentEffectAuthority.effectsForRendered(StandardCapability.BACKUP, params)
        val base = TaskNode(
            id = "backup",
            module = "standard",
            action = "execute",
            target = "standard",
            semanticCapability = StandardCapability.BACKUP.name,
            effectModel = expected,
            effects = expected.map(SemanticEffect::resource),
            params = params
        )

        authority.authorizeDiagnosticEvidence(
            testDiagnosticMaterializationRequest(
                ExecutionPlan(flowName = "valid-recovery", nodes = listOf(base)),
                "jenkins",
                targets
            )
        )

        val forgedEffects = expected.map { effect ->
            if (effect.recovery == null) effect else effect.copy(
                recovery = requireNotNull(effect.recovery).copy(
                    target = RecoveryEndpoint(RecoveryEndpointKind.BACKUP_DESTINATION, "forged-store")
                )
            )
        }
        val failure = assertFailsWith<InvalidPlanningEvidenceException> {
            authority.authorizeDiagnosticEvidence(
                testDiagnosticMaterializationRequest(
                    ExecutionPlan(
                        flowName = "forged-recovery",
                        nodes = listOf(base.copy(effectModel = forgedEffects))
                    ),
                    "jenkins",
                    targets
                )
            )
        }

        assertTrue(failure.issues.any { it.code == "planning.effect.evidence.invalid" })
    }

    @Test
    fun recoveryFacetRejectsStructurallyInvalidRelationships() {
        assertFailsWith<IllegalArgumentException> {
            RecoverySemantics(
                kind = RecoveryEffectKind.RECOVERY_POINT_CAPTURE,
                source = RecoveryEndpoint(RecoveryEndpointKind.RECOVERY_POINT, "point")
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RecoverySemantics(
                kind = RecoveryEffectKind.STATE_RESTORE,
                target = RecoveryEndpoint(RecoveryEndpointKind.BACKUP_DESTINATION, "store")
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RecoverySemantics(
                kind = RecoveryEffectKind.STATE_RESTORE,
                target = RecoveryEndpoint(RecoveryEndpointKind.PROTECTED_STATE, "state"),
                retention = "30d"
            )
        }
    }

    @Test
    fun abstractCapabilityEffectsDoNotInventUnauthoredRecoveryIdentities() {
        val backup = CanonicalIntentEffectAuthority.effectsFor(StandardCapability.BACKUP)
        val restore = CanonicalIntentEffectAuthority.effectsFor(StandardCapability.RESTORE)

        assertTrue(backup.all { it.domain == EffectDomain.STATE_RECOVERY })
        assertTrue(restore.all { it.domain == EffectDomain.STATE_RECOVERY })
        assertTrue(backup.all { it.recovery == null })
        assertTrue(restore.all { it.recovery == null })
    }

    @Test
    fun recoveryEffectRoundTripsAndSchemaOwnsTheNewWireVocabulary() {
        val effect = CanonicalIntentEffectAuthority.effectsFor(
            StandardCapability.BACKUP,
            mapOf(
                "subject" to IntentString("application-state"),
                "destination" to IntentString("durable-backup-store"),
                "retention" to IntentString("30d")
            )
        ).single { it.recovery != null }

        val serialized = Json.mapper.writeValueAsString(effect)
        val roundTrip = Json.mapper.readValue(serialized, SemanticEffect::class.java)
        assertEquals(effect, roundTrip)
        assertFalse(serialized.contains("recovery\":null"))

        val schema = Json.mapper.readTree(File("schemas/execution-plan.schema.json"))
        assertEquals("2.4", FlowStandardVersions.EXECUTION_PLAN_VERSION)
        assertEquals(
            "2.4",
            schema.path("properties").path("planVersion").path("const").asText()
        )
        assertTrue(
            schema.path("\$defs").path("semanticEffect").path("properties")
                .path("domain").path("enum").map { it.asText() }.contains("STATE_RECOVERY")
        )
        assertEquals(
            "#/\$defs/recoverySemantics",
            schema.path("\$defs").path("semanticEffect").path("properties")
                .path("recovery").path("\$ref").asText()
        )
        val recoveryVariants = schema.path("\$defs").path("recoverySemantics").path("oneOf")
        val recoveryKinds = recoveryVariants.map {
            it.path("properties").path("kind").path("const").asText()
        }.toSet()
        assertEquals(setOf("RECOVERY_POINT_CAPTURE", "STATE_RESTORE"), recoveryKinds)
        assertTrue(recoveryVariants.all { !it.path("additionalProperties").asBoolean() })
    }

    @Test
    fun unrelatedEffectObservationIdentityDoesNotChange() {
        val build = CanonicalIntentEffectAuthority.effectsFor(StandardCapability.BUILD).single()

        assertEquals(
            "SOFTWARE_DELIVERY:EXECUTE:software.build::true:BUILD",
            build.canonicalObservationValue()
        )
    }

    private fun taskFor(capability: StandardCapability, params: Map<String, String>): TaskNode {
        val effects = CanonicalIntentEffectAuthority.effectsForRendered(capability, params)
        return TaskNode(
            id = "recovery",
            module = "standard",
            action = "execute",
            target = "standard",
            sourceId = "recovery",
            semanticCapability = capability.name,
            effectModel = effects,
            effects = effects.map(SemanticEffect::resource),
            params = params
        )
    }

    private fun requirementsFor(task: TaskNode) = SemanticObservationAuthority.requirementsFor(
        ExecutionPlan(flowName = "recovery", nodes = listOf(task))
    )
}
