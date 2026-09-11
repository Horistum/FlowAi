package org.flowlang.conformance

/** Binds module closure to reviewed, immutable PR and actual-main evidence, not a future candidate run. */
internal object CompilerModuleAcceptance {
    const val EVIDENCE = ".flow-agent/evidence/compiler-module-acceptance.json"
    const val INVENTORY = ".flow-agent/architecture/compiler-adapter-boundary-inventory.yaml"

    fun errors(snapshot: WorkflowSemanticsRecoveryLifecycleSnapshot): List<String> = buildList {
        val work = snapshot.successorWorkPackage
        val evidence = snapshot.moduleAcceptanceEvidence
        val reference = section(work["acceptanceEvidence"])
        if (reference["path"] != EVIDENCE || !digest(reference["sha256"], 64) ||
            reference["sha256"] != snapshot.moduleAcceptanceSha256 || evidence.isEmpty()
        ) add("Module acceptance requires the exact reviewed evidence file and its SHA-256.")
        if (evidence["schemaVersion"] != 1 || evidence["status"] != "implementation-acceptance-verified-on-actual-merged-main") {
            add("Module acceptance requires independently recorded implementation and merged-main evidence.")
        }
        val revisions = section(evidence["revisions"])
        val head = section(revisions["exactPrHead"])
        val merge = section(revisions["originalSyntheticMerge"])
        val main = section(revisions["actualMergedMain"])
        val lifecycle = section(work["lifecycle"])
        val integrated = section(lifecycle["integratedBoundary"])
        val completion = section(lifecycle["completionBoundary"])
        addAll(WorkflowSemanticsRecoveryLifecycle.boundaryErrors("AR-03 integratedBoundary", integrated))
        val expectedIntegrated = mapOf(
            "workflowRunId" to head["workflowRunId"], "workflowRunNumber" to head["workflowRunNumber"],
            "exactHead" to head["commit"], "syntheticMergeCandidate" to merge["commit"],
            "exactHeadJobId" to head["jobId"], "mergeCandidateJobId" to merge["jobId"],
            "kotlinTestsPerJob" to section(head["junit"])["tests"], "failures" to 0, "errors" to 0, "skipped" to 0
        )
        addAll(matchingFields("AR-03 integratedBoundary", integrated, expectedIntegrated))
        val mainIsolation = section(section(evidence["isolation"])["actualMergedMain"])
        val isolationReceipt = section(mainIsolation["receipt"])
        val expectedCompletion = mapOf(
            "status" to "passed", "conclusion" to "success", "event" to "push",
            "mergedPullRequest" to evidence["pullRequest"], "mainCommit" to main["commit"],
            "sourceTree" to isolationReceipt["tree"], "implementationHead" to head["commit"],
            "workflowRunId" to main["workflowRunId"], "workflowRunNumber" to main["workflowRunNumber"],
            "exactHeadJobId" to main["jobId"], "kotlinTests" to section(main["junit"])["tests"],
            "toolingTests" to section(evidence["tooling"])["pythonTests"],
            "conformanceChecks" to section(main["conformance"])["passed"],
            "failures" to 0, "errors" to 0, "skipped" to 0, "evidence" to EVIDENCE
        )
        addAll(matchingFields("AR-03 completionBoundary", completion, expectedCompletion))
        if (positive(completion["isolationJobId"]) == null || completion["isolationJobId"] == main["jobId"] ||
            !digest(completion["sourceTree"], 40) || positive(evidence["pullRequest"]) == null ||
            listOf("syntheticMergeCandidate", "mergeCandidateJobId").any(completion::containsKey)
        ) add("Module completion must distinguish the actual push/isolation jobs from synthetic-merge evidence.")
        if (listOf("activationBoundary", "implementationBoundary", "offlineBoundary").any { name ->
            section(lifecycle[name])["workflowRunId"] in setOf(head["workflowRunId"], main["workflowRunId"])
        } || head["workflowRunId"] == main["workflowRunId"] || head["workflowRunId"] != merge["workflowRunId"] ||
            head["workflowRunNumber"] != merge["workflowRunNumber"] ||
            listOf(head, merge, main).map { it["jobId"] }.distinct().size != 3 ||
            listOf(head, merge, main).map { it["commit"] }.distinct().size != 3
        ) add("Module acceptance cannot reuse activation/kernel evidence or confuse PR and post-merge revisions.")
        val parents = section(evidence["integration"])["mergeParents"] as? List<*>
        if (parents?.size != 2 || parents.distinct().size != 2 || parents.any { !digest(it, 40) } || head["commit"] !in parents) {
            add("Accepted main must record the integrated implementation HEAD as an actual merge parent.")
        }
        val baseline = ((work["implementationSlices"] as? List<*>).orEmpty().map(::section)
            .singleOrNull { it["id"] == "AR-03D" })?.get("preservedBaselineTestIdentities")
        val headTests = section(head["junit"])
        for ((label, revision) in listOf("head" to head, "merge" to merge, "main" to main)) {
            val junit = section(revision["junit"])
            val checks = section(revision["conformance"])
            val count = positive(junit["tests"])
            if (!digest(revision["commit"], 40) || revision["jobConclusion"] != "success" ||
                listOf("workflowRunId", "workflowRunNumber", "jobId").any { positive(revision[it]) == null } ||
                count == null || positive(baseline) == null || count < positive(baseline)!! ||
                !same(count, junit["uniqueIdentities"]) || !same(count, headTests["tests"]) ||
                !digest(junit["identitiesSha256"], 64) || junit["identitiesSha256"] != headTests["identitiesSha256"] ||
                listOf("failures", "errors", "skipped").any { !same(junit[it], 0) } ||
                positive(checks["passed"]) == null || !same(checks["failed"], 0)
            ) add("Module $label evidence requires successful complete tests, preserved identities and conformance.")
        }
        for ((label, revision) in listOf("prHeadAndOriginalMerge" to head, "actualMergedMain" to main)) {
            val isolation = section(section(evidence["isolation"])[label])
            val receipt = section(isolation["receipt"])
            val proofs = section(isolation["proofs"])
            if (receipt["revision"] != revision["commit"] || receipt["run"] != revision["workflowRunId"].toString() ||
                !digest(receipt["tree"], 40) || proofs.keys != setOf("kernel", "compiler", "adapter", "product") ||
                proofs.values.map(::section).any { it["status"] != "passed" || positive(it["tests"]) == null ||
                    !same(it["failuresErrorsSkipped"], 0) }
            ) add("Module $label evidence requires all four actual physical isolation proofs for its exact tree.")
        }
        val decision = section(work["completionDecision"])
        if (decision["status"] != "complete" || decision["completedSlice"] != "AR-03D" ||
            decision["closesFindings"] != listOf("F-10") || decision["remainingFindings"] != listOf("F-20") ||
            decision["containedFindings"] != listOf("F-20") || decision["deferredClosureOwner"] != "AR-07" ||
            decision["integratedReadiness"] != "accepted-on-merged-main" || decision["nextItem"] != "AR-04" ||
            decision["nextItemActivationState"] != "not-activated"
        ) add("Module completion closes only F-10; F-20 remains AR-07-owned and successor activation is separately owned.")
        val inventory = section(snapshot.moduleBoundaryInventory["integratedDecision"])
        if (inventory["status"] != "accepted-on-merged-main" || inventory["closesFindings"] != listOf("F-10") ||
            inventory["containedFindings"] != listOf("F-20") || inventory["deferredClosureOwner"] != "AR-07" ||
            inventory["acceptanceEvidence"] != EVIDENCE || inventory["nextItem"] != "AR-04" ||
            inventory["nextItemActivation"] != "not-activated"
        ) add("Reviewed compatibility inventory must retain its exact acceptance and AR-07-owned containment.")
        val findings = (snapshot.recovery["findingRegister"] as? List<*>).orEmpty().map(::section)
        val f10 = findings.singleOrNull { it["id"] == "F-10" }
        val f20 = findings.singleOrNull { it["id"] == "F-20" }
        if (f10?.get("status") != "closed" || f10["closureMilestone"] != "AR-03" || f10["closureEvidence"] != EVIDENCE ||
            f20?.get("status") != "contained" || f20["containmentMilestone"] != "AR-03" || f20["closureMilestone"] != "AR-07"
        ) add("Finding coverage must close F-10 with accepted evidence and retain F-20 containment without retirement.")
    }

    internal fun section(value: Any?): Map<*, *> = value as? Map<*, *> ?: emptyMap<Any, Any>()
    internal fun positive(value: Any?): Long? = when (value) {
        is Int -> value.toLong().takeIf { it > 0 }
        is Long -> value.takeIf { it > 0 }
        else -> null
    }
    internal fun digest(value: Any?, length: Int): Boolean = value is String &&
        value.matches(Regex("[0-9a-f]{$length}")) && value.toSet().size > 1
    internal fun same(left: Any?, right: Any?): Boolean = when {
        left == null || right == null -> false
        left is Int && right is Long -> left.toLong() == right
        left is Long && right is Int -> left == right.toLong()
        else -> left == right
    }
    internal fun matchingFields(owner: String, actual: Map<*, *>, expected: Map<String, Any?>): List<String> =
        expected.filter { (key, value) -> !same(actual[key], value) }.keys.map {
            "$owner.$it must match the independently recorded acceptance evidence."
        }
}
