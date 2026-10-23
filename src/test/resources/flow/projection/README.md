# Projection fixtures

These inputs exercise existing review-only manifest, secret, parameter and renderer
contracts independently of unsupported source semantics. They contain no action
handler assertions. The API-sync fixture also uses empty transform, aggregate and
validate payloads solely to inspect data-operation node kinds in review artifacts.
It provides no evidence for filtering, mapping, aggregation or validation behavior.
The deployment fixture uses inline expressions instead of global declarations; it
provides no evidence for global initialization, order or lifetime semantics.

These fixtures are not migrations or equivalent replacements for the examples
under `examples/`. The original examples remain unchanged and are explicitly
rejected by `ActionResultHandlerContainmentTests` and
`DataOperationPayloadContainmentTests` and
`GlobalVariableInitializerContainmentTests`. Their authored assertions, data
payloads and global initializers must not disappear during planning. See
[the A-02 admission boundary](../../../../../docs/DATA_OPERATION_PAYLOAD_CONTAINMENT.md).

See also [the A-03 admission boundary](../../../../../docs/GLOBAL_VARIABLE_INITIALIZER_CONTAINMENT.md).
