package org.flowlang.targets.builtin

/**
 * Payload identifiers understood by the built-in edge renderers.
 *
 * They are deliberately absent from capability, semantic, planning,
 * materialization, registry and manifest Core contracts.
 */
internal object BuiltInProjectionPayloadKinds {
    const val JENKINS_STEP = "JENKINS_STEP"
    const val GITHUB_ACTION = "GITHUB_ACTION"
    const val TEKTON_TASK = "TEKTON_TASK"
}
