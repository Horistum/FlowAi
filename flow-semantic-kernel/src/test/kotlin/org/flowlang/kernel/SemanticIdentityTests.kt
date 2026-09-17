package org.flowlang.kernel

import kotlin.test.*
import org.flowlang.identity.*

class SemanticIdentityTests {
    @Test fun wireRoundTripIsLosslessForSeparatorsEscapesAndUnicode() {
        val names = listOf("audit-step", "audit_step", "A", "a", "a/b", "a~1b", "~", "~0", "~/", "é", "e\u0301", "東京", "\uD83D\uDE80", " a ")
        val ids = names.map(SemanticId::of)
        assertEquals(names.size, ids.toSet().size)
        assertEquals(names.size, ids.map { it.wire }.toSet().size)
        ids.forEach { assertEquals(it, SemanticId.fromWire(it.wire)) }
        assertEquals("a~1b", SemanticId.of("a/b").wire)
        assertEquals("a~01b", SemanticId.of("a~1b").wire)
    }
    @Test fun everySingleNonSurrogateCodeUnitRoundTripsWithoutNormalization() {
        for (code in 0..65535) {
            val c = code.toChar()
            if (Character.isSurrogate(c)) continue
            val raw = "left${c}right"
            assertEquals(raw, SemanticId.fromWire(SemanticId.of(raw).wire).authored, "U+${code.toString(16)}")
        }
    }
    @Test fun malformedWireDoesNotBecomeAnAlias() {
        listOf("~", "a~2b", "a~xb", "a/b").forEach { wire ->
            assertFailsWith<IllegalArgumentException>(wire) { SemanticId.fromWire(wire) }
        }
        assertNotEquals(SemanticId.fromWire("a~1b"), SemanticId.fromWire("a~01b"))
    }
    @Test fun blankOrIllFormedUnicodeIdentitiesAreRejected() {
        listOf("", " ", "\n\t", "\uD800", "\uDC00", "a\uD800b").forEach {
            assertFailsWith<IllegalArgumentException> { SemanticId.of(it) }
        }
        assertEquals("", IdentityWireSegment.decode(IdentityWireSegment.encode("")))
    }
    @Test fun derivedCollisionsAreOrderIndependentAndNeverReceiveSuffixes() {
        val ids = listOf("audit-step", "audit_step").map(SemanticId::of)
        fun failure(values: List<SemanticId>) = assertFailsWith<IdentityCollisionException> {
            DerivedIdentityNames.create("result", values) { it.authored.replace('-', '_') }
        }
        assertEquals("DERIVED_ID_COLLISION", failure(ids).code)
        assertEquals(failure(ids).message, failure(ids.reversed()).message)
    }
    @Test fun repeatedDeclarationsAreNotSilentlyCoalesced() {
        val id = SemanticId.of("same")
        assertEquals("DUPLICATE_SEMANTIC_ID", assertFailsWith<IdentityCollisionException> {
            DerivedIdentityNames.create("result", listOf(id, id)) { it.authored }
        }.code)
    }
    @Test fun aCapturedNameMapDoesNotBorrowCallerMutationOrInventUnknownNames() {
        val ids = mutableListOf(SemanticId.of("first"))
        val names = DerivedIdentityNames.create("result", ids) { "result_${it.authored}" }
        ids.clear(); ids += SemanticId.of("later")
        assertEquals("result_first", names[SemanticId.of("first")])
        assertFailsWith<NoSuchElementException> { names[SemanticId.of("later")] }
    }
}
