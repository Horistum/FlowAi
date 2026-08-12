import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.adapters.binding.AdapterCapabilityBindingLoader
import org.flowlang.adapters.binding.AdapterCapabilityBindingMigrationAuthority

class AdapterCapabilityBindingMigrationAuthorityTests {
    @Test
    fun repositoryMigrationMovesOnlyDockerfileToBindingConfiguration() {
        val report = AdapterCapabilityBindingMigrationAuthority().analyze()

        assertEquals("PASS", report.status, report.findings.joinToString(" | "))
        assertEquals("1.0", report.frozenVersion)
        assertEquals("1.1", report.liveVersion)

        val frozen = AdapterCapabilityBindingLoader.loadFrozenC04()
            .records.single { it.id == "docker.build#BUILD_IMAGE" }
        val live = AdapterCapabilityBindingLoader.load()
            .records.single { it.id == "docker.build#BUILD_IMAGE" }
        assertTrue("dockerfile" in frozen.mappedSemanticParameters)
        assertTrue("dockerfile" !in live.mappedSemanticParameters)
        assertTrue("dockerfile" in live.bindingParameters)
    }

    @Test
    fun anyAdditionalLiveBindingDeltaFailsClosed() = withBindingFixture { root ->
        val live = File(root, AdapterCapabilityBindingLoader.PATH)
        live.writeText(live.readText().replaceFirst("mapped: [image, path, push]", "mapped: [image, path]"))

        val report = AdapterCapabilityBindingMigrationAuthority(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any { "unauthorized migration delta" in it })
    }

    @Test
    fun frozenC04BindingDigestCannotBeRewritten() = withBindingFixture { root ->
        val frozen = File(root, AdapterCapabilityBindingLoader.C04_FROZEN_PATH)
        frozen.appendText("\n# rewritten\n")

        val report = AdapterCapabilityBindingMigrationAuthority(root).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.findings.any { "Frozen C0.4 binding digest changed" in it })
    }

    private fun withBindingFixture(block: (File) -> Unit) {
        val root = createTempDirectory("flow-si03-binding").toFile()
        File("modules").copyRecursively(File(root, "modules"), overwrite = true)
        listOf(AdapterCapabilityBindingLoader.C04_FROZEN_PATH, AdapterCapabilityBindingLoader.PATH).forEach { path ->
            val source = File(path)
            val target = File(root, path)
            target.parentFile.mkdirs()
            source.copyTo(target, overwrite = true)
        }
        block(root)
    }
}
