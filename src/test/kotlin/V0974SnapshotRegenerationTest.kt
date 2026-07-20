import java.io.File
import kotlin.test.Test
import org.flowlang.conformance.ReferenceSnapshotBundleGenerator

class V0974SnapshotRegenerationTest {
    @Test
    fun regenerateCanonicalSnapshots() {
        val generator = ReferenceSnapshotBundleGenerator()
        generator.generate(
            intentFile = File("examples/intent/checkout-build-image.intent.yaml"),
            outputDir = File("conformance/snapshots/checkout-build-image"),
            scenarioId = "checkout-build-image",
            targetIds = setOf("jenkins")
        )
        generator.generate(
            intentFile = File("examples/intent/build-test-deploy.intent.yaml"),
            outputDir = File("conformance/snapshots/build-test-deploy"),
            scenarioId = "build-test-deploy",
            targetIds = setOf("github-actions", "jenkins", "tekton")
        )
    }
}
