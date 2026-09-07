package org.flowlang.conformance

import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.generators.manifest.TargetStructuralProjectionKind
import org.flowlang.generators.manifest.sanitizeId
import org.flowlang.planner.WorkflowFailurePolicy

/**
 * Checks the simple, top-level handler fixtures used by the AR-02 target matrix.
 * Expected meaning comes from the authorized public policy, never from metadata.
 * Jenkins projects one structured boundary; job-oriented providers annotate each
 * handler job. Neither representation may be replaced by a matching marker on an
 * unrelated node. This observer does not grant target execution authorization.
 */
internal object Ar02FailureProjectionEvidence {
    fun errors(manifest: TargetManifest, expected: WorkflowFailurePolicy): List<String> = buildList {
        val handler = expected.handler
        if (handler == null) {
            add("Failure projection fixture requires an authorized handler region.")
            return@buildList
        }
        val expectedMetadata = mapOf(
            "workflowFailurePolicy" to "true",
            "workflowFailureDisposition" to expected.disposition.name,
            "workflowFailureErrorBinding" to handler.entry.errorBinding,
            "workflowFailurePriorSuccessfulValuesAvailable" to
                handler.entry.priorSuccessfulValuesAvailable.toString()
        )
        fun checkMetadata(subject: String, metadata: Map<String, String>) {
            expectedMetadata.forEach { (key, value) ->
                if (metadata[key] != value) add("$subject does not preserve canonical $key=$value.")
            }
        }
        fun visit(steps: List<TargetStep>): List<TargetStep> =
            steps.flatMap { listOf(it) + visit(it.children) }

        val markedSteps = manifest.jobs.flatMap { visit(it.steps) }
            .filter { it.metadata["workflowFailurePolicy"] == "true" }
        val markedJobs = manifest.jobs.filter { it.metadata["workflowFailurePolicy"] == "true" }
        val expectedHandlerIds = handler.nodeIds.map(::sanitizeId)
        if (expectedHandlerIds.isEmpty() || expectedHandlerIds.distinct().size != expectedHandlerIds.size) {
            add("Failure projection requires non-empty, unambiguous handler node identities.")
        }

        if (manifest.target == "jenkins") {
            val boundary = markedSteps.singleOrNull()
            if (markedJobs.isNotEmpty() || boundary == null) {
                add("Jenkins must preserve exactly one step-owned workflow failure boundary.")
                return@buildList
            }
            checkMetadata("Jenkins boundary", boundary.metadata)
            if (boundary.id != sanitizeId(handler.id) ||
                boundary.type != TargetStructuralProjectionKind.ERROR_BOUNDARY.stepType
            ) add("Jenkins failure boundary identity or structural kind differs from canonical policy.")
            val body = boundary.children.singleOrNull { it.metadata["tryRole"] == "body" }
            val failure = boundary.children.singleOrNull { it.metadata["tryRole"] == "errorHandler" }
            if (boundary.children.size != 2 || body == null || body.children.isEmpty() || failure == null) {
                add("Jenkins must preserve separate protected-body and handler regions.")
            }
            if (failure?.children?.map { it.id } != expectedHandlerIds) {
                add("Jenkins handler membership differs from the authorized handler region.")
            }
        } else {
            if (markedSteps.isNotEmpty() || markedJobs.map { it.id }.sorted() != expectedHandlerIds.sorted()) {
                add("Job-owned failure policy must cover exactly the authorized handler jobs.")
            }
            val protectedJobs = manifest.jobs.filterNot { it.id in expectedHandlerIds }.map { it.id }.toSet()
            if (protectedJobs.isEmpty()) add("Job-owned handler lost its protected workflow body.")
            markedJobs.forEach { job ->
                checkMetadata("Handler job '${job.id}'", job.metadata)
                if (job.metadata["errorHandler"] != "true") {
                    add("Handler job '${job.id}' lost its failure-role marker.")
                }
                if (!job.dependsOn.containsAll(protectedJobs) || job.id in job.dependsOn) {
                    add("Handler job '${job.id}' is not guarded by the protected workflow jobs.")
                }
            }
        }
    }
}
