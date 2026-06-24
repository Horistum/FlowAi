import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * JUnit bridge for the core scenario-based Flow specification suite.
 *
 * This is not advisory coverage. It exercises parser, validator, planner,
 * AST handling, expression engine and legacy generator compatibility scenarios.
 * Conformance bridges are covered by dedicated blocking JUnit tests, so failures
 * are reported at the correct layer instead of being hidden inside one giant wrapper.
 */
class FlowSpecJUnitTest {
    @Test
    fun runFlowSpecificationScenarios() {
        H.reset()
        lexerTests()
        expressionTests()
        templateTests()
        actionTests()
        controlFlowTests()
        dataStatementTests()
        simpleStatementTests()
        documentTests()
        validatorTests()
        plannerTests()
        endToEndTests()
        roundTripTests()
        miniYamlTests()
        moduleLoaderTests()
        descriptorRegistryParityTests()
        sourceLocationTests()
        jenkinsGeneratorTests()
        stressTests()

        val ok = H.report()
        assertTrue(ok, "Core Flow language specification scenarios failed: " + H.fails.take(120).joinToString(" | "))
    }
}
