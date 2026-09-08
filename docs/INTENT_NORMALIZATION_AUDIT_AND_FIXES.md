# Flow v0.3.0-rc1.8.3 Audit and Fixes

This release is a corrective release after the rc1.6 audit. The goal is to fix the regression causes rather than hide failing tests.

## Validated findings

| Area | Finding | Decision | rc1.7 action |
|---|---|---:|---|
| Version interpolation | `${version}` was treated as a literal string in generated Docker/Kubernetes commands. | Valid | Intent strings containing `${...}` are lowered to template expressions and target renderers translate them to `params.version`, `inputs.version`, or Tekton params. |
| JUnit bridge | The full Flow specification bridge was made advisory in rc1.6. | Valid | Restored as blocking. `./gradlew test` must fail when any spec scenario fails. |
| Version drift | Multiple version strings existed across build, constants, targets and snapshots. | Valid | Standard version, Gradle version, target metadata and scenario maturities aligned to `0.3.0-rc1.8.3`. |
| Scenario regression | Build/test, provisioning and cleanup requests fell back to custom. | Valid | Added dedicated scenario packs and conformance regression coverage. |
| Fabricated values | Backup cron/timezone and data-sync source/destination were invented. | Valid | Removed fabricated exact cron/source/destination. Required/recommended questions and assumptions are emitted instead. |
| Confidence constants | Dependency/entity/safety confidence values were mostly constants. | Valid | Confidence now depends on required questions, risk severity/mitigation and dependency sanity. |
| Required questions | Required questions did not block lowering by default. | Valid | Normalizer emits blocking safety policies; intent validation fails before AST lowering. |
| High risks | Unmitigated high risks were not a default lowering gate. | Valid | Unmitigated high risks emit blocking safety policies. |
| Over-synthesis | Rollback/health/kubernetes alone could trigger full deployment. | Valid | Deployment triggers narrowed to deployment intent only. Rollback-only falls to custom/review instead of fake deployment. |
| “Legacy” label | The JUnit bridge was incorrectly described as legacy/advisory. | Valid | Comment fixed and test restored as blocking specification coverage. |
| RuleBasedIntentNormalizer name | After scenario packs it became a pure delegate. | Valid | Added `ScenarioPackIntentNormalizer`; old name remains deprecated compatibility wrapper. |
| Generic `standard.execute` output | Non-deployment packs rendered as a generic placeholder. | Partly valid | Renderer now emits semantic operation-specific commands. Real external side effects still require module adapters. |
| Non-English aliases | Non-English keyword aliases were removed from project source text. | Valid | The repository now keeps normalizer triggers, examples and generated artifacts English-only. |

## Known not-fully-fixed design debts

These are not hidden; they remain tracked design work:

1. Full offline build without Maven/Gradle cache still requires dependency strategy work because CLI/conformance/YAML loaders use Jackson.
2. Deprecated legacy generators remain for compatibility tests but are no longer the public rendering path.
3. The project still has both MiniYaml and Jackson-based YAML loading. This should be unified in a later refactor.
4. Exact byte-for-byte snapshots are less useful while target renderers are still evolving. rc1.7 keeps blocking semantic snapshot/conformance checks and explicit stale-literal checks.

## Required local verification

```bash
./gradlew clean test
./gradlew run --args="conformance"
```

