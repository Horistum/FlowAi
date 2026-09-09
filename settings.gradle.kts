pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { mavenCentral() } }
rootProject.name = "flow-core"

// These modes are exclusively for the physical-deletion proofs. The ownership
// gate rejects a residual product tree in either mode; ordinary CI uses neither.
val isolatedBoundary = providers.gradleProperty("flow.isolatedBoundary").orNull
require(isolatedBoundary == null || isolatedBoundary in setOf("semantic-kernel", "compiler")) {
    "Unknown isolated production boundary: $isolatedBoundary"
}
include(":flow-semantic-kernel")
if (isolatedBoundary != "semantic-kernel") {
    include(":flow-module-contracts", ":flow-compiler")
}
if (isolatedBoundary == null) {
    include(":flow-frontends")
    include(":flow-adapter-contracts")
}
