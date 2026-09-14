package org.flowlang.distribution.reference.gitopts

class GitOptsPlanner {
    fun validate(intent: GitOptsIntent): List<GitOptsDiagnostic> {
        val diagnostics = mutableListOf<GitOptsDiagnostic>()

        fun error(code: String, path: String, message: String) {
            diagnostics += GitOptsDiagnostic(code, path, message)
        }

        if (intent.apiVersion != GIT_OPTS_API_VERSION) {
            error("GIT_OPTS_UNSUPPORTED_API_VERSION", "apiVersion", "Expected '$GIT_OPTS_API_VERSION'.")
        }
        if (intent.kind != GIT_OPTS_KIND) {
            error("GIT_OPTS_UNSUPPORTED_KIND", "kind", "Expected '$GIT_OPTS_KIND'.")
        }
        val repository = intent.repository.url
        if (repository.isBlank() || repository.any { it == '\u0000' || it == '\r' || it == '\n' }) {
            error("GIT_OPTS_INVALID_REPOSITORY", "repository.url", "Repository URL must be a non-blank single-line value.")
        } else if (repository != repository.trim()) {
            error("GIT_OPTS_INVALID_REPOSITORY", "repository.url", "Repository URL must not contain leading or trailing whitespace.")
        } else if (Regex("^https?://[^/]*@", RegexOption.IGNORE_CASE).containsMatchIn(repository)) {
            error(
                "GIT_OPTS_REPOSITORY_EMBEDS_CREDENTIALS",
                "repository.url",
                "HTTP(S) repository URLs must not embed user information or credentials. Bind credentials at execution time."
            )
        }
        validateRef(intent.baseRef, "baseRef")?.let { error("GIT_OPTS_INVALID_REF", "baseRef", it) }
        validateRef(intent.branch, "branch")?.let { error("GIT_OPTS_INVALID_REF", "branch", it) }
        if (intent.baseRef == intent.branch) {
            error("GIT_OPTS_BRANCH_EQUALS_BASE", "branch", "The change branch must differ from baseRef.")
        }
        if (intent.changes.isEmpty()) {
            error("GIT_OPTS_EMPTY_CHANGESET", "changes", "At least one file change is required.")
        }

        val seenPaths = mutableSetOf<String>()
        intent.changes.forEachIndexed { index, change ->
            val path = "changes[$index]"
            validateWorkspacePath(change.path)?.let { error("GIT_OPTS_INVALID_PATH", "$path.path", it) }
            if (!seenPaths.add(change.path)) {
                error("GIT_OPTS_DUPLICATE_PATH", "$path.path", "Each workspace path may be changed only once.")
            }
            when (change.operation) {
                GitFileOperation.CREATE, GitFileOperation.UPDATE, GitFileOperation.UPSERT -> if (change.content == null) {
                    error("GIT_OPTS_MISSING_CONTENT", "$path.content", "${change.operation.toWireName()} requires content.")
                }
                GitFileOperation.DELETE -> if (change.content != null) {
                    error("GIT_OPTS_DELETE_HAS_CONTENT", "$path.content", "delete must not carry replacement content.")
                }
            }
        }

        if (intent.commit.message.isBlank() || '\u0000' in intent.commit.message) {
            error("GIT_OPTS_INVALID_COMMIT_MESSAGE", "commit.message", "Commit message must be non-blank and contain no NUL byte.")
        }
        if (intent.delivery.mode == GitDeliveryMode.PULL_REQUEST && intent.delivery.title.isNullOrBlank()) {
            error("GIT_OPTS_MISSING_CHANGE_REQUEST_TITLE", "delivery.title", "pull-request delivery requires a title.")
        }
        if (intent.delivery.title?.contains('\u0000') == true || intent.delivery.body?.contains('\u0000') == true) {
            error("GIT_OPTS_INVALID_DELIVERY_TEXT", "delivery", "Delivery text must not contain a NUL byte.")
        }

        return diagnostics.sortedWith(compareBy(GitOptsDiagnostic::path, GitOptsDiagnostic::code, GitOptsDiagnostic::message))
    }

    fun plan(intent: GitOptsIntent): GitOptsPlan {
        val diagnostics = validate(intent)
        if (diagnostics.isNotEmpty()) throw GitOptsPlanningException(diagnostics)

        val operations = mutableListOf<GitOptsOperation>()
        operations += GitCheckoutOperation(repository = intent.repository.url, ref = intent.baseRef)
        operations += GitCreateBranchOperation(branch = intent.branch, fromRef = intent.baseRef)

        val fileOperations = intent.changes
            .sortedWith(compareBy<GitFileChange> { it.path }.thenBy { it.operation.toWireName() })
            .mapIndexed { index, change ->
                GitFileMutationOperation(
                    id = "change-${(index + 1).toString().padStart(3, '0')}",
                    path = change.path,
                    operation = change.operation,
                    content = change.content
                )
            }
        operations += fileOperations
        operations += GitCreateCommitOperation(
            message = intent.commit.message,
            dependsOn = fileOperations.map { it.id }
        )

        if (intent.delivery.mode != GitDeliveryMode.LOCAL) {
            operations += GitPublishRefOperation(branch = intent.branch)
        }
        if (intent.delivery.mode == GitDeliveryMode.PULL_REQUEST) {
            operations += GitOpenChangeRequestOperation(
                baseRef = intent.baseRef,
                headRef = intent.branch,
                title = requireNotNull(intent.delivery.title),
                body = intent.delivery.body
            )
        }

        val plan = GitOptsPlan(
            sourceApiVersion = intent.apiVersion,
            operations = operations,
            requiredCapabilities = operations.map(GitOptsOperation::capability)
                .distinct()
                .sortedBy { it.toWireName() }
        )
        return GitOptsPlanIntegrity.requireValid(plan)
    }

    private fun validateRef(value: String, name: String): String? {
        if (value.isBlank()) return "$name must not be blank."
        if (value != value.trim()) return "$name must not contain leading or trailing whitespace."
        if (value == "@" || value.startsWith('-') || value.startsWith('/') || value.endsWith('/')) {
            return "$name is not a safe Git ref."
        }
        if (".." in value || "@{" in value || "//" in value) return "$name contains a forbidden Git ref sequence."
        val forbidden = setOf(' ', '~', '^', ':', '?', '*', '[', '\\')
        if (value.any { it.code < 32 || it.code == 127 || it in forbidden }) return "$name contains a forbidden Git ref character."
        if (value.split('/').any {
                it.isBlank() || it == "." || it == ".." || it.startsWith('.') || it.endsWith('.') || it.endsWith(".lock")
            }) {
            return "$name contains an invalid Git ref path component."
        }
        return null
    }

    private fun validateWorkspacePath(value: String): String? {
        if (value.isBlank()) return "Path must not be blank."
        if (value != value.trim()) return "Path must not contain leading or trailing whitespace."
        if (value.startsWith('/') || '\\' in value || '\u0000' in value) return "Path must be a relative forward-slash workspace path."
        val segments = value.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) return "Path must not contain empty, '.' or '..' components."
        if (segments.any { it.equals(".git", ignoreCase = true) }) return "Planner changes must not target Git administrative metadata."
        return null
    }
}

object GitOptsPlanIntegrity {
    fun requireValid(plan: GitOptsPlan): GitOptsPlan {
        require(plan.plannerId == GIT_OPTS_PLANNER_ID) { "Unexpected planner id '${plan.plannerId}'." }
        require(plan.plannerVersion == GIT_OPTS_PLANNER_VERSION) { "Unexpected planner version '${plan.plannerVersion}'." }
        require(plan.sourceApiVersion == GIT_OPTS_API_VERSION) { "Unexpected source API version '${plan.sourceApiVersion}'." }
        require(plan.operations.isNotEmpty()) { "A git opts plan must contain operations." }

        val byId = plan.operations.associateBy(GitOptsOperation::id)
        require(byId.size == plan.operations.size) { "Git opts plan contains duplicate operation ids." }
        plan.operations.forEach { operation ->
            require(operation.id.isNotBlank()) { "Git opts operation id must not be blank." }
            require(operation.dependsOn.distinct().size == operation.dependsOn.size) {
                "Operation '${operation.id}' contains duplicate dependencies."
            }
            operation.dependsOn.forEach { dependency ->
                require(dependency != operation.id) { "Operation '${operation.id}' cannot depend on itself." }
                require(dependency in byId) { "Operation '${operation.id}' depends on missing operation '$dependency'." }
            }
        }

        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(id: String) {
            if (id in visited) return
            require(visiting.add(id)) { "Git opts plan contains a dependency cycle at '$id'." }
            byId.getValue(id).dependsOn.forEach(::visit)
            visiting.remove(id)
            visited += id
        }
        plan.operations.forEach { visit(it.id) }

        val derivedCapabilities = plan.operations.map(GitOptsOperation::capability)
            .distinct()
            .sortedBy { it.toWireName() }
        require(plan.requiredCapabilities == derivedCapabilities) {
            "Git opts plan requiredCapabilities must be derived exactly from operation semantics."
        }
        return plan
    }
}
