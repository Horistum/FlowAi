package org.flowlang.architecture

import java.io.File
import org.flowlang.serialization.FlowYaml

data class AuthorityResponsibilityCatalogReport(
    val status: String,
    val authorityCount: Int,
    val errors: List<String>
)

/**
 * Machine-verifies the AR0.1 responsibility inventory against production Kotlin sources.
 *
 * The catalog is intentionally descriptive rather than executable policy: source code remains
 * the behavior authority. This validator prevents ownership metadata from drifting away from
 * the real set of Authority types and their production dependency edges.
 */
class AuthorityResponsibilityCatalog(private val rootDir: File = File(".")) {
    fun analyze(): AuthorityResponsibilityCatalogReport {
        val errors = mutableListOf<String>()
        val catalogFile = File(rootDir, CATALOG_PATH)
        if (!catalogFile.isFile) {
            return AuthorityResponsibilityCatalogReport(
                status = "FAIL",
                authorityCount = 0,
                errors = listOf("Authority responsibility catalog is missing: ${catalogFile.path}")
            )
        }

        val document = runCatching { FlowYaml.readMap(catalogFile) }.getOrElse { exception ->
            return AuthorityResponsibilityCatalogReport(
                status = "FAIL",
                authorityCount = 0,
                errors = listOf("Authority responsibility catalog cannot be parsed: ${exception.message}")
            )
        }
        val unknownTopLevel = document.keys - TOP_LEVEL_FIELDS
        if (unknownTopLevel.isNotEmpty()) {
            errors += "Authority responsibility catalog contains unknown top-level fields: ${unknownTopLevel.sorted().joinToString()}."
        }
        if (document.string("version") != SUPPORTED_VERSION) {
            errors += "Authority responsibility catalog version must be $SUPPORTED_VERSION."
        }

        val discovery = discoverAuthorities()
        errors += discovery.errors
        val actual = discovery.authorities
        val entries = document.mapList("authorities")
        val catalogNames = entries.map { it.string("name") }
        val duplicateNames = catalogNames.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicateNames.isNotEmpty()) {
            errors += "Authority responsibility catalog contains duplicate names: ${duplicateNames.sorted().joinToString()}."
        }
        val blankNames = catalogNames.count(String::isBlank)
        if (blankNames > 0) errors += "Authority responsibility catalog contains $blankNames blank authority name(s)."

        val actualNames = actual.keys
        val catalogNameSet = catalogNames.filter(String::isNotBlank).toSet()
        val missing = actualNames - catalogNameSet
        val stale = catalogNameSet - actualNames
        if (missing.isNotEmpty()) {
            errors += "Production Authority types missing from the responsibility catalog: ${missing.sorted().joinToString()}."
        }
        if (stale.isNotEmpty()) {
            errors += "Responsibility catalog entries without a production Authority type: ${stale.sorted().joinToString()}."
        }

        entries.forEach { entry -> validateEntry(entry, actual[entry.string("name")], errors) }

        return AuthorityResponsibilityCatalogReport(
            status = if (errors.isEmpty()) "PASS" else "FAIL",
            authorityCount = discovery.authorityCount,
            errors = errors
        )
    }

    private fun validateEntry(
        entry: Map<String, Any?>,
        actual: DiscoveredAuthorityRecord?,
        errors: MutableList<String>
    ) {
        val name = entry.string("name")
        val label = name.ifBlank { "<blank>" }
        val unknownFields = entry.keys - ENTRY_FIELDS
        if (unknownFields.isNotEmpty()) {
            errors += "$label responsibility entry contains unknown fields: ${unknownFields.sorted().joinToString()}."
        }
        val role = entry.string("role")
        if (role !in ROLES) errors += "$label must declare one supported responsibility role: ${ROLES.sorted().joinToString()}."
        listOf("invariant", "inputs", "outputs").forEach { field ->
            if (entry.string(field).isBlank()) errors += "$label responsibility entry must document $field."
        }
        if (actual == null) return
        if (entry.string("path") != actual.path) {
            errors += "$label catalog path '${entry.string("path")}' does not match production definition '${actual.path}'."
        }
        val expectedCallers = actual.callers.sorted()
        val catalogCallers = entry.stringList("callers")
        if (catalogCallers != catalogCallers.distinct().sorted()) {
            errors += "$label callers must be unique and sorted."
        }
        if (catalogCallers != expectedCallers) {
            errors += "$label callers drifted: catalog=${catalogCallers.joinToString()} actual=${expectedCallers.joinToString()}."
        }
        if (!actual.hasProductionUse) {
            errors += "$label has no production use outside its own Authority declaration; remove it or add a real production orchestration boundary before AR0.1 can pass."
        }
    }

    private fun discoverAuthorities(): AuthorityDiscovery {
        val sourceRoot = File(rootDir, SOURCE_ROOT)
        if (!sourceRoot.isDirectory) {
            return AuthorityDiscovery(
                authorities = emptyMap(),
                authorityCount = 0,
                errors = listOf("Production Kotlin source root is missing: ${sourceRoot.path}")
            )
        }
        val files = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()
        val codeByPath = files.associate { file ->
            relative(file) to KotlinLexicalScanner.scan(file.readText())
                .filter { it.kind == KotlinSpanKind.CODE }
                .joinToString("\n") { it.text }
        }
        val definitions = mutableMapOf<String, MutableList<String>>()
        codeByPath.forEach { (path, code) ->
            AUTHORITY_DEFINITION.findAll(code).forEach { match ->
                definitions.getOrPut(match.groupValues[1]) { mutableListOf() } += path
            }
        }
        val duplicateErrors = definitions
            .filterValues { it.size > 1 }
            .map { (name, paths) ->
                "Production Authority type '$name' is defined more than once: ${paths.sorted().joinToString()}. Authority simple names must remain repository-unique so responsibility ownership is unambiguous."
            }
            .sorted()
        val uniqueDefinitions = definitions.mapNotNull { (name, paths) ->
            name.takeIf { paths.size == 1 }?.let { it to paths.single() }
        }.toMap()
        val authorities = uniqueDefinitions.mapValues { (name, definitionPath) ->
            val token = Regex("\\b${Regex.escape(name)}\\b")
            val callers = codeByPath.mapNotNull { (path, code) ->
                val occurrences = token.findAll(code).count()
                val definitionOccurrences = if (path == definitionPath) {
                    AUTHORITY_DEFINITION.findAll(code).count { it.groupValues[1] == name }
                } else 0
                path.takeIf { occurrences > definitionOccurrences }
            }
            val hasProductionUse = codeByPath.any { (path, code) ->
                when {
                    path != definitionPath -> token.containsMatchIn(code)
                    else -> hasProductionUseOutsideOwnDeclaration(code, name)
                }
            }
            DiscoveredAuthorityRecord(definitionPath, callers, hasProductionUse)
        }
        return AuthorityDiscovery(
            authorities = authorities,
            authorityCount = definitions.keys.size,
            errors = duplicateErrors
        )
    }

    private fun hasProductionUseOutsideOwnDeclaration(code: String, name: String): Boolean {
        val declaration = AUTHORITY_DEFINITION.findAll(code)
            .firstOrNull { it.groupValues[1] == name } ?: return false
        val declarationBody = declarationBodyRange(code, declaration)
        val reference = Regex("\\b${Regex.escape(name)}\\b")
        return reference.findAll(code).any { use -> use.range.first !in declarationBody }
    }

    private fun declarationBodyRange(code: String, declaration: MatchResult): IntRange {
        val bodyStart = declarationBodyStart(code, declaration) ?: return declaration.range
        var depth = 0
        for (index in bodyStart until code.length) {
            when (code[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return declaration.range.first..index
                }
            }
        }
        return declaration.range.first..code.lastIndex
    }

    private fun declarationBodyStart(code: String, declaration: MatchResult): Int? {
        var parentheses = 0
        var brackets = 0
        var index = declaration.range.last + 1
        while (index < code.length) {
            if (parentheses == 0 && brackets == 0 && NEXT_TOP_LEVEL_DECLARATION.matchAt(code, index) != null) {
                return null
            }
            when (code[index]) {
                '(' -> parentheses++
                ')' -> if (parentheses > 0) parentheses--
                '[' -> brackets++
                ']' -> if (brackets > 0) brackets--
                '{' -> if (parentheses == 0 && brackets == 0) return index
            }
            index++
        }
        return null
    }

    private fun relative(file: File): String =
        file.relativeTo(rootDir).invariantSeparatorsPath

    private fun Map<String, Any?>.string(key: String): String = get(key)?.toString().orEmpty()

    private fun Map<String, Any?>.mapList(key: String): List<Map<String, Any?>> =
        (get(key) as? Iterable<*>)?.mapNotNull { value ->
            (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
        }.orEmpty()

    private fun Map<String, Any?>.stringList(key: String): List<String> =
        (get(key) as? Iterable<*>)?.map { it.toString() }.orEmpty()

    private data class AuthorityDiscovery(
        val authorities: Map<String, DiscoveredAuthorityRecord>,
        val authorityCount: Int,
        val errors: List<String>
    )

    private data class DiscoveredAuthorityRecord(
        val path: String,
        val callers: List<String>,
        val hasProductionUse: Boolean
    )

    companion object {
        const val CATALOG_PATH = "standard/architecture/authority-responsibilities.yaml"
        private const val SOURCE_ROOT = "src/main/kotlin"
        private const val SUPPORTED_VERSION = "1.0"
        private val AUTHORITY_DEFINITION = Regex("\\b(?:class|object|interface)\\s+([A-Za-z_][A-Za-z0-9_]*Authority)\\b")
        private val NEXT_TOP_LEVEL_DECLARATION = Regex(
            "\\b(?:class|interface|fun|typealias|val|var)\\b|\\bobject\\s+[A-Za-z_][A-Za-z0-9_]*\\b"
        )
        private val TOP_LEVEL_FIELDS = setOf("version", "purpose", "authorities")
        private val ENTRY_FIELDS = setOf("name", "path", "role", "invariant", "inputs", "outputs", "callers")
        private val ROLES = setOf(
            "semantic-invariant-owner",
            "adapter-policy-owner",
            "evidence-integrity-owner",
            "lifecycle-boundary",
            "orchestration-boundary",
            "identity-owner",
            "target-edge-policy-owner",
            "release-policy-owner"
        )
    }
}
