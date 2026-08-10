import com.fasterxml.jackson.databind.JsonMappingException
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flowlang.cli.Json
import org.flowlang.controls.ControlRequirement
import org.flowlang.controls.ControlRequirementKind
import org.flowlang.controls.ControlRequirementScope
import org.flowlang.controls.ControlRequirementScopeKind
import org.flowlang.controls.ControlRequirementSource
import org.flowlang.standard.FlowStandardVersions

class ExecutionPlanControlScopeContractTests {
    @Test
    fun serializedControlScopesPreserveOnlyTheirOwnedIdentity() {
        fun serialize(scope: ControlRequirementScope) = Json.mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
            ControlRequirement(
                id = "control.test",
                kind = ControlRequirementKind.BACKUP,
                subject = "test",
                source = ControlRequirementSource.CANONICAL_CAPABILITY,
                scope = scope
            )
        ).path("scope")

        val intent = serialize(ControlRequirementScope.INTENT)
        assertEquals("INTENT", intent.path("kind").asText())
        assertEquals(setOf("kind"), intent.fieldNames().asSequence().toSet())

        val operation = serialize(ControlRequirementScope.operation("migration", "migrate"))
        assertEquals("OPERATION", operation.path("kind").asText())
        assertEquals("migration", operation.path("workflow").asText())
        assertEquals("migrate", operation.path("subjectId").asText())
        assertEquals(setOf("kind", "workflow", "subjectId"), operation.fieldNames().asSequence().toSet())

        val planNode = serialize(ControlRequirementScope.planNode("delete-1"))
        assertEquals("PLAN_NODE", planNode.path("kind").asText())
        assertEquals("delete-1", planNode.path("subjectId").asText())
        assertEquals(setOf("kind", "subjectId"), planNode.fieldNames().asSequence().toSet())
    }

    @Test
    fun malformedScopeCombinationsFailAtTheTypedBoundary() {
        assertFailsWith<IllegalArgumentException> {
            ControlRequirementScope(ControlRequirementScopeKind.INTENT, workflow = "unexpected")
        }
        assertFailsWith<IllegalArgumentException> {
            ControlRequirementScope(ControlRequirementScopeKind.OPERATION, workflow = "migration")
        }
        assertFailsWith<IllegalArgumentException> {
            ControlRequirementScope(ControlRequirementScopeKind.PLAN_NODE, workflow = "migration", subjectId = "task")
        }
    }

    @Test
    fun missingScopeCannotSilentlyDeserializeAsIntentScope() {
        val legacyRequirement = """
            {
              "id": "control.backup.database-migrate",
              "kind": "BACKUP",
              "subject": "DATABASE_MIGRATE",
              "source": "CANONICAL_CAPABILITY"
            }
        """.trimIndent()

        assertFailsWith<JsonMappingException> {
            Json.mapper.readValue(legacyRequirement, ControlRequirement::class.java)
        }
    }

    @Test
    fun astAndExecutionPlan21SchemasRequireStrictControlScope() {
        val ast = Json.mapper.readTree(File("schemas/ast.schema.json"))
        val plan = Json.mapper.readTree(File("schemas/execution-plan.schema.json"))

        assertEquals("2.1", FlowStandardVersions.AST_VERSION)
        assertEquals("2.1", ast.path("properties").path("astVersion").path("const").asText())
        assertScopeContract(ast)

        assertEquals("2.1", FlowStandardVersions.EXECUTION_PLAN_VERSION)
        assertEquals("2.1", plan.path("properties").path("planVersion").path("const").asText())
        assertScopeContract(plan)
    }

    private fun assertScopeContract(schema: com.fasterxml.jackson.databind.JsonNode) {
        val controlRequirement = schema.path("\$defs").path("controlRequirement")
        val required = controlRequirement.path("required").map { it.asText() }.toSet()
        assertTrue("scope" in required)
        assertFalse(controlRequirement.path("additionalProperties").asBoolean())
        assertEquals(
            "#/\$defs/controlRequirementScope",
            controlRequirement.path("properties").path("scope").path("\$ref").asText()
        )

        val variants = schema.path("\$defs").path("controlRequirementScope").path("oneOf")
        assertEquals(3, variants.size())
        val byKind = variants.associateBy { it.path("properties").path("kind").path("const").asText() }

        assertEquals(setOf("kind"), byKind.getValue("INTENT").path("required").map { it.asText() }.toSet())
        assertEquals(setOf("kind", "workflow", "subjectId"), byKind.getValue("OPERATION").path("required").map { it.asText() }.toSet())
        assertEquals(setOf("kind", "subjectId"), byKind.getValue("PLAN_NODE").path("required").map { it.asText() }.toSet())
        assertTrue(byKind.values.all { !it.path("additionalProperties").asBoolean() })
    }
}
