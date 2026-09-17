# External review correction contracts

## Jenkins authored values

The adapter emits regex operands as non-interpolating single-quoted Groovy strings. Regex characters retain their regular-expression meaning; they are not escaped as though they were literal regular-expression matches. Input properties retain exact authored names through quoted-property syntax. Unsupported free identifiers and expression operators fail explicitly. This does not certify additional Jenkins structures or introduce an allowlist of callable functions.

Previously generated Jenkinsfiles should be regenerated after updating the compiler. A valid Kotlin test or evaluated Groovy expression is not evidence that a complete Jenkins deployment has been certified. GroovyShell is a test dependency only.

## Approval evidence

A closed `requiresApproval` safety token is a requirement, not a runtime condition. It requires each protected operation to have an approval predecessor in its own workflow. Multiple controls may collectively cover the operations; no single approval is assumed to cross workflow boundaries.

Expression-based approval policies, including the existing explicit `true` expression form, retain their DYNAMIC/PENDING contract. Finding a conditional mechanism is not proof that a human has approved execution or that all runtime paths are protected. Target materialization still needs the existing enforcement evidence. An unconditional policy without a condition must have complete scope coverage.

## Decision report 1.1

`decisionModelVersion` is 1.1. Gate status now includes `pending`, alongside `satisfied` and `blocked`. Consumers using a closed enum must update their schema and must not interpret pending as satisfied. `validForLowering` indicates whether analysis can proceed; it is not target execution authorization.

Every canonical safety requirement is projected from its control assessment. Unknown/unsatisfied controls block, dynamic controls remain pending, and only satisfied controls are displayed as satisfied. Scoped backup and cleanup evidence is reused. Environment classification uses the existing policy rather than additional production-name heuristics. An unmatched explicit environment remains UNKNOWN and requires clarification; arbitrary application names are not environment evidence.

The report implementation now belongs to `flow-frontends`, preserving its package and source path. Direct consumers formerly depending on `flow-compiler` solely for this frontend report must add the frontend dependency. Core no longer owns a report that needs frontend default-policy composition. Custom composition may inject its EnvironmentSafetyPolicy explicitly.

## Canonical semantic digest correction

The internal digest encoding is domain-separated as `flow-canonical-semantic-v2`. Dependency evidence classification and the ordered dependency node path are included because they affect validation and derived dependency meaning. A change to either must invalidate the prior graph authorization.

`evidenceReference` remains a diagnostic provenance pointer, intentionally excluded from semantic identity. Moving source evidence without changing its semantic classification or path does not change canonical meaning. Storage order of nodes and independent edges remains irrelevant; order within a dependency path remains significant.

All canonical digests and compilation/materialization receipts must be recomputed with the corrected compiler. Old digests are not silently accepted or translated as equivalent. Public Intent/AST wire schemas and the product brand are unchanged. This is a bounded correction to AR-01's invariant, not a claim that AR-04 is complete.

## Architecture diagnostics

The actual structural scanner emits `ARCHITECTURE_FORBIDDEN_SYMBOL_IN_SOURCE`. The catalog and current negative corpus now agree with that behavior. `ARCHITECTURE_FORBIDDEN_TERM_IN_SOURCE` remains cataloged for compatibility with historical reports; it is not presented as the current scanner's output.

## Reference scenario and retained evidence

The `build-test-deploy` YAML declares an unconditional intent-wide approval policy. Its authored approval now precedes checkout, test, build, deployment and verification; the compiler does not silently insert or relocate gates. The earlier shape placed approval only before deployment and therefore did not satisfy the declared whole-intent requirement. Conditional-policy semantics remain separate and pending, and this correction does not claim additional target execution support.

The EF-09 baseline retains its initial MODEL_GAP observation for whole-change approval coverage. A separate `correctedOutcome` and `correctionReference` record the EXT-04 correction. The live evaluator must prove that correction, and a regression is rejected in active, validating and completed lifecycle states. The other six model gaps and EF-09's paused recovery relationship remain unchanged.
