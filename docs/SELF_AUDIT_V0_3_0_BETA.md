# Self Audit: v0.3.0-rc1.8.3

## Scope

This audit covers the AI Intent Normalization Layer added in `0.3.0-rc1.8.3`.

## Checks performed

- Verified version constants and Gradle version were updated to `0.3.0-rc1.8.3`.
- Added deterministic AI normalization model and scenario-pack implementation.
- Added CLI command `normalize` without tying core to an LLM provider.
- Verified the scenario-pack normalizer compiles independently with Kotlin compiler together with its direct model dependencies.
- Executed a small local JVM smoke test for the scenario-pack normalizer:
  - input: `Deploy application billing-api to Kubernetes. Require approval in production. Verify health after deploy and rollback on failure.`
  - output intent name: `billing-api`
  - synthesized capabilities: checkout, test, build-image, approve, deploy, verify, rollback
  - required open questions: 0
- Added conformance checks for normalization and required clarification behavior.
- Added unit tests for normalization behavior.
- Added documentation and schema draft for normalization report.

## Known limitations

- The MVP normalizer is deterministic and scenario-pack based. It is intentionally not a full LLM implementation.
- Entity extraction is conservative and only handles common common English deployment wording.
- Real AI provider adapters are not included in core and should be implemented as external adapters.
- Full Gradle test execution was not performed in the sandbox because the Gradle distribution/dependency download is unavailable.

## Architectural guardrail

The new layer keeps the original Flow principle: AI does not generate Jenkins/GitHub/Tekton syntax directly. AI normalizes intent; Flow validates, plans and renders through target manifests.
