package org.flowlang.targets.builtin

import org.flowlang.generators.manifest.TargetJob
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.AdapterManifestLowering.id as sanitizeId

/** Preserves GitHub cancellation and dependency success semantics while translating explicit Flow conditions. */
internal object GitHubJobConditionAuthority {
    fun expression(job: TargetJob, manifest: TargetManifest): String? {
        val own = job.metadata["condition"]?.let {
            GitHubActionsTargetExpressionTranslator.github(it, manifest.inputs, manifest.compatibility.expressionSupport)
        }
        if (job.metadata["errorHandler"] == "true") {
            val parts = mutableListOf("!cancelled()", "failure()")
            if (!own.isNullOrBlank()) parts += "($own)"
            return parts.joinToString(" && ")
        }

        val dependencyKinds = job.dependsOn.map { dependency ->
            val safeDependency = sanitizeId(dependency)
            val isProviderBackedApproval = manifest.jobs.firstOrNull {
                it.id == dependency
            }?.metadata?.get("providerApprovalPayload") == "true"
            safeDependency to isProviderBackedApproval
        }
        val hasProviderBackedApproval = dependencyKinds.any { (_, isApproval) -> isApproval }
        val dependencyConditions = if (hasProviderBackedApproval) {
            dependencyKinds.map { (safeDependency, isApproval) ->
                if (isApproval) {
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
