package org.flowlang.conformance

import java.io.File
import org.flowlang.serialization.FlowYaml
import org.flowlang.conformance.CompilerModuleAcceptance.section

/** Replays accepted implementation with pending closure, retaining immutable historical receipt bytes. */
internal fun artifactIntegrityImplementationSnapshot(
    s: WorkflowSemanticsRecoveryLifecycleSnapshot = artifactIntegrityCompletedSnapshot()
): WorkflowSemanticsRecoveryLifecycleSnapshot {
    if (s.artifactWorkPackage["status"] != "complete") return s
    val evidence = FlowYaml.readMap(requireNotNull(s.artifactCompletionEvidence), ArtifactIntegrityCompletion.EVIDENCE)
    val prior = section(evidence["predecessor"])
    fun restore(owner: Map<String, Any?>, sectionName: String, key: String) =
        owner + (sectionName to (stringMap(owner[sectionName]) + stringMap(prior[key])))
    val work = s.artifactWorkPackage
    return s.copy(artifactCompletionEvidence = null,
        artifactWorkPackage = (work - "acceptanceEvidence") + mapOf("status" to "active",
            "authorization" to prior["authorization"], "completionDecision" to prior["completionDecision"],
            "lifecycle" to (stringMap(work["lifecycle"]) + mapOf(
                "validationBoundary" to mapOf("status" to "pending"), "completionBoundary" to mapOf("status" to "pending")))),
        recovery = restore(s.recovery, "currentDecision", "recoveryDecision") + mapOf(
            "findingRegister" to evidence["findingStateBeforeClosure"],
            "milestones" to records(s.recovery["milestones"]).map { if (it["id"] == "AR-05") it + ("status" to "active") else it }),
        postToolchain = restore(restore(s.postToolchain, "currentDecision", "postDecision"), "recoveryRoadmap", "recoveryRoadmap") +
            ("sequence" to records(s.postToolchain["sequence"]).map { if (it["id"] == "ARCHITECTURE-RECOVERY")
                it + stringMap(prior["sequence"]) else it }),
        release = restore(s.release, "roadmapState", "releaseState") + ("recoveryAcceptance" to prior["recoveryAcceptance"]))
}

private fun stringMap(value: Any?): Map<String, Any?> = section(value).entries.associate { it.key.toString() to it.value }
private fun records(value: Any?): List<Map<String, Any?>> = (value as? List<*>).orEmpty().map(::stringMap)

/** Historical AR-05 assertions keep their pre-activation semantics. */
internal fun artifactIntegrityCompletedSnapshot(): WorkflowSemanticsRecoveryLifecycleSnapshot =
    AdapterCertificationLifecycle.predecessorSnapshot(WorkflowSemanticsRecoveryLifecycle.load(File(".")))
