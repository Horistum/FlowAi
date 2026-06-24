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
        val steps = plan.nodes.flatMap { it.toTargetSteps(target) }
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
    is TryPlanNode -> listOf(TargetStep(id = sanitizeId(id), name = id, type = "try", children = body.flatMap { it.toTargetSteps(targetName) } + errorHandler.flatMap { it.toTargetSteps(targetName) }, metadata = mapOf("sourceNodeKind" to kind, "errorHandlerCount" to errorHandler.size.toString())))
    is ApprovalNode -> listOf(TargetStep(id = sanitizeId(id), name = id, type = "approval", dependsOn = dependsOn.map(::sanitizeId), params = mapOf("mode" to mode) + (message?.let { mapOf("message" to it) } ?: emptyMap()), metadata = mapOf("sourceNodeKind" to kind, "resultName" to (resultName ?: "")).filterValues { it.isNotBlank() }))
    is DataOpNode -> listOf(TargetStep(id = sanitizeId(id), name = id, type = kind.lowercase(), params = mapOf("target" to (target ?: ""), "detail" to (detail ?: "")).filterValues { it.isNotBlank() }, metadata = mapOf("sourceNodeKind" to kind)))
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
        is TryPlanNode -> { body.forEach { it.toTargetJobs(out, condition, targetName) }; errorHandler.forEach { it.toTargetJobs(out, condition, targetName) } }
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

private fun commandMappingNotes(task: TaskNode): List<TargetMappingNote> = when (task.module to task.action) {
    "standard" to "execute" -> listOf(TargetMappingNote("info", "all", task.id, "semantic.standard-execute", "Standard capability is rendered as an auditable semantic command. Add a module-specific adapter when real external side effects are required."))
    else -> emptyList()
}

internal fun runCommandFor(task: TaskNode, targetName: String = "portable-shell"): String = when (task.module to task.action) {
    "shell" to "run" -> taskParam(task, "command", "echo Flow shell.run missing command", targetName)
    "git" to "checkout" -> {
        val url = taskParam(task, "url", ".", targetName)
        val branch = task.params["branch"]?.let { " --branch ${shellQuote(taskValue(it, targetName))}" } ?: ""
        "git clone$branch ${shellQuote(url)} ."
    }
    "docker" to "build" -> {
        val image = shellQuote(taskParam(task, "image", "image:latest", targetName))
        val path = shellQuote(taskParam(task, "path", ".", targetName))
        val dockerfile = task.params["dockerfile"]?.let { " -f ${shellQuote(taskValue(it, targetName))}" } ?: ""
        "docker build$dockerfile -t $image $path"
    }
    "docker" to "push" -> "docker push ${shellQuote(taskParam(task, "image", "image:latest", targetName))}"
    "helm" to "template" -> {
        val chart = shellQuote(taskParam(task, "chart", ".", targetName))
        val values = task.params["values"]?.let { " -f ${shellQuote(taskValue(it, targetName))}" } ?: ""
        "helm template $chart$values"
    }
    "helm" to "upgrade" -> {
        val release = shellQuote(taskParam(task, "release", task.target, targetName))
        val chart = shellQuote(taskParam(task, "chart", ".", targetName))
        val ns = task.params["namespace"]?.let { " --namespace ${shellQuote(taskValue(it, targetName))}" } ?: ""
        val values = task.params["values"]?.let { " -f ${shellQuote(taskValue(it, targetName))}" } ?: ""
        "helm upgrade --install $release $chart$ns$values"
    }
    "kubernetes" to "deploy" -> {
        val manifest = task.params["manifest"]?.let { taskValue(it, targetName) }
        val ns = shellQuote(taskParam(task, "namespace", "default", targetName))
        if (!manifest.isNullOrBlank()) {
            "kubectl apply -n $ns -f ${shellQuote(manifest)}"
        } else {
            val appParam = task.params["app"] ?: task.params["name"]
                ?: error("kubernetes.deploy without manifest requires an explicit app or name parameter")
            val app = shellQuote(taskValue(appParam, targetName))
            val rawImage = taskParam(task, "image", "", targetName)
            val image = shellQuote(rawImage)
            if (rawImage.isNotBlank()) {
                "kubectl set image deployment/$app *=${image} -n $ns\nkubectl rollout status deployment/$app -n $ns"
            } else {
                "kubectl rollout status deployment/$app -n $ns"
            }
        }
    }
    "kubernetes" to "get" -> {
        val resource = shellQuote(taskParam(task, "resource", "pods", targetName))
        val ns = shellQuote(taskParam(task, "namespace", "default", targetName))
        val selector = task.params["selector"]?.let { " -l ${shellQuote(taskValue(it, targetName))}" } ?: ""
        "kubectl get $resource -n $ns$selector"
    }
    "argocd" to "sync" -> {
        val app = shellQuote(taskParam(task, "app", task.target, targetName))
        val timeout = task.params["timeout"]?.let { " --timeout ${shellQuote(taskValue(it, targetName).removeSuffix("m"))}" } ?: ""
        "argocd app sync $app\nargocd app wait $app --health --sync$timeout"
    }
    "argocd" to "status" -> "argocd app get ${shellQuote(taskParam(task, "app", task.target, targetName))} -o json"
    "rest" to "call" -> {
        val method = taskParam(task, "method", "GET", targetName)
        val path = taskParam(task, "path", "/", targetName)
        val body = task.params["body"]?.let { " --data ${shellQuote(taskValue(it, targetName))}" } ?: ""
        "curl --fail --show-error --silent -X ${shellQuote(method)} ${shellQuote(path)}$body"
    }
    "database" to "query" -> {
        val sql = taskParam(task, "sql", "select 1", targetName)
        "psql --set ON_ERROR_STOP=1 -c ${shellQuote(sql)}"
    }
    "file" to "write" -> {
        val path = shellQuote(taskParam(task, "path", "flow-output.txt", targetName))
        val content = shellQuote(taskParam(task, "content", "", targetName))
        "printf %s $content > $path"
    }
    "notify" to "send" -> {
        val subject = shellQuote("Flow notification: ${taskParam(task, "subject", task.id, targetName)}")
        val body = shellQuote(taskParam(task, "body", "Flow notification from ${task.id}", targetName))
        "printf %s $body | mail -s $subject ${shellQuote(taskParam(task, "to", "team@example.com", targetName))} || echo ${shellQuote("Notification adapter is not configured")}"
    }
    "standard" to "rollback" -> "echo ${shellQuote("Flow rollback requested for ${taskParam(task, "flow", task.id, targetName)}. Configure a target-specific rollback adapter for production.")}"
    "standard" to "execute" -> standardExecuteCommand(task, targetName)
    else -> "echo ${shellQuote("Flow executes ${task.module}.${task.action} on ${task.target}")}"
}

private fun taskParam(task: TaskNode, name: String, default: String, targetName: String): String =
    task.params[name]?.let { taskValue(it, targetName) } ?: targetInterpolated(default, targetName)

private fun taskValue(raw: String, targetName: String): String = targetInterpolated(unquote(raw), targetName)


private fun standardExecuteCommand(task: TaskNode, targetName: String): String {
    val capability = taskParam(task, "capability", "custom", targetName)
    val op = taskParam(task, "operation", capability, targetName)
    fun p(name: String, default: String = ""): String = taskParam(task, name, default, targetName)
    fun echo(message: String): String = "echo ${shellQuote(message)}"

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

private fun targetInterpolated(value: String, targetName: String): String =
    Regex("""\$\{([A-Za-z_][A-Za-z0-9_.-]*)}""").replace(value) { match ->
        val name = match.groupValues[1]
        when (targetName) {
            "jenkins" -> "\${params.$name}"
            "github-actions" -> "\${{ inputs.$name }}"
            "tekton" -> "$(params.$name)"
            else -> "\${$name}"
        }
    }

private fun combineConditions(a: String?, b: String): String = if (a.isNullOrBlank()) b else "($a) and ($b)"

private fun mapOfNotNull(vararg pairs: Pair<String, String?>): Map<String, String> = pairs.mapNotNull { (k, v) -> v?.takeIf { it.isNotBlank() }?.let { k to it } }.toMap()

internal fun sanitizeId(value: String): String = value.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').ifBlank { "flow-job" }

internal fun unquote(value: String): String {
    val v = value.trim()
    return if (v.length >= 2 && v.first() == '"' && v.last() == '"') {
        v.substring(1, v.length - 1).replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t")
    } else v
}

internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
