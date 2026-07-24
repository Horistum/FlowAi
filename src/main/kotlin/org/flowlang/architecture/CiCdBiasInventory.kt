package org.flowlang.architecture

import java.io.File

/**
 * Classifies concrete CI/CD and infrastructure vocabulary while separating
 * inventory from actionable semantic coupling.
 *
 * Kotlin source is scanned lexically. Comments are ignored, catalog declarations
 * are self-identifying inventory, ordinary diagnostic strings are non-actionable,
 * and target names become actionable only when they occur in code identifiers or
 * control/default literals in active semantic source. Explicitly retained public
 * compatibility symbols remain visible inventory but do not masquerade as hidden
 * implementation defaults.
 */
data class CiCdBiasTerm(
    val term: String,
    val category: String,
    val reason: String
)

enum class CiCdBiasLexicalContext {
    CODE_IDENTIFIER,
    CONTROL_LITERAL,
    STRING_LITERAL,
    CATALOG_DECLARATION,
    COMPATIBILITY_SYMBOL,
    STRUCTURED_TEXT
}

data class CiCdBiasEvidence(
    val path: String,
    val line: Int,
    val term: String,
    val category: String,
    val classification: String,
    val snippet: String,
    val lexicalContext: CiCdBiasLexicalContext = CiCdBiasLexicalContext.STRUCTURED_TEXT,
    val actionable: Boolean = false
)

enum class CiCdBiasFollowUpArea {
    SEMANTIC_MODEL,
    APPLICATION_COMPOSITION,
    ADAPTER_BOUNDARY,
    SCENARIO_AND_CONFORMANCE,
    DOCUMENTATION,
    NOTES_AND_TARGET_DECLARATIONS
}

data class CiCdBiasInventoryReport(
    val status: String,
    val scannedFiles: Int,
    val evidence: List<CiCdBiasEvidence>,
    val activeSemanticEvidence: List<CiCdBiasEvidence>,
    val applicationCompositionEvidence: List<CiCdBiasEvidence>,
    val adapterBoundaryEvidence: List<CiCdBiasEvidence>,
    val scenarioAndConformanceEvidence: List<CiCdBiasEvidence>,
    val documentationEvidence: List<CiCdBiasEvidence>,
    val moduleAndTargetNoteEvidence: List<CiCdBiasEvidence>,
    val categories: Map<String, Int>,
    val requiredFollowUpAreas: List<CiCdBiasFollowUpArea>,
    val inventoryStatus: String = if (evidence.isEmpty()) "EMPTY" else "PRESENT",
    val healthStatus: String = status,
    val actionableEvidence: List<CiCdBiasEvidence> = emptyList()
)

class CiCdBiasInventoryAnalyzer(private val rootDir: File = File(".")) {
    fun analyze(): CiCdBiasInventoryReport {
        val files = scanRoots().flatMap { root ->
            val file = File(rootDir, root)
            when {
                file.isFile -> listOf(file)
                file.isDirectory -> file.walkTopDown().filter { it.isFile && it.shouldScan() }.toList()
                else -> emptyList()
            }
        }.distinctBy { it.canonicalFile }

        val evidence = files.flatMap(::evidenceIn)
            .distinctBy { listOf(it.path, it.line.toString(), it.term.lowercase(), it.lexicalContext.name) }
            .sortedWith(
                compareBy<CiCdBiasEvidence> { it.classification }
                    .thenBy { it.path }
                    .thenBy { it.line }
                    .thenBy { it.term }
            )

        val activeSemanticEvidence = evidence.filter { it.classification == ACTIVE_SEMANTIC_SOURCE }
        val applicationCompositionEvidence = evidence.filter { it.classification == APPLICATION_COMPOSITION }
        val adapterBoundaryEvidence = evidence.filter { it.classification == ADAPTER_BOUNDARY }
        val scenarioAndConformanceEvidence = evidence.filter { it.classification == SCENARIO_OR_CONFORMANCE }
        val documentationEvidence = evidence.filter { it.classification == DOCUMENTATION }
        val moduleAndTargetNoteEvidence = evidence.filter { it.classification == MODULE_OR_TARGET_NOTE }
        val categories = evidence.groupingBy { it.category }.eachCount().toSortedMap()
        val actionableEvidence = activeSemanticEvidence.filter(CiCdBiasEvidence::actionable)
        val healthStatus = if (actionableEvidence.isEmpty()) "PASS" else "REVIEW_REQUIRED"

        return CiCdBiasInventoryReport(
            status = healthStatus,
            scannedFiles = files.size,
            evidence = evidence,
            activeSemanticEvidence = activeSemanticEvidence,
            applicationCompositionEvidence = applicationCompositionEvidence,
            adapterBoundaryEvidence = adapterBoundaryEvidence,
            scenarioAndConformanceEvidence = scenarioAndConformanceEvidence,
            documentationEvidence = documentationEvidence,
            moduleAndTargetNoteEvidence = moduleAndTargetNoteEvidence,
            categories = categories,
            requiredFollowUpAreas = buildList {
                if (actionableEvidence.isNotEmpty()) add(CiCdBiasFollowUpArea.SEMANTIC_MODEL)
                if (applicationCompositionEvidence.isNotEmpty()) add(CiCdBiasFollowUpArea.APPLICATION_COMPOSITION)
                if (adapterBoundaryEvidence.isNotEmpty()) add(CiCdBiasFollowUpArea.ADAPTER_BOUNDARY)
                if (scenarioAndConformanceEvidence.isNotEmpty()) add(CiCdBiasFollowUpArea.SCENARIO_AND_CONFORMANCE)
                if (documentationEvidence.isNotEmpty()) add(CiCdBiasFollowUpArea.DOCUMENTATION)
                if (moduleAndTargetNoteEvidence.isNotEmpty()) add(CiCdBiasFollowUpArea.NOTES_AND_TARGET_DECLARATIONS)
            },
            inventoryStatus = if (evidence.isEmpty()) "EMPTY" else "PRESENT",
            healthStatus = healthStatus,
            actionableEvidence = actionableEvidence
        )
    }

    private fun evidenceIn(file: File): List<CiCdBiasEvidence> {
        val relative = file.relativeTo(rootDir).path.replace(File.separatorChar, '/')
        val classification = classify(relative)
        val text = file.readText()
        val lines = text.lines()
        return if (file.extension in setOf("kt", "kts")) {
            KotlinLexicalScanner.scan(text).flatMap { span ->
                catalog().flatMap { term ->
                    termOccurrences(span.text, term.term).map { offset ->
                        val occurrenceLine = span.line + span.text.take(offset).count { it == '\n' }
                        val lineText = lines.getOrElse(occurrenceLine - 1) { span.text }
                        val context = when {
                            lineText.contains("CiCdBiasTerm(") -> CiCdBiasLexicalContext.CATALOG_DECLARATION
                            span.kind == KotlinSpanKind.CODE && compatibilitySymbolAt(span.text, offset) ->
                                CiCdBiasLexicalContext.COMPATIBILITY_SYMBOL
                            span.kind == KotlinSpanKind.CODE -> CiCdBiasLexicalContext.CODE_IDENTIFIER
                            isControlLiteral(lineText, term.term) -> CiCdBiasLexicalContext.CONTROL_LITERAL
                            else -> CiCdBiasLexicalContext.STRING_LITERAL
                        }
                        evidence(relative, occurrenceLine, term, classification, lineText, context)
                    }
                }
            }
        } else {
            lines.flatMapIndexed { index, line ->
                catalog().filter { term -> line.contains(term.term, ignoreCase = true) }
                    .map { term ->
                        evidence(
                            relative,
                            index + 1,
                            term,
                            classification,
                            line,
                            CiCdBiasLexicalContext.STRUCTURED_TEXT
                        )
                    }
            }
        }
    }

    private fun termOccurrences(text: String, term: String): List<Int> =
        Regex(Regex.escape(term), RegexOption.IGNORE_CASE).findAll(text).map { it.range.first }.toList()

    private fun compatibilitySymbolAt(text: String, offset: Int): Boolean {
        var start = offset
        while (start > 0 && text[start - 1].isIdentifierPart()) start--
        var end = offset
        while (end < text.length && text[end].isIdentifierPart()) end++
        val symbol = text.substring(start, end).substringAfterLast('.')
        return symbol in retainedCompatibilitySymbols
    }

    private fun Char.isIdentifierPart(): Boolean = isLetterOrDigit() || this == '_' || this == '.'

    private fun evidence(
        path: String,
        line: Int,
        term: CiCdBiasTerm,
        classification: String,
        snippet: String,
        context: CiCdBiasLexicalContext
    ): CiCdBiasEvidence {
        val actionable = classification == ACTIVE_SEMANTIC_SOURCE &&
            term.category in ACTIONABLE_CATEGORIES &&
            context in setOf(CiCdBiasLexicalContext.CODE_IDENTIFIER, CiCdBiasLexicalContext.CONTROL_LITERAL)
        return CiCdBiasEvidence(
            path = path,
            line = line,
            term = term.term,
            category = term.category,
            classification = classification,
            snippet = snippet.trim().take(160),
            lexicalContext = context,
            actionable = actionable
        )
    }

    private fun isControlLiteral(line: String, term: String): Boolean {
        val normalized = line.lowercase()
        val escaped = Regex.escape(term.lowercase())
        val quotedTerm = "[\\\"']$escaped[\\\"']"
        val namedAssignment = Regex(
            "(?i)\\b(default(?:target|provider|platform|tool|engine)?|target|provider|platform|backend|implementation|engine|tool|adapter|runtime)\\b\\s*[:=].*$quotedTerm"
        )
        return namedAssignment.containsMatchIn(line) ||
            Regex("(?i)(==|!=)\\s*$quotedTerm").containsMatchIn(line) ||
            Regex("(?i)$quotedTerm\\s*(==|!=|->)").containsMatchIn(line) ||
            (normalized.contains("setof(") && Regex("(?i)$quotedTerm").containsMatchIn(line))
    }

    private fun classify(path: String): String = when {
        path.startsWith("docs/") || path.startsWith(".flow-agent/") || path == "REPORT.md" || path == "CHANGELOG.md" -> DOCUMENTATION
        path.startsWith("modules/") || path.startsWith("targets/") -> MODULE_OR_TARGET_NOTE
        path.startsWith("conformance/") ||
            path.startsWith("standard/") ||
            path.startsWith("examples/") ||
            path.startsWith("tests/") ||
            path.startsWith("src/test/") ||
            path.startsWith("src/main/kotlin/org/flowlang/conformance/") ||
            path.startsWith("src/main/kotlin/org/flowlang/scenarios/") -> SCENARIO_OR_CONFORMANCE
        path.startsWith("src/main/kotlin/org/flowlang/cli/") ||
            path.startsWith("src/main/kotlin/org/flowlang/release/") -> APPLICATION_COMPOSITION
        path.startsWith("src/main/kotlin/org/flowlang/generators/") ||
            path.startsWith("src/main/kotlin/org/flowlang/adapters/") ||
            path.startsWith("src/main/kotlin/org/flowlang/capabilities/") ||
            path.startsWith("src/main/kotlin/org/flowlang/targets/") -> ADAPTER_BOUNDARY
        else -> ACTIVE_SEMANTIC_SOURCE
    }

    private fun File.shouldScan(): Boolean {
        val path = relativeTo(rootDir).path.replace(File.separatorChar, '/')
        if (path.contains("/build/") || path.contains("/.gradle") || path.endsWith(".class") || path.endsWith(".jar")) return false
        return extension in setOf("kt", "kts", "md", "yaml", "yml", "json", "flow", "txt") ||
            name in setOf("README.md", "REPORT.md", "CHANGELOG.md")
    }

    private fun scanRoots(): List<String> = listOf(
        "src/main/kotlin",
        "src/test/kotlin",
        "tests",
        "docs",
        ".flow-agent",
        "conformance",
        "standard",
        "modules",
        "targets",
        "examples",
        "README.md",
        "REPORT.md",
        "CHANGELOG.md"
    )

    companion object {
        const val ACTIVE_SEMANTIC_SOURCE = "active-semantic-source"
        const val APPLICATION_COMPOSITION = "application-composition"
        const val ADAPTER_BOUNDARY = "adapter-boundary"
        const val SCENARIO_OR_CONFORMANCE = "scenario-or-conformance"
        const val DOCUMENTATION = "documentation"
        const val MODULE_OR_TARGET_NOTE = "module-or-target-note"

        private val ACTIONABLE_CATEGORIES = setOf("target", "infrastructure", "tool", "data-system")
        private val retainedCompatibilitySymbols = setOf("KUBERNETES_MAINTENANCE")

        fun catalog(): List<CiCdBiasTerm> = listOf(
            CiCdBiasTerm("Jenkins", "target", "Concrete CI target name."),
            CiCdBiasTerm("GitHub Actions", "target", "Concrete CI target name."),
            CiCdBiasTerm("github-actions", "target", "Concrete CI target identifier."),
            CiCdBiasTerm("Tekton", "target", "Concrete CI target name."),
            CiCdBiasTerm("ArgoCD", "target", "Concrete deployment target name."),
            CiCdBiasTerm("Argo CD", "target", "Concrete deployment target name."),
            CiCdBiasTerm("Kubernetes", "infrastructure", "Concrete infrastructure platform name."),
            CiCdBiasTerm("Docker", "tool", "Concrete build or container tool name."),
            CiCdBiasTerm("Maven", "tool", "Concrete build tool name."),
            CiCdBiasTerm("PostgreSQL", "data-system", "Concrete database implementation name."),
            CiCdBiasTerm("postgres", "data-system", "Concrete database identifier.")
        )
    }
}

private enum class KotlinSpanKind { CODE, STRING }

private data class KotlinLexicalSpan(
    val line: Int,
    val kind: KotlinSpanKind,
    val text: String
)

/** Small lexer sufficient to separate executable Kotlin from comments and strings. */
private object KotlinLexicalScanner {
    fun scan(text: String): List<KotlinLexicalSpan> {
        val spans = mutableListOf<KotlinLexicalSpan>()
        var index = 0
        var line = 1
        var blockCommentDepth = 0

        fun advance(value: String) {
            line += value.count { it == '\n' }
            index += value.length
        }

        while (index < text.length) {
            if (blockCommentDepth > 0) {
                when {
                    text.startsWith("/*", index) -> {
                        blockCommentDepth++
                        advance("/*")
                    }
                    text.startsWith("*/", index) -> {
                        blockCommentDepth--
                        advance("*/")
                    }
                    else -> advance(text[index].toString())
                }
                continue
            }
            if (text.startsWith("//", index)) {
                val end = text.indexOf('\n', index).let { if (it < 0) text.length else it }
                advance(text.substring(index, end))
                continue
            }
            if (text.startsWith("/*", index)) {
                blockCommentDepth = 1
                advance("/*")
                continue
            }
            if (text.startsWith("\"\"\"", index)) {
                val startLine = line
                val end = text.indexOf("\"\"\"", index + 3)
                val finish = if (end < 0) text.length else end + 3
                val value = text.substring(index + 3, if (end < 0) text.length else end)
                spans += KotlinLexicalSpan(startLine, KotlinSpanKind.STRING, value)
                advance(text.substring(index, finish))
                continue
            }
            if (text[index] == '"') {
                val startLine = line
                var cursor = index + 1
                var escaped = false
                while (cursor < text.length) {
                    val character = text[cursor]
                    if (!escaped && character == '"') break
                    escaped = !escaped && character == '\\'
                    if (character != '\\') escaped = false
                    cursor++
                }
                val finish = if (cursor < text.length) cursor + 1 else text.length
                spans += KotlinLexicalSpan(
                    startLine,
                    KotlinSpanKind.STRING,
                    text.substring(index + 1, cursor.coerceAtMost(text.length))
                )
                advance(text.substring(index, finish))
                continue
            }
            if (text[index] == '\'') {
                var cursor = index + 1
                var escaped = false
                while (cursor < text.length) {
                    val character = text[cursor]
                    if (!escaped && character == '\'') break
                    escaped = !escaped && character == '\\'
                    if (character != '\\') escaped = false
                    cursor++
                }
                advance(text.substring(index, if (cursor < text.length) cursor + 1 else text.length))
                continue
            }

            val startLine = line
            val start = index
            while (
                index < text.length &&
                !text.startsWith("//", index) &&
                !text.startsWith("/*", index) &&
                !text.startsWith("\"\"\"", index) &&
                text[index] != '"' &&
                text[index] != '\''
            ) {
                advance(text[index].toString())
            }
            if (index > start) {
                spans += KotlinLexicalSpan(startLine, KotlinSpanKind.CODE, text.substring(start, index))
            }
        }
        return spans
    }
}
