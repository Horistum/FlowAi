package org.flowlang.targets.builtin

import org.flowlang.generators.manifest.TargetJob
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.sanitizeId

/** Preserves GitHub cancellation semantics while translating explicit Flow conditions. */
internal object GitHubJobConditionAuthority {
    fun expression(job: TargetJob, manifest: TargetManifest): String? {
        val own = job.metadata["condition"]?.let {
            TargetExpressionTranslator.github(it, manifest.inputs, manifest.compatibility.expressionSupport)
        }
        if (job.metadata["errorHandler"] == "true") {
            val parts = mutableListOf("!cancelled()", "failure()")
            if (!own.isNullOrBlank()) parts += "($own)"
            return parts.joinToString(" && ")
        }

        val dependencyConditions = job.dependsOn.mapNotNull { dependency ->
            val safeDependency = sanitizeId(dependency)
            val isProviderBackedApproval = manifest.jobs.firstOrNull {
                sanitizeId(it.id) == safeDependency
            }?.metadata?.get("providerApprovalPayload") == "true"
            if (isProviderBackedApproval) {
                "(needs.$safeDependency.result == 'success' || needs.$safeDependency.result == 'skipped')"
            } else {
                null
            }
        }

        val parts = mutableListOf<String>()
        if (dependencyConditions.isNotEmpty()) parts += "!cancelled()"
        if (!own.isNullOrBlank()) parts += "($own)"
        parts += dependencyConditions
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" && ")
    }
}