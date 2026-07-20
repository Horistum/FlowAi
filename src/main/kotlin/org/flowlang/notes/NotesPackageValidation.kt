package org.flowlang.notes

class NotesPackageContractValidator {
    fun validate(contract: NotesPackageContract): NotesPackageContractReport = validateAll(listOf(contract))

    fun validateAll(contracts: List<NotesPackageContract>): NotesPackageContractReport {
        val issues = mutableListOf<NotesPackageContractIssue>()
        val ids = mutableSetOf<String>()
        contracts.forEach { contract ->
            val id = contract.packageId
            if (!PACKAGE_ID.matches(id)) {
                issues += issue("notes.package.id.invalid", id, "Notes package id must be lowercase dot-separated text.")
            }
            if (!ids.add(id)) {
                issues += issue("notes.package.id.duplicate", id, "Notes package id must be unique.")
            }
            if (contract.packageVersion.isBlank()) {
                issues += issue("notes.package.version.missing", id, "Notes package version is required.")
            }
            if (contract.description.isBlank()) {
                issues += issue("notes.package.description.missing", id, "Notes package description is required.")
            }
            if (contract.declarationsForKind().isEmpty()) {
                issues += issue("notes.package.declaration.missing", id, "Notes package must declare meaning for its kind.")
            }
            if (contract.boundaries.isEmpty()) {
                issues += issue("notes.package.boundary.missing", id, "Notes package must declare architectural boundaries.")
            }
            issues += validateDependencies(contract)
            issues += validateClaims(contract)
            issues += validateOwnership(contract)
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
                    add(issue("notes.dependency.id.invalid", contract.packageId, "Dependency id is invalid: ${dependency.packageId}"))
                }
                if (dependency.packageId == contract.packageId) {
                    add(issue("notes.dependency.self", contract.packageId, "Notes package must not depend on itself."))
                }
                if (dependency.versionConstraint.isBlank()) {
                    add(issue("notes.dependency.version.missing", contract.packageId, "Dependency version is required."))
                }
            }
        }

    private fun validateClaims(contract: NotesPackageContract): List<NotesPackageContractIssue> {
        val text = contract.allText().map(String::lowercase)
        return FORBIDDEN_CLAIMS.flatMap { forbidden ->
            text.filter { it.contains(forbidden) }.map {
                issue("notes.boundary.forbidden-claim", contract.packageId, "Notes package must not claim '$forbidden'.")
            }
        }
    }

    private fun validateOwnership(contract: NotesPackageContract): List<NotesPackageContractIssue> = buildList {
        if (contract.kind != NotesPackageKind.PROJECTION && contract.projectionRules.isNotEmpty()) {
            add(issue("notes.projection.kind-mismatch", contract.packageId, "Only projection notes may declare projection rules."))
        }
        if (contract.kind != NotesPackageKind.TARGET && contract.targetCapabilities.isNotEmpty()) {
            add(issue("notes.target.kind-mismatch", contract.packageId, "Only target notes may declare target capabilities."))
        }
        if (contract.kind == NotesPackageKind.PROJECTION && contract.runtimeRequirements.isNotEmpty()) {
            add(issue("notes.projection.runtime-ownership", contract.packageId, "Projection notes must not own runtime requirements."))
        }
    }

    private fun NotesPackageContract.allText(): List<String> = listOf(packageId, packageVersion, description) +
        declaredSemantics + declaredCapabilities + safetyPolicies + runtimeRequirements +
        targetCapabilities + projectionRules + conformanceChecks +
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
