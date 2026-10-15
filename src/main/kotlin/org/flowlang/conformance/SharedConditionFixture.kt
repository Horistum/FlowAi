package org.flowlang.conformance

/** Identical authored condition and immutable checkout inputs for both provider runtimes. */
internal object SharedConditionFixture {
    const val DIRECTORY = "flow-conformance-kit/src/runtimeTest"
    const val OBSERVER = "$DIRECTORY/github-condition-observer.py"
    const val PREPARE_JOB = "github-actions-condition-prepare"
    const val PRODUCER_JOB = "github-actions-condition-runtime"
    val values = listOf(true, false)
    fun source(value: Boolean) = "$DIRECTORY/shared-condition-$value.flow"
    fun scenario(target: String, value: Boolean) = "$target-shared-condition-$value-runtime"
    fun runIds(value: Boolean) = listOf("baseline", if (value) "omitted-checkout" else "flattened-condition", "inverted-condition")
    fun job(value: Boolean, id: String): String {
        require(id in runIds(value))
        return "github-condition-$value-$id"
    }
    fun executes(value: Boolean, id: String): Boolean {
        require(id in runIds(value))
        return when (id) { "baseline", "omitted-checkout" -> value; "flattened-condition" -> true; else -> !value }
    }
    fun checksOut(value: Boolean, id: String) = executes(value, id) && id != "omitted-checkout"
    fun expected(value: Boolean, id: String): ByteArray = SharedCheckoutFixture.expected(
        if (checksOut(value, id)) "baseline" else "omitted-checkout")
    val limitations = listOf(
        "One literal Boolean guard around one immutable public checkout; no inputs, else branch, nesting, joins, error handling or downstream scheduling claim.",
        "Jenkins executes the generated Jenkinsfile. GitHub Actions executes the generated job guard and checkout steps inside a compiler-checked observation envelope.",
        "Only revision and fixture-file bytes are compared. Jenkins skips a body and GitHub Actions skips a job; their provider results are retained separately.",
        "The runtime hosts and GitHub job outputs remain trusted; signatures authenticate admitted records, not provider honesty.",
        "No general condition support or portable execution is promoted. Public repository network access is required."
    )
}
