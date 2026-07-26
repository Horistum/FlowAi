import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.flowlang.ast.RetryNode
import org.flowlang.parser.FlowParser
import org.flowlang.parser.ParseException

class FlowRetryParserIntegrityTests {
    @Test
    fun validRetryPolicyPreservesEveryAuthoredValue() {
        val retry = parseRetry("max: 5 delay: \"2s\" backoff: exponential")

        assertEquals(5, retry.policy.max)
        assertEquals("2s", retry.policy.delay)
        assertEquals("exponential", retry.policy.backoff)
    }

    @Test
    fun malformedRetryValuesFailClosedWithPolicyPath() {
        val cases = listOf(
            "max: \"three\"" to "retry.max",
            "max: 3.5" to "retry.max",
            "delay: 30" to "retry.delay",
            "backoff: 5" to "retry.backoff"
        )

        cases.forEach { (policy, expectedPath) ->
            val error = assertFailsWith<ParseException> { parseRetry(policy) }
            assertContains(error.message.orEmpty(), expectedPath)
        }
    }

    @Test
    fun unknownAndDuplicateRetryKeysAreRejectedInsteadOfDiscardedOrOverwritten() {
        val unknown = assertFailsWith<ParseException> { parseRetry("maxAttempts: 5") }
        assertContains(unknown.message.orEmpty(), "retry.maxAttempts")
        assertContains(unknown.message.orEmpty(), "unknown")

        val duplicate = assertFailsWith<ParseException> { parseRetry("max: 3 max: 5") }
        assertContains(duplicate.message.orEmpty(), "retry.max")
        assertContains(duplicate.message.orEmpty(), "duplicate")
    }

    private fun parseRetry(policy: String): RetryNode {
        val document = FlowParser().parse(
            """
            flow "retry-integrity" {
              steps {
                retry { $policy } { fail "stop" }
              }
            }
            """.trimIndent(),
            "retry-integrity.flow"
        )
        return document.flow.steps.single() as RetryNode
    }
}
