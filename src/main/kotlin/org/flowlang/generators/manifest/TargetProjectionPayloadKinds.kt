package org.flowlang.generators.manifest

/**
 * Payload identifiers understood by the built-in edge renderers.
 *
 * These identifiers intentionally live outside the capability, semantic,
 * planning, materialization, and registry model. Adding another renderer must
 * not require extending a Flow Core enum.
 */
internal object BuiltInProjectionPayloadKinds {
    const val JENKINS_STEP = "JENKINS_STEP"
    const val GITHUB_ACTION = "GITHUB_ACTION"
    const val TEKTON_TASK = "TEKTON_TASK"
}
