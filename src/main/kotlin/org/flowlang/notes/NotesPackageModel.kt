package org.flowlang.notes

enum class NotesPackageKind {
    DOMAIN,
    CAPABILITY,
    SAFETY,
    RUNTIME,
    TARGET,
    PROJECTION,
    CONFORMANCE
}

data class NotesPackageDependency(
    val packageId: String,
    val versionConstraint: String
)

data class NotesPackageBoundary(
    val name: String,
    val description: String
)

data class NotesPackageContract(
    val packageId: String,
    val packageVersion: String,
    val kind: NotesPackageKind,
    val description: String,
    val declaredSemantics: Set<String> = emptySet(),
    val declaredCapabilities: Set<String> = emptySet(),
    val safetyPolicies: Set<String> = emptySet(),
    val runtimeRequirements: Set<String> = emptySet(),
    val targetCapabilities: Set<String> = emptySet(),
    val projectionRules: Set<String> = emptySet(),
    val conformanceChecks: Set<String> = emptySet(),
    val dependencies: Set<NotesPackageDependency> = emptySet(),
    val boundaries: Set<NotesPackageBoundary> = emptySet()
) {
    fun declarationsForKind(): Set<String> = when (kind) {
        NotesPackageKind.DOMAIN -> declaredSemantics
        NotesPackageKind.CAPABILITY -> declaredCapabilities
        NotesPackageKind.SAFETY -> safetyPolicies
        NotesPackageKind.RUNTIME -> runtimeRequirements
        NotesPackageKind.TARGET -> targetCapabilities
        NotesPackageKind.PROJECTION -> projectionRules
        NotesPackageKind.CONFORMANCE -> conformanceChecks
    }
}

enum class NotesPackageContractStatus {
    PASS,
    FAIL
}

data class NotesPackageContractIssue(
    val code: String,
    val packageId: String,
    val message: String
)

data class NotesPackageContractReport(
    val status: NotesPackageContractStatus,
    val packages: Int,
    val issues: List<NotesPackageContractIssue>
) {
    val valid: Boolean = status == NotesPackageContractStatus.PASS
}
