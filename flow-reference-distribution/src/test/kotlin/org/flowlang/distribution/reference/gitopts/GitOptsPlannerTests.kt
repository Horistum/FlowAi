package org.flowlang.distribution.reference.gitopts

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GitOptsPlannerTests {
    private val planner = GitOptsPlanner()

    @Test fun committedYamlExampleProducesACompletePullRequestDag() {
        val intent = GitOptsYaml.load(File("reference/git-opts-planner/examples/change-readme.yaml"))
        val plan = planner.plan(intent)

        assertEquals(GIT_OPTS_PLANNER_ID, plan.plannerId)
        assertEquals(
            listOf("checkout", "create-branch", "change-001", "change-002", "commit", "publish-ref", "open-change-request"),
            plan.operations.map(GitOptsOperation::id)
        )
        assertEquals(listOf("change-001", "change-002"), assertIs<GitCreateCommitOperation>(plan.operations[4]).dependsOn)
        assertEquals(listOf("publish-ref"), assertIs<GitOpenChangeRequestOperation>(plan.operations.last()).dependsOn)
        assertTrue(GitOptsCapability.CHANGE_REQUEST_OPEN in plan.requiredCapabilities)
        GitOptsPlanIntegrity.requireValid(plan)
    }

    @Test fun equivalentChangesProduceTheSameCanonicalPlanAndJsonRegardlessOfAuthoredOrder() {
        val first = baseIntent(
            changes = listOf(
                GitFileChange("z.txt", GitFileOperation.UPSERT, "z"),
                GitFileChange("a.txt", GitFileOperation.CREATE, "a")
            )
        )
        val second = first.copy(changes = first.changes.reversed())

        val firstPlan = planner.plan(first)
        val secondPlan = planner.plan(second)
        assertEquals(firstPlan, secondPlan)
        assertEquals(GitOptsYaml.renderPlan(firstPlan), GitOptsYaml.renderPlan(secondPlan))
        val mutations = firstPlan.operations.filterIsInstance<GitFileMutationOperation>()
        assertEquals(listOf("a.txt", "z.txt"), mutations.map(GitFileMutationOperation::path))
    }

    @Test fun operationTypeOwnsItsSemanticIdentity() {
        val checkout = GitCheckoutOperation(repository = "file:///tmp/origin.git", ref = "main")
        assertEquals("scm.repository.checkout", checkout.kind)
        assertEquals(GitOptsCapability.REPOSITORY_READ, checkout.capability)

        val delete = GitFileMutationOperation(
            id = "delete",
            path = "obsolete.txt",
            operation = GitFileOperation.DELETE,
            content = null
        )
        assertEquals("workspace.file.mutate", delete.kind)
        assertEquals(GitOptsCapability.FILE_DELETE, delete.capability)
    }

    @Test fun localDeliveryStopsAtTheCommitBoundary() {
        val plan = planner.plan(baseIntent())
        assertIs<GitCreateCommitOperation>(plan.operations.last())
        assertFalse(plan.operations.any { it is GitPublishRefOperation })
        assertFalse(plan.operations.any { it is GitOpenChangeRequestOperation })
        assertFalse(GitOptsCapability.REF_PUBLISH in plan.requiredCapabilities)
        assertFalse(GitOptsCapability.CHANGE_REQUEST_OPEN in plan.requiredCapabilities)
    }

    @Test fun unsafeWorkspacePathsFailBeforePlanning() {
        val failure = assertFailsWith<GitOptsPlanningException> {
            planner.plan(baseIntent(changes = listOf(GitFileChange("../secret", GitFileOperation.UPSERT, "no"))))
        }
        assertTrue(failure.diagnostics.any { it.code == "GIT_OPTS_INVALID_PATH" && it.path == "changes[0].path" })
    }

    @Test fun gitAdministrativeMetadataCannotBeChanged() {
        val failure = assertFailsWith<GitOptsPlanningException> {
            planner.plan(baseIntent(changes = listOf(GitFileChange(".git/config", GitFileOperation.UPDATE, "no"))))
        }
        assertTrue(failure.diagnostics.any { it.code == "GIT_OPTS_INVALID_PATH" })
    }

    @Test fun httpRepositoryCannotSmuggleCredentialsIntoCanonicalIntent() {
        val failure = assertFailsWith<GitOptsPlanningException> {
            planner.plan(baseIntent(repository = GitRepositorySpec("https://token@example.invalid/Horistum/example.git")))
        }
        assertTrue(failure.diagnostics.any { it.code == "GIT_OPTS_REPOSITORY_EMBEDS_CREDENTIALS" })
    }

    @Test fun unsafeGitRefShapesFailBeforePlanning() {
        listOf("@", ".hidden/change", "feature/../main", "feature.lock").forEach { branch ->
            val failure = assertFailsWith<GitOptsPlanningException> { planner.plan(baseIntent(branch = branch)) }
            assertTrue(failure.diagnostics.any { it.code == "GIT_OPTS_INVALID_REF" && it.path == "branch" }, branch)
        }
    }

    @Test fun pullRequestDeliveryRequiresAnExplicitTitle() {
        val failure = assertFailsWith<GitOptsPlanningException> {
            planner.plan(baseIntent(delivery = GitDeliverySpec(mode = GitDeliveryMode.PULL_REQUEST)))
        }
        assertTrue(failure.diagnostics.any { it.code == "GIT_OPTS_MISSING_CHANGE_REQUEST_TITLE" })
    }

    @Test fun planIntegrityRejectsAForgedDependencyCycle() {
        val plan = planner.plan(baseIntent())
        val forged = plan.copy(operations = plan.operations.map { operation ->
            if (operation is GitCreateBranchOperation) operation.copy(dependsOn = listOf("commit")) else operation
        })
        val failure = assertFailsWith<IllegalArgumentException> { GitOptsPlanIntegrity.requireValid(forged) }
        assertTrue(failure.message.orEmpty().contains("dependency cycle"))
    }

    @Test fun requiredCapabilitiesCannotBeForgedIndependentlyOfOperations() {
        val plan = planner.plan(baseIntent())
        val forged = plan.copy(requiredCapabilities = emptyList())
        val failure = assertFailsWith<IllegalArgumentException> { GitOptsPlanIntegrity.requireValid(forged) }
        assertTrue(failure.message.orEmpty().contains("derived exactly"))
    }

    @Test fun yamlDecoderRejectsUnknownFieldsInsteadOfSilentlyIgnoringThem() {
        withTempYaml(
            """
            apiVersion: horistum.dev/git-opts/v1
            kind: GitOptsIntent
            repository:
              url: https://github.com/Horistum/example.git
            branch: example/change
            changes:
              - path: README.md
                operation: upsert
                content: ok
            commit:
              message: test
            inventedField: true
            """
        ) { file -> assertFailsWith<Exception> { GitOptsYaml.load(file) } }
    }

    @Test fun yamlDecoderRejectsDuplicateAuthoredFields() {
        withTempYaml(
            """
            apiVersion: horistum.dev/git-opts/v1
            kind: GitOptsIntent
            repository:
              url: https://github.com/Horistum/example.git
            branch: example/first
            branch: example/second
            changes:
              - path: README.md
                operation: upsert
                content: ok
            commit:
              message: test
            """
        ) { file -> assertFailsWith<Exception> { GitOptsYaml.load(file) } }
    }

    @Test fun yamlDecoderRequiresVersionAndKindRatherThanInventingThem() {
        withTempYaml(
            """
            repository:
              url: https://github.com/Horistum/example.git
            branch: example/change
            changes:
              - path: README.md
                operation: upsert
                content: ok
            commit:
              message: test
            """
        ) { file -> assertFailsWith<Exception> { GitOptsYaml.load(file) } }
    }

    private fun withTempYaml(source: String, block: (File) -> Unit) {
        val temp = File.createTempFile("git-opts-test", ".yaml")
        try {
            temp.writeText(source.trimIndent())
            block(temp)
        } finally {
            temp.delete()
        }
    }

    private fun baseIntent(
        repository: GitRepositorySpec = GitRepositorySpec("https://github.com/Horistum/example.git"),
        branch: String = "example/reference-change",
        changes: List<GitFileChange> = listOf(GitFileChange("README.md", GitFileOperation.UPSERT, "# Horistum\n")),
        delivery: GitDeliverySpec = GitDeliverySpec()
    ) = GitOptsIntent(
        apiVersion = GIT_OPTS_API_VERSION,
        kind = GIT_OPTS_KIND,
        repository = repository,
        baseRef = "main",
        branch = branch,
        changes = changes,
        commit = GitCommitSpec("docs: demonstrate git opts planner"),
        delivery = delivery
    )
}
