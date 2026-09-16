# External review correction contracts

## Jenkins authored values

The adapter emits regex operands as non-interpolating single-quoted Groovy strings. Regex characters retain their regular-expression meaning; they are not escaped as though they were literal regular-expression matches. Input properties retain exact authored names through quoted-property syntax. Unsupported free identifiers and expression operators fail explicitly. This does not certify additional Jenkins structures or authorize arbitrary callable functions.

Previously generated Jenkinsfiles should be regenerated after updating the compiler. A valid Kotlin test or parsed Groovy expression is not evidence that a complete Jenkins deployment has been certified.

## Canonical semantic digest correction (EXT-07)

The internal digest encoding is domain-separated as `flow-canonical-semantic-v2`. Dependency evidence classification and the ordered dependency node path are included because they affect validation and derived dependency meaning. A change to either must invalidate the prior graph authorization.

`evidenceReference` remains a diagnostic provenance pointer, intentionally excluded from semantic identity. Moving source evidence without changing its semantic classification or path does not change the canonical meaning. Storage order of nodes and independent edges remains irrelevant; order within a dependency path remains significant.

All canonical digests and compilation/materialization receipts must be recomputed with the corrected compiler. Old digests are not silently accepted or translated as equivalent. Public Intent/AST wire schemas and the product brand are unchanged. This is a bounded correction to AR-01's invariant, not a claim that AR-04 is complete.
