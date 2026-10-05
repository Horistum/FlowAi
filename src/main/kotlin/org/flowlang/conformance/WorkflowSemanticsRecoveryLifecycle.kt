package org.flowlang.conformance

import java.io.File
import java.security.MessageDigest
import org.flowlang.serialization.FlowYaml

internal data class WorkflowSemanticsRecoveryLifecycleSnapshot(
    val workPackage: Map<String, Any?>,
    val recovery: Map<String, Any?>,
    val postToolchain: Map<String, Any?>,
    val release: Map<String, Any?>,
    val global: Map<String, Any?>,
    val successorWorkPackage: Map<String, Any?> = emptyMap(),
    val integrityWorkPackage: Map<String, Any?> = emptyMap(),
    val moduleAcceptanceEvidence: Map<String, Any?> = emptyMap(),
    val moduleAcceptanceSha256: String? = null,
    val moduleBoundaryInventory: Map<String, Any?> = emptyMap(),
    val languageActivationEvidence: Map<String, Any?> = emptyMap(),
    val languageActivationSha256: String? = null,
    val schemaIntegrityEvidence: Map<String, Any?> = emptyMap(),
    val schemaIntegritySha256: String? = null,
    val systemIdentityEvidence: Map<String, Any?> = emptyMap(),
    val systemIdentitySha256: String? = null,
    val semanticIdentityEvidence: Map<String, Any?> = emptyMap(),
    val semanticIdentitySha256: String? = null,
    val strictLoaderEvidence: Map<String, Any?> = emptyMap(),
    val strictLoaderSha256: String? = null,
    // Keep the immutable document: acceptance fingerprints and parses this same value.
    val integratedLanguageEvidence: String? = null,
    val languageCompletionEvidence: String? = null,
    val artifactWorkPackage: Map<String, Any?> = emptyMap(),
    val artifactActivationEvidence: String? = null,
    val cliArgumentAcceptanceEvidence: String? = null,
    val contractDistributionAcceptanceEvidence: String? = null,
    val boundedIoAcceptanceEvidence: String? = null,
    val atomicPublicationAcceptanceEvidence: String? = null
)

/** Checks the exact structured claim; a coherent active candidate is not a completion receipt. */
internal object WorkflowSemanticsRecoveryLifecycle {
    const val WORK_PACKAGE = ".flow-agent/work-packages/flow-sensitive-workflow-data-failure-semantics.yaml"
    val boundaryNames = listOf("activationBoundary", "implementationBoundary", "validationBoundary", "completionBoundary")
    private val receiptFields = listOf(
        "status", "conclusion", "workflowRunId", "workflowRunNumber",
        "exactHead", "syntheticMergeCandidate", "exactHeadJobId", "mergeCandidateJobId"
    )

    fun load(root: File): WorkflowSemanticsRecoveryLifecycleSnapshot {
        // Parse and fingerprint the same bytes; a second file read could attest different content.
        val evidence = File(root, CompilerModuleAcceptance.EVIDENCE).takeIf { it.isFile }?.readBytes()
        val activation = File(root, LanguageContractIntegrityLifecycle.ACTIVATION_EVIDENCE)
            .takeIf { it.isFile }?.readBytes()
        val schema = File(root, LanguageContractIntegrityLifecycle.SCHEMA_EVIDENCE)
            .takeIf { it.isFile }?.readBytes()
        val systemIdentity = File(root, LanguageContractIntegrityLifecycle.SYSTEM_IDENTITY_EVIDENCE)
            .takeIf { it.isFile }?.readBytes()
        val semanticIdentity = File(root, LanguageContractIntegrityLifecycle.SEMANTIC_IDENTITY_EVIDENCE)
            .takeIf { it.isFile }?.readBytes()
        val strictLoader = File(root, LanguageContractIntegrityLifecycle.STRICT_LOADER_EVIDENCE)
            .takeIf { it.isFile }?.readBytes()
        return WorkflowSemanticsRecoveryLifecycleSnapshot(
            FlowYaml.readMap(File(root, WORK_PACKAGE)),
            FlowYaml.readMap(File(root, ".flow-agent/roadmap-architecture-recovery.yaml")),
            FlowYaml.readMap(File(root, ".flow-agent/roadmap-post-toolchain.yaml")),
            FlowYaml.readMap(File(root, ".flow-agent/release-state.yaml")),
            FlowYaml.readMap(File(root, ".flow-agent/roadmap.yaml")),
            File(root, CompilerModuleExtractionLifecycle.WORK_PACKAGE).let { file ->
                if (file.isFile) FlowYaml.readMap(file) else emptyMap()
            },
            optionalMap(root, LanguageContractIntegrityLifecycle.WORK_PACKAGE),
            evidence?.let { FlowYaml.readMap(it.toString(Charsets.UTF_8), CompilerModuleAcceptance.EVIDENCE) } ?: emptyMap(),
            evidence?.let { bytes ->
                MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            },
            optionalMap(root, CompilerModuleAcceptance.INVENTORY),
            activation?.let { FlowYaml.readMap(it.toString(Charsets.UTF_8), LanguageContractIntegrityLifecycle.ACTIVATION_EVIDENCE) }
                ?: emptyMap(),
            activation?.let { bytes ->
                MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            },
            schema?.let { FlowYaml.readMap(it.toString(Charsets.UTF_8), LanguageContractIntegrityLifecycle.SCHEMA_EVIDENCE) }
                ?: emptyMap(),
            schema?.let { bytes ->
                MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            },
            systemIdentity?.let { FlowYaml.readMap(it.toString(Charsets.UTF_8), LanguageContractIntegrityLifecycle.SYSTEM_IDENTITY_EVIDENCE) }
                ?: emptyMap(),
            systemIdentity?.let { bytes ->
                MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            },
            semanticIdentity?.let { FlowYaml.readMap(it.toString(Charsets.UTF_8), LanguageContractIntegrityLifecycle.SEMANTIC_IDENTITY_EVIDENCE) }
                ?: emptyMap(),
            semanticIdentity?.let { bytes ->
                MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            },
            strictLoader?.let { FlowYaml.readMap(it.toString(Charsets.UTF_8), LanguageContractIntegrityLifecycle.STRICT_LOADER_EVIDENCE) }
                ?: emptyMap(),
            strictLoader?.let { bytes ->
                MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            },
            File(root, LanguageContractIntegrityLifecycle.INTEGRATED_EVIDENCE)
                .takeIf { it.isFile }?.readText(Charsets.UTF_8),
            File(root, LanguageIntegrityCompletion.EVIDENCE)
                .takeIf { it.isFile }?.readText(Charsets.UTF_8),
            optionalMap(root, ArtifactIntegrityLifecycle.WORK_PACKAGE),
            File(root, ArtifactIntegrityLifecycle.EVIDENCE).takeIf { it.isFile }?.readText(Charsets.UTF_8),
            File(root, ContractDistributionLifecycle.CLI_EVIDENCE).takeIf { it.isFile }?.readText(Charsets.UTF_8),
            File(root, BoundedIoLifecycle.EVIDENCE).takeIf { it.isFile }?.readText(Charsets.UTF_8),
            File(root, AtomicPublicationLifecycle.EVIDENCE).takeIf { it.isFile }?.readText(Charsets.UTF_8),
            File(root, IntegratedProductIntegrityLifecycle.EVIDENCE).takeIf { it.isFile }?.readText(Charsets.UTF_8)
        )
    }

    private fun optionalMap(root: File, path: String): Map<String, Any?> =
        File(root, path).let { if (it.isFile) FlowYaml.readMap(it) else emptyMap() }

    fun errors(snapshot: WorkflowSemanticsRecoveryLifecycleSnapshot): List<String> = buildList {
        if (map(snapshot.recovery["currentDecision"])["workPackage"] == ArtifactIntegrityLifecycle.WORK_PACKAGE) {
            addAll(ArtifactIntegrityLifecycle.errors(snapshot))
            return@buildList
        }
        val work = snapshot.workPackage
        val complete = work["status"] == "complete"
        if (work["version"] != "AR-02" || work["status"] !in setOf("active", "complete")) {
            add("AR-02 work package must declare a supported active or complete state.")
        }
        val authorization = map(work["authorization"])
        if (authorization["status"] != if (complete) "completed" else "active") {
            add("AR-02 authorization disagrees with the work package lifecycle.")
        }
        val milestones = records(snapshot.recovery["milestones"])
        val ar02 = milestones.singleOrNull { it["id"] == "AR-02" }
        val ar03 = milestones.singleOrNull { it["id"] == "AR-03" }
        if (ar02?.get("status") != if (complete) "completed" else "active") {
            add("AR-02 roadmap milestone disagrees with its lifecycle evidence.")
        }
        val decision = map(snapshot.recovery["currentDecision"])
        val integritySelected = complete && decision["workPackage"] == LanguageContractIntegrityLifecycle.WORK_PACKAGE
        val integrityComplete = integritySelected && snapshot.integrityWorkPackage["status"] == "complete"
        val successorActivated = complete && (integritySelected ||
            decision["workPackage"] == CompilerModuleExtractionLifecycle.WORK_PACKAGE)
        val moduleComplete = successorActivated && snapshot.successorWorkPackage["status"] == "complete"
        if (successorActivated) {
            addAll(CompilerModuleExtractionLifecycle.errors(snapshot))
        } else if (ar03?.get("status") != "planned") {
            add("AR-03 must remain planned unless its own activation transition authorizes it.")
        }
        if (integritySelected) {
            addAll(LanguageContractIntegrityLifecycle.errors(snapshot))
        } else if (milestones.singleOrNull { it["id"] == "AR-04" }?.get("status") != "planned") {
            add("AR-04 must remain planned unless its own activation transition authorizes it.")
        }
        val post = map(snapshot.postToolchain["currentDecision"])
        val recoveryState = map(snapshot.postToolchain["recoveryRoadmap"])
        val release = map(snapshot.release["roadmapState"])
        val expectedPrevious = if (integrityComplete) "AR-04" else if (moduleComplete) "AR-03" else if (complete) "AR-02" else "AR-01"
        val expectedNext = if (integrityComplete) "AR-05" else if (moduleComplete) "AR-04" else if (complete) "AR-03" else "AR-02"
        val expectedActivation = if (integrityComplete || (moduleComplete && !integritySelected) ||
            (complete && !successorActivated)) "not-activated" else "active"
        val expectedWorkPackage = when {
            integritySelected -> LanguageContractIntegrityLifecycle.WORK_PACKAGE
            successorActivated -> CompilerModuleExtractionLifecycle.WORK_PACKAGE
            else -> WORK_PACKAGE
        }
        if (decision["previousCompletedItem"] != expectedPrevious || decision["nextItem"] != expectedNext ||
            decision["activationState"] != expectedActivation || decision["workPackage"] != expectedWorkPackage
        ) add("Recovery currentDecision contradicts the AR-02 lifecycle.")
        if (post["completedItem"] != expectedPrevious || post["nextItem"] != expectedNext ||
            post["activationState"] != expectedActivation || post["workPackage"] != expectedWorkPackage
        ) add("Post-toolchain currentDecision contradicts the AR-02 lifecycle.")
        if (recoveryState["completedItem"] != expectedPrevious || recoveryState["activationState"] != expectedActivation) {
            add("Post-toolchain recovery state contradicts the AR-02 lifecycle.")
        }
        val slices = records(work["implementationSlices"])
        val sliceIds = slices.map { it["id"] }
        if (sliceIds != listOf("AR-02A", "AR-02B", "AR-02C", "AR-02D", "AR-02E") ||
            slices.take(4).any { it["status"] != "complete" } ||
            (complete && slices.lastOrNull()?.get("status") != "complete") ||
            (!complete && slices.lastOrNull()?.get("status") !in setOf("selected", "active"))
        ) add("AR-02 implementation-slice state is incomplete or contradictory.")

        val lifecycle = map(work["lifecycle"])
        if (complete) {
            val boundaries = boundaryNames.map { name ->
                val boundary = map(lifecycle[name])
                addAll(boundaryErrors(name, boundary))
                boundary
            }
            listOf("workflowRunId", "exactHead", "syntheticMergeCandidate").forEach { field ->
                if (boundaries.map { it[field] }.distinct().size != boundaries.size) {
                    add("Completed lifecycle boundaries must use distinct $field evidence.")
                }
            }
            val local = map(work["localValidation"])
            addAll(boundaryErrors("localValidation", local))
            val validation = map(lifecycle["validationBoundary"])
            addAll(validationAliasErrors(local, validation))
            val completion = map(work["completionDecision"])
            if (completion["status"] != "complete" || completion["completedSlice"] != "AR-02E" ||
                completion["nextItem"] != "AR-03" || completion["nextItemActivationState"] != "not-activated" ||
                (completion["closesFindings"] as? List<*>)?.toSet() != setOf("F-02", "F-08", "F-15")
            ) add("AR-02 completion decision lacks exact closure and successor boundaries.")
            if (release["completedRecoveryItem"] != expectedPrevious || release["nextRecoveryItem"] != expectedNext ||
                release["nextRecoveryActivationState"] != expectedActivation
            ) add("Release recovery succession disagrees with completed AR-02.")
        } else {
            if (map(work["completionDecision"])["status"] in setOf("complete", "implementation-complete") ||
                map(lifecycle["completionBoundary"])["status"] == "passed" ||
                release["completedRecoveryItem"] == "AR-02" || release["nextRecoveryItem"] == "AR-03"
            ) add("Active AR-02 candidate must not publish a completed recovery claim.")
        }

        val globalDecision = map(snapshot.global["currentDecision"])
        if (snapshot.global["currentTrack"] != "" || globalDecision["completedItem"] != "0.9.7.10" ||
            globalDecision["completedItemName"] != "Bounded Semantic Closure Gate" ||
            listOf("nextItem", "nextItemName", "nextItemStream").any { globalDecision[it] != "" } ||
            globalDecision.containsKey("completedRecoveryItem") || globalDecision.containsKey("nextRecoveryItem") ||
            release["nextItem"] != "" || release["completedItem"] != "0.9.7.10"
        ) add("Recovery lifecycle changed the terminal global roadmap focus.")
    }

    /** localValidation is an alias of one receipt, not an independently editable success claim. */
    fun validationAliasErrors(local: Map<*, *>, validation: Map<*, *>): List<String> = buildList {
        receiptFields.forEach { field ->
            val localValue = local[field]
            val recordedValue = validation[field]
            val equal = if ((localValue is Int || localValue is Long) &&
                (recordedValue is Int || recordedValue is Long)
            ) {
                (localValue as Number).toLong() == (recordedValue as Number).toLong()
            } else {
                localValue == recordedValue
            }
            if (localValue == null || recordedValue == null || !equal) {
                add("localValidation.$field must match the recorded validation boundary, not unrelated green evidence.")
            }
        }
    }

    fun boundaryErrors(name: String, boundary: Map<*, *>): List<String> = buildList {
        if (boundary["status"] != "passed" || boundary["conclusion"] != "success") {
            add("$name is not a passed validation boundary.")
        }
        fun positiveInteger(value: Any?): Boolean =
            (value is Int && value > 0) || (value is Long && value > 0)
        listOf("workflowRunId", "workflowRunNumber", "exactHeadJobId", "mergeCandidateJobId").forEach { field ->
            if (!positiveInteger(boundary[field])) add("$name.$field must identify actual positive integer evidence.")
        }
        if (boundary["exactHeadJobId"] == boundary["mergeCandidateJobId"]) {
            add("$name must distinguish exact-head and merge-candidate jobs.")
        }
        listOf("exactHead", "syntheticMergeCandidate").forEach { field ->
            val value = boundary[field] as? String
            if (value == null || !value.matches(Regex("[0-9a-f]{40}")) || value.toSet().size == 1) {
                add("$name.$field must identify an exact validated commit.")
            }
        }
        if (boundary["exactHead"] == boundary["syntheticMergeCandidate"]) {
            add("$name must distinguish head and synthetic merge revision.")
        }
    }

    private fun map(value: Any?): Map<*, *> = value as? Map<*, *> ?: emptyMap<Any, Any>()
    private fun records(value: Any?): List<Map<*, *>> = (value as? List<*>)?.map { map(it) }.orEmpty()
}
