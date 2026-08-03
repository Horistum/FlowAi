package org.flowlang.conformance

import java.io.File
import org.flowlang.adapters.rendering.AdapterRenderedArtifactKind
import org.flowlang.adapters.trigger.AdapterTriggerDecision
import org.flowlang.adapters.trigger.AdapterTriggerEvidenceIntegrityAuthority
import org.flowlang.adapters.trigger.AdapterTriggerEvidenceStatus
import org.flowlang.adapters.trigger.AdapterTriggerMaterializationAuthority
import org.flowlang.adapters.trigger.AdapterTriggerRoadmapLifecycleAuthority
import org.flowlang.ast.ScheduleNode
import org.flowlang.ast.TriggerNode
import org.flowlang.capabilities.CompatibilityAnalyzer
import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetCapability
import org.flowlang.cli.honest.CliTargetEvidenceAuthority
import org.flowlang.cli.honest.CliTargetEvidenceOutcome
import org.flowlang.generators.manifest.TargetManifestGenerationPipeline
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.materialization.TargetDiagnosticMaterializationRequest
import org.flowlang.materialization.TargetSelectionAuthority
import org.flowlang.modules.ModuleRegistry
import org.flowlang.parser.FlowParser
import org.flowlang.planner.ExecutionPlan
import org.flowlang.planner.FlowPlanner
import org.flowlang.planner.PlanSchedule
import org.flowlang.planner.PlanTrigger

class AdapterTriggerConformanceChecks(
    private val rootDir: File,
    private val targets: Map<String, TargetCapability>,
    private val projections: TargetProjectionRegistry
) {
    private val modules by lazy {
        ModuleRegistry.fromDirectory(File(rootDir, "modules"), includeDefaults = true)
    }
    private val authority by lazy {
        AdapterTriggerMaterializationAuthority(rootDir, targets, projections)
    }

    fun checks(): List<ConformanceCheck> {
        val lifecycleResult = runCatching { AdapterTriggerRoadmapLifecycleAuthority(rootDir).analyze() }
        val lifecycle = lifecycleResult.getOrNull()
        val evidenceResult = runCatching {
            AdapterTriggerEvidenceIntegrityAuthority(rootDir, targets, projections).analyze()
        }
        val evidence = evidenceResult.getOrNull()

        val lifecycleErrors = buildList {
            lifecycleResult.exceptionOrNull()?.let { add(it.message ?: it.javaClass.simpleName) }
            lifecycle?.failedChecks?.forEach { id ->
                val failed = lifecycle.checks.first { it.id == id }
                add("$id:${failed.evidence.joinToString()}:${failed.message}")
            }
        }
        val evidenceErrors = buildList {
            evidenceResult.exceptionOrNull()?.let { add(it.message ?: it.javaClass.simpleName) }
            evidence?.findings?.forEach { add("${it.code}:${it.target}:${it.family}:${it.message}") }
        }

        return listOf(
            ConformanceCheck(
                name = LIFECYCLE_CHECK,
                passed = lifecycle?.status == "PASS",
                message = lifecycleErrors.takeIf { it.isNotEmpty() }?.joinToString(" | ")
            ),
            ConformanceCheck(
                name = EVIDENCE_CHECK,
                passed = evidence?.status == "PASS",
                message = evidenceErrors.takeIf { it.isNotEmpty() }?.joinToString(" | ")
            ),
            check(RUNTIME_AUTHORITY_CHECK, runCatching(::runtimeAuthorityErrors)),
            check(NO_APPROXIMATION_CHECK, runCatching(::noApproximationErrors)),
            check(REVIEW_EVIDENCE_CHECK, runCatching(::reviewEvidenceErrors)),
            check(EXECUTABLE_PROOF_CHECK, runCatching(::executableTriggerErrors)),
            check(BOUNDED_EVENT_CHECK, runCatching(::boundedEventErrors))
        )
    }

    private fun runtimeAuthorityErrors(): List<String> = buildList {
        val manual = authority.assess(
            ExecutionPlan(
                flowName = "a0.7-runtime-manual",
                triggers = listOf(PlanTrigger("manual", "MANUAL", listOf("main")))
            ),
            "jenkins"
        )
        if (manual.decision != AdapterTriggerDecision.MATCHED) {
            add("Jenkins manual trigger did not match adapter evidence.")
        }

        val githubCron = authority.assess(
            ExecutionPlan(
                flowName = "a0.7-runtime-cron",
                triggers = listOf(cron("nightly", "0 3 * * *", "Europe/Prague"))
            ),
            "github-actions"
        )
        if (githubCron.decision != AdapterTriggerDecision.MATCHED) {
            add("GitHub portable CRON with IANA timezone did not match adapter evidence.")
        }
        if (githubCron.evidence.any { it.status != AdapterTriggerEvidenceStatus.SATISFIED }) {
            add("Matched GitHub CRON contains non-satisfied trigger evidence.")
        }
    }

    private fun noApproximationErrors(): List<String> = buildList {
        val probes = listOf(
            Triple(
                "github-actions",
                PlanTrigger("interval", "SCHEDULE", listOf("main"), PlanSchedule("INTERVAL", "PT15M", null)),
                "interval"
            ),
            Triple(
                "github-actions",
                PlanTrigger("calendar", "SCHEDULE", listOf("main"), PlanSchedule("CALENDAR", "FREQ=MONTHLY;BYMONTHDAY=1", null)),
                "calendar"
            ),
            Triple(
                "github-actions",
                PlanTrigger("hook", "WEBHOOK", listOf("main"), event = "deployment"),
                "webhook"
            ),
            Triple(
                "jenkins",
                PlanTrigger("event", "EVENT", listOf("main"), event = "push"),
                "event"
            ),
            Triple(
                "jenkins",
                cron("zone", "0 3 * * *", "Europe/Prague"),
                "timezone"
            )
        )
        probes.forEach { (target, trigger, label) ->
            val assessment = authority.assess(
                ExecutionPlan(flowName = "a0.7-no-approximation-$label", triggers = listOf(trigger)),
                target
            )
            if (assessment.decision != AdapterTriggerDecision.BLOCKED) {
                add("$target $label requirement was approximated instead of blocked.")
            }
        }
    }

    private fun reviewEvidenceErrors(): List<String> = buildList {
        val plan = triggeredCheckoutPlan(
            TriggerNode(
                id = "interval",
                triggerType = "SCHEDULE",
                schedule = ScheduleNode(kind = "INTERVAL", expression = "PT15M")
            )
        )
        val selection = TargetSelectionAuthority.fromConformanceCheck(
            value = "github-actions",
            checkId = REVIEW_EVIDENCE_CHECK,
            targets = targets
        )
        val result = CliTargetEvidenceAuthority(targets, projections, rootDir).evaluate(
            plan,
            selection,
            strict = false,
            renderRequested = true
        )
        if (result.outcome != CliTargetEvidenceOutcome.REVIEW_ONLY) {
            add("Unsupported interval did not produce a typed review-only CLI outcome.")
        }
        if (result.manifest.metadata["adapterTriggerDecision"] != AdapterTriggerDecision.BLOCKED.name) {
            add("Review manifest lost the blocked A0.7 decision.")
        }
        val artifact = result.renderedArtifact
        if (artifact == null || artifact.kind != AdapterRenderedArtifactKind.REVIEW_EVIDENCE) {
            add("Unsupported interval did not preserve dedicated review evidence.")
        } else {
            if (artifact.fileName == "github-actions.yml") {
                add("Blocked trigger review evidence impersonated executable GitHub Actions syntax.")
            }
            if ("executable: false" !in artifact.content) {
                add("Blocked trigger review artifact lacks an explicit non-executable marker.")
            }
        }
    }

    private fun executableTriggerErrors(): List<String> = buildList {
        val plan = triggeredCheckoutPlan(
            TriggerNode(
                id = "nightly",
                triggerType = "SCHEDULE",
                schedule = ScheduleNode(kind = "CRON", expression = "0 3 * * *")
            )
        )
        val selection = TargetSelectionAuthority.fromConformanceCheck(
            value = "jenkins",
            checkId = EXECUTABLE_PROOF_CHECK,
            targets = targets
        )
        val result = CliTargetEvidenceAuthority(targets, projections, rootDir).evaluate(
            plan,
            selection,
            strict = false,
            renderRequested = true
        )
        if (result.outcome != CliTargetEvidenceOutcome.EXECUTABLE) {
            add("Jenkins portable CRON did not retain executable CLI outcome.")
        }
        val artifact = result.renderedArtifact
        if (artifact == null || artifact.kind != AdapterRenderedArtifactKind.EXECUTABLE_TARGET) {
            add("Jenkins portable CRON produced no executable target artifact.")
        } else if ("cron('0 3 * * *')" !in artifact.content) {
            add("Jenkins executable artifact did not preserve the exact CRON expression.")
        }
        if (result.manifest.metadata["adapterTriggerDecision"] != AdapterTriggerDecision.MATCHED.name) {
            add("Executable Jenkins manifest lacks matched A0.7 evidence.")
        }
    }

    private fun boundedEventErrors(): List<String> = buildList {
        val plan = triggeredCheckoutPlan(
            TriggerNode(id = "release", triggerType = "EVENT", event = "release")
        )
        val preliminary = CompatibilityAnalyzer(targets).analyze(plan, "github-actions")
        if (preliminary.capabilityStatus != SupportLevel.PARTIAL) {
            add("Bounded event leaf proof expected preliminary PARTIAL registry context.")
            return@buildList
        }
        val selection = TargetSelectionAuthority.fromConformanceCheck(
            value = "github-actions",
            checkId = BOUNDED_EVENT_CHECK,
            targets = targets
        )
        val diagnosticManifest = TargetManifestGenerationPipeline(targets, projections)
            .generateDiagnosticEvidence(TargetDiagnosticMaterializationRequest(plan, selection))
        val leafCompatibility = diagnosticManifest.compatibility.copy(
            status = SupportLevel.SUPPORTED,
            capabilityStatus = SupportLevel.SUPPORTED,
            issues = emptyList(),
            executable = true
        )
        val leafManifest = diagnosticManifest.copy(compatibility = leafCompatibility)
        val assessment = authority.requireMatched(plan, "github-actions")
        val manifest = authority.reconcileDiagnostic(leafManifest, assessment)
        val rendered = projections.requireProvider("github-actions").render(manifest)
        if ("  release:" !in rendered) {
            add("GitHub bounded event renderer did not preserve the exact release event identity.")
        }
        if ("repository_dispatch" in rendered) {
            add("GitHub bounded event renderer silently converted a named event to repository_dispatch.")
        }
    }

    private fun triggeredCheckoutPlan(trigger: TriggerNode): ExecutionPlan {
        val document = FlowParser().parse(
            """
            version "1.0"
            use module "git" version "1.0"

            flow "a0.7-triggered-checkout" {
              systems {
                system "repo" {
                  type: git
                  url: "https://github.com/openai/openai.git"
                  branch: "main"
                }
              }

              steps {
                git.checkout repo {
                  depth: 2
                }
              }
            }
            """.trimIndent()
        )
        return FlowPlanner(modules).plan(
            document.copy(flow = document.flow.copy(triggers = listOf(trigger)))
        )
    }

    private fun cron(id: String, expression: String, timezone: String?) = PlanTrigger(
        id = id,
        type = "SCHEDULE",
        workflows = listOf("main"),
        schedule = PlanSchedule("CRON", expression, timezone)
    )

    private fun check(name: String, result: Result<List<String>>): ConformanceCheck {
        val errors = result.getOrElse { listOf(it.message ?: it.javaClass.simpleName) }
        return ConformanceCheck(
            name = name,
            passed = errors.isEmpty(),
            message = errors.takeIf { it.isNotEmpty() }?.joinToString(" | ")
        )
    }

    companion object {
        const val LIFECYCLE_CHECK = "adapters.a0.7.lifecycle-integrity"
        const val EVIDENCE_CHECK = "adapters.a0.7.trigger-evidence-integrity"
        const val RUNTIME_AUTHORITY_CHECK = "adapters.a0.7.runtime-trigger-authority"
        const val NO_APPROXIMATION_CHECK = "adapters.a0.7.no-trigger-approximation"
        const val REVIEW_EVIDENCE_CHECK = "adapters.a0.7.trigger-review-evidence"
        const val EXECUTABLE_PROOF_CHECK = "adapters.a0.7.executable-trigger-proof"
        const val BOUNDED_EVENT_CHECK = "adapters.a0.7.bounded-event-projection"
    }
}
