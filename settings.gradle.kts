pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { mavenCentral() } }
rootProject.name = "flow-core"

// Isolation modes are used only by physical source-deletion proofs. The source
// ownership gate rejects a residual product tree in every isolated mode.
val isolatedBoundary = providers.gradleProperty("flow.isolatedBoundary").orNull
require(isolatedBoundary == null || isolatedBoundary in setOf("semantic-kernel", "compiler", "adapter-runtime", "adapter-evidence")) {
    "Unknown isolated production boundary: $isolatedBoundary"
}
include(":flow-semantic-kernel")
if (isolatedBoundary != "semantic-kernel") {
    include(":flow-module-contracts", ":flow-compiler")
}
if (isolatedBoundary == null || isolatedBoundary in setOf("adapter-runtime", "adapter-evidence")) {
    include(":flow-frontends", ":flow-adapter-contracts", ":flow-adapter-runtime")
}
if (isolatedBoundary == null || isolatedBoundary == "adapter-evidence") {
    include(":flow-adapter-evidence")
}
if (isolatedBoundary == null) {
    include(":flow-adapter-jenkins", ":flow-adapter-github-actions", ":flow-adapter-tekton", ":flow-reference-distribution")
}
