fun main() {
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
    legacyProjectionBoundaryTests()
    yamlParsingTests()
    moduleLoaderTests()
    descriptorRegistryParityTests()
    sourceLocationTests()
    stressTests()
    betaConformanceTests()
    rc4SemanticGeneratorRegressionTests()
    val ok = H.report()
    if (!ok) {
        println("RESULT: FAILED")
        kotlin.system.exitProcess(1)
    } else {
        println("RESULT: ALL GREEN (${H.total} scenarios)")
    }
}
