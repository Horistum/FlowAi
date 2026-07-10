package org.flowlang.notes

/**
 * Contract model for notes packages.
 *
 * Notes packages declare meaning, constraints and projection requirements. They
 * are not plugins, SDK entrypoints, runtime executors or target-specific public
 * DSLs. The model is deliberately small because this layer is a contract
 * boundary, not another lifecycle framework wearing a fake moustache.
 */
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

class NotesPackageContractValidator {
    fun validate(contract: NotesPackageContract): NotesPackageContractReport = validateAll(listOf(contract))

    fun validateAll(contracts: List<NotesPackageContract>): NotesPackageContractReport {
        val issues = mutableListOf<NotesPackageContractIssue>()
        val ids = mutableSetOf<String>()

        contracts.forEach { contract ->
            val id = contract.packageId
            if (!PACKAGE_ID.matches(id)) {
                issues += issue("notes.package.id.invalid", id, "Notes package id must be lowercase dot-separated identifier text.")
            }
            if (!ids.add(id)) {
                issues += issue("notes.package.id.duplicate", id, "Notes package id must be unique in a contract set.")
            }
            if (contract.packageVersion.isBlank()) {
                issues += issue("notes.package.version.missing", id, "Notes package version is required.")
            }
            if (contract.description.isBlank()) {
                issues += issue("notes.package.description.missing", id, "Notes package description is required.")
            }
            if (contract.declarationsForKind().isEmpty()) {
                issues += issue("notes.package.declaration.missing", id, "Notes package must declare at least one item for its package kind.")
            }
            if (contract.boundaries.isEmpty()) {
                issues += issue("notes.package.boundary.missing", id, "Notes package must declare architectural boundaries.")
            }
            issues += validateDependencies(contract)
            issues += validateNoRuntimeOrSdkClaims(contract)
            issues += validateProjectionHonesty(contract)
        }

        return NotesPackageContractReport(
            status = if (issues.isEmpty()) NotesPackageContractStatus.PASS else NotesPackageContractStatus.FAIL,
            packages = contracts.size,
            issues = issues
        )
    }

    private fun validateDependencies(contract: NotesPackageContract): List<NotesPackageContractIssue> =
        contract.dependencies.flatMap { dependency ->
            buildList {
                if (!PACKAGE_ID.matches(dependency.packageId)) {
                    add(issue("notes.dependency.id.invalid", contract.packageId, "Dependency id '${dependency.packageId}' is not a valid notes package id."))
                }
                if (dependency.packageId == contract.packageId) {
                    add(issue("notes.dependency.self", contract.packageId, "Notes package must not depend on itself."))
                }
                if (dependency.versionConstraint.isBlank()) {
                    add(issue("notes.dependency.version.missing", contract.packageId, "Dependency '${dependency.packageId}' must declare a version constraint."))
                }
            }
        }

    private fun validateNoRuntimeOrSdkClaims(contract: NotesPackageContract): List<NotesPackageContractIssue> {
        val allText = contract.allText().map { it.lowercase() }
        return FORBIDDEN_CLAIMS.flatMap { forbidden ->
            allText.filter { it.contains(forbidden) }.map {
                issue("notes.boundary.forbidden-claim", contract.packageId, "Notes package must not claim '$forbidden'.")
            }
        }
    }

    private fun validateProjectionHonesty(contract: NotesPackageContract): List<NotesPackageContractIssue> = buildList {
        if (contract.kind != NotesPackageKind.PROJECTION && contract.projectionRules.isNotEmpty()) {
            add(issue("notes.projection.kind-mismatch", contract.packageId, "Only projection notes packages may declare projection rules."))
        }
        if (contract.kind != NotesPackageKind.TARGET && contract.targetCapabilities.isNotEmpty()) {
            add(issue("notes.target.kind-mismatch", contract.packageId, "Only target notes packages may declare target capabilities."))
        }
        if (contract.kind == NotesPackageKind.PROJECTION && contract.runtimeRequirements.isNotEmpty()) {
            add(issue("notes.projection.runtime-ownership", contract.packageId, "Projection notes must not own runtime requirements."))
        }
    }

    private fun NotesPackageContract.allText(): List<String> = listOf(
        packageId,
        packageVersion,
        description
    ) + declaredSemantics + declaredCapabilities + safetyPolicies + runtimeRequirements + targetCapabilities + projectionRules + conformanceChecks +
        dependencies.flatMap { listOf(it.packageId, it.versionConstraint) } +
        boundaries.flatMap { listOf(it.name, it.description) }

    private fun issue(code: String, packageId: String, message: String) =
        NotesPackageContractIssue(code = code, packageId = packageId, message = message)

    companion object {
        private val PACKAGE_ID = Regex("[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)*")
        private val FORBIDDEN_CLAIMS = listOf(
            "runtime executor",
            "sdk api",
            "plugin lifecycle",
            "public dsl",
            "shell projection",
            "command execution",
            "default shell",
            "target-specific semantic"
        )
    }
}

object StandardNotesPackageContracts {
    fun baseline(): List<NotesPackageContract> = listOf(
        NotesPackageContract(
            packageId = "flow.domain.core",
            packageVersion = "0.9.5.3",
            kind = NotesPackageKind.DOMAIN,
            description = "Core target-neutral automation meaning used by Flow before target projection.",
            declaredSemantics = setOf("automation.intent", "semantic.action", "safety.boundary"),
            boundaries = setOf(NotesPackageBoundary("no-target-truth", "Domain notes do not declare concrete target support."))
        ),
        NotesPackageContract(
            packageId = "flow.capability.core",
            packageVersion = "0.9.5.3",
            kind = NotesPackageKind.CAPABILITY,
            description = "Core capability vocabulary without runtime or renderer ownership.",
            declaredCapabilities = setOf("approval.require", "notification.send", "resource.read", "resource.delete", "rollback.perform"),
            dependencies = setOf(NotesPackageDependency("flow.domain.core", "0.9.x")),
            boundaries = setOf(NotesPackageBoundary("semantic-only", "Capabilities define what is meant, not how a target executes it."))
        ),
        NotesPackageContract(
            packageId = "flow.safety.core",
            packageVersion = "0.9.5.3",
            kind = NotesPackageKind.SAFETY,
            description = "Safety classification and approval policy vocabulary.",
            safetyPolicies = setOf("approval.required", "destructive.operation", "environment.sensitive"),
            dependencies = setOf(NotesPackageDependency("flow.capability.core", "0.9.x")),
            boundaries = setOf(NotesPackageBoundary("pre-projection", "Safety notes are evaluated before target rendering."))
        ),
        NotesPackageContract(
            packageId = "flow.runtime.core",
            packageVersion = "0.9.5.3",
            kind = NotesPackageKind.RUNTIME,
            description = "Runtime requirement vocabulary that does not execute work.",
            runtimeRequirements = setOf("input.required", "opaque.value", "human.approval"),
            dependencies = setOf(NotesPackageDependency("flow.safety.core", "0.9.x")),
            boundaries = setOf(NotesPackageBoundary("non-executing", "Runtime notes describe requirements only."))
        ),
        NotesPackageContract(
            packageId = "flow.conformance.core",
            packageVersion = "0.9.5.3",
            kind = NotesPackageKind.CONFORMANCE,
            description = "Conformance checks for notes package contract honesty.",
            conformanceChecks = setOf("notes.contract.valid", "notes.boundary.enforced", "notes.materialization.honest"),
            dependencies = setOf(NotesPackageDependency("flow.runtime.core", "0.9.x")),
            boundaries = setOf(NotesPackageBoundary("evidence-only", "Conformance notes validate declarations without materializing target work."))
        )
    )
}
