package org.flowlang.adapters.topology

import java.io.File
import org.flowlang.adapters.portfolio.AdapterPortfolioDocument
import org.flowlang.adapters.portfolio.AdapterPortfolioLoader
import org.flowlang.adapters.portfolio.AdapterPortfolioRole
import org.flowlang.adapters.portfolio.AdapterSupportClass
import org.flowlang.capabilities.TargetCapability
import org.flowlang.adapters.contract.AdapterCatalog
import org.flowlang.generators.manifest.TargetProjectionProvider
import org.flowlang.generators.manifest.providerFor
import org.flowlang.generators.manifest.requireProvider
import org.flowlang.serialization.FlowYaml
import org.flowlang.targets.TargetRegistryYamlLoader
import org.flowlang.topology.ExecutionTopologyKind
import org.flowlang.topology.ExecutionTopologySupportStatus

enum class AdapterTopologyFacet {
    EXECUTION_ISOLATION,
    STATE_LIFETIME,
    TRANSFER,
    SCOPE,
    INTERACTION,
    CONCURRENCY;

    companion object {
        fun parse(raw: String): AdapterTopologyFacet = when (raw.trim().lowercase().replace('-', '_')) {
            "execution_isolation" -> EXECUTION_ISOLATION
            "state_lifetime" -> STATE_LIFETIME
            "transfer" -> TRANSFER
            "scope" -> SCOPE
            "interaction" -> INTERACTION
            "concurrency" -> CONCURRENCY
            else -> error("Unknown adapter topology facet '$raw'.")
        }
    }
}

enum class AdapterTopologyClaimStatus {
    SUPPORTED,
    PARTIAL,
    UNSUPPORTED,
    UNKNOWN;

    fun toCoreStatus(): ExecutionTopologySupportStatus = when (this) {
        SUPPORTED -> ExecutionTopologySupportStatus.SUPPORTED
        PARTIAL -> ExecutionTopologySupportStatus.PARTIAL
        UNSUPPORTED -> ExecutionTopologySupportStatus.UNSUPPORTED
        UNKNOWN -> ExecutionTopologySupportStatus.UNKNOWN
    }

    companion object {
        fun parse(raw: String): AdapterTopologyClaimStatus = when (raw.trim().lowercase().replace('-', '_')) {
            "supported" -> SUPPORTED
            "partial" -> PARTIAL
            "unsupported" -> UNSUPPORTED
            "unknown" -> UNKNOWN
            else -> error("Unknown adapter topology status '$raw'.")
        }
    }
}

data class AdapterTopologyClaim(
    val key: String,
    val facet: AdapterTopologyFacet,
    val status: AdapterTopologyClaimStatus,
    val mechanism: String,
    val evidenceReferences: List<String>,
    val limitations: List<String>
)

data class AdapterTopologyRecord(
    val target: String,
    val claims: List<AdapterTopologyClaim>
)

data class AdapterTopologyEvidenceDocument(
    val kind: String,
    val version: String,
    val records: List<AdapterTopologyRecord>
)

data class AdapterTopologyFinding(
    val code: String,
    val target: String,
    val claim: String,
    val message: String
)

data class AdapterTopologyAssessment(
    val target: String,
    val role: AdapterPortfolioRole?,
    val supportClass: AdapterSupportClass?,
    val providerAvailable: Boolean,
    val claims: List<AdapterTopologyClaim>
)

data class AdapterTopologyEvidenceReport(
    val reportVersion: String = "1.0",
    val status: String,
    val assessments: List<AdapterTopologyAssessment>,
    val findings: List<AdapterTopologyFinding>
)

object AdapterTopologyEvidenceLoader {
    const val PATH = "adapters/topology/builtin-adapter-topology.yaml"

    fun load(rootDir: File = File(".")): AdapterTopologyEvidenceDocument {
        val file = File(rootDir, PATH)
        require(file.isFile) { "Adapter topology evidence is missing: ${file.path}" }
        val yaml = FlowYaml.readMap(file)
        requireExactKeys(yaml, TOP_LEVEL_KEYS, "adapter topology evidence")
        val records = yaml.mapList("records").mapIndexed { recordIndex, record ->
            requireExactKeys(record, RECORD_KEYS, "adapter topology record[$recordIndex]")
            val target = record.requiredString("target")
            val claimsMap = record.map("claims")
            val claims = claimsMap.entries.map { (key, rawClaim) ->
                require(key in AdapterTopologyClaimContract.requiredClaimKeys) {
                    "Adapter topology record '$target' contains unknown claim '$key'."
                }
                val claim = rawClaim.asStringMap("adapter topology claim '$target.$key'")
                requireExactKeys(claim, CLAIM_KEYS, "adapter topology claim '$target.$key'")
                AdapterTopologyClaim(
                    key = key,
                    facet = AdapterTopologyFacet.parse(claim.requiredString("facet")),
                    status = AdapterTopologyClaimStatus.parse(claim.requiredString("status")),
                    mechanism = claim.requiredString("mechanism"),
                    evidenceReferences = claim.stringList("evidenceReferences"),
                    limitations = claim.stringList("limitations")
                )
            }
            AdapterTopologyRecord(target, claims)
        }
        return AdapterTopologyEvidenceDocument(
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

    private fun Any?.asStringMap(subject: String): Map<String, Any?> =
        (this as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }
            ?: error("$subject must be a mapping.")

    private fun Map<String, Any?>.requiredString(key: String): String =
        (get(key) as? String)?.takeIf(String::isNotBlank)
            ?: error("Adapter topology field '$key' must be a non-blank string.")

    private fun Map<String, Any?>.stringList(key: String): List<String> =
        (get(key) as? Iterable<*>)?.mapIndexed { index, value ->
            (value as? String)?.takeIf(String::isNotBlank)
                ?: error("Adapter topology field '$key[$index]' must be a non-blank string.")
        } ?: error("Adapter topology field '$key' must be a list.")

    private fun Map<String, Any?>.map(key: String): Map<String, Any?> =
        get(key).asStringMap("Adapter topology field '$key'")

    private fun Map<String, Any?>.mapList(key: String): List<Map<String, Any?>> =
        (get(key) as? Iterable<*>)?.mapIndexed { index, value ->
            value.asStringMap("Adapter topology field '$key[$index]'")
        } ?: error("Adapter topology field '$key' must be a list.")

    private val TOP_LEVEL_KEYS = setOf("kind", "version", "records")
    private val RECORD_KEYS = setOf("target", "claims")
    private val CLAIM_KEYS = setOf("facet", "status", "mechanism", "evidenceReferences", "limitations")
}

object AdapterTopologyClaimContract {
    const val INTERACTION = "interaction"
    const val CONCURRENCY = "concurrency"

    val coreClaimKinds: Map<String, ExecutionTopologyKind> =
        ExecutionTopologyKind.entries.associateBy(ExecutionTopologyKind::registryKey)

    val requiredClaimKeys: Set<String> = coreClaimKinds.keys + setOf(INTERACTION, CONCURRENCY)

    private val expectedFacets = mapOf(
        "workflowScope" to AdapterTopologyFacet.SCOPE,
        "branchIsolation" to AdapterTopologyFacet.EXECUTION_ISOLATION,
        "attemptIsolation" to AdapterTopologyFacet.EXECUTION_ISOLATION,
        "workflowLifetime" to AdapterTopologyFacet.STATE_LIFETIME,
        "suspendResume" to AdapterTopologyFacet.STATE_LIFETIME,
        "ephemeralWorkspace" to AdapterTopologyFacet.SCOPE,
        "durableState" to AdapterTopologyFacet.STATE_LIFETIME,
        "valuePropagation" to AdapterTopologyFacet.TRANSFER,
        "workspacePropagation" to AdapterTopologyFacet.TRANSFER,
        "statePropagation" to AdapterTopologyFacet.TRANSFER,
        "failurePropagation" to AdapterTopologyFacet.TRANSFER,
        INTERACTION to AdapterTopologyFacet.INTERACTION,
        CONCURRENCY to AdapterTopologyFacet.CONCURRENCY
    )

    fun expectedFacet(key: String): AdapterTopologyFacet =
        requireNotNull(expectedFacets[key]) { "Unknown adapter topology claim '$key'." }

    fun registryEvidenceReference(target: String, key: String): String =
        "${AdapterTopologyEvidenceLoader.PATH}#records.$target.claims.$key"
}

/**
 * Adapter-owned topology evidence authority.
 *
 * The target registry remains the runtime profile consumed by Core matching, but
 * it may only mirror evidence from this independent adapter manifest. A registry
 * declaration cannot prove itself. Portfolio support class, provider composition,
 * claim completeness and concrete evidence are reconciled before any topology
 * statement is accepted.
 */
class AdapterTopologyEvidenceAuthority(
    private val rootDir: File = File("."),
    private val targets: Map<String, TargetCapability>,
    private val projections: AdapterCatalog<TargetProjectionProvider>,
    private val portfolio: AdapterPortfolioDocument = AdapterPortfolioLoader.load(rootDir)
) {
    fun analyze(): AdapterTopologyEvidenceReport = evaluate(AdapterTopologyEvidenceLoader.load(rootDir))

    fun evaluate(document: AdapterTopologyEvidenceDocument): AdapterTopologyEvidenceReport {
        val findings = mutableListOf<AdapterTopologyFinding>()
        if (document.kind != KIND) findings += finding("ADAPTER_TOPOLOGY_KIND_INVALID", "topology", "", "kind must be '$KIND'.")
        if (document.version != VERSION) findings += finding("ADAPTER_TOPOLOGY_VERSION_INVALID", "topology", "", "version must be '$VERSION'.")

        val duplicateTargets = document.records.groupingBy(AdapterTopologyRecord::target)
            .eachCount().filterValues { it > 1 }.keys.sorted()
        duplicateTargets.forEach {
            findings += finding("ADAPTER_TOPOLOGY_TARGET_DUPLICATE", it, "", "Target has more than one topology record.")
        }

        val declaredTargets = document.records.map(AdapterTopologyRecord::target).toSet()
        val portfolioTargets = portfolio.records.map { it.target }.toSet()
        (targets.keys - declaredTargets).sorted().forEach {
            findings += finding("ADAPTER_TOPOLOGY_TARGET_MISSING", it, "", "Every target registry entry requires topology evidence.")
        }
        (declaredTargets - targets.keys).sorted().forEach {
            findings += finding("ADAPTER_TOPOLOGY_TARGET_UNKNOWN", it, "", "Topology evidence has no target registry entry.")
        }
        (portfolioTargets - declaredTargets).sorted().forEach {
            findings += finding("ADAPTER_TOPOLOGY_PORTFOLIO_TARGET_MISSING", it, "", "Every portfolio record requires topology evidence.")
        }

        val portfolioByTarget = portfolio.records.associateBy { it.target }
        val assessments = document.records.sortedBy { it.target }.map { record ->
            val portfolioRecord = portfolioByTarget[record.target]
            val target = targets[record.target]
            val providerAvailable = projections.providerFor(record.target) != null
            val claimsByKey = record.claims.groupBy(AdapterTopologyClaim::key)

            claimsByKey.filterValues { it.size > 1 }.keys.sorted().forEach { key ->
                findings += finding("ADAPTER_TOPOLOGY_CLAIM_DUPLICATE", record.target, key, "Claim is declared more than once.")
            }
            val observedClaims = record.claims.map(AdapterTopologyClaim::key).toSet()
            (AdapterTopologyClaimContract.requiredClaimKeys - observedClaims).sorted().forEach { key ->
                findings += finding("ADAPTER_TOPOLOGY_CLAIM_MISSING", record.target, key, "Required topology claim is missing.")
            }

            record.claims.sortedBy { it.key }.forEach { claim ->
                val expectedFacet = AdapterTopologyClaimContract.expectedFacet(claim.key)
                if (claim.facet != expectedFacet) {
                    findings += finding(
                        "ADAPTER_TOPOLOGY_FACET_MISMATCH",
                        record.target,
                        claim.key,
                        "Claim belongs to $expectedFacet, not ${claim.facet}."
                    )
                }
                if (claim.evidenceReferences.isEmpty()) {
                    findings += finding("ADAPTER_TOPOLOGY_EVIDENCE_MISSING", record.target, claim.key, "Claim requires evidence references.")
                }
                if (claim.status != AdapterTopologyClaimStatus.SUPPORTED && claim.limitations.isEmpty()) {
                    findings += finding(
                        "ADAPTER_TOPOLOGY_LIMITATION_MISSING",
                        record.target,
                        claim.key,
                        "Partial, unsupported and unknown claims require explicit limitations."
                    )
                }
                claim.evidenceReferences.distinct().forEach { reference ->
                    val path = reference.substringBefore('#')
                    if (!File(rootDir, path).isFile) {
                        findings += finding(
                            "ADAPTER_TOPOLOGY_EVIDENCE_UNRESOLVED",
                            record.target,
                            claim.key,
                            "Evidence reference '$reference' does not resolve to a repository file."
                        )
                    }
                    if (path == AdapterTopologyEvidenceLoader.PATH || path.startsWith("targets/")) {
                        findings += finding(
                            "ADAPTER_TOPOLOGY_EVIDENCE_SELF_REFERENTIAL",
                            record.target,
                            claim.key,
                            "Claim evidence must not be the topology manifest or target registry itself."
                        )
                    }
                }
            }

            if (portfolioRecord == null) {
                findings += finding("ADAPTER_TOPOLOGY_PORTFOLIO_RECORD_MISSING", record.target, "", "Portfolio record is missing.")
            } else {
                when {
                    portfolioRecord.role == AdapterPortfolioRole.SEMANTIC_REFERENCE -> {
                        if (providerAvailable) {
                            findings += finding(
                                "ADAPTER_TOPOLOGY_SEMANTIC_REFERENCE_PROVIDER",
                                record.target,
                                "",
                                "Semantic reference targets cannot have a composed provider."
                            )
                        }
                    }
                    portfolioRecord.supportClass == AdapterSupportClass.PROFILE_ONLY -> {
                        record.claims.filter { it.status != AdapterTopologyClaimStatus.UNKNOWN }.forEach { claim ->
                            findings += finding(
                                "ADAPTER_TOPOLOGY_PROFILE_ONLY_PROMOTED",
                                record.target,
                                claim.key,
                                "Profile-only target claims must remain UNKNOWN until a provider supplies evidence."
                            )
                        }
                    }
                    portfolioRecord.supportClass in setOf(
                        AdapterSupportClass.NATIVE_LEAF_ONLY,
                        AdapterSupportClass.EXECUTABLE_REFERENCE
                    ) && !providerAvailable -> {
                        findings += finding(
                            "ADAPTER_TOPOLOGY_PROVIDER_MISSING",
                            record.target,
                            "",
                            "Native-leaf and executable targets require a composed provider."
                        )
                    }
                }
            }

            val registryDeclarations = target?.topologyProfile?.declarations.orEmpty()
                .associateBy { it.kind.registryKey }
            AdapterTopologyClaimContract.coreClaimKinds.keys.sorted().forEach { key ->
                val claim = claimsByKey[key]?.singleOrNull()
                val registry = registryDeclarations[key]
                when {
                    claim == null -> Unit
                    registry == null -> findings += finding(
                        "ADAPTER_TOPOLOGY_REGISTRY_CLAIM_MISSING",
                        record.target,
                        key,
                        "Runtime target profile does not mirror the adapter evidence claim."
                    )
                    registry.status != claim.status.toCoreStatus() -> findings += finding(
                        "ADAPTER_TOPOLOGY_REGISTRY_STATUS_MISMATCH",
                        record.target,
                        key,
                        "Registry status ${registry.status} does not match evidence status ${claim.status}."
                    )
                    registry.evidenceReference != AdapterTopologyClaimContract.registryEvidenceReference(record.target, key) ->
                        findings += finding(
                            "ADAPTER_TOPOLOGY_REGISTRY_EVIDENCE_MISMATCH",
                            record.target,
                            key,
                            "Registry evidence '${registry.evidenceReference}' does not point to the adapter claim."
                        )
                }
            }

            AdapterTopologyAssessment(
                target = record.target,
                role = portfolioRecord?.role,
                supportClass = portfolioRecord?.supportClass,
                providerAvailable = providerAvailable,
                claims = record.claims.sortedBy { it.key }
            )
        }

        return AdapterTopologyEvidenceReport(
            status = if (findings.isEmpty()) "PASS" else "FAIL",
            assessments = assessments,
            findings = findings.sortedWith(compareBy({ it.target }, { it.claim }, { it.code }, { it.message }))
        )
    }

    private fun finding(code: String, target: String, claim: String, message: String) =
        AdapterTopologyFinding(code, target, claim, message)

    companion object {
        const val KIND = "FlowAdapterTopologyEvidence"
        const val VERSION = "1.0"
    }
}
