package org.flowlang.architecture

import java.io.File
import org.flowlang.intent.StandardCapabilityCompatibility

/**
 * Classifies concrete delivery and infrastructure vocabulary while separating
 * inventory presence from actionable semantic coupling.
 *
 * The scanner is intentionally lexical rather than a raw grep. Comments are
 * ignored, Kotlin interpolation bodies are scanned as code, normative structured
 * values are distinguished from prose, and explicit compatibility, build tooling
 * or descriptive catalog boundaries remain visible without becoming semantic
 * authority.
 */
data class CiCdBiasTerm(
    val term: String,
    val category: String,
    val reason: String
)

enum class CiCdBiasLexicalContext {
    CODE_IDENTIFIER,
    CONTROL_LITERAL,
    SEMANTIC_LITERAL,
    DIAGNOSTIC_LITERAL,
    STRING_LITERAL,
    CATALOG_DECLARATION,
    COMPATIBILITY_SYMBOL,
    STRUCTURED_CONTROL,
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
        val actionableEvidence = evidence.filter(CiCdBiasEvidence::actionable)
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
        val supportedCompatibilityManifest =
            relative == STANDARD_CAPABILITY_ALIAS_MANIFEST &&
                StandardCapabilityCompatibility.isSupportedManifestText(text)
        return if (file.extension in setOf("kt", "kts")) {
            KotlinLexicalScanner.scan(text).flatMap { span ->
                catalog().flatMap { term ->
                    termOccurrences(span.text, term.term).map { offset ->
                        val occurrenceLine = span.line + span.text.take(offset).count { it == '\n' }
                        val lineText = lines.getOrElse(occurrenceLine - 1) { span.text }
                        val context = kotlinContext(classification, lineText, span.kind, term.term)
                        evidence(relative, occurrenceLine, term, classification, lineText, context)
                    }
                }
            }
        } else {
            lines.flatMapIndexed { index, line ->
                catalog().filter { term -> line.contains(term.term, ignoreCase = true) }
                    .map { term ->
                        val context = structuredContext(
                            relative,
                            classification,
                            line,
                            term.term,
                            supportedCompatibilityManifest
                        )
                        evidence(relative, index + 1, term, classification, line, context)
                    }
            }
        }
    }

    private fun kotlinContext(
        classification: String,
        line: String,
        spanKind: KotlinSpanKind,
        term: String
    ): CiCdBiasLexicalContext = when {
        classification == COMPATIBILITY_BOUNDARY && isDeprecatedCompatibilityAliasSymbol(line, term) ->
            CiCdBiasLexicalContext.COMPATIBILITY_SYMBOL
        line.contains("CiCdBiasTerm(") ||
            (classification == DESCRIPTIVE_CATALOG && line.contains("catalogModules(")) ->
            CiCdBiasLexicalContext.CATALOG_DECLARATION
        spanKind == KotlinSpanKind.CODE -> CiCdBiasLexicalContext.CODE_IDENTIFIER
        isControlLiteral(line, term) -> CiCdBiasLexicalContext.CONTROL_LITERAL
        isDiagnosticLiteral(line) -> CiCdBiasLexicalContext.DIAGNOSTIC_LITERAL
        isSemanticLiteral(line, term) -> CiCdBiasLexicalContext.SEMANTIC_LITERAL
        else -> CiCdBiasLexicalContext.STRING_LITERAL
    }

    private fun structuredContext(
        path: String,
        classification: String,
        line: String,
        term: String,
        supportedCompatibilityManifest: Boolean
    ): CiCdBiasLexicalContext = when {
        path == STANDARD_CAPABILITY_ALIAS_MANIFEST &&
            isCompatibilityAliasSource(line, term) -> CiCdBiasLexicalContext.COMPATIBILITY_SYMBOL
        isControlLiteral(line, term) -> CiCdBiasLexicalContext.CONTROL_LITERAL
        path == STANDARD_CAPABILITY_ALIAS_MANIFEST && !supportedCompatibilityManifest ->
            CiCdBiasLexicalContext.STRUCTURED_CONTROL
        classification in GOVERNED_PRODUCTION_CLASSIFICATIONS &&
            path.startsWith("schemas/") &&
            !isDescriptiveStructuredLine(line) -> CiCdBiasLexicalContext.STRUCTURED_CONTROL
        else -> CiCdBiasLexicalContext.STRUCTURED_TEXT
    }

    private fun termOccurrences(text: String, term: String): List<Int> =
        Regex(Regex.escape(term), RegexOption.IGNORE_CASE).findAll(text).map { it.range.first }.toList()

    private fun evidence(
        path: String,
        line: Int,
        term: CiCdBiasTerm,
        classification: String,
        snippet: String,
        context: CiCdBiasLexicalContext
    ): CiCdBiasEvidence {
        val actionable = classification in GOVERNED_PRODUCTION_CLASSIFICATIONS &&
            term.category in ACTIONABLE_CATEGORIES &&
            context in ACTIONABLE_CONTEXTS
        return CiCdBiasEvidence(
            path = path,
            line = line,
            term = term.term,
            category = term.category,
            classification = classification,
            snippet = snippet.trim().take(180),
            lexicalContext = context,
            actionable = actionable
        )
    }

    private fun isControlLiteral(line: String, term: String): Boolean {
        val controlSurface = line.replace("\\\"", "\"")
        val escaped = Regex.escape(term.lowercase())
        val quotedTerm = "[\\\"']$escaped[\\\"']"
        val quotedOrBareTerm = "(?:$quotedTerm|\\b$escaped\\b)"
        val controlKey =
            "default(?:target|provider|platform|tool|engine)?|target|provider|platform|backend|implementation|engine|tool|adapter|runtime"
        val namedAssignment = Regex(
            "(?i)(?:[\\\"']?(?:$controlKey)[\\\"']?)\\s*[:=].*$quotedOrBareTerm"
        )
        val predicateCall = Regex(
            "(?i)\\b(contains|containsKey|startsWith|endsWith|matches|find|matchEntire)\\s*\\(\\s*$quotedTerm"
        )
        val regexConstruction = Regex("(?i)\\bRegex\\s*\\(\\s*$quotedTerm")
        return namedAssignment.containsMatchIn(controlSurface) ||
            Regex("(?i)(==|!=)\\s*$quotedTerm").containsMatchIn(controlSurface) ||
            Regex("(?i)$quotedTerm\\s*(==|!=|->)").containsMatchIn(controlSurface) ||
            predicateCall.containsMatchIn(controlSurface) ||
            regexConstruction.containsMatchIn(controlSurface) ||
            (Regex("(?i)\\bsetOf\\s*\\(").containsMatchIn(controlSurface) &&
                Regex("(?i)$quotedTerm").containsMatchIn(controlSurface))
    }

    private fun isCompatibilityAliasSource(line: String, term: String): Boolean {
        val match = Regex("^\\s*-?\\s*source\\s*:\\s*([A-Za-z0-9_-]+)\\s*(?:#.*)?$").matchEntire(line)
            ?: return false
        val sourceName = match.groupValues[1]
        return sourceName in StandardCapabilityCompatibility.retiredSourceNames &&
            sourceName.contains(term, ignoreCase = true)
    }

    private fun isDeprecatedCompatibilityAliasSymbol(line: String, term: String): Boolean {
        val match = Regex(
            "^\\s*val\\s+StandardCapability\\.Companion\\.([A-Z0-9_]+)\\s*:\\s*StandardCapability\\s*$"
        ).matchEntire(line) ?: return false
        val normalizedTerm = term.replace('-', '_').replace(' ', '_').uppercase()
        return match.groupValues[1].contains(normalizedTerm)
    }

    private fun isSemanticLiteral(line: String, term: String): Boolean {
        val escaped = Regex.escape(term.lowercase())
        val quotedTerm = "[\\\"']$escaped[\\\"']"
        return Regex(
            "(?i)\\b(semanticCapability|sourceCapability|capability|capabilities|implements|resource|domain|operation|feature|identity)\\b\\s*[:=].*$quotedTerm"
        ).containsMatchIn(line)
    }

    private fun isDiagnosticLiteral(line: String): Boolean = Regex(
        "(?i)\\b(message|reason|description|notes?|diagnostic|warning|error|exception|detail|explanation)\\b\\s*[:=]"
    ).containsMatchIn(line) || line.contains("require(") || line.contains("error(")

    private fun isDescriptiveStructuredLine(line: String): Boolean = Regex(
        "(?i)\\\"(description|title|notes?|reason|message|examples?)\\\"\\s*:"
    ).containsMatchIn(line)

    private fun classify(path: String): String = when {
        path == STANDARD_CAPABILITY_COMPATIBILITY || path == STANDARD_CAPABILITY_ALIAS_MANIFEST ->
            COMPATIBILITY_BOUNDARY
        path == STANDARD_INTENT_CATALOG -> DESCRIPTIVE_CATALOG
        path in BUILD_CONFIGURATION_PATHS -> BUILD_CONFIGURATION
        path in TARGET_NEUTRAL_GENERATOR_AUTHORITIES -> ACTIVE_SEMANTIC_SOURCE
        path.startsWith("docs/") ||
            path.startsWith(".flow-agent/") ||
            path == "REPORT.md" ||
            path.startsWith("CHANGELOG") -> DOCUMENTATION
        path.startsWith("modules/") || path.startsWith("targets/") -> MODULE_OR_TARGET_NOTE
        path.startsWith("adapters/") ||
            path.startsWith("src/main/kotlin/org/flowlang/adapters/") ||
            path.startsWith("src/main/kotlin/org/flowlang/capabilities/") ||
            path.startsWith("src/main/kotlin/org/flowlang/targets/") ||
            path.startsWith("src/main/kotlin/org/flowlang/generators/") -> ADAPTER_BOUNDARY
        path.startsWith("conformance/") ||
            path.startsWith("verification/") ||
            path.startsWith("standard/") ||
            path.startsWith("examples/") ||
            path.startsWith("tests/") ||
            path.startsWith("src/test/") ||
            path.startsWith("src/main/kotlin/org/flowlang/conformance/") ||
            path.startsWith("src/main/kotlin/org/flowlang/scenarios/") -> SCENARIO_OR_CONFORMANCE
        path.startsWith("src/main/kotlin/org/flowlang/cli/") ||
            path.startsWith("src/main/kotlin/org/flowlang/release/") -> APPLICATION_COMPOSITION
        else -> ACTIVE_SEMANTIC_SOURCE
    }

    private fun File.shouldScan(): Boolean {
        val path = relativeTo(rootDir).path.replace(File.separatorChar, '/')
        if (path.contains("/build/") || path.contains("/.gradle") || path.endsWith(".class") || path.endsWith(".jar")) return false
        return extension in setOf("kt", "kts", "md", "yaml", "yml", "json", "flow", "txt", "properties", "toml") ||
            name in setOf("README.md", "REPORT.md", "CHANGELOG.md")
    }

    private fun scanRoots(): List<String> = listOf(
        "src/main/kotlin",
        "src/main/resources",
        "src/test/kotlin",
        "tests",
        "docs",
        ".flow-agent",
        "conformance",
        "verification",
        "standard",
        "schemas",
        "adapters",
        "modules",
        "targets",
        "examples",
        "build.gradle.kts",
        "settings.gradle.kts",
        "gradle.properties",
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
        const val COMPATIBILITY_BOUNDARY = "compatibility-boundary"
        const val DESCRIPTIVE_CATALOG = "descriptive-catalog"
        const val BUILD_CONFIGURATION = "build-configuration"

        private const val STANDARD_CAPABILITY_COMPATIBILITY =
            "src/main/kotlin/org/flowlang/intent/StandardCapabilityCompatibility.kt"
        private const val STANDARD_INTENT_CATALOG =
            "src/main/kotlin/org/flowlang/standard/StandardIntentCatalog.kt"
        private const val STANDARD_CAPABILITY_ALIAS_MANIFEST =
            "src/main/resources/standard/compatibility/capability-aliases.yaml"

        private val BUILD_CONFIGURATION_PATHS = setOf(
            "build.gradle.kts",
            "settings.gradle.kts",
            "gradle.properties"
        )
        private val TARGET_NEUTRAL_GENERATOR_AUTHORITIES = setOf(
            "src/main/kotlin/org/flowlang/generators/manifest/MandatoryMaterializationAuthority.kt",
            "src/main/kotlin/org/flowlang/generators/manifest/ExecutionPlanTopologyValidator.kt"
        )
        private val ACTIONABLE_CATEGORIES = setOf("target", "infrastructure", "tool", "data-system")
        private val PRODUCTION_CLASSIFICATIONS = setOf(ACTIVE_SEMANTIC_SOURCE, APPLICATION_COMPOSITION)
        private val GOVERNED_PRODUCTION_CLASSIFICATIONS = PRODUCTION_CLASSIFICATIONS + setOf(
            COMPATIBILITY_BOUNDARY,
            DESCRIPTIVE_CATALOG
        )
        private val ACTIONABLE_CONTEXTS = setOf(
            CiCdBiasLexicalContext.CODE_IDENTIFIER,
            CiCdBiasLexicalContext.CONTROL_LITERAL,
            CiCdBiasLexicalContext.SEMANTIC_LITERAL,
            CiCdBiasLexicalContext.STRUCTURED_CONTROL
        )

        fun catalog(): List<CiCdBiasTerm> = listOf(
            CiCdBiasTerm("Jenkins", "target", "Concrete CI target name."),
            CiCdBiasTerm("GitHub Actions", "target", "Concrete CI target name."),
            CiCdBiasTerm("github-actions", "target", "Concrete CI target identifier."),
            CiCdBiasTerm("GitLab CI", "target", "Concrete CI target name."),
            CiCdBiasTerm("gitlab-ci", "target", "Concrete CI target identifier."),
            CiCdBiasTerm("GitLab", "target", "Concrete CI platform name."),
            CiCdBiasTerm("CircleCI", "target", "Concrete CI target name."),
            CiCdBiasTerm("circleci", "target", "Concrete CI target identifier."),
            CiCdBiasTerm("Azure DevOps", "target", "Concrete CI target name."),
            CiCdBiasTerm("azure-devops", "target", "Concrete CI target identifier."),
            CiCdBiasTerm("Azure", "infrastructure", "Concrete cloud or delivery platform name."),
            CiCdBiasTerm("Tekton", "target", "Concrete CI target name."),
            CiCdBiasTerm("ArgoCD", "target", "Concrete deployment target name."),
            CiCdBiasTerm("Argo CD", "target", "Concrete deployment target name."),
            CiCdBiasTerm("Kubernetes", "infrastructure", "Concrete infrastructure platform name."),
            CiCdBiasTerm("k8s", "infrastructure", "Concrete infrastructure platform identifier."),
            CiCdBiasTerm("kubectl", "tool", "Concrete infrastructure command-line tool."),
            CiCdBiasTerm("Docker", "tool", "Concrete build or container tool name."),
            CiCdBiasTerm("Helm", "tool", "Concrete package and deployment tool name."),
            CiCdBiasTerm("Terraform", "tool", "Concrete infrastructure tool name."),
            CiCdBiasTerm("Maven", "tool", "Concrete build tool name."),
            CiCdBiasTerm("PostgreSQL", "data-system", "Concrete database implementation name."),
            CiCdBiasTerm("postgres", "data-system", "Concrete database identifier.")
        )
    }
}
