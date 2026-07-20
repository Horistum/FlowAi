fun referenceSourceLocationTests() {
    unresolvedReferenceLocationTest()
    missingParameterLocationTest()
}

private fun unresolvedReferenceLocationTest() {
    val source = listOf(
        "use module \"shell\" version \"1.0\"",
        "flow \"t\" {",
        "  systems { system \"l\" { type: shell } }",
        "  steps {",
        "    shell.run l { command: undefinedVar }",
        "  }",
        "}"
    ).joinToString("\n")
    val found = validateSrc(source).issues.firstOrNull { it.code == "UNRESOLVED_REFERENCE" }
    H.ok("loc/unresolved-present", found?.location != null)
    H.eq("loc/unresolved-line", found?.location?.line, 5)
}

private fun missingParameterLocationTest() {
    val source = listOf(
        "use module \"shell\" version \"1.0\"",
        "flow \"t\" {",
        "  systems { system \"l\" { type: shell } }",
        "  steps {",
        "    shell.run l { }",
        "  }",
        "}"
    ).joinToString("\n")
    val found = validateSrc(source).issues.firstOrNull { it.code == "MISSING_PARAM" }
    H.ok("loc/missing-param-present", found?.location != null)
    H.eq("loc/missing-param-line", found?.location?.line, 5)
}
