package org.flowlang.targets

object StandardTargetRegistryHonestySnapshots {
    fun baseline(): TargetRegistrySnapshot = TargetRegistrySnapshot(
        registryId = "flow.target.registry.baseline",
        entries = listOf(
            TargetRegistryEntry(
                targetId = "jenkins",
                displayName = "Jenkins",
                status = TargetRegistryStatus.TESTED,
                declaredCapabilities = setOf("approval.require", "notification.send"),
                evidence = listOf(
                    TargetRegistryEvidence("JenkinsManifestRenderer", TargetRegistryEvidenceKind.IMPLEMENTATION, "Renderer implementation exists."),
                    TargetRegistryEvidence("flow.projection.baseline", TargetRegistryEvidenceKind.PROJECTION_PLAN, "Projection contract is recorded."),
                    TargetRegistryEvidence("FlowNoShellTargetProjectionTests", TargetRegistryEvidenceKind.TEST, "No-shell projection tests cover the boundary.")
                ),
                reason = "Jenkins has implemented and tested projection boundaries but is not claimed as production-supported by registry default."
            ),
            TargetRegistryEntry(
                targetId = "github-actions",
                displayName = "GitHub Actions",
                status = TargetRegistryStatus.TESTED,
                declaredCapabilities = setOf("approval.require", "notification.send"),
                evidence = listOf(
                    TargetRegistryEvidence("GitHubActionsManifestRenderer", TargetRegistryEvidenceKind.IMPLEMENTATION, "Renderer implementation exists."),
                    TargetRegistryEvidence("flow.projection.baseline", TargetRegistryEvidenceKind.PROJECTION_PLAN, "Projection contract is recorded."),
                    TargetRegistryEvidence("FlowNoShellTargetProjectionTests", TargetRegistryEvidenceKind.TEST, "No-shell projection tests cover the boundary.")
                ),
                reason = "GitHub Actions has implemented and tested projection boundaries but is not claimed as production-supported by registry default."
            ),
            TargetRegistryEntry(
                targetId = "tekton",
                displayName = "Tekton",
                status = TargetRegistryStatus.TESTED,
                declaredCapabilities = setOf("approval.require"),
                evidence = listOf(
                    TargetRegistryEvidence("TektonManifestRenderer", TargetRegistryEvidenceKind.IMPLEMENTATION, "Renderer implementation exists."),
                    TargetRegistryEvidence("flow.projection.baseline", TargetRegistryEvidenceKind.PROJECTION_PLAN, "Projection contract is recorded."),
                    TargetRegistryEvidence("FlowNoShellTargetProjectionTests", TargetRegistryEvidenceKind.TEST, "No-shell projection tests cover the boundary.")
                ),
                reason = "Tekton has implemented and tested projection boundaries but is not claimed as production-supported by registry default."
            ),
            TargetRegistryEntry(
                targetId = "shell",
                displayName = "Shell",
                status = TargetRegistryStatus.BLOCKED,
                evidence = listOf(
                    TargetRegistryEvidence("v0.9.5.1", TargetRegistryEvidenceKind.POLICY_DECISION, "Shell projection is prohibited by the correction track.")
                ),
                reason = "Shell is retained only as blocked registry evidence."
            )
        )
    )
}
