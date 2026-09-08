# AR0.2 Architecture Readiness Baseline

## Evidence boundary

This report is generated from the live repository architecture measured by `ArchitectureReadinessBaselineAnalyzer` after the first AR0.2 implementation boundary passed.

- Flow CI: `#2987`
- Run ID: `32446414154`
- Exact head: `4afb48d46b35342accf499fc01123d4cf7effa9a`
- Synthetic merge candidate: `4b12b9702a5268dff6baf2faa67124f5ba8ca879`
- Exact-head result: PASS
- Merge-candidate result: PASS
- Standalone conformance: PASS on both boundaries

## Measured architecture

| Measurement | Value | Source of authority |
| --- | ---: | --- |
| Production Kotlin type declarations | 1130 | lexical CODE spans under `src/main/kotlin` |
| Production `*Authority` declarations | 77 | `AuthorityResponsibilityCatalog` |
| Complete conformance checks | 92 | `ConformanceSuiteInventory` including closure |
| Documented lexical architecture directions | 5 | `ArchitectureGovernanceAnalyzer` |
| Forbidden lexical architecture terms | 31 | `ArchitectureGovernanceAnalyzer` |
| Core-to-adapter dependency violations | 0 | imports from declared target-neutral roots |
| Stringly typed semantic candidates | 17 | target-neutral semantic source scan |

These are baseline measurements, not quality scores. AR0.2 deliberately does not set an arbitrary target such as "fewer authorities is always better". Later structural work must demonstrate that complexity is removed without collapsing independent semantic responsibilities.

## Public artifact contract boundaries

The measurement milestone did not advance any public contract:

| Artifact | Version |
| --- | --- |
| Intent | 2.0 |
| AST | 2.2 |
| Execution Plan | 2.4 |
| Execution Plan Lowering Evidence | 2.1 |
| Target Manifest | 3.0 |
| Target Registry | 3.2 |

## Target-neutral dependency boundary

No forbidden imports from `org.flowlang.adapters`, `org.flowlang.targets`, or `org.flowlang.generators` were found in the AR0.2 target-neutral roots:

- `org.flowlang.intent`
- `org.flowlang.effects`
- `org.flowlang.controls`
- `org.flowlang.topology`
- `org.flowlang.planner`

This is a zero-violation baseline. SB-01 can later replace part of this lexical assurance with compiler-enforced module boundaries.

## Stringly typed semantic candidates

The analyzer found 17 fields whose names indicate semantic identity/state while their Kotlin representation is currently `String` or `String?`:

1. `src/main/kotlin/org/flowlang/controls/ControlContracts.kt:condition`
2. `src/main/kotlin/org/flowlang/effects/SemanticEffects.kt:resource`
3. `src/main/kotlin/org/flowlang/intent/CanonicalIntentMeaning.kt:kind`
4. `src/main/kotlin/org/flowlang/intent/IntentDecisionAnalyzer.kt:policy`
5. `src/main/kotlin/org/flowlang/intent/IntentDecisionAnalyzer.kt:status`
6. `src/main/kotlin/org/flowlang/intent/IntentDesignAnalyzer.kt:capability`
7. `src/main/kotlin/org/flowlang/intent/IntentDesignAnalyzer.kt:type`
8. `src/main/kotlin/org/flowlang/intent/IntentModel.kt:condition`
9. `src/main/kotlin/org/flowlang/intent/IntentModel.kt:kind`
10. `src/main/kotlin/org/flowlang/intent/IntentModel.kt:type`
11. `src/main/kotlin/org/flowlang/planner/CanonicalExecutionPlan.kt:condition`
12. `src/main/kotlin/org/flowlang/planner/CanonicalExecutionPlan.kt:kind`
13. `src/main/kotlin/org/flowlang/planner/DependencyContinuity.kt:capability`
14. `src/main/kotlin/org/flowlang/planner/ExecutionPlan.kt:condition`
15. `src/main/kotlin/org/flowlang/planner/ExecutionPlan.kt:kind`
16. `src/main/kotlin/org/flowlang/planner/ExecutionPlan.kt:mode`
17. `src/main/kotlin/org/flowlang/planner/ExecutionPlan.kt:type`

These are **review candidates, not automatic defects**. Some strings may correctly belong to extensible or wire-level contracts. Any later strengthening must be evidence-bounded and must preserve serialization compatibility deliberately rather than replacing strings merely to improve this count.

## Architectural interpretation

The current codebase has a strong target-neutral dependency baseline and explicit conformance/governance ownership, but its structural complexity is substantial: 1130 production type declarations, 77 named authorities and 92 conformance checks. The next growth phase should therefore avoid adding parallel semantic authorities when existing ones can express the external corpus evidence.

The most actionable future comparison points are:

- Core-to-adapter violations must remain zero.
- External Falsification should not increase `StandardCapability` or Authority inventory without demonstrated target-independent semantic need.
- SB-01/SB-02 should replace lexical enforcement with compiler-enforced boundaries where possible and should reduce redundant governance rather than merely adding modules and retaining all old checks.
- Stringly typed candidates should be revisited only where External Falsification demonstrates ambiguity, invalid states, or unsafe coercion.

## AR0.2 conclusion

The repository is architecturally ready to begin External Corpus Foundation work. AR0.2 found no target-neutral dependency violation and no artifact-contract drift. It records the current complexity so EF and later structural work can be judged against code-derived evidence rather than architectural memory.
