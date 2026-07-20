fun policySourceLocationTests() {
    secretPolicyLocationTest()
    retryPolicyLocationTest()
}

private fun secretPolicyLocationTest() {
    val source = listOf(
        "use module \"rest\" version \"1.0\"",
        "flow \"t\" {",
        "  systems {",
        "    system \"api\" {",
        "      type: rest",
        "      token: \"plain\"",
        "    }",
        "  }",
        "  steps { }",
        "}"
    ).joinToString("\n")
    val found = validateSrc(source).issues.firstOrNull { it.code == "SECRET_PLAINTEXT" }
    H.ok("loc/secret-present", found?.location != null)
    H.eq("loc/secret-line", found?.location?.line, 4)
}

private fun retryPolicyLocationTest() {
    val source = listOf(
        "use module \"shell\" version \"1.0\"",
        "flow \"t\" {",
        "  systems { system \"l\" { type: shell } }",
        "  steps {",
        "    retry { max: 0 } {",
        "      shell.run l { command: \"x\" }",
        "    }",
        "  }",
        "}"
    ).joinToString("\n")
    val found = validateSrc(source).issues.firstOrNull { it.code == "RETRY_MAX_INVALID" }
    H.ok("loc/retry-present", found?.location != null)
    H.eq("loc/retry-line", found?.location?.line, 5)
}
