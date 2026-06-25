package org.flowlang.standard

data class CoreContractCheckReport(
    val standardVersion: String = FlowStandardVersions.FLOW_STANDARD_VERSION,
    val status: String,
    val requiredArtifacts: List<String>,
    val stableArtifacts: List<String>,
    val candidateChecks: List<String>,
    val issues: List<String>
)

object CoreContractCheck {
    private val requiredArtifacts = listOf(
        "intent.schema.json",
        "execution-plan.schema.json",
        "target-manifest.schema.json",
        "public-standard-surface.json",
        "compatibility-migration-policy.json",
        "reference-intent-corpus.json",
        "target-semantics-matrix.json",
        "standard-export-bundle.json",
        "conformance-levels.json",
        "standard-export-manifest.json",
        "conformance-vector-index.json"
    )

    fun report(): CoreContractCheckReport {
        val stableArtifacts = StandardModel.stableArtifacts()
        val candidateChecks = StandardModel.candidateCheckIds()
        val issues = mutableListOf<String>()

        requiredArtifacts.filterNot { it in stableArtifacts }.also { missing ->
            if (missing.isNotEmpty()) issues += "Missing artifacts: ${missing.joinToString()}"
        }

        stableArtifacts.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.sorted().also { duplicates ->
            if (duplicates.isNotEmpty()) issues += "Duplicate artifacts: ${duplicates.joinToString()}"
        }

        candidateChecks.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.sorted().also { duplicates ->
            if (duplicates.isNotEmpty()) issues += "Duplicate checks: ${duplicates.joinToString()}"
        }

        StandardModel.wellFormednessIssues().forEach { issues += "Model issue: $it" }
        if (candidateChecks.isEmpty()) issues += "Candidate checks must not be empty."

        return CoreContractCheckReport(
            status = if (issues.isEmpty()) "PASS" else "FAIL",
            requiredArtifacts = requiredArtifacts,
            stableArtifacts = stableArtifacts,
            candidateChecks = candidateChecks,
            issues = issues.sorted()
        )
    }
}
