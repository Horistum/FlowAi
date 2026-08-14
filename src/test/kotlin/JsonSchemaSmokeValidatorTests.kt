import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.flowlang.conformance.JsonSchemaSmokeValidator

class JsonSchemaSmokeValidatorTests {
    private val mapper = ObjectMapper()

    @Test
    fun rejectsUnknownSchemaAssertionsInsteadOfSilentlyIgnoringThem() {
        val schema = mapper.readTree("""{"type":"string","maxLength":3}""")
        val value = mapper.readTree("\"toolong\"")

        assertFailsWith<IllegalArgumentException> {
            JsonSchemaSmokeValidator.validate(value, schema)
        }
    }

    @Test
    fun enforcesAdditionalPropertiesAndPropertyNames() {
        val schema = mapper.readTree(
            """
            {
              "type":"object",
              "properties":{"known":{"type":"string"}},
              "propertyNames":{"pattern":"^[a-z]+$"},
              "additionalProperties":false
            }
            """.trimIndent()
        )

        JsonSchemaSmokeValidator.validate(mapper.readTree("""{"known":"ok"}"""), schema)
        assertFailsWith<IllegalArgumentException> {
            JsonSchemaSmokeValidator.validate(mapper.readTree("""{"Unknown":"x"}"""), schema)
        }
        assertFailsWith<IllegalArgumentException> {
            JsonSchemaSmokeValidator.validate(mapper.readTree("""{"extra":"x"}"""), schema)
        }
    }

    @Test
    fun enforcesConditionalAndCombinatorSemantics() {
        val schema = mapper.readTree(
            """
            {
              "type":"object",
              "required":["kind"],
              "properties":{
                "kind":{"enum":["native","review"]},
                "payload":{"type":"string"}
              },
              "if":{"properties":{"kind":{"const":"native"}},"required":["kind"]},
              "then":{"required":["payload"]},
              "else":{"not":{"required":["payload"]}},
              "additionalProperties":false
            }
            """.trimIndent()
        )

        JsonSchemaSmokeValidator.validate(mapper.readTree("""{"kind":"native","payload":"x"}"""), schema)
        JsonSchemaSmokeValidator.validate(mapper.readTree("""{"kind":"review"}"""), schema)
        assertFailsWith<IllegalArgumentException> {
            JsonSchemaSmokeValidator.validate(mapper.readTree("""{"kind":"native"}"""), schema)
        }
        assertFailsWith<IllegalArgumentException> {
            JsonSchemaSmokeValidator.validate(mapper.readTree("""{"kind":"review","payload":"x"}"""), schema)
        }
    }

    @Test
    fun enforcesUniqueItemsAndLocalReferences() {
        val schema = mapper.readTree(
            """
            {
              "type":"array",
              "items":{"${'$'}ref":"#/${'$'}defs/name"},
              "uniqueItems":true,
              "${'$'}defs":{"name":{"type":"string","minLength":1}}
            }
            """.trimIndent()
        )

        JsonSchemaSmokeValidator.validate(mapper.readTree("""["a","b"]"""), schema)
        assertFailsWith<IllegalArgumentException> {
            JsonSchemaSmokeValidator.validate(mapper.readTree("""["a","a"]"""), schema)
        }
    }
}
