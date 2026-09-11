import org.flowlang.modules.SchemaType

/**
 * Test-only bridge for two historical fixtures that predate the closed SchemaType API.
 * Production code has no String-based SchemaField constructor.
 */
@Suppress("FunctionName")
internal fun SchemaField(
    type: String,
    required: Boolean = false,
    sensitive: Boolean = false,
    defaultValue: Any? = null
): org.flowlang.modules.SchemaField = org.flowlang.modules.SchemaField(
    type = requireNotNull(SchemaType.fromWireName(type)) { "Unsupported test schema type '$type'." },
    required = required,
    sensitive = sensitive,
    defaultValue = defaultValue
)
