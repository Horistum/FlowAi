package org.flowlang.adapters.portfolio

import org.flowlang.adapters.continuity.AdapterContinuityScopedSupport
import java.io.File
import org.flowlang.capabilities.TargetCapability
import org.flowlang.adapters.contract.AdapterCatalog
import org.flowlang.generators.manifest.TargetProjectionProvider
import org.flowlang.generators.manifest.providerFor
import org.flowlang.generators.manifest.requireProvider
import org.flowlang.serialization.FlowYaml

/** Post-A0 bounded promotion. Historical portfolio evidence remains immutable. */
data class AdapterExecutableReferencePromotion(
    val target: String,
    val supportClass: AdapterSupportClass,
    val scenarioId: String,
    val snapshotReference: String,
    val scopeIdentity: String,
    val evidenceReferences: List<String>
)

data class AdapterExecutableReferencePromotionDocument(
    val kind: String,
    val version: String,
    val promotions: List<AdapterExecutableReferencePromotion>
)

data class AdapterExecutableReferencePromotionFinding(
    val code: String,
    val target: String,
    val message: String
)

data class AdapterExecutableReferencePromotionReport(
    val reportVersion: String = "1.0",
    val status: String,
    val promotions: List<AdapterExecutableReferencePromotion>,
    val findings: List<AdapterExecutableReferencePromotionFinding>
)

object AdapterExecutableReferencePromotionLoader {
    const val PATH = "adapters/portfolio/executable-reference-promotions.yaml"
    const val KIND = "FlowAdapterExecutableReferencePromotions"
    const val VERSION = "1.0"

    fun load(rootDir: File = File(".")): AdapterExecutableReferencePromotionDocument {
        val file = File(rootDir, PATH)
        require(file.isFile) { "Adapter executable-reference promotion document is missing: ${file.path}" }
        val yaml = FlowYaml.readMap(file)
        requireExactKeys(yaml, ROOT_KEYS, PATH)
        val promotions = yaml.mapList("promotions").mapIndexed { index, record ->
            requireExactKeys(record, PROMOTION_KEYS, "$PATH.promotions[$index]")
            AdapterExecutableReferencePromotion(
                target = record.requiredString("target"),
                supportClass = AdapterSupportClass.parse(record.requiredString("supportClass")),
                scenarioId = record.requiredString("scenarioId"),
                snapshotReference = record.requiredString("snapshotReference"),
                scopeIdentity = record.requiredString("scopeIdentity"),
                evidenceReferences = record.stringList("evidenceReferences", required = true)
            )
        }
        require(promotions.isNotEmpty()) { "$PATH.promotions must not be empty." }
        return AdapterExecutableReferencePromotionDocument(
            kind = yaml.requiredString("kind"),
            version = yaml.requiredString("version"),
            promotions = promotions
        )
    }

    private fun requireExactKeys(value: Map<String, Any?>, expected: Set<String>, path: String) {
        val unknown = value.keys - expected
        val missing = expected - value.keys
        require(unknown.isEmpty()) { "$path has unknown fields: ${unknown.sorted().joinToString()}." }
        require(missing.isEmpty()) { "$path is missing fields: ${missing.sorted().joinToString()}." }
    }

    private fun Map<String, Any?>.requiredString(key: String): String =
        (get(key) as? String)?.takeIf(String::isNotBlank)
            ?: error("Promotion field '$key' must be non-blank text.")

    private fun Map<String, Any?>.stringList(key: String, required: Boolean): List<String> =
        (get(key) as? Iterable<*>)?.mapIndexed { index, value ->
            (value as? String)?.takeIf(String::isNotBlank)
                ?: error("Promotion field '$key[$index]' must be non-blank text.")
        }?.also { values ->
            require(!required || values.isNotEmpty()) { "Promotion field '$key' must not be empty." }
            require(values.size == values.toSet().size) { "Promotion field '$key' contains duplicates." }
        } ?: error("Promotion field '$key' must be a list.")

    private fun Map<String, Any?>.mapList(key: String): List<Map<String, Any?>> =
        (get(key) as? Iterable<*>)?.mapIndexed { index, value ->
            (value as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
                ?: error("Promotion field '$key[$index]' must be a mapping.")
        } ?: error("Promotion field '$key' must be a list.")

    private val ROOT_KEYS = setOf("kind", "version", "promotions")
    private val PROMOTION_KEYS = setOf(
        "target", "supportClass", "scenarioId", "snapshotReference", "scopeIdentity", "evidenceReferences"
    )
}

class AdapterExecutableReferencePromotionAuthority(
    private val rootDir: File = File("."),
    private val targets: Map<String, TargetCapability>,
    private val projections: AdapterCatalog<TargetProjectionProvider>,
    private val scopedSupports: List<AdapterContinuityScopedSupport>
) {
    fun analyze(): AdapterExecutableReferencePromotionReport =
        evaluate(AdapterExecutableReferencePromotionLoader.load(rootDir))

    fun evaluate(document: AdapterExecutableReferencePromotionDocument): AdapterExecutableReferencePromotionReport {
        val findings = mutableListOf<AdapterExecutableReferencePromotionFinding>()
        if (document.kind != AdapterExecutableReferencePromotionLoader.KIND) {
            findings += finding("ADAPTER_PROMOTION_KIND_INVALID", "promotions", "Unexpected document kind '${document.kind}'.")
        }
        if (document.version != AdapterExecutableReferencePromotionLoader.VERSION) {
            findings += finding("ADAPTER_PROMOTION_VERSION_INVALID", "promotions", "Unsupported version '${document.version}'.")
        }

        document.promotions.groupingBy { it.target }.eachCount().filterValues { it > 1 }.keys.sorted().forEach {
            findings += finding("ADAPTER_PROMOTION_TARGET_DUPLICATE", it, "Target has more than one executable-reference promotion.")
        }
        document.promotions.groupingBy { it.snapshotReference }.eachCount().filterValues { it > 1 }.keys.sorted().forEach {
            findings += finding("ADAPTER_PROMOTION_SNAPSHOT_DUPLICATE", it, "Snapshot is assigned to more than one promotion.")
        }
        document.promotions.groupingBy { it.scopeIdentity }.eachCount().filterValues { it > 1 }.keys.sorted().forEach {
            findings += finding("ADAPTER_PROMOTION_SCOPE_DUPLICATE", it, "Scope identity is promoted more than once.")
        }

        val basePortfolio = AdapterPortfolioLoader.load(rootDir).records.associateBy { it.target }
        val declaredScopes = scopedSupports.associateBy { it.identity }
        document.promotions.forEach { promotion ->
            val target = promotion.target
            val base = basePortfolio[target]
            if (targets[target] == null) {
                findings += finding("ADAPTER_PROMOTION_TARGET_UNKNOWN", target, "Promotion target is absent from the target registry.")
            }
            if (projections.providerFor(target) == null) {
                findings += finding("ADAPTER_PROMOTION_PROVIDER_MISSING", target, "Executable-reference promotion requires a composed provider.")
            }
            if (base == null) {
                findings += finding("ADAPTER_PROMOTION_BASE_PORTFOLIO_MISSING", target, "Promotion target has no historical portfolio record.")
            } else {
                if (base.role != AdapterPortfolioRole.TARGET_ADAPTER) {
                    findings += finding("ADAPTER_PROMOTION_ROLE_INVALID", target, "Only a target adapter may be promoted.")
                }
                if (base.supportClass != AdapterSupportClass.NATIVE_LEAF_ONLY) {
                    findings += finding(
                        "ADAPTER_PROMOTION_BASE_CLASS_INVALID",
                        target,
                        "Bounded A1.0 promotion expects the historical base class NATIVE_LEAF_ONLY, found ${base.supportClass}."
                    )
                }
            }
            if (promotion.supportClass != AdapterSupportClass.EXECUTABLE_REFERENCE) {
                findings += finding("ADAPTER_PROMOTION_CLASS_INVALID", target, "Promotion class must be EXECUTABLE_REFERENCE.")
            }
            if (declaredScopes[promotion.scopeIdentity] == null) {
                findings += finding("ADAPTER_PROMOTION_SCOPE_UNKNOWN", target, "Promotion scope is not declared by the continuity authority.")
            }
            validateRepositoryReference(target, promotion.snapshotReference, "ADAPTER_PROMOTION_SNAPSHOT", findings)
            promotion.evidenceReferences.forEach { reference ->
                validateRepositoryReference(target, reference, "ADAPTER_PROMOTION_EVIDENCE", findings)
            }
            val paths = promotion.evidenceReferences.map { it.substringBefore('#') }
            if (paths.none { it.startsWith("src/main/") }) {
                findings += finding("ADAPTER_PROMOTION_IMPLEMENTATION_MISSING", target, "Production implementation evidence is required.")
            }
            if (paths.none { it.startsWith("src/test/") || it.startsWith("tests/") }) {
                findings += finding("ADAPTER_PROMOTION_BEHAVIOR_MISSING", target, "Independent behavioral evidence is required.")
            }
            if (!promotion.snapshotReference.contains(promotion.target)) {
                findings += finding(
                    "ADAPTER_PROMOTION_SNAPSHOT_NOT_TARGET_SCOPED",
                    target,
                    "Snapshot path must be explicitly target-scoped and must not reuse historical cross-boundary evidence."
                )
            }
        }

        return AdapterExecutableReferencePromotionReport(
            status = if (findings.isEmpty()) "PASS" else "FAIL",
            promotions = document.promotions.sortedBy { it.target },
            findings = findings.sortedWith(compareBy({ it.target }, { it.code }, { it.message }))
        )
    }

    private fun validateRepositoryReference(
        target: String,
        reference: String,
        codePrefix: String,
        findings: MutableList<AdapterExecutableReferencePromotionFinding>
    ) {
        if (reference.startsWith("http://") || reference.startsWith("https://")) {
            findings += finding("${codePrefix}_EXTERNAL", target, "External reference cannot certify repository behavior: $reference")
            return
        }
        val relative = reference.substringBefore('#')
        val root = rootDir.canonicalFile
        val file = File(rootDir, relative).canonicalFile
        if (file.path != root.path && !file.path.startsWith(root.path + File.separator)) {
            findings += finding("${codePrefix}_ESCAPES_REPOSITORY", target, "Reference escapes the repository: $reference")
        } else if (!file.isFile) {
            findings += finding("${codePrefix}_MISSING", target, "Reference does not resolve: $reference")
        }
    }

    private fun finding(code: String, target: String, message: String) =
        AdapterExecutableReferencePromotionFinding(code, target, message)
}
