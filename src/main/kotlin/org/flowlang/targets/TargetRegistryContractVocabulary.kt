package org.flowlang.targets

import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetProjectionMode
import org.flowlang.topology.ExecutionTopologySupportStatus

/** Closed TargetRegistry 3.2 wire vocabulary shared by production parsing and schema parity tests. */
object TargetRegistryContractVocabulary {
    val capabilityNames: Set<String> = linkedSetOf(
        "sequentialTasks",
        "parallel",
        "conditions",
        "dynamicLoops",
        "match",
        "retry",
        "approvals",
        "errorHandlers",
        "artifacts",
        "secrets",
        "nativeRuntime"
    )

    val supportLevels: Map<String, SupportLevel> = linkedMapOf(
        "supported" to SupportLevel.SUPPORTED,
        "full" to SupportLevel.SUPPORTED,
        "partial" to SupportLevel.PARTIAL,
        "unsupported" to SupportLevel.UNSUPPORTED,
        "none" to SupportLevel.UNSUPPORTED,
        "requires_runtime" to SupportLevel.REQUIRES_RUNTIME
    )

    val topologySupportLevels: Map<String, ExecutionTopologySupportStatus> = linkedMapOf(
        "supported" to ExecutionTopologySupportStatus.SUPPORTED,
        "partial" to ExecutionTopologySupportStatus.PARTIAL,
        "unsupported" to ExecutionTopologySupportStatus.UNSUPPORTED,
        "unknown" to ExecutionTopologySupportStatus.UNKNOWN
    )

    val projectionModes: Map<String, TargetProjectionMode> = linkedMapOf(
        "native" to TargetProjectionMode.NATIVE,
        "notes_projected" to TargetProjectionMode.NOTES_PROJECTED,
        "adapter_required" to TargetProjectionMode.ADAPTER_REQUIRED,
        "unsupported" to TargetProjectionMode.UNSUPPORTED,
        "blocked" to TargetProjectionMode.BLOCKED
    )
}
