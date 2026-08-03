import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.adapters.trigger.AdapterTriggerDecision
import org.flowlang.adapters.trigger.AdapterTriggerEvidenceLoader
import org.flowlang.adapters.trigger.AdapterTriggerEvidenceStatus
import org.flowlang.adapters.trigger.AdapterTriggerFamily
import org.flowlang.adapters.trigger.AdapterTriggerMaterializationAuthority
import org.flowlang.adapters.trigger.AdapterTriggerRequirementAuthority
import org.flowlang.adapters.trigger.UnresolvedAdapterTriggerMaterializationException
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.PlanSchedule
import org.flowlang.planner.PlanTrigger
import org.flowlang.targets.builtin.BuiltInTargetProjections

class AdapterTriggerMaterializationAuthorityTests {
    private val root = File(".")
    private val targets = TargetRegistryYamlLoader.loadDirectory(File(root, "targets"))
    private val authority = AdapterTriggerMaterializationAuthority(root, targets, BuiltInTargetProjections.registry)

    @Test
    fun committedTriggerEvidenceIsCompleteAndValid() {
        val document = AdapterTriggerEvidenceLoader.load(root)
        val report = authority.analyze(document)

        assertEquals("PASS", report.status, report.findings.joinToString("\n"))
        assertEquals(targets.size, report.targetCount)
        assertEquals(targets.size * 6, report.claimCount)
    }

    @Test
    fun requirementDerivationPreservesClosedTriggerFamilies() {
        val requirements = AdapterTriggerRequirementAuthority.derive(
            ExecutionPlan(
                flowName = "trigger-families",
                triggers = listOf(
                    manual("manual"),
                    cron("cron", "0 3 * * *", null),
                    schedule("interval", "INTERVAL", "PT15M"),
                    schedule("calendar", "CALENDAR", "FREQ=MONTHLY;BYMONTHDAY=1"),
                    event("event", "push"),
                    webhook("webhook", "deployment")
                )
            )
        )

        assertEquals(
            setOf(
                AdapterTriggerFamily.MANUAL,
                AdapterTriggerFamily.CRON,
                AdapterTriggerFamily.INTERVAL,
                AdapterTriggerFamily.CALENDAR,
                AdapterTriggerFamily.EVENT,
                AdapterTriggerFamily.WEBHOOK
            ),
            requirements.map { it.family }.toSet()
        )
        assertTrue(requirements.all { it.id.startsWith("adapter-trigger-") })
        assertTrue(requirements.all { it.completeness.name == "RESOLVED" })
    }

    @Test
    fun semanticallyDuplicateTriggersAreRejected() {
        val failure = assertFailsWith<IllegalArgumentException> {
            AdapterTriggerRequirementAuthority.derive(
                ExecutionPlan(
                    flowName = "duplicate-trigger",
                    triggers = listOf(
                        cron("first", "0 3 * * *", null),
                        cron("second", "0 3 * * *", null)
                    )
                )
            )
        }

        assertTrue(failure.message.orEmpty().contains("duplicate", ignoreCase = true))
    }

    @Test
    fun jenkinsPortableCronMatchesButTimezoneAndIntervalBlock() {
        val matched = authority.assess(
            ExecutionPlan(flowName = "jenkins-cron", triggers = listOf(cron("nightly", "0 3 * * *", null))),
            "jenkins"
        )
        assertEquals(AdapterTriggerDecision.MATCHED, matched.decision)
        assertEquals(AdapterTriggerEvidenceStatus.SATISFIED, matched.evidence.single().status)

        val timezone = authority.assess(
            ExecutionPlan(flowName = "jenkins-zone", triggers = listOf(cron("nightly", "0 3 * * *", "Europe/Prague"))),
            "jenkins"
        )
        assertEquals(AdapterTriggerDecision.BLOCKED, timezone.decision)
        assertEquals(AdapterTriggerEvidenceStatus.UNSUPPORTED, timezone.evidence.single().status)
        assertTrue(timezone.evidence.single().detail.contains("timezone"))

        val interval = authority.assess(
            ExecutionPlan(flowName = "jenkins-interval", triggers = listOf(schedule("every", "INTERVAL", "PT15M"))),
            "jenkins"
        )
        assertEquals(AdapterTriggerDecision.BLOCKED, interval.decision)
        assertEquals(AdapterTriggerEvidenceStatus.UNSUPPORTED, interval.evidence.single().status)
    }

    @Test
    fun githubBoundedEventAndTimezoneMatchButUnknownEventAndWebhookBlock() {
        val cron = authority.requireMatched(
            ExecutionPlan(
                flowName = "github-cron",
                triggers = listOf(cron("nightly", "0 3 * * *", "Europe/Prague"))
            ),
            "github-actions"
        )
        assertEquals(AdapterTriggerDecision.MATCHED, cron.decision)

        val event = authority.requireMatched(
            ExecutionPlan(flowName = "github-push", triggers = listOf(event("push", "push"))),
            "github-actions"
        )
        assertEquals(AdapterTriggerDecision.MATCHED, event.decision)

        val unknownEvent = authority.assess(
            ExecutionPlan(flowName = "github-issue", triggers = listOf(event("issue", "issues"))),
            "github-actions"
        )
        assertEquals(AdapterTriggerDecision.BLOCKED, unknownEvent.decision)
        assertEquals(AdapterTriggerEvidenceStatus.UNSUPPORTED, unknownEvent.evidence.single().status)

        val webhookFailure = assertFailsWith<UnresolvedAdapterTriggerMaterializationException> {
            authority.requireMatched(
                ExecutionPlan(flowName = "github-hook", triggers = listOf(webhook("hook", "deployment"))),
                "github-actions"
            )
        }
        assertEquals(AdapterTriggerFamily.WEBHOOK, webhookFailure.assessment.requirements.single().family)
    }

    @Test
    fun profileOnlyAndTektonTargetsNeverAcquireTriggerSupportFromRegistryFlags() {
        val plan = ExecutionPlan(flowName = "profile-cron", triggers = listOf(cron("nightly", "0 3 * * *", null)))

        assertEquals(AdapterTriggerEvidenceStatus.UNKNOWN, authority.assess(plan, "argo-workflows").evidence.single().status)
        assertEquals(AdapterTriggerEvidenceStatus.UNKNOWN, authority.assess(plan, "azure-devops").evidence.single().status)
        assertEquals(AdapterTriggerEvidenceStatus.UNKNOWN, authority.assess(plan, "local").evidence.single().status)
        assertEquals(AdapterTriggerEvidenceStatus.UNSUPPORTED, authority.assess(plan, "tekton").evidence.single().status)
    }

    private fun manual(id: String) = PlanTrigger(id = id, type = "MANUAL", workflows = listOf("main"))

    private fun cron(id: String, expression: String, timezone: String?) = PlanTrigger(
        id = id,
        type = "SCHEDULE",
        workflows = listOf("main"),
        schedule = PlanSchedule("CRON", expression, timezone)
    )

    private fun schedule(id: String, kind: String, expression: String) = PlanTrigger(
        id = id,
        type = "SCHEDULE",
        workflows = listOf("main"),
        schedule = PlanSchedule(kind, expression, null)
    )

    private fun event(id: String, name: String) = PlanTrigger(
        id = id,
        type = "EVENT",
        workflows = listOf("main"),
        event = name
    )

    private fun webhook(id: String, name: String) = PlanTrigger(
        id = id,
        type = "WEBHOOK",
        workflows = listOf("main"),
        event = name
    )
}
