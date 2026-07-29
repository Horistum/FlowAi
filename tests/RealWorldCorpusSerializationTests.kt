package org.flowlang.tests

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.flowlang.conformance.RealWorldCorpusSerialization
import org.flowlang.conformance.RealWorldEvidence

class RealWorldCorpusSerializationTests {
    @Test
    fun unknownEvidenceFieldFailsInsteadOfDisappearing() {
        val file = temporaryYaml(
            """
            kind: FlowRealWorldEvidence
            version: '0.2'
            caseId: C06
            status: ACCEPTED
            outcome: SUPPORTED_WITH_BINDING
            expectedDiagnostics: []
            sourceRevision: 0123456789012345678901234567890123456789
            validatedBy: [real-world-corpus.case.c06]
            inventedPassSwitch: true
            """.trimIndent()
        )

        val error = assertFailsWith<IllegalArgumentException> {
            RealWorldCorpusSerialization.readYaml(file, RealWorldEvidence::class.java)
        }
        assertTrue(error.message.orEmpty().contains("inventedPassSwitch"))
    }

    @Test
    fun duplicateEvidenceFieldFailsAtTheSerializationBoundary() {
        val file = temporaryYaml(
            """
            kind: FlowRealWorldEvidence
            version: '0.2'
            caseId: C06
            caseId: C02
            status: ACCEPTED
            outcome: SUPPORTED_WITH_BINDING
            expectedDiagnostics: []
            sourceRevision: 0123456789012345678901234567890123456789
            validatedBy: [real-world-corpus.case.c06]
            """.trimIndent()
        )

        assertFailsWith<IllegalArgumentException> {
            RealWorldCorpusSerialization.readYaml(file, RealWorldEvidence::class.java)
        }
    }

    private fun temporaryYaml(content: String): File = File.createTempFile("flow-real-world-corpus-", ".yaml").also {
        it.deleteOnExit()
        it.writeText(content + "\n")
    }
}
