package org.flowlang.intent

/** Small built-in examples used by documentation and future tests. */
object IntentExamples {
    val buildTestDeploy = IntentDocument(
        name = "build-test-deploy",
        description = "High-level deployment intent. This is the layer AI should produce first.",
        inputs = listOf(
            IntentInput("environment", "option[dev,test,prod]", required = true),
            IntentInput("version", "text", required = true)
        ),
        systems = listOf(
            IntentSystem("source", "git", "Application source repository"),
            IntentSystem("registry", "dockerRegistry", "Image registry"),
            IntentSystem("cluster", "kubernetes", "Declared deployment context; generic deploy remains target-neutral"),
            IntentSystem("standard", "standard", "Semantic build, test, deploy and verify operations")
        ),
        workflows = listOf(
            IntentWorkflow(
                name = "application-lifecycle",
                kind = IntentWorkflowKind.DEPLOY,
                steps = listOf(
                    IntentStep("checkout", StandardCapability.CHECKOUT),
                    IntentStep("test", StandardCapability.TEST, requires = listOf("checkout")),
                    IntentStep("build-image", StandardCapability.BUILD_IMAGE, requires = listOf("test")),
                    IntentStep("approve-prod", StandardCapability.APPROVE, requires = listOf("build-image")),
                    IntentStep("deploy", StandardCapability.DEPLOY, requires = listOf("approve-prod")),
                    IntentStep("verify", StandardCapability.VERIFY, requires = listOf("deploy"))
                )
            )
        ),
        policies = listOf(
            IntentPolicy(
                name = "production-approval",
                type = IntentPolicyType.APPROVAL,
                condition = "environment == 'prod'",
                message = "Production deployment requires approval."
            )
        ),
        failure = IntentFailurePolicy(notify = true, rollback = true, stopOnError = true)
    )
}
