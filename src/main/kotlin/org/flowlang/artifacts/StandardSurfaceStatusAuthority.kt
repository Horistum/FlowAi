package org.flowlang.artifacts

/** Computes public report status from report content instead of self-certifying literals. */
object StandardSurfaceStatusAuthority {
    private val allowedStabilities = setOf("stable", "draft", "experimental", "internal")
    private val allowedScenarioStatuses = setOf("ACCEPTED", "BLOCKED")

    fun publicSurface(entries: List<PublicSurfaceEntry>, requiredChangeGates: List<String>): String = status(
        entries.isNotEmpty(),
        entries.map { it.artifact }.distinct().size == entries.size,
        entries.all {
            it.artifact.isNotBlank() &&
                it.schema.isNotBlank() &&
                it.stability in allowedStabilities &&
                it.area.isNotBlank() &&
                it.introducedIn.isNotBlank() &&
                it.changeGate.isNotBlank()
        },
        requiredChangeGates.isNotEmpty(),
        requiredChangeGates.none(String::isBlank),
        requiredChangeGates.distinct().size == requiredChangeGates.size
    )

    fun compatibilityMigrationPolicy(
        rules: List<CompatibilityPolicyRule>,
        deprecationWindowMinorReleases: Int,
        migrationArtifacts: List<String>,
        breakingChangeGate: String
    ): String = status(
        rules.isNotEmpty(),
        rules.map { it.id }.distinct().size == rules.size,
        rules.none { it.id.isBlank() || it.category.isBlank() || it.description.isBlank() },
        rules.filter { it.category == "breaking" }.all {
            !it.allowedInMinor && it.requiresMigrationNote && it.requiresDeprecationWindow
        },
        deprecationWindowMinorReleases > 0,
        migrationArtifacts.isNotEmpty(),
        migrationArtifacts.none(String::isBlank),
        breakingChangeGate.isNotBlank()
    )

    fun referenceIntentCorpus(
        scenarios: List<ReferenceIntentScenario>,
        requiredScenarioIds: List<String>,
        negativeScenarioIds: List<String>
    ): String {
        val ids = scenarios.map { it.id }
        val blocked = scenarios.filter { it.expectedStatus == "BLOCKED" }.map { it.id }
        return status(
            scenarios.isNotEmpty(),
            ids.none(String::isBlank),
            ids.distinct().size == ids.size,
            scenarios.all { it.expectedStatus in allowedScenarioStatuses },
            requiredScenarioIds == ids,
            negativeScenarioIds == blocked,
            blocked.isNotEmpty(),
            scenarios.filter { it.expectedStatus == "BLOCKED" }.all {
                it.expectedRequiredClarifications.isNotEmpty() || it.expectedRejectionCodes.isNotEmpty()
            }
        )
    }

    fun referenceCorpusExecutionHarness(
        corpus: ReferenceIntentCorpusReport,
        replayStages: List<String>,
        assertions: List<ReferenceCorpusHarnessAssertion>,
        mustLowerAccepted: Boolean,
        mustBlockNegative: Boolean
    ): String = status(
        corpus.status == "PASS",
        replayStages.isNotEmpty(),
        replayStages.none(String::isBlank),
        assertions.isNotEmpty(),
        assertions.map { it.id }.distinct().size == assertions.size,
        assertions.all { it.id.isNotBlank() && it.scope.isNotBlank() && it.description.isNotBlank() },
        assertions.any { it.id == "corpus.accepted-lowers" && it.blocking },
        assertions.any { it.id == "corpus.blocked-stays-blocked" && it.blocking },
        mustLowerAccepted,
        mustBlockNegative
    )

    fun targetSemanticsMatrix(targetIds: List<String>, entries: List<TargetSemanticsEntry>): String {
        val allowed = setOf(
            "native",
            "partial",
            "unsupported-blocked",
            "adapter-required-review-only",
            "not-declared-review-only"
        )
        return status(
            targetIds.isNotEmpty(),
            targetIds.none(String::isBlank),
            targetIds.distinct().size == targetIds.size,
            entries.isNotEmpty(),
            entries.map { it.feature }.distinct().size == entries.size,
            entries.all { entry ->
                entry.feature.isNotBlank() &&
                    entry.semanticsByTarget.keys == targetIds.toSet() &&
                    entry.semanticsByTarget.values.all { it in allowed } &&
                    entry.requiredDiagnosticWhenUnsupported.isNotBlank()
            }
        )
    }

    fun standardExportBundle(
        requiredDirectories: List<String>,
        requiredFiles: List<String>,
        requiredArtifacts: List<String>,
        packageName: String
    ): String = status(
        requiredDirectories.isNotEmpty(),
        requiredDirectories.none(String::isBlank),
        requiredDirectories.distinct().size == requiredDirectories.size,
        requiredFiles.isNotEmpty(),
        requiredFiles.none(String::isBlank),
        requiredFiles.distinct().size == requiredFiles.size,
        requiredArtifacts.isNotEmpty(),
        requiredArtifacts.none(String::isBlank),
        requiredArtifacts.distinct().size == requiredArtifacts.size,
        packageName.isNotBlank()
    )

    fun conformanceLevels(
        defaultLevel: String,
        levels: List<ConformanceLevelEntry>,
        levelOrder: List<String>
    ): String {
        val ids = levels.map { it.id }
        return status(
            levels.isNotEmpty(),
            ids.none(String::isBlank),
            ids.distinct().size == ids.size,
            defaultLevel in ids,
            levelOrder == ids,
            levels.all {
                it.title.isNotBlank() &&
                    it.description.isNotBlank() &&
                    it.requiredChecks.isNotEmpty() &&
                    it.requiredChecks.none(String::isBlank) &&
                    it.requiredArtifacts.isNotEmpty() &&
                    it.requiredArtifacts.none(String::isBlank)
            }
        )
    }

    fun standardExportManifest(
        candidate: String,
        requiredDocuments: List<String>,
        requiredJsonArtifacts: List<String>,
        requiredSchemas: List<String>,
        requiredDirectories: List<String>,
        releaseGateChecks: List<String>,
        evidenceArtifacts: List<String>,
        selfVerificationCommands: List<String>,
        verificationInputs: List<String>
    ): String = status(
        candidate.isNotBlank(),
        requiredDocuments.isNotEmpty() && requiredDocuments.none(String::isBlank),
        requiredJsonArtifacts.isNotEmpty() && requiredJsonArtifacts.none(String::isBlank),
        requiredSchemas.isNotEmpty() && requiredSchemas.none(String::isBlank),
        requiredDirectories.isNotEmpty() && requiredDirectories.none(String::isBlank),
        releaseGateChecks.isNotEmpty() && releaseGateChecks.none(String::isBlank),
        evidenceArtifacts.isNotEmpty() && evidenceArtifacts.none(String::isBlank),
        selfVerificationCommands.isNotEmpty() && selfVerificationCommands.none(String::isBlank),
        verificationInputs.isNotEmpty() && verificationInputs.none(String::isBlank)
    )

    private fun status(vararg conditions: Boolean): String =
        if (conditions.all { it }) "PASS" else "FAIL"
}
