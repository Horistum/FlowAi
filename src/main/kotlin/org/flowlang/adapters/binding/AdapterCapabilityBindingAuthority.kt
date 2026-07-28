package org.flowlang.adapters.binding

import java.io.File
import org.flowlang.intent.IntentBindingContractAuthority
import org.flowlang.intent.IntentBindingEffectPolicy
import org.flowlang.intent.StandardCapability
import org.flowlang.modules.Effects
import org.flowlang.modules.ModuleRegistry
import org.flowlang.serialization.FlowYaml

enum class AdapterCapabilityBindingClass {
    CONCRETE_IMPLEMENTATION,
    SEMANTIC_FALLBACK
}

data class AdapterCapabilityBindingRecord(
    val id: String,
    val bindingClass: AdapterCapabilityBindingClass,
    val capability: StandardCapability,
    val module: String,
    val action: String,
    val systemTypes: Set<String>,
    val mappedSemanticParameters: Set<String>,
    val unsupportedSemanticParameters: Map<String, String>,
    val bindingParameters: Set<String>,
    val effectPolicy: IntentBindingEffectPolicy,
    val evidenceReferences: List<String>,
    val limitations: List<String>
) {
    val key: BindingKey get() = BindingKey(capability, module, action)
}

data class AdapterCapabilityBindingDocument(
    val version: String,
    val records: List<AdapterCapabilityBindingRecord>
)

data class BindingKey(
    val capability: StandardCapability,
    val module: String,
    val action: String
) {
    override fun toString(): String = "$module.$action#${capability.name}"
}

data class AdapterCapabilityBindingFinding(
    val code: String,
    val binding: String,
    val message: String
)

data class AdapterCapabilityBindingReport(
    val reportVersion: String = "1.0",
    val status: String,
    val findings: List<AdapterCapabilityBindingFinding>,
    val recordCount: Int,
    val implementationClaimCount: Int
)

object AdapterCapabilityBindingLoader {
    const val PATH = "adapters/bindings/builtin-capability-bindings.yaml"

    fun load(rootDir: File = File(".")): AdapterCapabilityBindingDocument {
        val file = File(rootDir, PATH)
        require(file.isFile) { "Adapter capability binding manifest is missing: ${file.path}" }
        val root = FlowYaml.readMap(file)
        requireExactKeys(root, ROOT_KEYS, PATH)
        val version = text(root, "version", PATH)
        val records = objectList(root["bindings"], "$PATH.bindings").mapIndexed { index, raw ->
            parseRecord(raw, "$PATH.bindings[$index]")
        }
        require(records.isNotEmpty()) { "$PATH.bindings must not be empty." }
        return AdapterCapabilityBindingDocument(version, records)
    }

    private fun parseRecord(raw: Map<String, Any?>, path: String): AdapterCapabilityBindingRecord {
        requireExactKeys(raw, RECORD_KEYS, path)
        val semantics = map(raw["semanticParameters"], "$path.semanticParameters")
        requireExactKeys(semantics, SEMANTIC_KEYS, "$path.semanticParameters")
        val unsupported = map(semantics["unsupported"], "$path.semanticParameters.unsupported")
            .mapValues { (name, reason) ->
                require(name.isNotBlank()) { "$path.semanticParameters.unsupported contains a blank parameter." }
                (reason as? String)?.takeIf(String::isNotBlank)
                    ?: error("$path.semanticParameters.unsupported.$name must be non-blank text.")
            }
        return AdapterCapabilityBindingRecord(
            id = text(raw, "id", path),
            bindingClass = enumValue(text(raw, "class", path), "$path.class"),
            capability = enumValue(text(raw, "capability", path), "$path.capability"),
            module = text(raw, "module", path),
            action = text(raw, "action", path),
            systemTypes = stringList(raw["systemTypes"], "$path.systemTypes").toSet(),
            mappedSemanticParameters = stringList(semantics["mapped"], "$path.semanticParameters.mapped").toSet(),
            unsupportedSemanticParameters = unsupported,
            bindingParameters = stringList(raw["bindingParameters"], "$path.bindingParameters").toSet(),
            effectPolicy = enumValue(text(raw, "effectPolicy", path), "$path.effectPolicy"),
            evidenceReferences = stringList(raw["evidenceReferences"], "$path.evidenceReferences", required = true),
            limitations = stringList(raw["limitations"], "$path.limitations", required = true)
        )
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String, path: String): T =
        runCatching { enumValueOf<T>(value) }
            .getOrElse { error("$path has unknown value '$value'; expected ${enumValues<T>().joinToString()}.") }

    private fun requireExactKeys(value: Map<String, Any?>, keys: Set<String>, path: String) {
        val unknown = value.keys - keys
        val missing = keys - value.keys
        require(unknown.isEmpty()) { "$path has unknown fields: ${unknown.sorted().joinToString()}." }
        require(missing.isEmpty()) { "$path is missing fields: ${missing.sorted().joinToString()}." }
    }

    private fun text(value: Map<String, Any?>, key: String, path: String): String =
        (value[key] as? String)?.takeIf(String::isNotBlank)
            ?: error("$path.$key must be non-blank text.")

    @Suppress("UNCHECKED_CAST")
    private fun map(value: Any?, path: String): Map<String, Any?> =
        value as? Map<String, Any?> ?: error("$path must be a map.")

    @Suppress("UNCHECKED_CAST")
    private fun objectList(value: Any?, path: String): List<Map<String, Any?>> = when (value) {
        is List<*> -> value.mapIndexed { index, item ->
            item as? Map<String, Any?> ?: error("$path[$index] must be a map.")
        }
        else -> error("$path must be a list.")
    }

    private fun stringList(value: Any?, path: String, required: Boolean = false): List<String> = when (value) {
        is List<*> -> value.mapIndexed { index, item ->
            (item as? String)?.takeIf(String::isNotBlank)
                ?: error("$path[$index] must be non-blank text.")
        }.also { list ->
            require(!required || list.isNotEmpty()) { "$path must not be empty." }
            require(list.size == list.toSet().size) { "$path must not contain duplicates." }
        }
        else -> error("$path must be a list.")
    }

    private val ROOT_KEYS = setOf("version", "bindings")
    private val RECORD_KEYS = setOf(
        "id", "class", "capability", "module", "action", "systemTypes",
        "semanticParameters", "bindingParameters", "effectPolicy",
        "evidenceReferences", "limitations"
    )
    private val SEMANTIC_KEYS = setOf("mapped", "unsupported")
}

/**
 * Distribution authority for built-in explicit capability bindings.
 *
 * Runtime binding derivation remains target-neutral and lives in the Intent
 * contract layer. This authority certifies that every built-in implementation
 * claim has one exact adapter-owned evidence record matching that derivation.
 */
class AdapterCapabilityBindingAuthority(
    private val rootDir: File = File("."),
    private val registry: ModuleRegistry = ModuleRegistry.fromDirectory(File(rootDir, "modules"))
) {
    fun analyze(document: AdapterCapabilityBindingDocument = AdapterCapabilityBindingLoader.load(rootDir)): AdapterCapabilityBindingReport {
        val findings = mutableListOf<AdapterCapabilityBindingFinding>()
        val claims = implementationClaims()
        val recordsByKey = document.records.groupBy(AdapterCapabilityBindingRecord::key)

        document.records.groupBy(AdapterCapabilityBindingRecord::id)
            .filterValues { it.size > 1 }
            .forEach { (id, records) -> finding(findings, "BINDING_ID_DUPLICATE", id, "Binding id occurs ${records.size} times.") }
        recordsByKey.filterValues { it.size > 1 }.forEach { (key, records) ->
            finding(findings, "BINDING_CLAIM_DUPLICATE", key.toString(), "Binding claim occurs ${records.size} times.")
        }

        (claims.keys - recordsByKey.keys).sortedBy { it.toString() }.forEach { key ->
            finding(findings, "BINDING_EVIDENCE_MISSING", key.toString(), "Built-in implementation claim has no adapter binding evidence.")
        }
        (recordsByKey.keys - claims.keys).sortedBy { it.toString() }.forEach { key ->
            finding(findings, "BINDING_CLAIM_UNKNOWN", key.toString(), "Adapter binding evidence has no matching built-in implementation claim.")
        }

        document.records.forEach { record -> evaluateRecord(record, claims[record.key], findings) }
        return AdapterCapabilityBindingReport(
            status = if (findings.isEmpty()) "PASS" else "FAIL",
            findings = findings,
            recordCount = document.records.size,
            implementationClaimCount = claims.size
        )
    }

    private fun implementationClaims(): Map<BindingKey, org.flowlang.modules.ModuleActionContract> = buildMap {
        registry.allModules().forEach { module ->
            module.actions.forEach { (actionName, action) ->
                action.implementedCapabilities.forEach { capabilityName ->
                    val capability = StandardCapability.valueOf(capabilityName)
                    val key = BindingKey(capability, module.name, actionName)
                    require(put(key, action) == null) { "Duplicate built-in implementation claim: $key" }
                }
            }
        }
    }

    private fun evaluateRecord(
        record: AdapterCapabilityBindingRecord,
        action: org.flowlang.modules.ModuleActionContract?,
        findings: MutableList<AdapterCapabilityBindingFinding>
    ) {
        val label = record.key.toString()
        if (record.id != label) {
            finding(findings, "BINDING_ID_MISMATCH", label, "Record id '${record.id}' must be '$label'.")
        }
        if (record.effectPolicy != IntentBindingEffectPolicy.PRESERVE_CANONICAL) {
            finding(findings, "BINDING_EFFECT_POLICY_INVALID", label, "Bindings must preserve canonical effects.")
        }
        if (record.module == "standard" && record.bindingClass != AdapterCapabilityBindingClass.SEMANTIC_FALLBACK) {
            finding(findings, "SEMANTIC_FALLBACK_MISCLASSIFIED", label, "Standard semantic actions cannot be certified as concrete implementations.")
        }
        if (record.module != "standard" && record.bindingClass != AdapterCapabilityBindingClass.CONCRETE_IMPLEMENTATION) {
            finding(findings, "CONCRETE_BINDING_MISCLASSIFIED", label, "Non-standard built-in bindings must be concrete implementations.")
        }
        if (action == null) return

        val derived = IntentBindingContractAuthority.derive(record.module, record.action, record.capability, action)
        if (record.systemTypes != derived.systemTypes) {
            finding(findings, "BINDING_SYSTEM_TYPES_MISMATCH", label, "declared=${record.systemTypes.sorted()} derived=${derived.systemTypes.sorted()}")
        }
        if (record.mappedSemanticParameters != derived.mappedSemanticParameters) {
            finding(findings, "BINDING_SEMANTIC_MAPPING_MISMATCH", label, "declared=${record.mappedSemanticParameters.sorted()} derived=${derived.mappedSemanticParameters.sorted()}")
        }
        if (record.unsupportedSemanticParameters.keys != derived.unsupportedSemanticParameters) {
            finding(findings, "BINDING_UNSUPPORTED_SEMANTICS_MISMATCH", label, "declared=${record.unsupportedSemanticParameters.keys.sorted()} derived=${derived.unsupportedSemanticParameters.sorted()}")
        }
        if (record.bindingParameters != derived.bindingOnlyParameters) {
            finding(findings, "BINDING_PARAMETERS_MISMATCH", label, "declared=${record.bindingParameters.sorted()} derived=${derived.bindingOnlyParameters.sorted()}")
        }

        val requiredCanonical = org.flowlang.standard.StandardCapabilityContracts
            .requireContract(record.capability).requiredParams.toSet() -
            org.flowlang.intent.CanonicalIntentMeaningAuthority.BINDING_METADATA_PARAMS
        val unsupportedRequired = requiredCanonical.intersect(record.unsupportedSemanticParameters.keys)
        if (unsupportedRequired.isNotEmpty()) {
            finding(findings, "REQUIRED_CANONICAL_PARAMETER_UNSUPPORTED", label, "Required canonical parameters cannot be unsupported: ${unsupportedRequired.sorted()}.")
        }
        if (action.effects.isEmpty()) {
            finding(findings, "BINDING_EFFECT_EVIDENCE_MISSING", label, "Implementation action has no concrete effect evidence.")
        }
        record.evidenceReferences.forEach { reference ->
            val filePart = reference.substringBefore('#')
            if (filePart == AdapterCapabilityBindingLoader.PATH || filePart == "targets/builtin-targets.yaml") {
                finding(findings, "BINDING_EVIDENCE_SELF_REFERENTIAL", label, "Evidence cannot cite '$filePart'.")
            } else if (!File(rootDir, filePart).isFile) {
                finding(findings, "BINDING_EVIDENCE_UNRESOLVED", label, "Evidence file does not exist: $reference")
            }
        }
    }

    private fun Effects.isEmpty(): Boolean =
        reads.isEmpty() && writes.isEmpty() && creates.isEmpty() && updates.isEmpty() &&
            deletes.isEmpty() && executes.isEmpty() && network.isEmpty() && filesystem.isEmpty()

    private fun finding(
        findings: MutableList<AdapterCapabilityBindingFinding>,
        code: String,
        binding: String,
        message: String
    ) {
        findings += AdapterCapabilityBindingFinding(code, binding, message)
    }
}
