import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * JUnit bridge for the core scenario-based Flow specification suite.
 *
 * This is not advisory coverage. It exercises parser, validator, planner,
 * AST handling and the expression engine. Target projection behavior is covered
 * by dedicated manifest, materialization and renderer-readiness tests so an
 * obsolete generator fixture cannot define active Flow semantics.
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
        yamlParsingTests()
        moduleLoaderTests()
        descriptorRegistryParityTests()
        sourceLocationTests()
        stressTests()

        val ok = H.report()
        assertTrue(ok, "Core Flow language specification scenarios failed: " + H.fails.take(120).joinToString(" | "))
    }
}
