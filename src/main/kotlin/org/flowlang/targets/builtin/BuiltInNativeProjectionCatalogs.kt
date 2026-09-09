package org.flowlang.targets.builtin

import org.flowlang.generators.manifest.TargetNativeProjectionCatalog

/** Compatibility facade. Concrete native catalogs are owned by their adapter modules. */
@Deprecated("Use the target-specific native projection catalog; scheduled for AR-07 removal")
object BuiltInNativeProjectionCatalogs {
    val jenkins: TargetNativeProjectionCatalog get() = JenkinsNativeProjectionCatalog.catalog
    val githubActions: TargetNativeProjectionCatalog get() = GitHubActionsNativeProjectionCatalog.catalog
    val tekton: TargetNativeProjectionCatalog get() = TektonNativeProjectionCatalog.catalog
    val byTarget: Map<String, TargetNativeProjectionCatalog>
        get() = listOf(jenkins, githubActions, tekton).associateBy(TargetNativeProjectionCatalog::target)
}
