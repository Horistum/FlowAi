import org.flowlang.capabilities.TargetRendererPayloadKind

/** Test fixtures for built-in edge renderer payload identifiers. */
internal val String.Companion.JENKINS_STEP: TargetRendererPayloadKind get() = "JENKINS_STEP"
internal val String.Companion.GITHUB_ACTION: TargetRendererPayloadKind get() = "GITHUB_ACTION"
internal val String.Companion.TEKTON_TASK: TargetRendererPayloadKind get() = "TEKTON_TASK"
