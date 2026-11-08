package org.flowlang.cli.honest

import java.io.File
import org.flowlang.artifacts.ArtifactContent
import org.flowlang.artifacts.AtomicArtifactWriter
import org.flowlang.artifacts.FlowArtifactBundleReport
import org.flowlang.artifacts.PublishedArtifactReceipt
import org.flowlang.io.BoundedIo
import org.flowlang.io.InputLimits
import org.flowlang.io.IoBudget

/** Encode and bound the entire batch, then publish only verified, staged bytes. */
internal object CliArtifactOutput {
    fun write(directory: File, values: Map<String, Any>, bundle: FlowArtifactBundleReport? = null): PublishedArtifactReceipt {
        BoundedIo.requireWithin(values.size.toLong(), InputLimits.MAX_FILES, "OUTPUT_COUNT_LIMIT")
        val budget = IoBudget(InputLimits.MAX_TOTAL_OUTPUT_BYTES, code = "OUTPUT")
        val schemas = bundle?.artifacts?.associate { it.name to it.schema }.orEmpty()
        val standaloneSchemas = mapOf("standard-diagnostic-catalog.json" to "schemas/standard-diagnostic-catalog.schema.json",
            "proposal-review.json" to "schemas/proposal-review.schema.json",
            "request-fidelity-review.json" to "schemas/request-fidelity-review.schema.json",
            "operation-outcome-review.json" to "schemas/operation-outcome-review.schema.json",
            "standard-bundle-verification.json" to "schemas/standard-bundle-verification.schema.json")
        val contents = values.mapValues { (name, value) ->
            require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name) {
                "Artifact names must be plain file names."
            }
            val schema = if (name == "proposal-review.json" && value is ProposalReviewReport && value.formatVersion == 2)
                "schemas/proposal-review-v2.schema.json" else schemas[name] ?: standaloneSchemas[name].orEmpty()
            ArtifactContent.encode(name, value, schema).also { budget.add(it.bytes.size) }
        }
        return AtomicArtifactWriter().publish(directory, contents, bundle)
    }
}
