package org.flowlang.targets.builtin

import org.flowlang.generators.manifest.TargetJob
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.AdapterManifestLowering.id as sanitizeId

/** Preserves GitHub cancellation and dependency success semantics while translating explicit Flow conditions. */
internal object GitHubJobConditionAuthority {
    /** Additional waits expose ancestor outcomes to GitHub's direct-needs-only context.
     * They are adapter scheduling evidence, never new canonical dependency edges.
     */
    fun materialize(jobs: List<TargetJob>): List<TargetJob> {
        val byId = jobs.associateBy { it.id }
        require(byId.size == jobs.size) { "GitHub job identities must be unique." }
        val cache = mutableMapOf<String, Set<String>>()
        fun ancestors(job: TargetJob, visiting: Set<String>): Set<String> {
            require(job.id !in visiting) { "GitHub job dependencies contain a cycle at '${job.id}'." }
            cache[job.id]?.let { return it }
            return job.dependsOn.flatMap { id ->
                val dependency = requireNotNull(byId[id]) { "GitHub job '${job.id}' has missing dependency '$id'." }
                listOf(id) + ancestors(dependency, visiting + job.id)
            }.toSet().also { cache[job.id] = it }
        }
        return jobs.map { job ->
            val ancestors = ancestors(job, emptySet())
            if (ancestors.none { byId.getValue(it).metadata["condition"] != null }) job
            else {
                val added = ancestors - job.dependsOn.toSet()
                job.copy(dependsOn = job.dependsOn + added,
                    metadata = job.metadata + if (added.isEmpty()) emptyMap() else mapOf(
                        "githubConditionAncestorWaits" to added.joinToString(",")))
            }
        }
    }

    fun expression(job: TargetJob, manifest: TargetManifest): String? {
        val own = job.metadata["condition"]?.let {
            GitHubActionsTargetExpressionTranslator.github(it, manifest.inputs, manifest.compatibility.expressionSupport)
        }
        if (job.metadata["errorHandler"] == "true") {
            val parts = mutableListOf("!cancelled()", "failure()")
            if (!own.isNullOrBlank()) parts += "($own)"
            return parts.joinToString(" && ")
        }

        // Ordering can complete through an unselected branch; workspace availability cannot.
        // The renderer validates transfer topology before reaching this scheduling boundary.
        val workspaceProducers = job.steps.flatMap { it.flatten() }
            .filter { it.type == "adapter-continuity-download" }
            .map { step -> requireNotNull(step.metadata["continuitySourceJob"]) {
                "GitHub workspace download '${step.id}' has no producer identity."
            } }.toSet()
        require(job.dependsOn.containsAll(workspaceProducers)) {
            "GitHub workspace consumers must retain their producer dependencies."
        }
        val strictDependencies = workspaceProducers.map(::sanitizeId).toSet()
        val dependencyKinds = job.dependsOn.map { dependency ->
            val safeDependency = sanitizeId(dependency)
            val predecessor = manifest.jobs.firstOrNull {
                it.id == dependency
            }
            val guard = predecessor?.metadata?.get("condition")
            // Only the typed Boolean contract permits re-evaluating a predecessor's guard.
            val conditional = guard?.takeIf { GitHubBooleanCondition.supports(it,
                manifest.inputs.filter { input -> input.type == "boolean" }.map { input -> input.name }.toSet()) }
            Triple(safeDependency, predecessor?.metadata?.get("providerApprovalPayload") == "true", conditional)
        }
        val needsExplicitScheduling = dependencyKinds.any { (_, isApproval, guard) -> isApproval || guard != null }
        val dependencyConditions = if (needsExplicitScheduling) {
            dependencyKinds.map { (safeDependency, isApproval, guard) ->
                if (safeDependency in strictDependencies) {
                    "needs.$safeDependency.result == 'success'"
                } else if (guard != null) {
                    val translated = GitHubActionsTargetExpressionTranslator.github(guard, manifest.inputs, manifest.compatibility.expressionSupport)
                    "(needs.$safeDependency.result == 'success' || (needs.$safeDependency.result == 'skipped' && !($translated)))"
                } else if (isApproval) {
                    "(needs.$safeDependency.result == 'success' || needs.$safeDependency.result == 'skipped')"
                } else {
                    "needs.$safeDependency.result == 'success'"
                }
            }
        } else {
            emptyList()
        }

        val parts = mutableListOf<String>()
        if (dependencyConditions.isNotEmpty()) parts += "!cancelled()"
        if (!own.isNullOrBlank()) parts += "($own)"
        parts += dependencyConditions
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" && ")
    }
}
