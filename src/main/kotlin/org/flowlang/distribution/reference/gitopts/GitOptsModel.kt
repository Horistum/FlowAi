package org.flowlang.distribution.reference.gitopts

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

const val GIT_OPTS_API_VERSION: String = "horistum.dev/git-opts/v1"
const val GIT_OPTS_KIND: String = "GitOptsIntent"
const val GIT_OPTS_PLANNER_ID: String = "git-opts-planner"
const val GIT_OPTS_PLANNER_VERSION: String = "1.0.0"

data class GitOptsIntent(
    val apiVersion: String = GIT_OPTS_API_VERSION,
    val kind: String = GIT_OPTS_KIND,
    val repository: GitRepositorySpec,
    val baseRef: String = "main",
    val branch: String,
    val changes: List<GitFileChange>,
    val commit: GitCommitSpec,
    val delivery: GitDeliverySpec = GitDeliverySpec()
)

data class GitRepositorySpec(val url: String)

data class GitFileChange(
    val path: String,
    val operation: GitFileOperation,
    val content: String? = null
)

enum class GitFileOperation(private val wireName: String) {
    CREATE("create"),
    UPDATE("update"),
    UPSERT("upsert"),
    DELETE("delete");

    @JsonValue fun toWireName(): String = wireName

    companion object {
        @JvmStatic
        @JsonCreator
        fun fromWireName(value: String): GitFileOperation = entries.firstOrNull { it.wireName == value }
            ?: throw IllegalArgumentException("Unknown git file operation '$value'.")
    }
}

data class GitCommitSpec(val message: String)

data class GitDeliverySpec(
    val mode: GitDeliveryMode = GitDeliveryMode.LOCAL,
    val title: String? = null,
    val body: String? = null
)

enum class GitDeliveryMode(private val wireName: String) {
    LOCAL("local"),
    PUSH("push"),
    PULL_REQUEST("pull-request");

    @JsonValue fun toWireName(): String = wireName

    companion object {
        @JvmStatic
        @JsonCreator
        fun fromWireName(value: String): GitDeliveryMode = entries.firstOrNull { it.wireName == value }
            ?: throw IllegalArgumentException("Unknown git delivery mode '$value'.")
    }
}

enum class GitOptsCapability(private val wireName: String) {
    REPOSITORY_READ("scm.repository.read"),
    BRANCH_CREATE("scm.branch.create"),
    FILE_CREATE("workspace.file.create"),
    FILE_UPDATE("workspace.file.update"),
    FILE_UPSERT("workspace.file.upsert"),
    FILE_DELETE("workspace.file.delete"),
    COMMIT_CREATE("scm.commit.create"),
    REF_PUBLISH("scm.ref.publish"),
    CHANGE_REQUEST_OPEN("scm.change-request.open");

    @JsonValue fun toWireName(): String = wireName
}

sealed interface GitOptsOperation {
    val id: String
    val kind: String
    val dependsOn: List<String>
    val capability: GitOptsCapability
}

data class GitCheckoutOperation(
    override val id: String = "checkout",
    val repository: String,
    val ref: String,
    override val dependsOn: List<String> = emptyList(),
    override val kind: String = "scm.repository.checkout",
    override val capability: GitOptsCapability = GitOptsCapability.REPOSITORY_READ
) : GitOptsOperation

data class GitCreateBranchOperation(
    override val id: String = "create-branch",
    val branch: String,
    val fromRef: String,
    override val dependsOn: List<String> = listOf("checkout"),
    override val kind: String = "scm.branch.create",
    override val capability: GitOptsCapability = GitOptsCapability.BRANCH_CREATE
) : GitOptsOperation

data class GitFileMutationOperation(
    override val id: String,
    val path: String,
    val operation: GitFileOperation,
    val content: String?,
    override val dependsOn: List<String> = listOf("create-branch"),
    override val kind: String = "workspace.file.mutate",
    override val capability: GitOptsCapability = when (operation) {
        GitFileOperation.CREATE -> GitOptsCapability.FILE_CREATE
        GitFileOperation.UPDATE -> GitOptsCapability.FILE_UPDATE
        GitFileOperation.UPSERT -> GitOptsCapability.FILE_UPSERT
        GitFileOperation.DELETE -> GitOptsCapability.FILE_DELETE
    }
) : GitOptsOperation

data class GitCreateCommitOperation(
    override val id: String = "commit",
    val message: String,
    override val dependsOn: List<String>,
    override val kind: String = "scm.commit.create",
    override val capability: GitOptsCapability = GitOptsCapability.COMMIT_CREATE
) : GitOptsOperation

data class GitPublishRefOperation(
    override val id: String = "publish-ref",
    val branch: String,
    override val dependsOn: List<String> = listOf("commit"),
    override val kind: String = "scm.ref.publish",
    override val capability: GitOptsCapability = GitOptsCapability.REF_PUBLISH
) : GitOptsOperation

data class GitOpenChangeRequestOperation(
    override val id: String = "open-change-request",
    val baseRef: String,
    val headRef: String,
    val title: String,
    val body: String? = null,
    override val dependsOn: List<String> = listOf("publish-ref"),
    override val kind: String = "scm.change-request.open",
    override val capability: GitOptsCapability = GitOptsCapability.CHANGE_REQUEST_OPEN
) : GitOptsOperation

data class GitOptsPlan(
    val plannerId: String = GIT_OPTS_PLANNER_ID,
    val plannerVersion: String = GIT_OPTS_PLANNER_VERSION,
    val sourceApiVersion: String,
    val operations: List<GitOptsOperation>,
    val requiredCapabilities: List<GitOptsCapability>
)

data class GitOptsDiagnostic(
    val code: String,
    val path: String,
    val message: String
)

class GitOptsPlanningException(val diagnostics: List<GitOptsDiagnostic>) : IllegalArgumentException(
    diagnostics.joinToString(prefix = "Git opts intent is invalid: ", separator = "; ") { diagnostic ->
        "${diagnostic.code} at ${diagnostic.path}: ${diagnostic.message}"
    }
)
