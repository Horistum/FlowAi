import org.flowlang.frontend.FrontendCompilerComposition
import org.flowlang.parser.FlowParser
import org.flowlang.parser.ParseException
import org.flowlang.validator.FlowValidator

fun parserSourceLocationTests() {
    duplicateSystemLocationTest()
    parserExceptionLocationTest()
    exampleLocationRegressionTest()
}

private fun duplicateSystemLocationTest() {
    val source = listOf(
        "use module \"shell\" version \"1.0\"",
        "flow \"t\" {",
        "  systems {",
        "    system \"l\" { type: shell }",
        "    system \"l\" { type: shell }",
        "  }",
        "  steps { }",
        "}"
    ).joinToString("\n")
    val found = validateSrc(source).issues.firstOrNull { it.code == "DUPLICATE_SYSTEM" }
    H.ok("loc/dup-system-present", found?.location != null)
    H.eq("loc/dup-system-line", found?.location?.line, 5)
}

private fun parserExceptionLocationTest() {
    try {
        doc("flow \"t\" {\n  steps {\n    fail\n  }\n}")
        doc("flow \"t\" { steps { @bad } }")
        H.ok("loc/parse-exception", false)
    } catch (error: ParseException) {
        H.ok("loc/parse-exception", error.line >= 1 && error.column >= 1)
    }
}

private fun exampleLocationRegressionTest() {
    val report = FrontendCompilerComposition.flowValidator().validate(FlowParser().parse(exampleFile("api-sync.flow")))
    H.ok("loc/examples-no-errors", report.issues.none { it.level == "error" })
}
