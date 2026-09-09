package org.flowlang.distribution.reference

import org.flowlang.generators.manifest.TargetProjectionProvider
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.targets.builtin.GitHubActionsManifestGenerator
import org.flowlang.targets.builtin.GitHubActionsManifestRenderer
import org.flowlang.targets.builtin.JenkinsManifestGenerator
import org.flowlang.targets.builtin.JenkinsManifestRenderer
import org.flowlang.targets.builtin.TektonManifestGenerator
import org.flowlang.targets.builtin.TektonManifestRenderer

/** Concrete adapter composition owned by the reference distribution, never by generic authorities. */
object ReferenceTargetProjections {
    val registry: TargetProjectionRegistry = TargetProjectionRegistry.of(
        TargetProjectionProvider(JenkinsManifestGenerator(), JenkinsManifestRenderer()),
        TargetProjectionProvider(GitHubActionsManifestGenerator(), GitHubActionsManifestRenderer()),
        TargetProjectionProvider(TektonManifestGenerator(), TektonManifestRenderer())
    )
}
