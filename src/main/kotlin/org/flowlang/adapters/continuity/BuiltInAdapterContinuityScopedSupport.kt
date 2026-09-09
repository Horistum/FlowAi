package org.flowlang.adapters.continuity

/** Existing bounded evidence owned and compiled by the concrete workspace-transfer adapter. */
object BuiltInAdapterContinuityScopedSupport {
    val githubActionsCheckoutBuildWorkspace = AdapterContinuityScopedSupport(
        target = "github-actions",
        family = AdapterContinuityFamily.ARTIFACT,
        semantic = AdapterContinuitySemanticContract.ARTIFACT_SHARED_WORKSPACE,
        sourceAction = "git.checkout",
        targetAction = "docker.build",
        channel = "source",
        exactPlanActions = listOf("git.checkout", "docker.build"),
        evidenceReferences = listOf(
            "src/main/kotlin/org/flowlang/targets/builtin/GitHubActionsWorkspaceContinuityPlanner.kt",
            "src/main/kotlin/org/flowlang/targets/builtin/GitHubActionsManifestRenderer.kt",
            "src/test/kotlin/GitHubActionsWorkspaceContinuityTests.kt",
            "src/test/kotlin/AdapterContinuityProviderBehaviorTests.kt",
            "conformance/snapshots/github-actions-checkout-build-image/github-actions.executable.yaml",
            "docs/GITHUB_ACTIONS_ARTIFACT_WORKSPACE_CONTINUITY.md"
        ),
        limitations = listOf(
            "The proof covers regular-file bytes, relative paths and hidden entries for the exact checkout-build-image source channel only.",
            "The zipped GitHub Actions artifact transfer does not preserve Unix mode bits, so consumers that require executable permissions are outside the certified scope.",
            "Symbolic-link identity is not certified by the current repository evidence and must not be inferred from regular-file byte reconstruction."
        )
    )

    val declarations: List<AdapterContinuityScopedSupport> = listOf(githubActionsCheckoutBuildWorkspace)
}
