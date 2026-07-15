@file:Suppress("DEPRECATION")

package org.flowlang.generators.manifest

/**
 * Temporary internal compile bridge for the legacy monolithic conformance runner.
 *
 * This file is removed before v0.9.6.3 is marked ready. Production ownership
 * remains in org.flowlang.targets.builtin.
 */
internal typealias JenkinsManifestGenerator = org.flowlang.targets.builtin.JenkinsManifestGenerator
internal typealias GitHubActionsManifestGenerator = org.flowlang.targets.builtin.GitHubActionsManifestGenerator
internal typealias TektonManifestGenerator = org.flowlang.targets.builtin.TektonManifestGenerator
internal typealias JenkinsManifestRenderer = org.flowlang.targets.builtin.JenkinsManifestRenderer
internal typealias GitHubActionsManifestRenderer = org.flowlang.targets.builtin.GitHubActionsManifestRenderer
internal typealias TektonManifestRenderer = org.flowlang.targets.builtin.TektonManifestRenderer
internal typealias TargetExpressionTranslator = org.flowlang.targets.builtin.TargetExpressionTranslator
internal typealias TargetExpressionTranslationException = org.flowlang.targets.builtin.TargetExpressionTranslationException
