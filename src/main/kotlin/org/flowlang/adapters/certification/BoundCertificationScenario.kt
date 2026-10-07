package org.flowlang.adapters.certification

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.security.MessageDigest
import java.util.Collections
import org.flowlang.adapters.rendering.AdapterArtifactRenderingAuthority
import org.flowlang.adapters.rendering.AdapterRenderedArtifactKind
import org.flowlang.compiler.CompilationAuthorizationOrigin
import org.flowlang.generators.manifest.TargetManifestGenerationPipeline
import org.flowlang.generators.manifest.TargetNativeProjectionCatalog
import org.flowlang.generators.manifest.TargetStep
import org.flowlang.generators.manifest.TargetStructuralProjectionKind
import org.flowlang.io.InputLimits
import org.flowlang.materialization.TargetMaterializationRequest

/**
 * Compiler/rendering-bound inputs. Occurrence in a graph is not behavioral coverage.
 * Construction executes the existing authorities; neither a manifest nor a receipt
 * supplied by the candidate can substitute for them. No mutable compiler objects escape.
 */
class BoundCertificationScenario private constructor(
    val id: String,
    val target: String,
    val graphDigest: String,
    val canonicalGraph: CertificationEvidenceReference,
    val fixture: CertificationEvidenceReference,
    val artifact: CertificationEvidenceReference,
    val subjects: Set<CertificationSubject>,
    private val evidence: Map<CertificationEvidenceReference, ByteArray>
) {
    fun resolve(reference: CertificationEvidenceReference): ByteArray? = evidence[reference]?.copyOf()

    fun scenario(
        expectedObservation: CertificationEvidenceReference,
        runtimePrerequisites: List<CertificationRuntimePrerequisite>,
        negativeMutants: List<CertificationNegativeMutant>
    ): CertificationScenario = CertificationScenario(id, canonicalGraph, fixture, artifact, expectedObservation,
        Collections.unmodifiableList(runtimePrerequisites.toList()), Collections.unmodifiableList(negativeMutants.toList()))

    companion object {
        fun capture(
            id: String,
            source: ByteArray,
            request: TargetMaterializationRequest,
            pipeline: TargetManifestGenerationPipeline,
            rendering: AdapterArtifactRenderingAuthority,
            provider: TargetNativeProjectionCatalog
        ): BoundCertificationScenario {
            require(id.isNotBlank()) { "Certification scenario requires an identity." }
            require(source.size in 1..InputLimits.MAX_SOURCE_BYTES) { "Certification source exceeds its byte budget." }
            require(request.graphAuthorized) { "Certification requires a compilation-unit authorization." }
            val authorization = request.authorization.requireIntegrity()
            require(authorization.validationBinding.origin == CompilationAuthorizationOrigin.COMPILATION_UNIT)
            val sourceBytes = source.copyOf()
            require(sha256(sourceBytes) == authorization.validationBinding.sourceSha256) {
                "Certification source does not match the authorized compilation."
            }
            require(provider.target == request.target) { "Certification provider does not match the selected target." }
            val manifest = pipeline.generate(request)
            require(manifest.target == request.target)
            provider.requireManifest(manifest)
            val rendered = rendering.render(manifest).artifact
            require(rendered.target == request.target && rendered.kind == AdapterRenderedArtifactKind.EXECUTABLE_TARGET) {
                "Certification requires an executable target artifact."
            }
            // Revalidate after injected projection/rendering code has consumed the authorization.
            authorization.requireIntegrity()
            val graph = authorization.graph
            val graphBytes = mapper.writeValueAsBytes(graph)
            val artifactBytes = rendered.content.toByteArray(Charsets.UTF_8)
            require(sha256(artifactBytes) == rendered.sha256)
            val bytes = listOf(graphBytes, sourceBytes, artifactBytes)
            require(bytes.all { it.size in 1..InputLimits.MAX_ARTIFACT_BYTES } &&
                bytes.sumOf { it.size.toLong() } <= InputLimits.MAX_TOTAL_INPUT_BYTES)
            val references = listOf("graph", "source", "artifact").zip(bytes).map { (role, content) ->
                CertificationEvidenceReference("$id:$role", sha256(content), content.size)
            }
            val semantics = (graph.requiredCapabilities + graph.nodes.flatMap {
                listOfNotNull(it.semantics.capability) + it.semantics.requiredCapabilities
            }).map { CertificationSubject.Semantic(it.value) }
            val structures = graph.nodes.mapNotNull { node ->
                TargetStructuralProjectionKind.fromStepType(node.kind.name.lowercase())
                    ?.let { CertificationSubject.Structural(it) }
            }
            val nativeLeaves = provider.definitions.map { CertificationSubject.Leaf(it.kind, it.reference) }.toSet()
            fun leaves(step: TargetStep): List<CertificationSubject.Leaf> =
                listOfNotNull(step.rendererPayload?.let { CertificationSubject.Leaf(it.kind, it.reference) }
                    ?.takeIf { it in nativeLeaves }) + step.children.flatMap(::leaves)
            val subjects = semantics + structures + manifest.jobs.flatMap { it.steps }.flatMap(::leaves)
            return BoundCertificationScenario(id, request.target, authorization.graphDigest.value,
                references[0], references[1], references[2], Collections.unmodifiableSet(subjects.toSet()),
                references.zip(bytes).toMap())
        }

        // Internal evidence encoding only; graphDigest remains the compiler's semantic identity.
        private val mapper = ObjectMapper().registerKotlinModule().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
