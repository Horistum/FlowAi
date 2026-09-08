package org.flowlang.tests

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.roadmap.ToolchainModernizationLifecycle
import org.flowlang.roadmap.ToolchainModernizationPhase

class ToolchainModernizationLifecycleTests {
    @Test
    fun activeMilestonePreservesClosedProductRoadmaps() {
        val root = activeFixture()

        val report = ToolchainModernizationLifecycle(root).analyze()

        assertEquals(ToolchainModernizationPhase.ACTIVE, report.phase, report.errors.joinToString(" | "))
        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
    }

    @Test
    fun activeMilestoneRejectsFabricatedProductSuccessor() {
        val root = activeFixture()
        val roadmap = File(root, ToolchainModernizationLifecycle.ROADMAP)
        replaceRequired(roadmap, "nextItem: \"\"", "nextItem: \"EXTERNAL-FALSIFICATION\"")

        val report = ToolchainModernizationLifecycle(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "must not impersonate a product-roadmap successor" in it })
    }

    @Test
    fun activeMilestoneRejectsUnselectedToolchainVersion() {
        val root = activeFixture()
        val build = File(root, ToolchainModernizationLifecycle.BUILD_FILE)
        replaceRequired(build, "version \"1.9.24\"", "version \"2.3.0\"")

        val report = ToolchainModernizationLifecycle(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "must follow the approved sequence" in it })
    }

    @Test
    fun kotlinMigrationRequiresImplementedWorkPackageStep() {
        val root = activeFixture()
        val build = File(root, ToolchainModernizationLifecycle.BUILD_FILE)
        replaceRequired(build, "version \"1.9.24\"", "version \"2.4.10\"")

        val report = ToolchainModernizationLifecycle(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "KOTLIN step to be implemented or validated" in it })
    }

    @Test
    fun kotlinMigrationWithBaselineGradleIsAccepted() {
        val root = activeFixture()
        val build = File(root, ToolchainModernizationLifecycle.BUILD_FILE)
        replaceRequired(build, "version \"1.9.24\"", "version \"2.4.10\"")
        setStepStatus(root, "KOTLIN", "planned", "implemented")

        val report = ToolchainModernizationLifecycle(root).analyze()

        assertEquals(ToolchainModernizationPhase.ACTIVE, report.phase, report.errors.joinToString(" | "))
        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
    }

    @Test
    fun gradleFirstMigrationIsRejectedEvenWhenBothVersionsAreSelectedEndpoints() {
        val root = activeFixture()
        val wrapper = File(root, ToolchainModernizationLifecycle.WRAPPER_FILE)
        replaceRequired(wrapper, "gradle-8.10.2-bin.zip", "gradle-9.5.0-bin.zip")

        val report = ToolchainModernizationLifecycle(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "must follow the approved sequence" in it })
    }

    @Test
    fun gradleBoundaryRequiresValidatedKotlinEvidence() {
        val root = activeFixture()
        migrateRepositoryToGradle95(root)
        setStepStatus(root, "KOTLIN", "planned", "implemented")
        setStepStatus(root, "GRADLE", "planned", "implemented")

        val report = ToolchainModernizationLifecycle(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "KOTLIN step must be 'validated'" in it })
    }

    @Test
    fun gradleBoundaryIsReachableAfterValidatedKotlinEvidence() {
        val root = activeFixture()
        migrateRepositoryToGradle95(root)
        setStepValidated(root, "KOTLIN", 3000, 40000000000, 'a', 'b')
        setStepStatus(root, "GRADLE", "planned", "implemented")

        val report = ToolchainModernizationLifecycle(root).analyze()

        assertEquals(ToolchainModernizationPhase.ACTIVE, report.phase, report.errors.joinToString(" | "))
        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
    }

    @Test
    fun jdkMigrationRequiresValidatedGradleEvidence() {
        val root = activeFixture()
        migrateRepositoryToJdk25(root)
        setStepValidated(root, "KOTLIN", 3000, 40000000000, 'a', 'b')
        setStepStatus(root, "GRADLE", "planned", "implemented")
        setStepStatus(root, "JDK", "planned", "implemented")

        val report = ToolchainModernizationLifecycle(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "GRADLE step must be 'validated'" in it })
    }

    @Test
    fun jdkMigrationIsReachableAfterValidatedGradleEvidence() {
        val root = activeFixture()
        migrateRepositoryToJdk25(root)
        setStepValidated(root, "KOTLIN", 3000, 40000000000, 'a', 'b')
        setStepValidated(root, "GRADLE", 3001, 40000000001, 'c', 'd')
        setStepStatus(root, "JDK", "planned", "implemented")

        val report = ToolchainModernizationLifecycle(root).analyze()

        assertEquals(ToolchainModernizationPhase.ACTIVE, report.phase, report.errors.joinToString(" | "))
        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
    }

    @Test
    fun unsupportedJdkEndpointIsRejected() {
        val root = activeFixture()
        migrateRepositoryToGradle95(root)
        val build = File(root, ToolchainModernizationLifecycle.BUILD_FILE)
        replaceRequired(build, "jvmToolchain(21)", "jvmToolchain(24)")

        val report = ToolchainModernizationLifecycle(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "must follow the approved sequence" in it })
    }

    @Test
    fun offlineRefreshCannotAdvanceBeforeJdkValidation() {
        val root = activeFixture()
        migrateRepositoryToJdk25(root)
        setStepValidated(root, "KOTLIN", 3000, 40000000000, 'a', 'b')
        setStepValidated(root, "GRADLE", 3001, 40000000001, 'c', 'd')
        setStepStatus(root, "JDK", "planned", "implemented")
        setStepStatus(root, "OFFLINE-REFRESH", "planned", "implemented")

        val report = ToolchainModernizationLifecycle(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "OFFLINE-REFRESH step must be 'planned'" in it })
    }

    @Test
    fun offlineRefreshCanAdvanceAfterValidatedJdkEvidence() {
        val root = activeFixture()
        migrateRepositoryToJdk25(root)
        setStepValidated(root, "KOTLIN", 3000, 40000000000, 'a', 'b')
        setStepValidated(root, "GRADLE", 3001, 40000000001, 'c', 'd')
        setStepValidated(root, "JDK", 3002, 40000000002, 'e', 'f')
        setStepStatus(root, "OFFLINE-REFRESH", "planned", "implemented")

        val report = ToolchainModernizationLifecycle(root).analyze()

        assertEquals(ToolchainModernizationPhase.ACTIVE, report.phase, report.errors.joinToString(" | "))
        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
    }

    @Test
    fun validatedJdkRequiresDistinctExactHeadAndMergeCandidateEvidence() {
        val root = activeFixture()
        migrateRepositoryToJdk25(root)
        setStepValidated(root, "KOTLIN", 3000, 40000000000, 'a', 'b')
        setStepValidated(root, "GRADLE", 3001, 40000000001, 'c', 'd')
        setStepValidated(root, "JDK", 3002, 40000000002, 'e', 'e')

        val report = ToolchainModernizationLifecycle(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "JDK validated status requires complete passed exact-head and merge-candidate Flow CI evidence" in it })
    }

    @Test
    fun activeMilestoneRejectsSemanticIntegrityReopening() {
        val root = activeFixture()
        val semantic = File(root, ToolchainModernizationLifecycle.SEMANTIC_INTEGRITY_ROADMAP)
        replaceRequired(semantic, "status: completed", "status: active")

        val report = ToolchainModernizationLifecycle(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.errors.any { "terminally completed at SI-08" in it })
    }

    @Test
    fun completionRequiresDistinctImplementationAndCompletionBoundaries() {
        val root = activeFixture()
        val roadmap = File(root, ToolchainModernizationLifecycle.ROADMAP)
        replaceRequired(roadmap, "status: active", "status: completed")
        replaceRequired(roadmap, "activeEnablingMilestone: \"TOOLCHAIN-MODERNIZATION\"", "activeEnablingMilestone: \"\"")
        replaceRequired(roadmap, "activeEnablingMilestoneName: \"Kotlin and Gradle Toolchain Modernization\"", "activeEnablingMilestoneName: \"\"")
        val release = File(root, ToolchainModernizationLifecycle.RELEASE_STATE)
        replaceRequired(release, "activeEnablingMilestone: \"TOOLCHAIN-MODERNIZATION\"", "activeEnablingMilestone: \"\"")
        replaceRequired(release, "activeEnablingMilestoneName: \"Kotlin and Gradle Toolchain Modernization\"", "activeEnablingMilestoneName: \"\"")
        val workPackage = File(root, ToolchainModernizationLifecycle.WORK_PACKAGE)
        replaceRequired(workPackage, "status: active", "status: complete")
        replaceRequired(workPackage, "authorization:\n  status: active", "authorization:\n  status: completed")
        workPackage.appendText(
            """
            implementationEvidence:
              status: passed
              workflow: "Flow CI"
              runNumber: 3000
              runId: 40000000000
              exactHead: "${"a".repeat(40)}"
              mergeCandidate: "${"b".repeat(40)}"
            completionBoundary:
              status: passed
              workflow: "Flow CI"
              runNumber: 3001
              runId: 40000000001
              exactHead: "${"c".repeat(40)}"
              mergeCandidate: "${"d".repeat(40)}"
            completionMergeCommit: "${"e".repeat(40)}"
            """.trimIndent() + "\n"
        )

        val report = ToolchainModernizationLifecycle(root).analyze()

        assertEquals(ToolchainModernizationPhase.COMPLETE, report.phase, report.errors.joinToString(" | "))
        assertEquals("PASS", report.status, report.errors.joinToString(" | "))
    }

    private fun activeFixture(): File {
        val root = createTempDirectory("flow-toolchain-modernization").toFile()
        write(root, ToolchainModernizationLifecycle.ROADMAP, """
            project: Flow
            roadmapVersion: 29
            currentTrack: "toolchain-modernization"
            primaryRoadmapStream: conformance
            versionBoundary:
              publishedPackageVersion: "0.9.5"
              activePublicStandardVersion: "0.8.0"
              artifactContracts:
                intent: "2.0"
                ast: "2.2"
                executionPlan: "2.4"
                executionPlanLoweringEvidence: "2.1"
                targetManifest: "3.0"
                targetRegistry: "3.2"
            strategicDirection:
              enablingMilestones:
                - id: "TOOLCHAIN-MODERNIZATION"
                  status: active
                  workPackage: ".flow-agent/work-packages/TOOLCHAIN-MODERNIZATION.yaml"
            currentDecision:
              closureItem: "0.9.7.10"
              closureItemStatus: completed
              completedConformanceItem: "C1.0"
              completedArchitectureItem: "AR0.1"
              activeEnablingMilestone: "TOOLCHAIN-MODERNIZATION"
              activeEnablingMilestoneName: "Kotlin and Gradle Toolchain Modernization"
              nextItem: ""
              nextItemName: ""
              nextItemStream: ""
        """)
        write(root, ToolchainModernizationLifecycle.RELEASE_STATE, """
            project: Flow
            versionBoundary:
              publishedPackageVersion: "0.9.5"
              publicStandardVersion: "0.8.0"
              artifactContracts:
                intent: "2.0"
                ast: "2.2"
                executionPlan: "2.4"
                executionPlanLoweringEvidence: "2.1"
                targetManifest: "3.0"
                targetRegistry: "3.2"
            roadmapState:
              roadmapVersion: 29
              activeTrack: "toolchain-modernization"
              primaryStream: "conformance"
              closureItem: "0.9.7.10"
              closureItemStatus: completed
              completedConformanceItem: "C1.0"
              completedArchitectureItem: "AR0.1"
              activeEnablingMilestone: "TOOLCHAIN-MODERNIZATION"
              activeEnablingMilestoneName: "Kotlin and Gradle Toolchain Modernization"
              nextItem: ""
              nextItemName: ""
              nextItemStream: ""
        """)
        write(root, ToolchainModernizationLifecycle.SEMANTIC_INTEGRITY_ROADMAP, """
            stream: semantic-integrity
            status: completed
            currentDecision:
              completedItem: "SI-08"
              nextItem: ""
              nextItemName: ""
        """)
        write(root, ToolchainModernizationLifecycle.WORK_PACKAGE, """
            version: "TOOLCHAIN-MODERNIZATION"
            name: "Kotlin and Gradle Toolchain Modernization"
            type: "enabling-maintenance"
            status: active
            stream: "enabling-maintenance"
            authorization:
              status: active
              predecessor: "SI-08"
              strategicSource: "docs/PROJECT_SEMANTIC_INTEGRITY_DIRECTION.md#enabling-milestone-kotlin-and-gradle-toolchain-modernization"
            baseline:
              kotlin: "1.9.24"
              gradle: "8.10.2"
              jdk: "21"
            selectedTarget:
              kotlin: "2.4.10"
              gradle: "9.5.0"
              jdk: "25"
            sequence:
              - id: "KOTLIN"
                status: planned
              - id: "GRADLE"
                status: planned
              - id: "JDK"
                status: planned
              - id: "OFFLINE-REFRESH"
                status: planned
            localValidation:
              status: pending
        """)
        write(root, ToolchainModernizationLifecycle.BUILD_FILE, """
            plugins {
                kotlin("jvm") version "1.9.24"
                application
            }
            version = "0.9.5"
            kotlin { jvmToolchain(21) }
        """)
        write(root, ToolchainModernizationLifecycle.WRAPPER_FILE, """
            distributionUrl=https\://services.gradle.org/distributions/gradle-8.10.2-bin.zip
        """)
        return root
    }

    private fun migrateRepositoryToGradle95(root: File) {
        val build = File(root, ToolchainModernizationLifecycle.BUILD_FILE)
        val wrapper = File(root, ToolchainModernizationLifecycle.WRAPPER_FILE)
        replaceRequired(build, "version \"1.9.24\"", "version \"2.4.10\"")
        replaceRequired(wrapper, "gradle-8.10.2-bin.zip", "gradle-9.5.0-bin.zip")
    }

    private fun migrateRepositoryToJdk25(root: File) {
        migrateRepositoryToGradle95(root)
        val build = File(root, ToolchainModernizationLifecycle.BUILD_FILE)
        replaceRequired(build, "jvmToolchain(21)", "jvmToolchain(25)")
    }

    private fun setStepStatus(root: File, stepId: String, oldStatus: String, newStatus: String) {
        val workPackage = File(root, ToolchainModernizationLifecycle.WORK_PACKAGE)
        replaceRequired(
            workPackage,
            "  - id: \"$stepId\"\n    status: $oldStatus",
            "  - id: \"$stepId\"\n    status: $newStatus"
        )
    }

    private fun setStepValidated(
        root: File,
        stepId: String,
        runNumber: Int,
        runId: Long,
        exactHeadChar: Char,
        mergeCandidateChar: Char
    ) {
        val workPackage = File(root, ToolchainModernizationLifecycle.WORK_PACKAGE)
        val replacement = listOf(
            "  - id: \"$stepId\"",
            "    status: validated",
            "    validationEvidence:",
            "      status: passed",
            "      workflow: \"Flow CI\"",
            "      runNumber: $runNumber",
            "      runId: $runId",
            "      exactHead: \"${exactHeadChar.toString().repeat(40)}\"",
            "      mergeCandidate: \"${mergeCandidateChar.toString().repeat(40)}\""
        ).joinToString("\n")
        replaceRequired(
            workPackage,
            "  - id: \"$stepId\"\n    status: planned",
            replacement
        )
    }

    private fun replaceRequired(file: File, oldValue: String, newValue: String) {
        val original = file.readText()
        require(oldValue in original) { "Mutation source '$oldValue' is missing from ${file.path}." }
        file.writeText(original.replaceFirst(oldValue, newValue))
    }

    private fun write(root: File, path: String, content: String) {
        File(root, path).apply {
            parentFile.mkdirs()
            writeText(content.trimIndent() + "\n")
        }
    }
}
