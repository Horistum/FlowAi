package org.flowlang.targets.builtin

import groovy.lang.Binding
import groovy.lang.GroovyShell
import kotlin.test.*
import org.flowlang.ast.SecretRefNode
import org.flowlang.generators.GroovyExpr
import org.flowlang.generators.manifest.*
import org.flowlang.capabilities.CompatibilityReport
import org.flowlang.capabilities.SupportLevel
import org.flowlang.standard.FlowStandardVersions

class JenkinsSecretConditionProjectionTests {
    private fun manifest(condition: String) = TargetManifest(
        target = "jenkins", flowName = "secret-condition", standardVersion = FlowStandardVersions.FLOW_STANDARD_VERSION,
        compatibility = CompatibilityReport("jenkins", SupportLevel.SUPPORTED, emptyList()),
        jobs = listOf(TargetJob(id = "job", name = "job", steps = listOf(TargetStep(
            id = "guard", type = "condition", params = mapOf("condition" to condition))))))

    @Test fun secretExpressionUsesTheCredentialEnvironmentAndNeverTheAmbientName() {
        val expression = GroovyExpr(emptySet()).render(SecretRefNode(name = "api-token"))
        assertEquals("env.FLOW_SECRET_API_TOKEN", expression)
        assertEquals("bound", GroovyShell(Binding(mapOf("env" to mapOf(
            "FLOW_SECRET_API_TOKEN" to "bound", "api-token" to "ambient")))).evaluate(expression))
    }

    @Test fun typedTraversalRetainsNamesButIgnoresLookalikeLiteralText() {
        assertEquals(listOf("leftToken", "rightToken"), JenkinsConditionSecrets.names(manifest(
            "secret(\"leftToken\") != null and secret:rightToken == secret:leftToken")))
        assertEquals(emptyList(), JenkinsConditionSecrets.names(manifest("\"secret:fake\" == \"secret:fake\"")))
        assertEquals(listOf("nested"), JenkinsConditionSecrets.names(manifest("secret:nested in [secret:nested]")))
        assertFails { JenkinsConditionSecrets.names(manifest("secret:")) }
    }

    @Test fun conditionOnlyCredentialAliasesCannotCollide() {
        val m = manifest("secret(\"api-token\") == secret(\"api_token\")")
        assertFails { TargetRenderingIdentityChecks.requireValid(m, { it }, { it }, JenkinsConditionSecrets.names(m)) }
    }
}
