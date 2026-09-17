package org.flowlang.conformance

import org.flowlang.conformance.CompilerModuleAcceptance.matchingFields
import org.flowlang.conformance.CompilerModuleAcceptance.section

/** Owns AR-04 slice progression without rewriting accepted predecessor history. */
internal object LanguageContractIntegrityLifecycle {
    const val WORK_PACKAGE = ".flow-agent/work-packages/language-contract-type-identity-integrity.yaml"
    const val ACTIVATION_EVIDENCE = ".flow-agent/evidence/language-activation-acceptance.json"
    private const val ACTIVATION_SHA256 = "81afd6901fc3de408a2d53e7bde8879a518b171f17fcf354043e1199dbde8758"
    const val SCHEMA_EVIDENCE = ".flow-agent/evidence/schema-integrity-acceptance.json"
    private const val SCHEMA_SHA256 = "0576dfe6ac1b8713be5ecca683f30980b7840e27deeae1bba41fddedb162225c"
    const val SYSTEM_IDENTITY_EVIDENCE = ".flow-agent/evidence/system-identity-acceptance.json"
    private const val SYSTEM_IDENTITY_SHA256 = "1f304dbfd544df93ea906086d935cff0c41067f9649f7d76cf8a8ed78a51b075"
    private val findings = listOf("F-07", "F-12", "F-13", "F-14", "F-21")
    private val sliceIds = listOf("AR-04A", "AR-04B", "AR-04C", "AR-04D", "AR-04E", "AR-04F")
    private val requiredChecks = listOf("compile-test-conformance", "merge-candidate-compile-test-conformance")
    private val ar04aAcceptance = mapOf<String, Any?>(
        "source" to "current-revision-ci",
        "requiredChecks" to requiredChecks,
        "pullRequest" to 178,
        "base" to "0a863eb10f60a64945f9657b0fcf090714ab543a",
        "head" to "5d19cef68c0cf84c26a9bfd4004c5d7579ec43c0",
        "syntheticMerge" to "eca3cc2976b4eb8fd29ac37114ace08af1efc719",
        "mergedMain" to "9053658838dd7c06622717a34007c67c5cb1c38c",
        "sourceTree" to "54bcfd11fc641bb1357b842af931cac841cee436",
        "workflowRunId" to 34493567009L,
        "workflowRunNumber" to 3265,
        "exactHeadJobId" to 102929485718L,
        "mergeCandidateJobId" to 102929485758L,
        "kotlinTests" to 1590,
        "toolingTests" to 151,
        "conformanceChecks" to 246,
        "failures" to 0,
        "errors" to 0,
        "skipped" to 0
    )

    fun errors(snapshot: WorkflowSemanticsRecoveryLifecycleSnapshot): List<String> = buildList {
        val work = snapshot.integrityWorkPackage
        val predecessor = snapshot.successorWorkPackage
        val authorization = section(work["authorization"])
        if (work["version"] != "AR-04" || work["status"] != "active" || authorization["status"] != "active" ||
            authorization["predecessor"] != "AR-03" ||
            authorization["strategicSource"] != ".flow-agent/roadmap-architecture-recovery.yaml#AR-04" ||
            predecessor["status"] != "complete"
        ) add("Language integrity requires its own active work package and completed AR-03 predecessor.")
        val completion = section(section(predecessor["lifecycle"])["completionBoundary"])
        val baseline = section(work["verifiedBaseline"])
        val baselineFields = listOf("mergedPullRequest", "mainCommit", "sourceTree", "workflowRunId", "workflowRunNumber",
            "exactHeadJobId", "isolationJobId", "kotlinTests", "toolingTests", "conformanceChecks",
            "failures", "errors", "skipped", "evidence")
        addAll(matchingFields("Language integrity baseline", baseline, baselineFields.associateWith { completion[it] }))
        if (authorization["mainAtActivation"] != baseline["mainCommit"] || completion["status"] != "passed" ||
            completion["conclusion"] != "success"
        ) add("Language integrity activation must use the independently accepted merged main.")
        val milestones = (snapshot.recovery["milestones"] as? List<*>).orEmpty().map(::section)
        val milestone = milestones.singleOrNull { it["id"] == "AR-04" }
        if (milestone?.get("status") != "active" || milestone["workPackage"] != WORK_PACKAGE ||
            milestone["dependsOn"] != listOf("AR-01", "AR-03") || milestone["closesFindings"] != findings
        ) add("Language integrity roadmap must preserve its explicit dependencies and five finding owners.")
        val post = section(snapshot.postToolchain["currentDecision"])
        val state = section(snapshot.postToolchain["recoveryRoadmap"])
        val recovery = section(snapshot.recovery["currentDecision"])
        val sequence = (snapshot.postToolchain["sequence"] as? List<*>).orEmpty().map(::section)
        val recoverySequence = sequence.singleOrNull { it["id"] == "ARCHITECTURE-RECOVERY" }
        if (state["activeItem"] != "AR-04" || state["nextItem"] != "AR-04" || state["activeWorkPackage"] != WORK_PACKAGE ||
            recoverySequence?.get("completedItem") != "AR-03" || recoverySequence["activeItem"] != "AR-04" ||
            recoverySequence["nextItem"] != "AR-04" || recoverySequence["activationState"] != "active" ||
            recoverySequence["workPackage"] != WORK_PACKAGE
        ) add("Post-toolchain recovery pointers must agree on language integrity activation.")
        if (post["pausedItem"] != "EF-09" || recovery["preemptedItem"] != "EF-09" ||
            sequence.singleOrNull { it["id"] == "EF-09" }?.get("status") != "paused" ||
            milestones.filter { it["id"] in setOf("AR-05", "AR-06", "AR-07") }.let {
                it.size != 3 || it.any { milestone -> milestone["status"] != "planned" }
            }
        ) add("Language integrity cannot resume EF-09 or activate later recovery milestones.")
        val release = section(snapshot.release["roadmapState"])
        if (release["activeRecoveryWorkPackage"] != WORK_PACKAGE ||
            release["completedRecoveryWorkPackage"] != CompilerModuleExtractionLifecycle.WORK_PACKAGE
        ) add("Release metadata must distinguish accepted modules from active language integrity.")
        addAll(matchingFields("Release recovery acceptance", section(snapshot.release["recoveryAcceptance"]), mapOf(
            "completedItem" to "AR-03", "evidence" to CompilerModuleAcceptance.EVIDENCE,
            "acceptedMain" to completion["mainCommit"], "workflowRunId" to completion["workflowRunId"],
            "successorCandidate" to "AR-04", "candidateValidation" to "current-revision-ci-required"
        )))

        val slices = (work["implementationSlices"] as? List<*>).orEmpty().map(::section)
        val structurallyValid = slices.map { it["id"] } == sliceIds &&
            slices.map { it["ownsFindings"] } == findings.map { listOf(it) } + listOf(emptyList<String>())
        if (!structurallyValid) {
            add("Language integrity slice identities and finding ownership must remain stable.")
        }

        val lifecycle = section(work["lifecycle"])
        val boundaries = listOf("activationBoundary", "implementationBoundary", "validationBoundary", "completionBoundary")
        when (work["selectedSlice"]) {
            "AR-04A" -> validateAr04aPhase(snapshot, work, slices, lifecycle, boundaries, this)
            "AR-04B" -> validateAr04bPhase(snapshot, work, slices, lifecycle, boundaries, this)
            "AR-04C" -> validateAr04cPhase(snapshot, work, slices, lifecycle, boundaries, this)
            "AR-04D" -> validateAr04dPhase(snapshot, work, slices, lifecycle, boundaries, this)
            else -> add("Language integrity may advance only through an explicitly supported AR-04 slice transition.")
        }

        val decision = section(work["completionDecision"])
        if (decision["status"] != "not-complete" || decision["closesFindings"] != emptyList<String>() ||
            decision["remainingFindings"] != findings || decision["nextItem"] != "AR-05" ||
            decision["nextItemActivationState"] != "not-activated"
        ) add("Language integrity activation cannot close any finding or activate AR-05.")
        val register = (snapshot.recovery["findingRegister"] as? List<*>).orEmpty().map(::section)
        findings.forEach { id ->
            val finding = register.singleOrNull { it["id"] == id }
            if (finding?.get("closureMilestone") != "AR-04" || finding["status"] in setOf("closed", "complete", "completed")) {
                add("Language integrity finding $id must remain open and AR-04-owned during activation.")
            }
        }
    }

    private fun validateAr04aPhase(
        snapshot: WorkflowSemanticsRecoveryLifecycleSnapshot,
        work: Map<*, *>,
        slices: List<Map<*, *>>,
        lifecycle: Map<*, *>,
        boundaries: List<String>,
        errors: MutableList<String>
    ) {
        val implemented = slices.firstOrNull()?.get("status") == "implemented"
        if (work["nextSlice"] != "AR-04B" || slices.firstOrNull()?.get("status") !in setOf("selected", "implemented") ||
            slices.drop(1).any { it["status"] != "planned" }
        ) errors += "Language integrity activation selects duplicate-declaration work only; implementation needs a separate green activation."
        if (implemented) {
            errors += activationErrors(snapshot, section(lifecycle["activationBoundary"]))
            if (section(slices.first()["acceptance"]) != mapOf(
                    "source" to "current-revision-ci", "requiredChecks" to requiredChecks)) {
                errors += "Implemented duplicate-declaration work requires its own current-revision CI acceptance."
            }
        }
        val pending = if (implemented) boundaries.drop(1) else boundaries
        if (lifecycle.keys != boundaries.toSet() || pending.any {
            section(lifecycle[it]) != mapOf("status" to "pending")
        }) errors += "Language integrity activation cannot publish its own future receipt or borrow predecessor success."
    }

    private fun validateAr04bPhase(
        snapshot: WorkflowSemanticsRecoveryLifecycleSnapshot,
        work: Map<*, *>,
        slices: List<Map<*, *>>,
        lifecycle: Map<*, *>,
        boundaries: List<String>,
        errors: MutableList<String>
    ) {
        val ar04a = slices.getOrNull(0).orEmpty()
        val ar04b = slices.getOrNull(1).orEmpty()
        val ar04bStatus = ar04b["status"]
        if (work["nextSlice"] != "AR-04C" || ar04a["status"] != "complete" ||
            ar04bStatus !in setOf("selected", "implemented") || slices.drop(2).any { it["status"] != "planned" }
        ) {
            errors += "AR-04B requires completed duplicate-declaration work and may select only closed schema type/default integrity."
        }

        errors += activationErrors(snapshot, section(lifecycle["activationBoundary"]))
        val acceptedA = section(ar04a["acceptance"])
        errors += matchingFields("AR-04A acceptance", acceptedA, ar04aAcceptance)
        if (acceptedA.keys != ar04aAcceptance.keys) {
            errors += "AR-04A acceptance must contain only the immutable accepted PR receipt fields."
        }

        if (ar04bStatus == "implemented") {
            val expected = mapOf("source" to "current-revision-ci", "requiredChecks" to requiredChecks)
            if (section(ar04b["acceptance"]) != expected) {
                errors += "Implemented schema-integrity work requires its own current-revision CI acceptance."
            }
        }

        if (lifecycle.keys != boundaries.toSet() || boundaries.drop(1).any {
            section(lifecycle[it]) != mapOf("status" to "pending")
        }) {
            errors += "AR-04B cannot publish a future receipt for milestone-wide implementation, validation or completion success."
        }
    }

    private fun validateAr04cPhase(
        snapshot: WorkflowSemanticsRecoveryLifecycleSnapshot,
        work: Map<*, *>,
        slices: List<Map<*, *>>,
        lifecycle: Map<*, *>,
        boundaries: List<String>,
        errors: MutableList<String>
    ) {
        // Validate historical B independently; accepting it does not rewrite A or activation.
        val pendingAcceptance = mapOf("source" to "current-revision-ci", "requiredChecks" to requiredChecks)
        val historical = slices.mapIndexed { index, slice -> when (index) {
            1 -> slice + mapOf("status" to "implemented", "acceptance" to pendingAcceptance)
            2 -> (slice - "acceptance") + ("status" to "planned")
            else -> slice
        } }
        validateAr04bPhase(snapshot, work + ("nextSlice" to "AR-04C"), historical, lifecycle, boundaries, errors)
        if (work["nextSlice"] != "AR-04D" || slices.getOrNull(1)?.get("status") != "complete" ||
            slices.getOrNull(2)?.get("status") != "implemented" || slices.drop(3).any { it["status"] != "planned" } ||
            section(slices.getOrNull(2)?.get("acceptance")) != pendingAcceptance ||
            slices.drop(3).any { it.containsKey("acceptance") }
        ) errors += "System-contract integrity requires accepted AR-04B, its own current-revision CI and no later-slice activation."

        val evidence = snapshot.schemaIntegrityEvidence
        val jobs = section(evidence["jobs"])
        val junit = section(evidence["junit"])
        val head = section(junit["exactHead"])
        val merge = section(junit["mergeCandidate"])
        if (snapshot.schemaIntegritySha256 != SCHEMA_SHA256 || evidence["status"] != "passed" ||
            evidence["repository"] != "Horistum/FlowAi" || !CompilerModuleAcceptance.same(evidence["pullRequest"], 179) ||
            evidence["base"] != ar04aAcceptance["mergedMain"] ||
            evidence["head"] == evidence["mergedMain"] ||
            listOf(head, merge).any { result ->
                !CompilerModuleAcceptance.same(result["tests"], 1600) ||
                    !CompilerModuleAcceptance.same(result["uniqueIdentities"], 1600) ||
                    listOf("failures", "errors", "skipped").any { !CompilerModuleAcceptance.same(result[it], 0) }
            } || head["identitiesSha256"] != merge["identitiesSha256"]
        ) errors += "AR-04B acceptance requires its independently inspected, byte-pinned complete CI evidence."
        val expected = mapOf(
            "source" to "current-revision-ci", "requiredChecks" to requiredChecks,
            "pullRequest" to evidence["pullRequest"], "base" to evidence["base"], "head" to evidence["head"],
            "mergedMain" to evidence["mergedMain"], "sourceTree" to evidence["sourceTree"],
            "workflowRunId" to evidence["workflowRunId"], "exactHeadJobId" to jobs["exactHead"],
            "mergeCandidateJobId" to jobs["mergeCandidate"], "isolationJobId" to jobs["physicalIsolation"],
            "kotlinTests" to 1600, "toolingTests" to 151, "conformanceChecks" to 246,
            "failures" to 0, "errors" to 0, "skipped" to 0,
            "evidence" to SCHEMA_EVIDENCE, "evidenceSha256" to SCHEMA_SHA256
        )
        val receipt = section(slices.getOrNull(1)?.get("acceptance"))
        errors += matchingFields("AR-04B acceptance", receipt, expected)
        if (receipt.keys != expected.keys) errors += "AR-04B acceptance has missing or unsupported receipt fields."
    }

    private fun validateAr04dPhase(
        snapshot: WorkflowSemanticsRecoveryLifecycleSnapshot,
        work: Map<*, *>,
        slices: List<Map<*, *>>,
        lifecycle: Map<*, *>,
        boundaries: List<String>,
        errors: MutableList<String>
    ) {
        // Replay the accepted predecessor phase without widening its authority to the new work.
        val pending = mapOf("source" to "current-revision-ci", "requiredChecks" to requiredChecks)
        val historical = slices.mapIndexed { index, slice -> when (index) {
            2 -> slice + mapOf("status" to "implemented", "acceptance" to pending)
            3 -> (slice - "acceptance") + ("status" to "planned")
            else -> slice
        } }
        validateAr04cPhase(snapshot, work + ("nextSlice" to "AR-04D"), historical, lifecycle, boundaries, errors)
        if (work["nextSlice"] != "AR-04E" || slices.getOrNull(2)?.get("status") != "complete" ||
            slices.getOrNull(3)?.get("status") != "implemented" ||
            section(slices.getOrNull(3)?.get("acceptance")) != pending ||
            slices.drop(4).any { it["status"] != "planned" || it.containsKey("acceptance") }
        ) errors += "Semantic identity requires accepted AR-04C, its own current-revision CI and no later-slice activation."

        val evidence = snapshot.systemIdentityEvidence
        if (snapshot.systemIdentitySha256 != SYSTEM_IDENTITY_SHA256) {
            errors += "AR-04C acceptance requires its independently inspected, byte-pinned complete CI evidence."
        }
        errors += matchingFields("AR-04C independent evidence", evidence, mapOf(
            "version" to 1, "status" to "passed", "repository" to "Horistum/FlowAi", "pullRequest" to 184,
            "base" to "e1574def2dbe8ef82dd2903eb551bd4c61807770",
            "head" to "c9fa14d48abb0e09b6e6860443961e00cb9cdc34",
            "syntheticMerge" to "837bc45474f2e1f8a451fb01349fe11ae21be198",
            "mergedMain" to "1d125c154394cd8d6e1083eddd20c6b217a89bb4",
            "sourceTree" to "d86118179709fde20d8c91b1260b51c52c1116d6",
            "workflowRunId" to 35175006081L, "workflowRunNumber" to 3285, "toolingTests" to 151
        ))
        val jobs = section(evidence["jobs"])
        errors += matchingFields("AR-04C CI jobs", jobs, mapOf("exactHead" to 105055907055L,
            "mergeCandidate" to 105055906949L, "physicalIsolation" to 105054706708L))
        val post = section(evidence["postMerge"])
        errors += matchingFields("AR-04C post-merge evidence", post, mapOf(
            "workflowRunId" to 35176517700L, "workflowRunNumber" to 3286,
            "head" to evidence["mergedMain"], "status" to "completed", "conclusion" to "success",
            "exactHeadJobId" to 105061032303L, "isolationJobId" to 105059361646L
        ))
        listOf("exactHead", "mergeCandidate", "postMerge").forEach { boundary ->
            errors += matchingFields("AR-04C $boundary tests", section(section(evidence["junit"])[boundary]), mapOf(
                "tests" to 1665, "suites" to 293, "uniqueIdentities" to 1665,
                "failures" to 0, "errors" to 0, "skipped" to 0,
                "identitiesSha256" to "fef33ec05256eda3fe467a8fd3637fa2ba8d7a08da0b91613bf32821dd3a862b"
            ))
            errors += matchingFields("AR-04C $boundary conformance", section(section(evidence["conformance"])[boundary]), mapOf(
                "passed" to 249, "failed" to 0,
                "orderedIdentitiesSha256" to "88c006b6d35f78d50da4fc14824728dc74f6d1598222f5b2c2f92281ebec8792"
            ))
        }
        listOf("pullRequest", "postMerge").forEach { boundary ->
            errors += matchingFields("AR-04C $boundary isolation", section(section(evidence["physicalIsolation"])[boundary]), mapOf(
                "status" to "passed", "allListedInputsMatchSourceTree" to true,
                "proofs" to listOf("adapter-isolation/proof.json", "compiler-isolation/proof.json", "kernel-isolation/proof.json", "product-isolation/proof.json")
            ))
        }
        val expected = mapOf(
            "source" to "current-revision-ci", "requiredChecks" to requiredChecks,
            "pullRequest" to evidence["pullRequest"], "base" to evidence["base"], "head" to evidence["head"],
            "syntheticMerge" to evidence["syntheticMerge"], "mergedMain" to evidence["mergedMain"], "sourceTree" to evidence["sourceTree"],
            "workflowRunId" to evidence["workflowRunId"], "workflowRunNumber" to evidence["workflowRunNumber"],
            "exactHeadJobId" to jobs["exactHead"], "mergeCandidateJobId" to jobs["mergeCandidate"], "isolationJobId" to jobs["physicalIsolation"],
            "postMergeWorkflowRunId" to post["workflowRunId"], "postMergeJobId" to post["exactHeadJobId"],
            "postMergeIsolationJobId" to post["isolationJobId"],
            "kotlinTests" to 1665, "toolingTests" to 151, "conformanceChecks" to 249,
            "failures" to 0, "errors" to 0, "skipped" to 0,
            "evidence" to SYSTEM_IDENTITY_EVIDENCE, "evidenceSha256" to SYSTEM_IDENTITY_SHA256
        )
        val receipt = section(slices.getOrNull(2)?.get("acceptance"))
        errors += matchingFields("AR-04C acceptance", receipt, expected)
        if (receipt.keys != expected.keys) errors += "AR-04C acceptance has missing or unsupported receipt fields."
    }

    private fun activationErrors(
        snapshot: WorkflowSemanticsRecoveryLifecycleSnapshot,
        receipt: Map<*, *>
    ): List<String> = buildList {
        val evidence = snapshot.languageActivationEvidence
        if (snapshot.languageActivationSha256 != ACTIVATION_SHA256 || evidence["status"] != "passed" ||
            evidence["repository"] != "milank78git/FlowAi" || !CompilerModuleAcceptance.same(evidence["pullRequest"], 178)) {
            add("Implementation needs the exact independently verified language activation evidence bytes.")
        }
        val jobs = section(evidence["jobs"])
        val junit = section(evidence["junit"])
        val head = section(junit["exactHead"])
        val merge = section(junit["mergeCandidate"])
        val expected = mapOf(
            "status" to "passed", "conclusion" to "success",
            "workflowRunId" to evidence["workflowRunId"], "workflowRunNumber" to evidence["workflowRunNumber"],
            "exactHead" to evidence["head"], "syntheticMergeCandidate" to evidence["syntheticMerge"],
            "base" to evidence["base"], "sourceTree" to evidence["sourceTree"],
            "exactHeadJobId" to jobs["exactHead"], "mergeCandidateJobId" to jobs["mergeCandidate"],
            "isolationJobId" to jobs["physicalIsolation"], "kotlinTests" to head["tests"],
            "toolingTests" to 151, "conformanceChecks" to 242,
            "failures" to 0, "errors" to 0, "skipped" to 0,
            "evidence" to ACTIVATION_EVIDENCE, "evidenceSha256" to ACTIVATION_SHA256
        )
        addAll(matchingFields("Language activation receipt", receipt, expected))
        if (receipt.keys != expected.keys || evidence["head"] == evidence["syntheticMerge"] ||
            evidence["base"] != section(snapshot.integrityWorkPackage["verifiedBaseline"])["mainCommit"] ||
            !CompilerModuleAcceptance.same(head["tests"], 1544) ||
            !CompilerModuleAcceptance.same(head["uniqueIdentities"], 1544) ||
            !CompilerModuleAcceptance.same(merge["tests"], 1544) ||
            !CompilerModuleAcceptance.same(merge["uniqueIdentities"], 1544) ||
            head["identitiesSha256"] != merge["identitiesSha256"] ||
            !CompilerModuleAcceptance.digest(head["identitiesSha256"], 64) ||
            listOf(head, merge).any { result ->
                listOf("failures", "errors", "skipped").any { !CompilerModuleAcceptance.same(result[it], 0) }
            }) {
            add("Implementation requires a separate green activation with both complete identical test sets.")
        }
    }
}
