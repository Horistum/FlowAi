import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flowlang.adapters.yaml.TargetRegistryYamlLoader
import org.flowlang.capabilities.SupportLevel
import org.flowlang.capabilities.TargetCapability
import org.flowlang.capabilities.TargetCapabilityMatrixAnalyzer
import java.io.File

class TargetCapabilityMatrixTests {
    @Test
    fun repositoryTargetRegistryPassesCapabilityMatrix() {
        val targets = TargetRegistryYamlLoader.loadDirectory(File("targets"))
        val report = TargetCapabilityMatrixAnalyzer(targets, requiredTargets = setOf("jenkins", "github-actions", "tekton")).analyze()

        assertEquals("PASS", report.status, report.issues.joinToString())
        assertTrue(report.targets.containsAll(listOf("jenkins", "github-actions", "tekton")))
        assertTrue(report.capabilityNames.containsAll(listOf("sequentialTasks", "conditions", "approvals", "secrets", "nativeRuntime")))
        assertTrue(report.entries.any { it.target == "tekton" && it.support == SupportLevel.UNSUPPORTED })
        assertTrue(targets.values.all { it.expressionSupport != null })
    }

    @Test
    fun missingRequiredTargetFailsCapabilityMatrix() {
        val targets = mapOf(
            "jenkins" to testTargetCapability(target = "jenkins", description = "Jenkins test target")
        )
        val report = TargetCapabilityMatrixAnalyzer(targets, requiredTargets = setOf("jenkins", "github-actions", "tekton")).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.issues.any { it.contains("github-actions") })
        assertTrue(report.issues.any { it.contains("tekton") })
    }

    @Test
    fun blankTargetDescriptionFailsCapabilityMatrix() {
        val targets = mapOf(
            "jenkins" to testTargetCapability(target = "jenkins", description = ""),
            "github-actions" to testTargetCapability(target = "github-actions", description = "GitHub Actions test target"),
            "tekton" to testTargetCapability(target = "tekton", description = "Tekton test target")
        )
        val report = TargetCapabilityMatrixAnalyzer(targets, requiredTargets = setOf("jenkins", "github-actions", "tekton")).analyze()

        assertEquals("FAIL", report.status)
        assertTrue(report.issues.any { it.contains("must declare a description") })
    }
}
