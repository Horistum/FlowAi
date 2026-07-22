import org.flowlang.capabilities.*
import org.flowlang.topology.ExecutionTopologyProfile

internal fun testTargetCapability(
    target: String,
    description: String,
    sequentialTasks: SupportLevel = SupportLevel.SUPPORTED,
    parallel: SupportLevel = SupportLevel.SUPPORTED,
    conditions: SupportLevel = SupportLevel.SUPPORTED,
    dynamicLoops: SupportLevel = SupportLevel.PARTIAL,
    match: SupportLevel = SupportLevel.PARTIAL,
    retry: SupportLevel = SupportLevel.PARTIAL,
    approvals: SupportLevel = SupportLevel.PARTIAL,
    errorHandlers: SupportLevel = SupportLevel.PARTIAL,
    artifacts: SupportLevel = SupportLevel.PARTIAL,
    secrets: SupportLevel = SupportLevel.PARTIAL,
    nativeRuntime: SupportLevel = SupportLevel.PARTIAL,
    notes: List<String> = emptyList(),
    features: Map<String, SupportLevel> = emptyMap(),
    expressionSupport: TargetExpressionSupportDeclaration? = null,
    projectionRules: List<TargetProjectionRule> = emptyList()
): TargetCapability = TargetCapability(
    target = target,
    description = description,
    sequentialTasks = sequentialTasks,
    parallel = parallel,
    conditions = conditions,
    dynamicLoops = dynamicLoops,
    match = match,
    retry = retry,
    approvals = approvals,
    errorHandlers = errorHandlers,
    artifacts = artifacts,
    secrets = secrets,
    nativeRuntime = nativeRuntime,
    notes = notes,
    features = features,
    expressionSupport = expressionSupport,
    projectionRules = projectionRules,
    topologyProfile = ExecutionTopologyProfile.fullySupported(target, "test:$target:topology")
)
