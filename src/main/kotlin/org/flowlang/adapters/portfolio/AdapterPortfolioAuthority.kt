package org.flowlang.adapters.portfolio

import java.io.File
import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetCapability
import org.flowlang.capabilities.TargetProjectionMode
import org.flowlang.generators.manifest.TargetNativeProjectionCatalog
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.generators.manifest.TargetStructuralProjectionKind
import org.flowlang.serialization.FlowYaml
import org.flowlang.targets.TargetRegistryYamlLoader
import org.flowlang.targets.builtin.BuiltInNativeProjectionCatalogs
import org.flowlang.targets.builtin.BuiltInTargetProjections

enum class AdapterPortfolioRole {
    SEMANTIC_REFERENCE,
    TARGET_ADAPTER;

    companion object {
        fun parse(raw: String): AdapterPortfolioRole = when (raw.trim().lowercase().replace('-', '_')) {
            "semantic_reference" -> SEMANTIC_REFERENCE
            "target_adapter" -> TARGET_ADAPTER
            else -> error("Unknown adapter portfolio role '$raw'.")
        }
    }
}

enum class AdapterSupportClass {
    EXECUTABLE_REFERENCE,
    NATIVE_LEAF_ONLY,
    PROFILE_ONLY;

    companion object {
        fun parse(raw: String): AdapterSupportClass = when (raw.trim().lowercase().replace('-', '_')) {
            "executable_reference" -> EXECUTABLE_REFERENCE
            "native_leaf_only" -> NATIVE_LEAF_ONLY
            "profile_only" -> PROFILE_ONLY
            else -> error("Unknown adapter support class '$raw'.")
        }
    }
}

/** Cumulative target maturity. Later stages require every earlier stage. */
enum class TargetMaturityStage {
    DECLARED,
    ANALYZABLE,
    RENDERABLE,
    EXECUTABLE,
    BEHAVIORALLY_CERTIFIED
}

data class AdapterPortfolioLimitation(
    val id: String,
    val statement: String,
    val evidenceReference: String
)

data class AdapterPortfolioRecord(
    val target: String,
    val role: AdapterPortfolioRole,
    val supportClass: AdapterSupportClass,
    val summary: String,
    val evidenceReferences: List<String>,
    val executableEvidence: List<String>,
    val behavioralEvidence: List<String>,
    val certificationScope: String,
    val limitations: List<AdapterPortfolioLimitation>
)

data class AdapterPortfolioDocument(
    val kind: String,
    val version: String,
    val records: List<AdapterPortfolioRecord>
)

data class AdapterPortfolioFinding(
    val code: String,
    val target: String,
    val message: String
)

data class AdapterPortfolioAssessment(
    val target: String,
    val role: AdapterPortfolioRole,
    val supportClass: AdapterSupportClass,
    val maturityStages: List<TargetMaturityStage>,
    val certificationScope: String,
    val providerAvailable: Boolean,
    val nativeProjectionRules: List<String>,
    val nativeStructuralProjections: List<String>,
    val executableEvidence: List<String>,
    val behavioralEvidence: List<String>,
    val limitations: List<AdapterPortfolioLimitation>
)

data class AdapterPortfolioReport(
    val reportVersion: String = "1.1",
    val status: String,
    val assessments: List<AdapterPortfolioAssessment>,
    val findings: List<AdapterPortfolioFinding>
)

object AdapterPortfolioLoader {
    const val PATH = "adapters/portfolio/builtin-adapter-portfolio.yaml"

    fun load(rootDir: File = File(".")): AdapterPortfolioDocument {
        val file = File(rootDir, PATH)
        require(file.isFile) { "Adapter portfolio document is missing: ${file.path}" }
        val yaml = FlowYaml.readMap(file)
        requireExactKeys(yaml, TOP_LEVEL_KEYS, "adapter portfolio")
        val records = yaml.mapList("records").mapIndexed { index, record ->
            requireExactKeys(record, RECORD_KEYS, "adapter portfolio record[$index]")
            AdapterPortfolioRecord(
                target = record.requiredString("target"),
                role = AdapterPortfolioRole.parse(record.requiredString("role")),
                supportClass = AdapterSupportClass.parse(record.requiredString("supportClass")),
                summary = record.requiredString("summary"),
                evidenceReferences = record.stringList("evidenceReferences"),
                executableEvidence = record.stringList("executableEvidence"),
                behavioralEvidence = record.stringList("behavioralEvidence"),
                certificationScope = record.requiredString("certificationScope"),
                limitations = record.mapList("limitations").mapIndexed { limitationIndex, limitation ->
                    requireExactKeys(
                        limitation,
                        LIMITATION_KEYS,
                        "adapter portfolio record[$index].limitations[$limitationIndex]"
                    )
                    AdapterPortfolioLimitation(
                        id = limitation.requiredString("id"),
                        statement = limitation.requiredString("statement"),
                        evidenceReference = limitation.requiredString("evidenceReference")
                    )
                }
            )
        }
        return AdapterPortfolioDocument(
            kind = yaml.requiredString("kind"),
            version = yaml.requiredString("version"),
            records = records
        )
    }

    private fun requireExactKeys(document: Map<String, Any?>, expected: Set<String>, subject: String) {
        val unknown = document.keys - expected
        val missing = expected - document.keys
        require(unknown.isEmpty()) { "$subject contains unknown fields: ${unknown.sorted().joinToString()}." }
        require(missing.isEmpty()) { "$subject is missing fields: ${missing.sorted().joinToString()}." }
    }

    private fun Map<String, Any?>.requiredString(key: String): String =
        (get(key) as? String)?.takeIf(String::isNotBlank)
            ?: error("Adapter portfolio field '$key' must be a non-blank string.")

    private fun Map<String, Any?>.stringList(key: String): List<String> =
        (get(key) as? Iterable<*>)?.mapIndexed { index, value ->
            (value as? String)?.takeIf(String::isNotBlank)
                ?: error("Adapter portfolio field '$key[$index]' must be a non-blank string.")
        } ?: error("Adapter portfolio field '$key' must be a list.")

    private fun Map<String, Any?>.mapList(key: String): List<Map<String, Any?>> =
        (get(key) as? Iterable<*>)?.mapIndexed { index, value ->
            (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
                ?: error("Adapter portfolio field '$key[$index]' must be a mapping.")
        } ?: error("Adapter portfolio field '$key' must be a list.")

    private val TOP_LEVEL_KEYS = setOf("kind", "version", "records")
    private val RECORD_KEYS = setOf(
        "target",
        "role",
        "supportClass",
        "summary",
        "evidenceReferences",
        "executableEvidence",
        "behavioralEvidence",
        "certificationScope",
        "limitations"
    )
    private val LIMITATION_KEYS = setOf("id", "statement", "evidenceReference")
}

/**
 * Distribution-owned reassessment of target adapters against frozen Core contracts.
 *
 * Registry text is preliminary declaration evidence. Renderability is derived from
 * actual provider composition and native contracts; executable and behavioral
 * maturity require explicit bounded evidence. Structural SUPPORTED claims must also
 * have provider-owned implementation plus behavioral evidence.
 */
class AdapterPortfolioAuthority(
    private val rootDir: File = File("."),
    private val targets: Map<String, TargetCapability> =
        TargetRegistryYamlLoader.loadDirectory(File(rootDir, "targets")),
    private val projections: TargetProjectionRegistry = BuiltInTargetProjections.registry,
    private val nativeProjectionCatalogs: Map<String, TargetNativeProjectionCatalog> =
        BuiltInNativeProjectionCatalogs.byTarget
) {
    fun analyze(): AdapterPortfolioReport = evaluate(AdapterPortfolioLoader.load(rootDir))

    fun evaluate(document: AdapterPortfolioDocument): AdapterPortfolioReport {
        val findings = mutableListOf<AdapterPortfolioFinding>()
        if (document.kind != KIND) findings += finding("ADAPTER_PORTFOLIO_KIND_INVALID", "portfolio", "kind must be '$KIND'.")
        if (document.version != VERSION) findings += finding("ADAPTER_PORTFOLIO_VERSION_INVALID", "portfolio", "version must be '$VERSION'.")

        val duplicateTargets = document.records.groupingBy { it.target }.eachCount().filterValues { it > 1 }.keys.sorted()
        duplicateTargets.forEach { findings += finding("ADAPTER_PORTFOLIO_TARGET_DUPLICATE", it, "Target has more than one portfolio record.") }

        val declaredTargets = document.records.map { it.target }.toSet()
        (targets.keys - declaredTargets).sorted().forEach {
            findings += finding("ADAPTER_PORTFOLIO_TARGET_MISSING", it, "Every target registry entry requires one portfolio record.")
        }
        (declaredTargets - targets.keys).sorted().forEach {
            findings += finding("ADAPTER_PORTFOLIO_TARGET_UNKNOWN", it, "Portfolio record has no target registry entry.")
        }

        val assessments = document.records.sortedBy { it.target }.map { record ->
            val capability = targets[record.target]
            val providerAvailable = projections.providerFor(record.target) != null
            val nativeRules = capability?.projectionRules.orEmpty()
                .filter { it.mode == TargetProjectionMode.NATIVE }
                .map { "${it.module}.${it.action}" }
                .sorted()
            val nativeStructures = nativeProjectionCatalogs[record.target]
                ?.structuralDefinitions.orEmpty()
                .map { it.structure.name }
                .sorted()

            if (record.summary.isBlank()) {
                findings += finding("ADAPTER_PORTFOLIO_SUMMARY_MISSING", record.target, "Portfolio summary must not be blank.")
            }
            if (record.evidenceReferences.isEmpty()) {
                findings += finding("ADAPTER_PORTFOLIO_EVIDENCE_MISSING", record.target, "At least one support evidence reference is required.")
            }
            if (record.certificationScope.isBlank()) {
                findings += finding("ADAPTER_PORTFOLIO_CERTIFICATION_SCOPE_MISSING", record.target, "Certification scope must be explicit even when it is profile-only or native-leaf-only.")
            }
            if (record.limitations.isEmpty()) {
                findings += finding("ADAPTER_PORTFOLIO_LIMITATION_MISSING", record.target, "At least one explicit limitation is required.")
            }
            val duplicateLimitations = record.limitations.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys.sorted()
            duplicateLimitations.forEach {
                findings += finding("ADAPTER_PORTFOLIO_LIMITATION_DUPLICATE", record.target, "Limitation '$it' is duplicated.")
            }

            when (record.role) {
                AdapterPortfolioRole.SEMANTIC_REFERENCE -> {
                    if (record.supportClass != AdapterSupportClass.PROFILE_ONLY) {
                        findings += finding(
                            "ADAPTER_PORTFOLIO_SEMANTIC_REFERENCE_PROMOTED",
                            record.target,
                            "A semantic reference target must remain PROFILE_ONLY."
                        )
                    }
                    if (
                        providerAvailable || nativeRules.isNotEmpty() ||
                        record.executableEvidence.isNotEmpty() || record.behavioralEvidence.isNotEmpty()
                    ) {
                        findings += finding(
                            "ADAPTER_PORTFOLIO_SEMANTIC_REFERENCE_HAS_ADAPTER_CLAIM",
                            record.target,
                            "A semantic reference target cannot claim provider, native-leaf, executable or behavioral adapter evidence."
                        )
                    }
                }
                AdapterPortfolioRole.TARGET_ADAPTER -> when (record.supportClass) {
                    AdapterSupportClass.EXECUTABLE_REFERENCE -> {
                        if (
                            !providerAvailable || nativeRules.isEmpty() ||
                            record.executableEvidence.isEmpty() || record.behavioralEvidence.isEmpty()
                        ) {
                            findings += finding(
                                "ADAPTER_PORTFOLIO_EXECUTABLE_CLAIM_UNSUPPORTED",
                                record.target,
                                "Executable reference requires a provider, native rules, exact executable evidence and independent behavioral evidence."
                            )
                        }
                    }
                    AdapterSupportClass.NATIVE_LEAF_ONLY -> {
                        if (
                            !providerAvailable || nativeRules.isEmpty() ||
                            record.executableEvidence.isNotEmpty() || record.behavioralEvidence.isNotEmpty()
                        ) {
                            findings += finding(
                                "ADAPTER_PORTFOLIO_NATIVE_LEAF_CLAIM_INVALID",
                                record.target,
                                "Native-leaf-only requires a provider and native rules but no end-to-end executable or behavioral certification claim."
                            )
                        }
                    }
                    AdapterSupportClass.PROFILE_ONLY -> {
                        if (
                            providerAvailable || nativeRules.isNotEmpty() ||
                            record.executableEvidence.isNotEmpty() || record.behavioralEvidence.isNotEmpty()
                        ) {
                            findings += finding(
                                "ADAPTER_PORTFOLIO_PROFILE_ONLY_CLAIM_INVALID",
                                record.target,
                                "Profile-only targets cannot have a composed provider, native rules, executable evidence or behavioral certification."
                            )
                        }
                    }
                }
            }

            if (record.role == AdapterPortfolioRole.TARGET_ADAPTER && capability != null) {
                structuralSupport(capability).forEach { (kind, support) ->
                    if (
                        support == SupportLevel.SUPPORTED &&
                        (!providerAvailable || nativeProjectionCatalogs[record.target]?.hasStructuralProjection(kind) != true)
                    ) {
                        findings += finding(
                            "ADAPTER_PORTFOLIO_STRUCTURAL_SUPPORT_UNPROVEN",
                            record.target,
                            "SUPPORTED structural capability '${kind.capability}' has no composed provider-owned production and behavioral projection evidence."
                        )
                    }
                }
            }

            val references = record.evidenceReferences + record.executableEvidence + record.behavioralEvidence +
                record.limitations.map { it.evidenceReference }
            references.distinct().forEach { reference ->
                if (!evidenceFile(reference).isFile) {
                    findings += finding(
                        "ADAPTER_PORTFOLIO_EVIDENCE_UNRESOLVED",
                        record.target,
                        "Evidence reference '$reference' does not resolve to a repository file."
                    )
                }
            }
            if (record.evidenceReferences.none { it.substringBefore('#').startsWith("targets/") }) {
                findings += finding(
                    "ADAPTER_PORTFOLIO_REGISTRY_EVIDENCE_MISSING",
                    record.target,
                    "Every record must cite target registry evidence."
                )
            }
            if (providerAvailable && record.evidenceReferences.none {
                    it.substringBefore('#') == BUILT_IN_PROJECTIONS
                }) {
                findings += finding(
                    "ADAPTER_PORTFOLIO_PROVIDER_EVIDENCE_MISSING",
                    record.target,
                    "A composed provider claim must cite the distribution composition root."
                )
            }

            AdapterPortfolioAssessment(
                target = record.target,
                role = record.role,
                supportClass = record.supportClass,
                maturityStages = maturityStages(
                    capability = capability,
                    providerAvailable = providerAvailable,
                    nativeRules = nativeRules,
                    executableEvidence = record.executableEvidence,
                    behavioralEvidence = record.behavioralEvidence
                ),
                certificationScope = record.certificationScope,
                providerAvailable = providerAvailable,
                nativeProjectionRules = nativeRules,
                nativeStructuralProjections = nativeStructures,
                executableEvidence = record.executableEvidence,
                behavioralEvidence = record.behavioralEvidence,
                limitations = record.limitations
            )
        }

        return AdapterPortfolioReport(
            status = if (findings.isEmpty()) "PASS" else "FAIL",
            assessments = assessments,
            findings = findings.sortedWith(compareBy({ it.target }, { it.code }, { it.message }))
        )
    }

    private fun maturityStages(
        capability: TargetCapability?,
        providerAvailable: Boolean,
        nativeRules: List<String>,
        executableEvidence: List<String>,
        behavioralEvidence: List<String>
    ): List<TargetMaturityStage> = buildList {
        if (capability == null) return@buildList
        add(TargetMaturityStage.DECLARED)
        if (capability.expressionSupport == null || capability.topologyProfile == null) return@buildList
        add(TargetMaturityStage.ANALYZABLE)
        if (!providerAvailable || nativeRules.isEmpty()) return@buildList
        add(TargetMaturityStage.RENDERABLE)
        if (executableEvidence.isEmpty()) return@buildList
        add(TargetMaturityStage.EXECUTABLE)
        if (behavioralEvidence.isEmpty()) return@buildList
        add(TargetMaturityStage.BEHAVIORALLY_CERTIFIED)
    }

    private fun structuralSupport(capability: TargetCapability): Map<TargetStructuralProjectionKind, SupportLevel> =
        linkedMapOf(
            TargetStructuralProjectionKind.CONDITION to
                capability.feature("conditions.inline", capability.conditions),
            TargetStructuralProjectionKind.PARALLEL to
                capability.feature("parallel.dag", capability.parallel),
            TargetStructuralProjectionKind.LOOP to
                capability.feature("loops.dynamic", capability.dynamicLoops),
            TargetStructuralProjectionKind.MATCH to
                capability.feature("match.basic", capability.match),
            TargetStructuralProjectionKind.RETRY to
                capability.feature("retry.task", capability.retry),
            TargetStructuralProjectionKind.ERROR_BOUNDARY to
                capability.feature("errorHandlers.finally", capability.errorHandlers)
        )

    private fun evidenceFile(reference: String): File = File(rootDir, reference.substringBefore('#'))

    private fun finding(code: String, target: String, message: String) =
        AdapterPortfolioFinding(code, target, message)

    companion object {
        const val KIND = "FlowAdapterPortfolio"
        const val VERSION = "1.1"
        const val BUILT_IN_PROJECTIONS =
            "src/main/kotlin/org/flowlang/targets/builtin/BuiltInTargetProjections.kt"
    }
}
