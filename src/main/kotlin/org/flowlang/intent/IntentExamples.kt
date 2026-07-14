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
            IntentSystem("cluster", "kubernetes", "Deployment target"),
            IntentSystem("standard", "standard", "Semantic build and test operations")
        ),
        workflows = listOf(
            IntentWorkflow(
                name = "application-lifecycle",
                kind = IntentWorkflowKind.DEPLOY,
                steps = listOf(
                    IntentStep("checkout", StandardCapability.CHECKOUT, uses = "git"),
                    IntentStep("test", StandardCapability.TEST, uses = "standard", requires = listOf("checkout")),
                    IntentStep("build-image", StandardCapability.BUILD_IMAGE, uses = "docker", requires = listOf("test")),
                    IntentStep("approve-prod", StandardCapability.APPROVE, requires = listOf("build-image")),
                    IntentStep("deploy", StandardCapability.DEPLOY, uses = "kubernetes", requires = listOf("approve-prod")),
                    IntentStep("verify", StandardCapability.VERIFY, uses = "kubernetes", requires = listOf("deploy"))
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
