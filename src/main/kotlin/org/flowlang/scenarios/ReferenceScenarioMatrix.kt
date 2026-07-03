package org.flowlang.scenarios

/**
 * v0.9.4 reference scenario matrix.
 *
 * This matrix is the target-neutral source of truth for realistic Flow automation semantics. It
 * records scenario intent, required universal capabilities, risks, safety requirements and
 * portability expectations without naming concrete renderer targets as part of the core scenario
 * model. Target adapter expectations live in ReferenceAdapterProjectionMatrix.
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
        semanticExpectation = ReferenceSemanticExpectation(
            requiredCapabilities = setOf("source.checkout", "command.run", "deployment.apply", "notification.send", "approval.require"),
            portabilityClass = ReferencePortabilityClass.UNIVERSAL_WITH_ADAPTER_REQUIREMENTS,
            notes = setOf("production deployment requires an approval boundary before target projection")
        ),
        risks = setOf(ReferenceScenarioRisk.PRODUCTION_CHANGE, ReferenceScenarioRisk.SECRET_ACCESS),
        safetyRequirements = setOf("production deployment requires explicit approval", "deployment must keep target projection reviewable"),
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
                system "repo" {
                  type: git
                  url: "https://example.invalid/app.git"
                  branch: "main"
                }
                system "local" {
                  type: shell
                }
                system "k8s" {
                  type: kubernetes
                  context: "prod"
                }
                system "mailer" {
                  type: notify
                  channel: email
                }
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
        semanticExpectation = ReferenceSemanticExpectation(
            requiredCapabilities = setOf("api.call", "data.transform", "data.validate", "data.write", "data.aggregate", "notification.send", "secret.consume"),
            portabilityClass = ReferencePortabilityClass.UNIVERSAL_WITH_ADAPTER_REQUIREMENTS,
            notes = setOf("runtime secrets stay external", "data operations remain explicit even when a target cannot materialise them natively")
        ),
        risks = setOf(ReferenceScenarioRisk.SECRET_ACCESS, ReferenceScenarioRisk.DATA_WRITE),
        safetyRequirements = setOf("runtime secrets stay external", "data operations must be explicit when not materialised by the target"),
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
                system "crm" {
                  type: rest
                  baseUrl: secret("CRM_URL")
                  token: secret("CRM_TOKEN")
                }
                system "warehouse" {
                  type: database
                  engine: postgres
                  url: secret("WAREHOUSE_URL")
                }
                system "mailer" {
                  type: notify
                  channel: email
                }
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
        semanticExpectation = ReferenceSemanticExpectation(
            requiredCapabilities = setOf("data.read", "data.write", "notification.send", "secret.consume"),
            portabilityClass = ReferencePortabilityClass.UNIVERSAL_WITH_ADAPTER_REQUIREMENTS,
            notes = setOf("database URL is represented as a secret", "migration action remains auditable")
        ),
        risks = setOf(ReferenceScenarioRisk.DATA_WRITE, ReferenceScenarioRisk.SECRET_ACCESS),
        safetyRequirements = setOf("migration records an audit row", "database URL is represented as a secret"),
        source = """
            version "1.0"
            use module "database" version "1.0"
            use module "notify" version "1.0"
            flow "database-migration" {
              systems {
                system "db" {
                  type: database
                  engine: postgres
                  url: secret("DB_URL")
                }
                system "mailer" {
                  type: notify
                  channel: email
                }
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
        semanticExpectation = ReferenceSemanticExpectation(
            requiredCapabilities = setOf("workflow.execute", "rollback.perform", "notification.send", "error.handle"),
            portabilityClass = ReferencePortabilityClass.UNIVERSAL_WITH_ADAPTER_REQUIREMENTS,
            notes = setOf("rollback is only valid as an error-handler behavior")
        ),
        risks = setOf(ReferenceScenarioRisk.PRODUCTION_CHANGE, ReferenceScenarioRisk.ROLLBACK),
        safetyRequirements = setOf("rollback is only allowed inside an error handler", "failure path remains explicit"),
        source = """
            version "1.0"
            use module "standard" version "1.0"
            use module "notify" version "1.0"
            flow "rollback-workflow" {
              systems {
                system "standard" {
                  type: standard
                }
                system "mailer" {
                  type: notify
                  channel: email
                }
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
        name = "Approved cleanup",
        kind = ReferenceScenarioKind.CLEANUP,
        semanticExpectation = ReferenceSemanticExpectation(
            requiredCapabilities = setOf("resource.delete", "approval.require"),
            portabilityClass = ReferencePortabilityClass.UNIVERSAL_WITH_ADAPTER_REQUIREMENTS,
            notes = setOf("destructive cleanup requires explicit approval regardless of target adapter")
        ),
        risks = setOf(ReferenceScenarioRisk.DESTRUCTIVE_CHANGE),
        safetyRequirements = setOf("destructive cleanup requires explicit approval"),
        source = """
            version "1.0"
            use module "kubernetes" version "1.0"
            flow "cleanup-approved" {
              systems {
                system "k8s" {
                  type: kubernetes
                  context: "maintenance"
                }
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
        semanticExpectation = ReferenceSemanticExpectation(
            requiredCapabilities = setOf("secret.rotate", "notification.send", "workflow.execute"),
            portabilityClass = ReferencePortabilityClass.ADAPTER_REQUIRED,
            notes = setOf("secret values are never embedded in Flow source", "rotation is represented as an auditable workflow intent")
        ),
        risks = setOf(ReferenceScenarioRisk.SECRET_ACCESS, ReferenceScenarioRisk.PRODUCTION_CHANGE),
        safetyRequirements = setOf("secret value is not embedded in Flow source", "rotation must stay auditable"),
        source = """
            version "1.0"
            use module "standard" version "1.0"
            use module "notify" version "1.0"
            flow "secret-rotation" {
              systems {
                system "standard" {
                  type: standard
                }
                system "mailer" {
                  type: notify
                  channel: email
                }
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
        semanticExpectation = ReferenceSemanticExpectation(
            requiredCapabilities = setOf("notification.send"),
            portabilityClass = ReferencePortabilityClass.UNIVERSAL,
            notes = setOf("notification content remains explicit")
        ),
        risks = setOf(ReferenceScenarioRisk.NOTIFICATION_ONLY),
        safetyRequirements = setOf("notification content remains explicit"),
        source = """
            version "1.0"
            use module "notify" version "1.0"
            flow "notification-workflow" {
              systems {
                system "mailer" {
                  type: notify
                  channel: email
                }
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
        semanticExpectation = ReferenceSemanticExpectation(
            requiredCapabilities = setOf("resource.delete"),
            portabilityClass = ReferencePortabilityClass.BLOCKED_BY_POLICY,
            notes = setOf("negative coverage proves destructive cleanup is rejected without approval")
        ),
        risks = setOf(ReferenceScenarioRisk.DESTRUCTIVE_CHANGE),
        safetyRequirements = setOf("negative coverage proves destructive cleanup is rejected without approval"),
        negativeCoverage = true,
        expectedDiagnosticCodes = setOf("SAFETY_REQUIRED", "APPROVAL_REQUIRED"),
        source = """
            version "1.0"
            use module "kubernetes" version "1.0"
            flow "cleanup-without-approval-negative" {
              systems {
                system "k8s" {
                  type: kubernetes
                  context: "maintenance"
                }
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
}

data class ReferenceScenario(
    val id: String,
    val name: String,
    val kind: ReferenceScenarioKind,
    val source: String,
    val semanticExpectation: ReferenceSemanticExpectation,
    val risks: Set<ReferenceScenarioRisk>,
    val safetyRequirements: Set<String>,
    val negativeCoverage: Boolean = false,
    val expectedDiagnosticCodes: Set<String> = emptySet()
)

data class ReferenceSemanticExpectation(
    val requiredCapabilities: Set<String>,
    val portabilityClass: ReferencePortabilityClass,
    val notes: Set<String> = emptySet()
)

enum class ReferencePortabilityClass {
    UNIVERSAL,
    UNIVERSAL_WITH_ADAPTER_REQUIREMENTS,
    ADAPTER_REQUIRED,
    BLOCKED_BY_POLICY
}

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
