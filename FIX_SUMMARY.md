# Flow v0.7.6 Semantic Correctness Hardening - Fix 1

This package is a compile and validation correction for the v0.7.6 semantic-correctness-hardening source tree.

Flow remains an AI-first standardization layer for DevOps/IT automation intent. This fix does not introduce a runtime executor, SDK-first architecture, plugin lifecycle, target-specific public DSL or new Flow syntax.

## Fixed in this package

- Fixed `TargetExpressionTranslator` Kotlin compilation by avoiding an invalid smart-cast on the public `BinaryExpressionNode.right` property.
- Reworked mandatory safety enforcement so `DATABASE_MIGRATE` and `DEPROVISION` obligations are reported by the core intent validator instead of being silently synthesized before validation.
- Preserved the intended v0.7.6 semantic fixes:
  - Kubernetes deploy uses explicit application identity instead of a literal `app` fallback.
  - Runtime references such as `environment` and `version` render as target-native runtime parameters.
  - Target command interpolation remains centralized across action families.
  - Validator block scopes preserve sibling declarations inside control-flow blocks.
  - Mixed safe-navigation paths preserve per-segment semantics.
  - Exact `${name}` YAML intent values load as references without malformed braces.
  - Jenkins named patterns no longer fall back to a match-everything regex.
  - Intent `requires` remains deterministic ordering, not hidden parallelization.

## Why mandatory safety was changed again

The previous implementation tried to normalize missing mandatory safety into the intent before validation. That looked convenient, which is usually where production bugs put on a little hat and wave.

The corrected behavior is stricter and clearer:

- The validator reports missing mandatory safety mitigations.
- The planner refuses to lower invalid intents.
- Manual intent files and AI-normalized intents receive the same safety verdict.
- No hidden policy is invented behind the user's back.

## Validation performed

- Dependency-ordered source compilation with `kotlinc 1.9.0` completed for the main source surface.
- Target manifest generator sources compiled after the smart-cast fix.
- A semantic smoke program executed successfully and verified Kubernetes deploy identity, Jenkins runtime input rendering, mandatory database migration safety, safe-navigation preservation and Jenkins named-pattern rendering.

## Validation limitation

The uploaded full-offline package contains the Gradle wrapper but not a usable local Gradle distribution/cache in the sandbox. `./gradlew --offline` still attempts to download Gradle from `services.gradle.org`, so full Gradle test execution could not be completed here.

