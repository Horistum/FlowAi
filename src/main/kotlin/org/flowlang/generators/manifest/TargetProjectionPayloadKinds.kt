package org.flowlang.generators.manifest

/**
 * Payload identifiers understood by the built-in edge renderers.
 *
 * These identifiers intentionally live outside the capability, semantic,
 * planning, materialization, and registry model. Adding another renderer must
 * not require extending a Flow Core enum.
 */
internal val String.Companion.JENKINS_STEP: String get() = "JENKINS_STEP"
internal val String.Companion.GITHUB_ACTION: String get() = "GITHUB_ACTION"
internal val String.Companion.TEKTON_TASK: String get() = "TEKTON_TASK"
