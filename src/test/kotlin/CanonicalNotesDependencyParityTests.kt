import kotlin.test.Test
import kotlin.test.assertEquals
import org.flowlang.notes.CanonicalNotesPackageLoader

class CanonicalNotesDependencyParityTests {
    @Test
    fun canonicalNotesPreserveTheDeclaredCoreDependencyChain() {
        val packages = CanonicalNotesPackageLoader.load().associateBy { it.packageId }

        assertEquals(
            setOf("flow.domain.core"),
            packages.getValue("flow.capability.core").dependencies.map { it.packageId }.toSet()
        )
        assertEquals(
            setOf("flow.capability.core"),
            packages.getValue("flow.safety.core").dependencies.map { it.packageId }.toSet()
        )
        assertEquals(
            setOf("flow.safety.core"),
            packages.getValue("flow.runtime.core").dependencies.map { it.packageId }.toSet()
        )
        assertEquals(
            setOf("flow.runtime.core"),
            packages.getValue("flow.conformance.core").dependencies.map { it.packageId }.toSet()
        )
    }
}
