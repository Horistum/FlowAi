package org.flowlang.generators.manifest

import org.flowlang.capabilities.CompatibilityLevel
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.planner.*
import org.flowlang.standard.FlowStandardVersions

/**
 * Platform-neutral generator contract output.
 *
 * TargetManifest is the auditable artifact between Flow ExecutionPlan and vendor
 * syntax. Renderers render this manifest; they do not re-plan Flow. This keeps
 * Flow aligned with the main goal: one AI-first automation standard, many DevOps
 * targets, no requirement for users to learn every platform lifecycle dialect.
 */
data class TargetManifest(
    val manifestVersion: String = FlowStandardVersions.TARGET_MANIFEST_VERSION,
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val target: String,
    val flowName: String,
    val compatibility: CompatibilityReport,
    val inputs: List<TargetInput> = emptyList(),
    val jobs: List<TargetJob> = emptyList(),
    val mappingNotes: List<TargetMappingNote> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
)

data class TargetMappingNote(
    val level: String,
    val target: String,
    val nodeId: String,
    val feature: String,
    val message: String
)

data class TargetInput(
    val name: String,
    val type: String = "text",
    val required: Boolean = false,
    val defaultValue: String? = null,
    val choices: List<String> = emptyList()
)

data class TargetJob(
    val id: String,
    val name: String = id,
    val dependsOn: List<String> = emptyList(),
    val steps: List<TargetStep> = emptyList(),
    val mappingNotes: List<TargetMappingNote> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
)

data class TargetStep(
    val id: String,
    val name: String = id,
    val type: String,
    val module: String? = null,
    val action: String? = null,
    val target: String? = null,
    val run: String? = null,
    val dependsOn: List<String> = emptyList(),
    val params: Map<String, String> = emptyMap(),
    val children: List<TargetStep> = emptyList(),
    val mappingNotes: List<TargetMappingNote> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
)

interface TargetManifestGenerator {
    val target: String
    fun generate(plan: ExecutionPlan, compatibility: CompatibilityReport): TargetManifest
}

class JenkinsManifestGenerator : TargetManifestGenerator {
    override val target: String = "jenkins"

    override fun generate(plan: ExecutionPlan, compatibility: CompatibilityReport): TargetManifest {
        val steps = plan.nodes.toJenkinsTargetSteps(target)
        return TargetManifest(
            target = target,
            flowName = plan.flowName,
            compatibility = compatibility,
            inputs = plan.inputs.map { it.toTargetInput() },
            jobs = listOf(TargetJob(id = sanitizeId(plan.flowName), name = plan.flowName, steps = steps)),
            mappingNotes = compatibility.toMappingNotes(target),
            metadata = baseMetadata(plan, "JenkinsManifestGenerator") + ("nodePreserving" to "true")
        )
    }
}

class GitHubActionsManifestGenerator : TargetManifestGenerator {
    override val target: String = "github-actions"

    override fun generate(plan: ExecutionPlan, compatibility: CompatibilityReport): TargetManifest {
        val jobs = mutableListOf<TargetJob>()
        plan.nodes.forEach { it.toTargetJobs(jobs, condition = null, targetName = target) }
        return TargetManifest(
            target = target,
            flowName = plan.flowName,
            compatibility = compatibility,
            inputs = plan.inputs.map { it.toTargetInput() },
            jobs = jobs.ifEmpty { listOf(TargetJob(id = sanitizeId(plan.flowName), name = plan.flowName)) },
            mappingNotes = compatibility.toMappingNotes(target),
            metadata = baseMetadata(plan, "GitHubActionsManifestGenerator") + ("jobPerTask" to "true")
        )
    }
}

class TektonManifestGenerator : TargetManifestGenerator {
    override val target: String = "tekton"

    override fun generate(plan: ExecutionPlan, compatibility: CompatibilityReport): TargetManifest {
        val jobs = mutableListOf<TargetJob>()
        plan.nodes.forEach { it.toTargetJobs(jobs, condition = null, targetName = target) }
        val resolvedJobs = jobs.ifEmpty { listOf(TargetJob(id = sanitizeId(plan.flowName), name = plan.flowName)) }
        val inputs = plan.inputs.map { it.toTargetInput() }
        val partialNote = TargetMappingNote("warning", target, "manifest", "target.partial", "Tekton renderer is a partial generator and intentionally emits mapping notes for features that require platform-specific adapters.")
        // Silent-semantic-fallback guard (standard/architecture/forbidden-directions.yaml#silent-semantic-fallback):
        // a Flow condition that cannot be expressed as a native Tekton 'when' must surface as an explicit
        // diagnostic, never be dropped so the task silently runs unconditionally.
        val untranslatableConditionNotes = resolvedJobs.mapNotNull { job ->
            val condition = job.metadata["condition"]
            if (condition != null && TargetExpressionTranslator.tektonWhen(condition, inputs) == null) {
                TargetMappingNote(
                    level = "error",
                    target = target,
                    nodeId = job.id,
                    feature = "condition.unsupported",
                    message = "Flow condition cannot be enforced as a native Tekton 'when' guard; without resolution the task would run unconditionally. Use a supported condition shape or an explicit external adapter before production use: $condition"
                )
            } else null
        }
        return TargetManifest(
            target = target,
            flowName = plan.flowName,
            compatibility = compatibility,
            inputs = inputs,
            jobs = resolvedJobs,
            mappingNotes = compatibility.toMappingNotes(target) + partialNote + untranslatableConditionNotes,
            metadata = baseMetadata(plan, "TektonManifestGenerator") + mapOf("supportLevel" to "partial", "jobPerTask" to "true")
        )
    }
}

private fun PlanInput.toTargetInput(): TargetInput = TargetInput(name, type, required, defaultValue, choices)

private fun baseMetadata(plan: ExecutionPlan, generator: String): Map<String, String> = mapOf(
    "sourcePlanVersion" to plan.planVersion,
    "generator" to generator,
    "standardVersion" to FlowStandardVersions.FLOW_STANDARD_VERSION
)

private fun CompatibilityReport.toMappingNotes(targetName: String): List<TargetMappingNote> = issues.map {
    TargetMappingNote(
        level = it.level.name.lowercase(),
        target = targetName,
        nodeId = it.nodeId,
        feature = it.feature,
        message = it.message
    )
}

private fun List<PlanNode>.toJenkinsTargetSteps(targetName: String): List<TargetStep> {
    val flowHandler = lastOrNull() as? TryPlanNode
    if (flowHandler != null && flowHandler.body.isEmpty() && flowHandler.errorHandler.isNotEmpty()) {
        val bodyNodes = dropLast(1)
        if (bodyNodes.isNotEmpty()) {
            val bodyStep = TargetStep(
                id = sanitizeId("flow_1_body"),
                name = "flow_1 body",
                type = "try-body",
                children = bodyNodes.flatMap { it.toTargetSteps(targetName) },
                metadata = mapOf("sourceNodeKind" to flowHandler.kind, "tryRole" to "body")
            )
            val handlerStep = TargetStep(
                id = sanitizeId("flow_1_handler"),
                name = "flow_1 error handler",
                type = "error-handler",
                children = flowHandler.errorHandler.flatMap { it.toTargetSteps(targetName) },
                metadata = mapOf("sourceNodeKind" to flowHandler.kind, "tryRole" to "errorHandler")
            )
            return listOf(TargetStep(
                id = sanitizeId("flow_1"),
                name = "flow_1",
                type = "try",
                children = listOf(bodyStep, handlerStep),
                metadata = mapOf(
                    "sourceNodeKind" to flowHandler.kind,
                    "flowLevelErrorBoundary" to "true",
                    "errorHandlerCount" to flowHandler.errorHandler.size.toString()
                )
            ))
        }
    }
    return flatMap { it.toTargetSteps(targetName) }
}

internal fun PlanNode.toTargetSteps(targetName: String = "portable-shell"): List<TargetStep> = when (this) {
    is TaskNode -> listOf(toTargetStep(targetName))
    is ConditionNode -> {
        val out = mutableListOf<TargetStep>()
        if (then.isNotEmpty()) out += TargetStep(
            id = sanitizeId("${id}_then"),
            name = "$id then",
            type = "condition",
            params = mapOf("condition" to condition),
            children = then.flatMap { it.toTargetSteps(targetName) },
            metadata = mapOf("sourceNodeKind" to kind, "branch" to "then")
        )
        if (otherwise.isNotEmpty()) out += TargetStep(
            id = sanitizeId("${id}_else"),
            name = "$id else",
            type = "condition",
            params = mapOf("condition" to "not ($condition)"),
            children = otherwise.flatMap { it.toTargetSteps(targetName) },
            metadata = mapOf("sourceNodeKind" to kind, "branch" to "else")
        )
        out
    }
    is LoopNode -> listOf(TargetStep(id = sanitizeId(id), name = id, type = "loop", params = mapOf("item" to item, "source" to source), children = body.flatMap { it.toTargetSteps(targetName) }, metadata = mapOf("sourceNodeKind" to kind, "supportLevel" to "partial")))
    is ParallelGroupNode -> listOf(TargetStep(
        id = sanitizeId(id), name = id, type = "parallel",
        children = branches.mapIndexed { index, branch -> TargetStep(id = sanitizeId(branch.name ?: "branch-${index + 1}"), name = branch.name ?: "branch-${index + 1}", type = "parallel-branch", children = branch.steps.flatMap { it.toTargetSteps(targetName) }, metadata = mapOf("sourceNodeKind" to "ParallelBranch")) },
        metadata = mapOf("sourceNodeKind" to kind, "failFast" to failFast.toString())
    ))
    is MatchPlanNode -> listOf(TargetStep(id = sanitizeId(id), name = id, type = "match", params = mapOf("source" to source), children = cases.flatMap { c -> c.steps.flatMap { it.toTargetSteps(targetName) } } + errorCase.flatMap { it.toTargetSteps(targetName) } + defaultSteps.flatMap { it.toTargetSteps(targetName) }, metadata = mapOf("sourceNodeKind" to kind, "supportLevel" to "partial")))
    is RetryGroupNode -> listOf(TargetStep(id = sanitizeId(id), name = id, type = "retry", params = mapOf("max" to max.toString(), "delay" to delay, "backoff" to backoff), children = body.flatMap { it.toTargetSteps(targetName) }, metadata = mapOf("sourceNodeKind" to kind)))
    is TryPlanNode -> {
        val bodyStep = TargetStep(
            id = sanitizeId("${id}_body"),
            name = "$id body",
            type = "try-body",
            children = body.flatMap { it.toTargetSteps(targetName) },
            metadata = mapOf("sourceNodeKind" to kind, "tryRole" to "body")
        )
        val handlerStep = TargetStep(
            id = sanitizeId("${id}_handler"),
            name = "$id error handler",
            type = "error-handler",
            children = errorHandler.flatMap { it.toTargetSteps(targetName) },
            metadata = mapOf("sourceNodeKind" to kind, "tryRole" to "errorHandler")
        )
        if (body.isEmpty()) {
            listOf(handlerStep)
        } else {
            listOf(TargetStep(
                id = sanitizeId(id),
                name = id,
                type = "try",
                children = listOf(bodyStep, handlerStep),
                metadata = mapOf("sourceNodeKind" to kind, "errorHandlerCount" to errorHandler.size.toString())
            ))
        }
    }
    is ApprovalNode -> listOf(TargetStep(id = sanitizeId(id), name = id, type = "approval", dependsOn = dependsOn.map(::sanitizeId), params = mapOf("mode" to mode) + (message?.let { mapOf("message" to it) } ?: emptyMap()), metadata = mapOf("sourceNodeKind" to kind, "resultName" to (resultName ?: "")).filterValues { it.isNotBlank() }))
    is DataOpNode -> listOf(TargetStep(id = sanitizeId(id), name = id, type = kind.lowercase(), params = mapOf("target" to (target ?: ""), "detail" to (detail ?: "")).filterValues { it.isNotBlank() }, mappingNotes = listOf(
        // A Flow data operation (transform/validate/aggregate) has no target-side execution: its result
        // exists only in the Flow layer. Without this note the projected step looks like a green no-op
        // and downstream commands appear to "use" a value that was never computed on the target.
        TargetMappingNote("warning", "all", id, "dataop.not-materialised", "Flow ${kind.lowercase()} is a Flow-layer data operation and is not materialised by this projection; downstream steps must not depend on its result at runtime without a target-specific adapter.")
    ), metadata = mapOf("sourceNodeKind" to kind)))
    is ControlNode -> listOf(TargetStep(id = sanitizeId(id), name = id, type = kind.lowercase(), params = mapOf("detail" to (detail ?: "")).filterValues { it.isNotBlank() }, metadata = mapOf("sourceNodeKind" to kind)))
}

private fun PlanNode.toTargetJobs(out: MutableList<TargetJob>, condition: String?, targetName: String) {
    when (this) {
        is TaskNode -> {
            val step = toTargetStep(targetName).let { if (condition != null) it.copy(metadata = it.metadata + ("condition" to condition)) else it }
            out += TargetJob(id = sanitizeId(id), name = id, dependsOn = dependsOn.map(::sanitizeId), steps = listOf(step), metadata = mapOfNotNull("condition" to condition))
        }
        is ApprovalNode -> {
            val step = toTargetSteps(targetName).single().let { if (condition != null) it.copy(metadata = it.metadata + ("condition" to condition)) else it }
            out += TargetJob(id = sanitizeId(id), name = id, dependsOn = dependsOn.map(::sanitizeId), steps = listOf(step), metadata = mapOfNotNull("condition" to condition, "approval" to "true"))
        }
        is ConditionNode -> {
            then.forEach { it.toTargetJobs(out, combineConditions(condition, this.condition), targetName) }
            otherwise.forEach { it.toTargetJobs(out, combineConditions(condition, "not (${this.condition})"), targetName) }
        }
        is ParallelGroupNode -> branches.flatMap { it.steps }.forEach { it.toTargetJobs(out, condition, targetName) }
        is RetryGroupNode -> body.forEach { it.toTargetJobs(out, condition, targetName) }
        is TryPlanNode -> {
            val previousJobIds = out.map { it.id }
            val bodyJobs = mutableListOf<TargetJob>()
            body.forEach { it.toTargetJobs(bodyJobs, condition, targetName) }
            out += bodyJobs
            if (targetName != "tekton") {
                val guardDependencies = bodyJobs.map { it.id }.ifEmpty { previousJobIds }
                val handlerJobs = mutableListOf<TargetJob>()
                errorHandler.forEach { it.toTargetJobs(handlerJobs, condition, targetName) }
                out += handlerJobs.map { job ->
                    job.copy(
                        dependsOn = (job.dependsOn + guardDependencies).distinct(),
                        metadata = job.metadata + ("errorHandler" to "true")
                    )
                }
            }
        }
        is LoopNode -> out += TargetJob(id = sanitizeId(id), name = id, steps = toTargetSteps(targetName), metadata = mapOfNotNull("condition" to condition, "supportLevel" to "partial"))
        is MatchPlanNode -> out += TargetJob(id = sanitizeId(id), name = id, steps = toTargetSteps(targetName), metadata = mapOfNotNull("condition" to condition, "supportLevel" to "partial"))
        is DataOpNode, is ControlNode -> out += TargetJob(id = sanitizeId(id), name = id, steps = toTargetSteps(targetName), metadata = mapOfNotNull("condition" to condition))
    }
}

private fun TaskNode.toTargetStep(targetName: String = "portable-shell"): TargetStep = TargetStep(
    id = sanitizeId(id),
    name = id,
    type = "action",
    module = module,
    action = action,
    target = target,
    run = runCommandFor(this, targetName),
    dependsOn = dependsOn.map(::sanitizeId),
    params = params,
    mappingNotes = commandMappingNotes(this),
    metadata = mapOf(
        "sourceTask" to id,
        "sourceNodeKind" to kind,
        "resultName" to (resultName ?: ""),
        "destructive" to destructive.toString(),
        "safety" to (safety ?: "")
    ).filterValues { it.isNotBlank() }
)

/** Action pairs for which [runCommandFor] emits a real portable command (kept in sync with its branches). */
private val PORTABLE_ACTIONS = setOf(
    "shell" to "run", "git" to "checkout", "docker" to "build", "docker" to "push",
    "helm" to "template", "helm" to "upgrade", "kubernetes" to "deploy", "kubernetes" to "get",
    "argocd" to "sync", "argocd" to "status", "rest" to "call", "database" to "query",
    "file" to "write", "notify" to "send", "standard" to "rollback", "standard" to "execute"
)

// A ${ref.path} interpolation whose root is not a plain input token: step results, aggregates and
// other runtime values that only exist in the Flow layer. The manifest cannot materialise them.
private val RUNTIME_RESULT_REF = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*\.[^}]+)}""")

private fun commandMappingNotes(task: TaskNode): List<TargetMappingNote> {
    val notes = mutableListOf<TargetMappingNote>()
    when (task.module to task.action) {
        "standard" to "execute" -> notes += TargetMappingNote("info", "all", task.id, "semantic.standard-execute", "Standard capability is rendered as an auditable semantic command. Add a module-specific adapter when real external side effects are required.")
        "standard" to "rollback" -> notes += TargetMappingNote("info", "all", task.id, "semantic.rollback-adapter", "Rollback is rendered as an auditable semantic command only; no state is reverted until a target-specific rollback adapter is configured.")
        !in PORTABLE_ACTIONS -> notes += TargetMappingNote("error", "all", task.id, "command.unmapped", "No portable command mapping exists for ${task.module}.${task.action}; the generated step fails at runtime instead of silently succeeding without the intended side effect. Provide a target-specific adapter.")
        else -> Unit
    }
    val unresolved = task.params.values.flatMap { RUNTIME_RESULT_REF.findAll(it).map { m -> m.groupValues[1] } }.distinct()
    if (unresolved.isNotEmpty()) {
        notes += TargetMappingNote("warning", "all", task.id, "interpolation.runtime-result", "Runtime result reference(s) ${unresolved.joinToString(", ") { "\${$it}" }} exist only in the Flow layer and are not materialised by this projection; they render as literal text in the command.")
    }
    return notes
}

/**
 * Builds the shell command for a task. The produced command is target-neutral: runtime
 * input values are referenced through shell environment variables ("$FLOW_NAME") rather
 * than spliced into the script text. Each target renderer binds those variables to its
 * own parameter expression (Jenkins params, GitHub inputs, Tekton params) in a scope that
 * covers the command, so the value is delivered via the environment and is never able to
 * break out of quoting or inject additional shell. The `targetName` parameter is retained
 * for backwards compatibility but no longer influences interpolation.
 */
internal fun runCommandFor(task: TaskNode, targetName: String = "portable-shell"): String = when (task.module to task.action) {
    "shell" to "run" -> interpolateShellVars(taskRaw(task, "command", "echo Flow shell.run missing command"))
    "git" to "checkout" -> {
        val url = taskArg(task, "url", ".")
        val branch = task.params["branch"]?.let { " --branch ${shellArg(unquote(it))}" } ?: ""
        "git clone$branch $url ."
    }
    "docker" to "build" -> {
        val image = taskArg(task, "image", "image:latest")
        val path = taskArg(task, "path", ".")
        val dockerfile = task.params["dockerfile"]?.let { " -f ${shellArg(unquote(it))}" } ?: ""
        "docker build$dockerfile -t $image $path"
    }
    "docker" to "push" -> "docker push ${taskArg(task, "image", "image:latest")}"
    "helm" to "template" -> {
        val chart = taskArg(task, "chart", ".")
        val values = task.params["values"]?.let { " -f ${shellArg(unquote(it))}" } ?: ""
        "helm template $chart$values"
    }
    "helm" to "upgrade" -> {
        val release = taskArg(task, "release", task.target)
        val chart = taskArg(task, "chart", ".")
        val ns = task.params["namespace"]?.let { " --namespace ${shellArg(unquote(it))}" } ?: ""
        val values = task.params["values"]?.let { " -f ${shellArg(unquote(it))}" } ?: ""
        "helm upgrade --install $release $chart$ns$values"
    }
    "kubernetes" to "deploy" -> {
        val manifest = task.params["manifest"]?.let { shellArg(unquote(it)) }
        val ns = taskArg(task, "namespace", "default")
        if (!manifest.isNullOrBlank()) {
            "kubectl apply -n $ns -f $manifest"
        } else {
            val appParam = task.params["app"] ?: task.params["name"]
                ?: error("kubernetes.deploy without manifest requires an explicit app or name parameter")
            val app = shellArg(unquote(appParam))
            val rawImage = taskRaw(task, "image", "")
            if (rawImage.isNotBlank()) {
                val image = shellArg(rawImage)
                "kubectl set image deployment/$app *=$image -n $ns\nkubectl rollout status deployment/$app -n $ns"
            } else {
                "kubectl rollout status deployment/$app -n $ns"
            }
        }
    }
    "kubernetes" to "get" -> {
        val resource = taskArg(task, "resource", "pods")
        val ns = taskArg(task, "namespace", "default")
        val selector = task.params["selector"]?.let { " -l ${shellArg(unquote(it))}" } ?: ""
        "kubectl get $resource -n $ns$selector"
    }
    "argocd" to "sync" -> {
        val app = taskArg(task, "app", task.target)
        val timeout = task.params["timeout"]?.let { " --timeout ${shellArg(unquote(it).removeSuffix("m"))}" } ?: ""
        "argocd app sync $app\nargocd app wait $app --health --sync$timeout"
    }
    "argocd" to "status" -> "argocd app get ${taskArg(task, "app", task.target)} -o json"
    "rest" to "call" -> {
        val method = taskArg(task, "method", "GET")
        val base = task.params["baseUrl"]?.let { unquote(it) }?.takeIf { it.isNotBlank() }
        val rawPath = taskRaw(task, "path", "/")
        val urlArg = if (base != null) shellArg(base.trimEnd('/') + "/" + rawPath.trimStart('/')) else shellArg(rawPath)
        val body = task.params["body"]?.let { " --data ${shellArg(unquote(it))}" } ?: ""
        "curl --fail --show-error --silent -X $method $urlArg$body"
    }
    "database" to "query" -> {
        val sql = taskArg(task, "sql", "select 1")
        val url = task.params["url"]?.let { unquote(it) }?.takeIf { it.isNotBlank() }
        val conn = url?.let { " -d ${shellArg(it)}" } ?: ""
        "psql --set ON_ERROR_STOP=1$conn -c $sql"
    }
    "file" to "write" -> {
        val path = taskArg(task, "path", "flow-output.txt")
        val content = taskArg(task, "content", "")
        "printf %s $content > $path"
    }
    "notify" to "send" -> {
        val subject = shellArg("Flow notification: " + taskRaw(task, "subject", task.id))
        val body = taskArg(task, "body", "Flow notification from ${task.id}")
        val to = taskArg(task, "to", "team@example.com")
        "printf %s $body | mail -s $subject $to || { echo ${shellArg("Flow notify delivery failed: mail adapter not configured")} >&2; exit 1; }"
    }
    "standard" to "rollback" -> "echo ${shellArg("Flow rollback requested for ${taskRaw(task, "flow", task.id)}. Configure a target-specific rollback adapter for production.")}"
    "standard" to "execute" -> standardExecuteCommand(task)
    // No portable command mapping exists for this action. A pipeline step that silently succeeds
    // while performing no side effect is a semantic lie (the flow's data writes or effects simply
    // do not happen), so the step fails loudly instead; the mapping note carries the explanation.
    else -> "echo ${shellArg("Flow has no portable command mapping for ${task.module}.${task.action} on ${task.target}; provide a target-specific adapter")} >&2; exit 1"
}

private fun taskRaw(task: TaskNode, name: String, default: String): String =
    task.params[name]?.let { unquote(it) } ?: default

/** A single shell-safe command argument with runtime inputs bound through "$FLOW_NAME". */
private fun taskArg(task: TaskNode, name: String, default: String): String = shellArg(taskRaw(task, name, default))

private fun standardExecuteCommand(task: TaskNode): String {
    val capability = taskRaw(task, "capability", "custom")
    val op = taskRaw(task, "operation", capability)
    fun p(name: String, default: String = ""): String = taskRaw(task, name, default)
    fun echo(message: String): String = "echo ${shellArg(message)}"

    val message = when (op) {
        "schedule" -> "Flow schedule requested: cadence=${p("cadence")} cron=${p("cron")} timezone=${p("timezone")}"
        "backup" -> "Flow backup: subject=${p("subject")} retention=${p("retention", "unspecified")} destination=${p("destination", "unspecified")}"
        "restore" -> "Flow restore: subject=${p("subject")} recoveryPoint=${p("recoveryPoint", "unspecified")}"
        "data-sync" -> "Flow data sync: source=${p("source")} destination=${p("destination")} mode=${p("mode", "default")}"
        "data-transform" -> "Flow data transform: mapping=${p("mapping", "default")}"
        "validate" -> "Flow validation: operation=${p("operation", "validate")}"
        "secret-rotate" -> "Flow secret rotation: subject=${p("subject")} provider=${p("provider", "unspecified")}"
        "provision" -> "Flow provision: tool=${p("tool", "unspecified")} mode=${p("mode", "plan")}"
        "cleanup" -> "Flow cleanup: resource=${p("resource")} safety=${p("safety", "unspecified")}"
        "runbook" -> "Flow runbook: ${p("description", task.id)}"
        "incident" -> "Flow incident: ${p("description", task.id)}"
        "policy-check" -> "Flow policy check: policy=${p("policy", "unspecified")}"
        else -> "Flow standard capability $capability: ${p("description", task.id)}"
    }
    return echo(message.trim())
}

private fun combineConditions(a: String?, b: String): String = if (a.isNullOrBlank()) b else "($a) and ($b)"

private fun mapOfNotNull(vararg pairs: Pair<String, String?>): Map<String, String> = pairs.mapNotNull { (k, v) -> v?.takeIf { it.isNotBlank() }?.let { k to v } }.toMap()

internal fun sanitizeId(value: String): String = value.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').ifBlank { "flow-job" }

internal fun unquote(value: String): String {
    val v = value.trim()
    return if (v.length >= 2 && v.first() == '"' && v.last() == '"') {
        v.substring(1, v.length - 1).replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t")
    } else v
}

internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

// Recognises the two runtime references Flow can place in a command: an input token ${name} and a
// system secret reference secret("NAME"). Both are delivered through the environment (never spliced
// into the script text) — inputs via FLOW_<NAME>, secrets via FLOW_SECRET_<NAME>.
private val FLOW_RUNTIME_TOKEN = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)}|secret\("([A-Za-z_][A-Za-z0-9_]*)"\)""")

/** Deterministic shell environment-variable name that carries a Flow input's runtime value. */
internal fun flowEnvVar(name: String): String = "FLOW_" + name.uppercase().replace(Regex("[^A-Z0-9_]"), "_")

/** The exact shell token a command uses to reference a Flow input value: "$FLOW_NAME" (double-quoted). */
internal fun flowVarToken(name: String): String = "\"$" + flowEnvVar(name) + "\""

/** Environment-variable name that carries a materialised secret; the suffix is the exact secret name. */
internal fun flowSecretEnvVar(name: String): String = "FLOW_SECRET_$name"

/** The shell token a command uses to reference a materialised secret value: "$FLOW_SECRET_NAME". */
internal fun flowSecretToken(name: String): String = "\"$" + flowSecretEnvVar(name) + "\""

private fun runtimeTokenReplacement(m: MatchResult): String =
    if (m.groupValues[1].isNotEmpty()) flowVarToken(m.groupValues[1]) else flowSecretToken(m.groupValues[2])

/**
 * Renders one shell command ARGUMENT that is safe regardless of runtime values.
 * Literal segments are POSIX single-quoted; each `${name}` interpolation token becomes a
 * double-quoted input variable ("$FLOW_NAME") and each `secret("NAME")` becomes a double-quoted
 * secret variable ("$FLOW_SECRET_NAME"). The renderer binds those variables to the target's native
 * parameter/secret mechanism, so values pass through the environment and can never terminate
 * quoting or inject further shell.
 */
internal fun shellArg(value: String): String {
    val out = StringBuilder()
    var last = 0
    for (m in FLOW_RUNTIME_TOKEN.findAll(value)) {
        if (m.range.first > last) out.append(shellQuote(value.substring(last, m.range.first)))
        out.append(runtimeTokenReplacement(m))
        last = m.range.last + 1
    }
    if (last < value.length) out.append(shellQuote(value.substring(last)))
    return if (out.isEmpty()) shellQuote("") else out.toString()
}

/**
 * Substitutes `${name}` and `secret("NAME")` tokens for their environment references inside a whole
 * shell command line (e.g. shell.run), leaving the author's surrounding shell verbatim. Used where
 * the value is an entire command rather than a single argument.
 */
internal fun interpolateShellVars(value: String): String =
    FLOW_RUNTIME_TOKEN.replace(value) { runtimeTokenReplacement(it) }
