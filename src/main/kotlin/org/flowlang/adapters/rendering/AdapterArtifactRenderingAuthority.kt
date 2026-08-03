package org.flowlang.adapters.rendering

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import java.security.MessageDigest
import org.flowlang.generators.manifest.TargetManifest
import org.flowlang.generators.manifest.TargetProjectionRegistry
import org.flowlang.generators.manifest.TargetRenderFinding
import org.flowlang.generators.manifest.TargetRenderMode
import org.flowlang.generators.manifest.TargetRenderPolicy
import org.flowlang.generators.manifest.TargetRenderReadiness
import org.flowlang.generators.manifest.TargetReviewArtifactRenderer
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.targets.builtin.BuiltInTargetProjections

/**
 * The single application-edge authority for adapter artifacts.
 *
 * Concrete provider file names are reserved for executable target syntax. A
 * REVIEW_ONLY manifest is rendered as a dedicated Flow review document, never as
 * a Jenkinsfile, workflow or Pipeline that merely happens to contain YAML prose.
 */
class AdapterArtifactRenderingAuthority(
    private val rootDir: File = File("."),
    private val projections: TargetProjectionRegistry = BuiltInTargetProjections.registry,
    private val documentOverride: AdapterArtifactRenderingDocument? = null
) {
    private val document: AdapterArtifactRenderingDocument by lazy {
        documentOverride ?: AdapterArtifactRenderingEvidenceLoader.load(rootDir)
    }
    private val integrity = AdapterArtifactRenderingEvidenceIntegrityAuthority(rootDir, projections)

    private val certifiedRecords: Map<String, AdapterArtifactRenderingRecord> by lazy {
        val report = integrity.analyze(document, requireCompletePortfolio = documentOverride == null)
        check(report.status == "PASS") {
            "Adapter artifact rendering evidence is invalid: " + report.findings.joinToString(" | ") {
                "${it.code}:${it.target}:${it.message}"
            }
        }
        document.targets.associateBy { it.target }
    }

    fun analyze(
        input: AdapterArtifactRenderingDocument = document
    ): AdapterArtifactRenderingEvidenceReport = integrity.analyze(
        input,
        requireCompletePortfolio = documentOverride == null
    )

    fun render(manifest: TargetManifest): AdapterArtifactRenderingBundle {
        val record = certifiedRecords[manifest.target]
            ?: throw AdapterArtifactRenderingBlockedException(
                manifest.target,
                listOf("No certified adapter artifact rendering record exists.")
            )
        val initialReadiness = TargetRenderPolicy.evaluate(manifest)
        if (initialReadiness.mode == TargetRenderMode.FAIL_FAST) {
            throw AdapterArtifactRenderingBlockedException(
                manifest.target,
                initialReadiness.findings.map { "${it.nodeId}:${it.status}:${it.reason}" }
            )
        }

        val executable = initialReadiness.mode == TargetRenderMode.EXECUTABLE &&
            record.status == AdapterArtifactRenderingClaimStatus.SUPPORTED
        val effectiveReadiness = if (executable) {
            initialReadiness
        } else {
            reviewReadiness(initialReadiness, record)
        }
        val format: AdapterArtifactFormat
        val kind: AdapterRenderedArtifactKind
        val content: String
        if (executable) {
            val executableFormat = record.executable
                ?: error("Certified executable renderer '${record.target}' has no artifact format.")
            val provider = projections.requireProvider(record.target)
            require(provider.artifactFileName == executableFormat.fileName) {
                "Certified renderer '${record.target}' changed artifact identity from '${executableFormat.fileName}' to '${provider.artifactFileName}'."
            }
            format = executableFormat
            kind = AdapterRenderedArtifactKind.EXECUTABLE_TARGET
            content = provider.render(manifest)
        } else {
            format = record.review
            kind = AdapterRenderedArtifactKind.REVIEW_EVIDENCE
            content = TargetReviewArtifactRenderer.render(manifest, effectiveReadiness)
        }
        require(content.isNotBlank()) { "Adapter renderer '${record.target}' produced an empty artifact." }

        val artifactHash = AdapterArtifactRenderingFingerprint.sha256(content.toByteArray(Charsets.UTF_8))
        val manifestHash = AdapterArtifactRenderingFingerprint.manifest(manifest)
        val evidence = AdapterArtifactEvidenceInventory.from(manifest, effectiveReadiness, record)
        val artifact = AdapterRenderedArtifact(
            target = manifest.target,
            flowName = manifest.flowName,
            kind = kind,
            fileName = format.fileName,
            mediaType = format.mediaType,
            content = content,
            sha256 = artifactHash
        )
        val receipt = AdapterArtifactEvidenceReceipt(
            target = manifest.target,
            flowName = manifest.flowName,
            kind = kind,
            renderMode = effectiveReadiness.mode,
            artifactFileName = format.fileName,
            mediaType = format.mediaType,
            artifactSha256 = artifactHash,
            manifestSha256 = manifestHash,
            standardVersion = manifest.standardVersion,
            manifestVersion = manifest.manifestVersion,
            evidence = evidence
        )
        return AdapterArtifactRenderingIntegrityAuthority.requireValid(
            AdapterArtifactRenderingBundle(
                artifact = artifact,
                evidenceFileName = "target-artifact-evidence.json",
                receipt = receipt
            ),
            manifest = manifest,
            readiness = effectiveReadiness,
            record = record
        )
    }

    private fun reviewReadiness(
        initial: TargetRenderReadiness,
        record: AdapterArtifactRenderingRecord
    ): TargetRenderReadiness {
        val certificationFinding = when (record.status) {
            AdapterArtifactRenderingClaimStatus.SUPPORTED -> null
            AdapterArtifactRenderingClaimStatus.REVIEW_ONLY -> TargetRenderFinding(
                nodeId = "renderer",
                status = "ADAPTER_RENDERING_REVIEW_ONLY",
                reason = "Adapter renderer '${record.target}' is certified only for review evidence."
            )
            AdapterArtifactRenderingClaimStatus.UNKNOWN -> TargetRenderFinding(
                nodeId = "renderer",
                status = "ADAPTER_RENDERING_UNKNOWN",
                reason = "Adapter renderer '${record.target}' has no executable rendering evidence."
            )
        }
        return TargetRenderReadiness(
            target = initial.target,
            mode = TargetRenderMode.REVIEW_ONLY,
            findings = (initial.findings + listOfNotNull(certificationFinding)).distinct()
                .ifEmpty {
                    listOf(TargetRenderFinding(
                        nodeId = "renderer",
                        status = "ADAPTER_RENDERING_NOT_EXECUTABLE",
                        reason = "Executable target syntax is not authorized by adapter rendering evidence."
                    ))
                }
        )
    }
}

internal object AdapterArtifactRenderingIntegrityAuthority {
    fun requireValid(
        bundle: AdapterArtifactRenderingBundle,
        manifest: TargetManifest,
        readiness: TargetRenderReadiness,
        record: AdapterArtifactRenderingRecord
    ): AdapterArtifactRenderingBundle {
        val artifact = bundle.artifact
        val receipt = bundle.receipt
        require(bundle.evidenceFileName == "target-artifact-evidence.json") {
            "Adapter rendering receipt must use the canonical target-artifact-evidence.json identity."
        }
        require(artifact.target == manifest.target && artifact.flowName == manifest.flowName) {
            "Rendered artifact identity does not match its source manifest."
        }
        require(receipt.target == manifest.target && receipt.flowName == manifest.flowName) {
            "Rendering receipt identity does not match its source manifest."
        }
        require(receipt.kind == artifact.kind && receipt.artifactFileName == artifact.fileName) {
            "Rendering receipt does not describe the produced artifact."
        }
        require(receipt.mediaType == artifact.mediaType) {
            "Rendering receipt media type does not match the produced artifact."
        }
        require(receipt.renderMode == readiness.mode) {
            "Rendering receipt mode does not match evaluated readiness."
        }
        require(receipt.artifactSha256 == artifact.sha256) {
            "Rendering receipt artifact digest does not match the produced artifact."
        }
        require(artifact.sha256 == AdapterArtifactRenderingFingerprint.sha256(artifact.content.toByteArray(Charsets.UTF_8))) {
            "Rendered artifact content does not match its digest."
        }
        require(receipt.manifestSha256 == AdapterArtifactRenderingFingerprint.manifest(manifest)) {
            "Rendering receipt does not bind the exact source manifest."
        }
        val expectedEvidence = AdapterArtifactEvidenceInventory.from(manifest, readiness, record)
        require(receipt.evidence == expectedEvidence) {
            "Rendering receipt does not preserve the complete manifest evidence inventory."
        }
        require(receipt.evidence.map { it.id }.distinct().size == receipt.evidence.size) {
            "Rendering receipt contains duplicate semantic evidence identities."
        }
        when (artifact.kind) {
            AdapterRenderedArtifactKind.EXECUTABLE_TARGET -> {
                require(readiness.mode == TargetRenderMode.EXECUTABLE) {
                    "Executable target artifact requires EXECUTABLE render readiness."
                }
                require(record.status == AdapterArtifactRenderingClaimStatus.SUPPORTED) {
                    "Executable target artifact requires SUPPORTED adapter renderer evidence."
                }
                require(record.executable?.fileName == artifact.fileName) {
                    "Executable target artifact does not use the certified provider file identity."
                }
            }
            AdapterRenderedArtifactKind.REVIEW_EVIDENCE -> {
                require(readiness.mode == TargetRenderMode.REVIEW_ONLY) {
                    "Review evidence requires REVIEW_ONLY render readiness."
                }
                require(artifact.fileName == record.review.fileName) {
                    "Review evidence does not use its dedicated certified file identity."
                }
                require(artifact.fileName != record.executable?.fileName) {
                    "Review evidence cannot impersonate executable target syntax."
                }
                require("kind: TargetProjectionReview" in artifact.content && "executable: false" in artifact.content) {
                    "Review artifact must identify itself as non-executable TargetProjectionReview evidence."
                }
            }
        }
        return bundle
    }
}

internal object AdapterArtifactRenderingFingerprint {
    private val mapper = ObjectMapper()
        .registerKotlinModule()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .disable(SerializationFeature.INDENT_OUTPUT)

    fun manifest(manifest: TargetManifest): String = sha256(mapper.writeValueAsBytes(manifest))

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

internal object AdapterArtifactEvidenceInventory {
    fun from(
        manifest: TargetManifest,
        readiness: TargetRenderReadiness,
        record: AdapterArtifactRenderingRecord
    ): List<AdapterArtifactSemanticEvidence> {
        val candidates = buildList {
            add(candidate("manifest", "identity", "PRESERVED", "target-manifest.json", listOf(
                manifest.standardVersion,
                manifest.manifestVersion,
                manifest.target,
                manifest.flowName
            ).joinToString("|")))
            add(candidate(
                "compatibility",
                "effective",
                manifest.compatibility.status.name,
                "target-manifest.json#compatibility",
                "status=${manifest.compatibility.status}; capability=${manifest.compatibility.capabilityStatus}; executable=${manifest.compatibility.executable}; errors=${manifest.compatibility.hasErrors}"
            ))
            manifest.compatibility.issues.forEachIndexed { index, issue ->
                add(candidate(
                    "compatibility",
                    "issue-$index",
                    issue.level.name,
                    "target-manifest.json#compatibility.issues[$index]",
                    "${issue.target}|${issue.nodeId}|${issue.feature}|${issue.message}"
                ))
            }
            manifest.mappingNotes.forEachIndexed { index, note ->
                add(candidate(
                    "mapping",
                    "note-$index",
                    note.level,
                    "target-manifest.json#mappingNotes[$index]",
                    "${note.target}|${note.nodeId}|${note.feature}|${note.message}"
                ))
            }
            manifest.inputs.forEachIndexed { index, input ->
                add(candidate(
                    "input",
                    "input-$index",
                    "PRESERVED",
                    "target-manifest.json#inputs[$index]",
                    "${input.name}|${input.type}|${input.required}|${input.defaultValue}|${input.choices.joinToString()}"
                ))
            }
            manifest.triggers.forEachIndexed { index, trigger ->
                add(candidate(
                    "trigger",
                    "trigger-$index",
                    "PRESERVED",
                    "target-manifest.json#triggers[$index]",
                    "${trigger.id}|${trigger.type}|${trigger.scheduleKind}|${trigger.scheduleExpression}|${trigger.timezone}|${trigger.event}|${trigger.params.toSortedMap()}"
                ))
            }
            manifest.metadata.toSortedMap().forEach { (key, value) ->
                add(candidate(
                    "manifest-metadata",
                    key,
                    "PRESERVED",
                    "target-manifest.json#metadata.$key",
                    value
                ))
            }
            manifest.jobs.forEachIndexed { jobIndex, job ->
                add(candidate(
                    "job",
                    "job-$jobIndex",
                    "PRESERVED",
                    "target-manifest.json#jobs[$jobIndex]",
                    "${job.id}|${job.name}|${job.dependsOn.joinToString()}|${job.metadata.toSortedMap()}"
                ))
                job.steps.forEachIndexed { stepIndex, step ->
                    addAll(stepEvidence(step, "jobs[$jobIndex].steps[$stepIndex]"))
                }
            }
            readiness.findings.forEachIndexed { index, finding ->
                add(candidate(
                    "render-readiness",
                    "finding-$index",
                    finding.status,
                    "target-render-readiness.json#findings[$index]",
                    "${finding.nodeId}|${finding.reason}"
                ))
            }
            record.evidenceReferences.sorted().forEachIndexed { index, reference ->
                add(candidate(
                    "renderer-certification",
                    "evidence-$index",
                    record.status.name,
                    reference,
                    "Certified repository evidence for ${record.target}."
                ))
            }
            record.limitations.sorted().forEachIndexed { index, limitation ->
                add(candidate(
                    "renderer-limitation",
                    "limitation-$index",
                    record.status.name,
                    AdapterArtifactRenderingEvidenceLoader.PATH,
                    limitation
                ))
            }
        }
        return candidates.distinctBy { listOf(it.category, it.status, it.reference, it.detail) }
            .map { evidence ->
                evidence.copy(id = "${evidence.category}.${AdapterArtifactRenderingFingerprint.sha256(
                    listOf(evidence.category, evidence.status, evidence.reference, evidence.detail)
                        .joinToString("\u0000").toByteArray(Charsets.UTF_8)
                ).take(16)}")
            }
            .sortedBy { it.id }
    }

    private fun stepEvidence(step: TargetStep, path: String): List<AdapterArtifactSemanticEvidence> = buildList {
        add(candidate(
            "materialization",
            step.id,
            step.materialization.status.name,
            "target-manifest.json#$path",
            "id=${step.id}|name=${step.name}|type=${step.type}|module=${step.module}|action=${step.action}|target=${step.target}|dependsOn=${step.dependsOn.joinToString()}|capability=${step.materialization.capability}|reason=${step.materialization.reason}|requirements=${step.materialization.requirements.toSortedMap()}|metadata=${step.materialization.metadata.toSortedMap()}|params=${step.params.toSortedMap()}|stepMetadata=${step.metadata.toSortedMap()}"
        ))
        step.mappingNotes.forEachIndexed { index, note ->
            add(candidate(
                "mapping",
                "${step.id}-$index",
                note.level,
                "target-manifest.json#$path.mappingNotes[$index]",
                "${note.target}|${note.nodeId}|${note.feature}|${note.message}"
            ))
        }
        step.rendererPayload?.let { payload ->
            add(candidate(
                "renderer-payload",
                step.id,
                "PRESERVED",
                payload.evidenceReference,
                "${payload.kind}|${payload.target}|${payload.reference}"
            ))
            payload.bindings.toSortedMap().forEach { (name, binding) ->
                add(candidate(
                    "renderer-binding",
                    "${step.id}-$name",
                    binding.resolutionStatus?.name ?: "UNKNOWN",
                    payload.evidenceReference,
                    "name=$name|kind=${binding.kind}|value=${binding.value}|sourceName=${binding.name}|taskId=${binding.taskId}|output=${binding.output}|field=${binding.field}|target=${binding.target}|expression=${binding.expression}|default=${binding.defaultValue}|reason=${binding.reason}"
                ))
            }
        }
        step.children.forEachIndexed { index, child ->
            addAll(stepEvidence(child, "$path.children[$index]"))
        }
    }

    private fun candidate(
        category: String,
        provisionalId: String,
        status: String,
        reference: String,
        detail: String
    ) = AdapterArtifactSemanticEvidence(
        id = provisionalId,
        category = category,
        status = status,
        reference = reference,
        detail = detail
    )
}
