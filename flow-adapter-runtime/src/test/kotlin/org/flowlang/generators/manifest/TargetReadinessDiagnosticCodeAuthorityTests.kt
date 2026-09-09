package org.flowlang.generators.manifest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TargetReadinessDiagnosticCodeAuthorityTests {
    @Test
    fun internalStatusesMapToExistingStableDiagnosticCodes() {
        val expected = mapOf(
            "NOTES_PROJECTED" to "TARGET_NOTES_PROJECTED",
            "ADAPTER_REQUIRED" to "TARGET_REQUIRES_RUNTIME",
            "DECLARATIVE_ONLY" to "TARGET_PARTIAL_FEATURE",
            "SEMANTIC_ONLY" to "TARGET_PARTIAL_FEATURE",
            "UNSUPPORTED" to "TARGET_UNSUPPORTED_FEATURE",
            "BLOCKED" to "TARGET_UNSUPPORTED_FEATURE",
            "COMPATIBILITY_UNSUPPORTED" to "TARGET_UNSUPPORTED_FEATURE",
            "COMPATIBILITY_PARTIAL" to "TARGET_PARTIAL_FEATURE",
            "COMPATIBILITY_REQUIRES_RUNTIME" to "TARGET_REQUIRES_RUNTIME",
            "CAPABILITY_UNSUPPORTED" to "TARGET_UNSUPPORTED_CAPABILITY",
            "CAPABILITY_PARTIAL" to "TARGET_PARTIAL_CAPABILITY",
            "CAPABILITY_REQUIRES_RUNTIME" to "TARGET_REQUIRES_RUNTIME",
            "TARGET_PAYLOAD_MISSING" to "TARGET_UNSUPPORTED_FEATURE",
            "TARGET_PAYLOAD_MISMATCH" to "TARGET_UNSUPPORTED_FEATURE",
            "TARGET_PAYLOAD_KIND_MISSING" to "TARGET_UNSUPPORTED_FEATURE",
            "TARGET_PAYLOAD_KIND_INVALID" to "TARGET_UNSUPPORTED_FEATURE",
            "TARGET_PAYLOAD_REFERENCE_MISSING" to "TARGET_UNSUPPORTED_FEATURE",
            "TARGET_PAYLOAD_EVIDENCE_MISSING" to "TARGET_UNSUPPORTED_FEATURE",
            "TARGET_BINDING_INVALID" to "TARGET_UNSUPPORTED_FEATURE",
            "TARGET_BINDING_UNRESOLVED" to "TARGET_TARGET_BINDING_UNRESOLVED"
        )

        expected.forEach { (status, code) ->
            assertEquals(code, TargetReadinessDiagnosticCodeAuthority.codeFor(status), status)
        }
    }

    @Test
    fun unknownInternalStatusFailsClosedInsteadOfInventingPublicCode() {
        assertFailsWith<IllegalStateException> {
            TargetReadinessDiagnosticCodeAuthority.codeFor("FUTURE_STATUS_WITHOUT_GOVERNANCE")
        }
    }
}
