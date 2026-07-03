package org.flowlang.scenarios

/**
 * v0.9.4 reference scenario matrix.
 *
 * The matrix is intentionally declarative. It records realistic scenario coverage, expected
 * capabilities, risks, safety requirements and target outcomes without introducing a runtime
 * executor or target-specific public Flow syntax.
 */
object ReferenceScenarioMatrix {
    fun all(): List<ReferenceScenario> = positiveScenarios() + negativeScenarios()
    fun positiveScenarios(): List<ReferenceScenario> = listOf(
        buildTestDeploy(),
        apiSync(),
        databaseMigration(),
        rollbackWorkflow(),
        cleanupWorkflow(),
        secretRotation(),
        notificationWorkflow()
    )
    fun negativeScenarios(): List<ReferenceScenario> = listOf(unsafeCleanupWithoutApproval())

    private fun buildTestDeploy() = ReferenceScenario(
        id = "build-test-deploy",
        name = "Build, test and deploy service",
        kind = ReferenceScenarioKind.BUILD_TEST_DEPLOY,
        expectedCapabilities = setOf("git.checkout", "shell.command", "kubernetes.deploy", "notify.send", "approval.required"),
        risks = setOf(ReferenceScenarioRisk.PRODUCTION_CHANGE, ReferenceScenarioRisk.SECRET_ACCESS),
        safetyRequirements = setOf("production deployment requires explicit approval", "deployment must keep target projection reviewable"),
        targetExpectations = mainTargetExpectations(),
        source = """
            version "1.0"
            use module "git" version "1.0"
            use module "shell" version "1.0"
            use module "kubernetes" version "1.0"
            use module "notify" version "1.0"
            flow "build-test-deploy" {
              input {
                environment: option ["dev", "test", "prod"] required
              }
              systems {
                system "repo" { type: git url: "https://example.invalid/app.git" branch: "main" }
                system "local" { type: shell }
                system "k8s" { type: kubernetes context: "prod" }
                system "mailer" { type: notify channel: email }
              }
              steps {
                git.checkout repo {
                  branch: "main"
                  depth: 1
                } -> checkoutResult
                shell.run local {
                  command: "./gradlew clean test"
                } -> testResult
                kubernetes.deploy k8s {
                  app: "billing-api"
                  namespace: "prod"
                  image: "registry.example.invalid/billing-api:1.0.0"
                  safety: requiresApproval
                } -> deployResult
                notify.send mailer {
                  subject: "Deployment finished"
                  body: "billing-api deployment finished"
                }
              }
            }
        """.trimIndent()
    )

    private fun apiSync() = ReferenceScenario(
        id = "api-sync",
        name = "Synchronize API data into a warehouse",
        kind = ReferenceScenarioKind.API_SYNC,
        expectedCapabilities = setOf("rest.call", "database.upsert", "data.transform", "data.validate", "data.aggregate", "notify.send"),
        risks = setOf(ReferenceScenarioRisk.SECRET_ACCESS, ReferenceScenarioRisk.DATA_WRITE),
        safetyRequirements = setOf("runtime secrets stay external", "data operations must be explicit when not materialised by the target"),
        targetExpectations = mainTargetExpectations(reviewRequired = true),
        source = """
            version "1.0"
            use module "rest" version "1.0"
            use module "database" version "1.0"
            use module "notify" version "1.0"
            flow "sync-customers-matrix" {
              input {
                environment: option ["dev", "test", "prod"] required
              }
              systems {
                system "crm" { type: rest baseUrl: secret("CRM_URL") token: secret("CRM_TOKEN") }
                system "warehouse" { type: database engine: postgres url: secret("WAREHOUSE_URL") }
                system "mailer" { type: notify channel: email }
              }
              steps {
                rest.call crm {
                  method: GET
                  path: "/customers"
                } -> crmResponse {
                  expect {
                    ok == true
                    code == 200
                    json.items not empty
                  }
                }
                transform crmResponse.json.items -> customers {
                  where item.deleted != true
                  select {
                    id: item.id
                    email: item.email
                    active: item.status == "ACTIVE"
                  }
                }
                validate customers {
                  required item.id
                  required item.email
                  item.email matches email
                }
                for customer in customers {
                  database.upsert warehouse {
                    table: "customers"
                    key: customer.id
                    values: customer
                  }
                }
                aggregate customers -> summary {
                  total: count()
                }
                notify.send mailer {
                  subject: "Customer sync finished"
                  body: "Total: ${'$'}{summary.total}"
                }
              }
            }
        """.trimIndent()
    )

    private fun databaseMigration() = ReferenceScenario(
        id = "database-migration",
        name = "Database migration with audit notification",
        kind = ReferenceScenarioKind.DATABASE_MIGRATION,
        expectedCapabilities = setOf("database.query", "database.upsert", "notify.send"),
        risks = setOf(ReferenceScenarioRisk.DATA_WRITE, ReferenceScenarioRisk.SECRET_ACCESS),
        safetyRequirements = setOf("migration records an audit row", "database URL is represented as a secret"),
        targetExpectations = mainTargetExpectations(),
        source = """
            version "1.0"
            use module "database" version "1.0"
            use module "notify" version "1.0"
            flow "database-migration" {
              systems {
                system "db" { type: database engine: postgres url: secret("DB_URL") }
                system "mailer" { type: notify channel: email }
              }
              steps {
                database.query db {
                  sql: "select 1 as ready"
                } -> readiness
                database.upsert db {
                  table: "schema_migrations"
                  key: "2026_07_03_reference_matrix"
                  values: "applied"
                } -> migrationResult
                notify.send mailer {
                  subject: "Migration finished"
                  body: "schema migration recorded"
                }
              }
            }
        """.trimIndent()
    )

    private fun rollbackWorkflow() = ReferenceScenario(
        id = "rollback-workflow",
        name = "Rollback on deployment failure",
        kind = ReferenceScenarioKind.ROLLBACK,
        expectedCapabilities = setOf("standard.execute", "standard.rollback", "notify.send", "error.handler"),
        risks = setOf(ReferenceScenarioRisk.PRODUCTION_CHANGE, ReferenceScenarioRisk.ROLLBACK),
        safetyRequirements = setOf("rollback is only allowed inside an error handler", "failure path remains explicit"),
        targetExpectations = mainTargetExpectations(reviewRequired = true),
        source = """
            version "1.0"
            use module "standard" version "1.0"
            use module "notify" version "1.0"
            flow "rollback-workflow" {
              systems {
                system "standard" { type: standard }
                system "mailer" { type: notify channel: email }
              }
              steps {
                standard.execute standard {
                  operation: "deploy"
                  flow: "prod-deploy"
                } -> deployResult
              }
              on error {
                standard.rollback standard {
                  flow: "prod-deploy"
                  reason: error.message
                }
                notify.send mailer {
                  subject: "Rollback executed"
                  body: "Deployment failed and rollback handler executed"
                }
              }
            }
        """.trimIndent()
    )

    private fun cleanupWorkflow() = ReferenceScenario(
        id = "cleanup-approved",
        name = "Approved Kubernetes cleanup",
        kind = ReferenceScenarioKind.CLEANUP,
        expectedCapabilities = setOf("kubernetes.delete", "approval.required"),
        risks = setOf(ReferenceScenarioRisk.DESTRUCTIVE_CHANGE),
        safetyRequirements = setOf("destructive cleanup requires explicit approval"),
        targetExpectations = mainTargetExpectations(),
        source = """
            version "1.0"
            use module "kubernetes" version "1.0"
            flow "cleanup-approved" {
              systems {
                system "k8s" { type: kubernetes context: "maintenance" }
              }
              steps {
                kubernetes.delete k8s {
                  resource: "job"
                  name: "old-maintenance-job"
                  namespace: "tools"
                  safety: requiresApproval
                }
              }
            }
        """.trimIndent()
    )

    private fun secretRotation() = ReferenceScenario(
        id = "secret-rotation",
        name = "Rotate application secret",
        kind = ReferenceScenarioKind.SECRET_ROTATION,
        expectedCapabilities = setOf("standard.execute", "notify.send", "secret.rotation"),
        risks = setOf(ReferenceScenarioRisk.SECRET_ACCESS, ReferenceScenarioRisk.PRODUCTION_CHANGE),
        safetyRequirements = setOf("secret value is not embedded in Flow source", "rotation must stay auditable"),
        targetExpectations = mainTargetExpectations(reviewRequired = true),
        source = """
            version "1.0"
            use module "standard" version "1.0"
            use module "notify" version "1.0"
            flow "secret-rotation" {
              systems {
                system "standard" { type: standard }
                system "mailer" { type: notify channel: email }
              }
              steps {
                standard.execute standard {
                  operation: "rotate-secret"
                  capability: "secret.rotation"
                  description: "Rotate APP_TOKEN through the approved secret backend"
                } -> rotation
                notify.send mailer {
                  subject: "Secret rotation finished"
                  body: "APP_TOKEN rotation workflow completed"
                }
              }
            }
        """.trimIndent()
    )

    private fun notificationWorkflow() = ReferenceScenario(
        id = "notification-workflow",
        name = "Send operational notification",
        kind = ReferenceScenarioKind.NOTIFICATION,
        expectedCapabilities = setOf("notify.send"),
        risks = setOf(ReferenceScenarioRisk.NOTIFICATION_ONLY),
        safetyRequirements = setOf("notification content remains explicit"),
        targetExpectations = mainTargetExpectations(),
        source = """
            version "1.0"
            use module "notify" version "1.0"
            flow "notification-workflow" {
              systems {
                system "mailer" { type: notify channel: email }
              }
              steps {
                notify.send mailer {
                  subject: "Daily automation summary"
                  body: "All scheduled automation checks completed"
                }
              }
            }
        """.trimIndent()
    )

    private fun unsafeCleanupWithoutApproval() = ReferenceScenario(
        id = "cleanup-without-approval-negative",
        name = "Unsafe cleanup without approval",
        kind = ReferenceScenarioKind.CLEANUP,
        expectedCapabilities = setOf("kubernetes.delete"),
        risks = setOf(ReferenceScenarioRisk.DESTRUCTIVE_CHANGE),
        safetyRequirements = setOf("negative coverage proves destructive cleanup is rejected without approval"),
        targetExpectations = mapOf(
            "jenkins" to ReferenceTargetExpectation(ReferenceTargetOutcome.BLOCKED, "destructive cleanup lacks approval"),
            "github-actions" to ReferenceTargetExpectation(ReferenceTargetOutcome.BLOCKED, "destructive cleanup lacks approval"),
            "tekton" to ReferenceTargetExpectation(ReferenceTargetOutcome.BLOCKED, "destructive cleanup lacks approval")
        ),
        negativeCoverage = true,
        expectedDiagnosticCodes = setOf("APPROVAL_REQUIRED"),
        source = """
            version "1.0"
            use module "kubernetes" version "1.0"
            flow "cleanup-without-approval-negative" {
              systems {
                system "k8s" { type: kubernetes context: "maintenance" }
              }
              steps {
                kubernetes.delete k8s {
                  resource: "job"
                  name: "old-maintenance-job"
                  namespace: "tools"
                }
              }
            }
        """.trimIndent()
    )

    private fun mainTargetExpectations(reviewRequired: Boolean = false): Map<String, ReferenceTargetExpectation> = mapOf(
        "jenkins" to ReferenceTargetExpectation(if (reviewRequired) ReferenceTargetOutcome.REVIEW_REQUIRED else ReferenceTargetOutcome.SUPPORTED, "Jenkins projection must remain structurally renderable."),
        "github-actions" to ReferenceTargetExpectation(if (reviewRequired) ReferenceTargetOutcome.REVIEW_REQUIRED else ReferenceTargetOutcome.SUPPORTED, "GitHub Actions projection must remain structurally renderable."),
        "tekton" to ReferenceTargetExpectation(ReferenceTargetOutcome.REVIEW_REQUIRED, "Tekton remains a partial projection and requires review.")
    )
}

data class ReferenceScenario(
    val id: String,
    val name: String,
    val kind: ReferenceScenarioKind,
    val source: String,
    val expectedCapabilities: Set<String>,
    val risks: Set<ReferenceScenarioRisk>,
    val safetyRequirements: Set<String>,
    val targetExpectations: Map<String, ReferenceTargetExpectation>,
    val negativeCoverage: Boolean = false,
    val expectedDiagnosticCodes: Set<String> = emptySet()
)

enum class ReferenceScenarioKind {
    BUILD_TEST_DEPLOY,
    API_SYNC,
    DATABASE_MIGRATION,
    ROLLBACK,
    CLEANUP,
    SECRET_ROTATION,
    NOTIFICATION
}

enum class ReferenceScenarioRisk {
    PRODUCTION_CHANGE,
    DESTRUCTIVE_CHANGE,
    DATA_WRITE,
    SECRET_ACCESS,
    ROLLBACK,
    NOTIFICATION_ONLY
}

enum class ReferenceTargetOutcome {
    SUPPORTED,
    REVIEW_REQUIRED,
    BLOCKED
}

data class ReferenceTargetExpectation(
    val outcome: ReferenceTargetOutcome,
    val rationale: String
)
