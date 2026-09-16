# External Code Review Findings — September 2026

## Provenance and status (read this first)

This report is **not** an `.flow-agent` audit closure report. It was produced by an independent, single-pass line-by-line review of the full production source tree and test suite (`src/main/kotlin/org/flowlang/**`, ~374 files, ~76,000 lines; test suite, 188 files, ~28,827 lines), requested by a repository maintainer outside the normal work-package lifecycle. It has:

- **No Flow CI run.** No exact-head or synthetic-merge-candidate validation boundary exists for any finding below.
- **No work package.** None of these findings has passed `activationBoundary`/`implementationBoundary`/`validationBoundary`/`completionBoundary` as defined in `.flow-agent/work-packages/*.yaml`.
- **No authority to modify `auditBaseline` or `findingRegister`.** The 22 findings in `.flow-agent/roadmap-architecture-recovery.yaml` (F-01 through F-22) were established by the project's own audit process pinned to `originalAuditCommit: 6b7ad6bae914da558740b4ae39c8e3817e09b8eb`. This report does not add to, remove from, or reclassify that register. New findings here use an `EXT-` prefix specifically so they can never be confused with an `F-` finding.

Every item below is **proposed and pending maintainer triage**. Per `.flow-agent/forbidden-directions.yaml` (`dishonest-governance-signal`, `self-referential-governance`), this report deliberately does not claim PASS/FAIL validation evidence it does not have, and does not create a new governance authority to track itself — the intended next step for any accepted item is a normal work package under the closest fitting existing milestone, or a plain unbounded bug-fix pull request where no architecture-recovery milestone applies.

Full file:line evidence, failure-scenario reasoning and additional medium/low-severity findings for every item below are in `docs/analysis/COMPLETE_ANALYSIS.md` (section 4) and `docs/analysis/details/*.md`, committed to branch `claude/adoring-faraday-w9acat`.

## Severity summary

| Severity | Count |
|---|---:|
| CRITICAL | 1 |
| HIGH | 7 |
| (Additional MEDIUM/LOW items) | ~21 / ~43, listed in `docs/analysis/COMPLETE_ANALYSIS.md` §4.3–4.4, not individually ID'd here |

## Findings

| ID | Severity | Title | Location | Related milestone |
|---|---|---|---|---|
| EXT-01 | CRITICAL | Groovy code injection via unescaped `$` in the `matches` operator's slashy-string regex translation | `src/main/kotlin/org/flowlang/generators/JenkinsGroovyExpr.kt:61,76` | none (immediate hotfix; see below) |
| EXT-02 | HIGH | `groovyEscape()` does not escape backslash, unlike sibling `groovyString()` | `src/main/kotlin/org/flowlang/targets/builtin/JenkinsProjectionRenderingSupport.kt:43-45` | none (immediate hotfix; see below) |
| EXT-03 | HIGH | `FlowValidator.checkExpr` does not validate the `operator` field on unary/postfix expressions; unknown operators are silently dropped by both Jenkins and GitHub Actions renderers | `src/main/kotlin/org/flowlang/validator/FlowValidator.kt:352-353` (+ `generators/JenkinsGroovyExpr.kt:32-36`, `targets/builtin/GitHubActionsTargetExpressionTranslator.kt:53-56`) | AR-04 (active) |
| EXT-04 | HIGH | `.any` vs `.all` inconsistency between `intentControlSteps` and `controlStepsProtecting` lets a partially-approved multi-operation intent satisfy an intent-wide APPROVAL requirement | `src/main/kotlin/org/flowlang/controls/CanonicalControlRequirementAuthority.kt:492-502` vs `:504-526` | none identified — flagged for maintainer scoping |
| EXT-05 | HIGH | Three independent, mutually inconsistent "is this a production environment" heuristics (exact match / word-boundary regex / naive `contains("prod")`) | `intent/IntentDecisionAnalyzer.kt:288-294`, `scenarios/BaseScenarioPack.kt:90-101`, `scenarios/MaintenanceScenarioPacks.kt:34` | none identified — flagged for maintainer scoping |
| EXT-06 | HIGH | `IntentDecisionAnalyzer.detectPolicySafetyGates` is an incomplete hand-written duplicate of the canonical `PolicyCondition`/`SafetyRequirement` dictionary (missing 3 of 9 values, silently `false`) | `intent/IntentDecisionAnalyzer.kt:256-276` | none identified — flagged for maintainer scoping |
| EXT-07 | HIGH | `CanonicalExecutionGraph` SHA-256 digest omits the `evidence`/`path`/`evidenceReference` fields of a dependency edge | `compiler/CanonicalExecutionGraphDigest.kt:232-241` | **AR-01 (completed) — see note below** |
| EXT-08 | HIGH | Diagnostic code actually emitted by the governance scanner (`ARCHITECTURE_FORBIDDEN_SYMBOL_IN_SOURCE`) does not match the code declared in the public catalog (`ARCHITECTURE_FORBIDDEN_TERM_IN_SOURCE`) or the code asserted by the negative conformance fixture | `architecture/ArchitectureGovernance.kt:469` vs `standard/StandardDiagnosticCatalog.kt:120` vs `artifacts/PublicStandardDraft.kt:169` | AR-07 (planned) |

## Notes on milestone fit

**EXT-01 / EXT-02 — do not wait for a milestone.** These are directly exploitable injection paths from authored Flow input into generated Jenkins Groovy, independent of the architecture-recovery sequence. `language-contract-type-identity-integrity.yaml` (AR-04) itself states the project's own operating principle: *"a known critical or high semantic-integrity defect interrupts feature expansion until it is contained."* A draft, ready-to-activate work package for a minimal fix is provided at `.flow-agent/work-packages/proposed-jenkins-groovy-injection-hotfix.yaml`, marked `status: proposed`. It intentionally does not touch `AR-06`'s adapter-certification scope — the fix is an escaping correctness bug, not a structural-fidelity gap.

**EXT-03 fits AR-04, which is currently active.** AR-04's own stated purpose is *"Eliminate silent authored-meaning loss, open type fallbacks and order-dependent identity resolution at every source and contract boundary."* A silently dropped unary/postfix operator on a safety-relevant expression (`exists`/`empty` checks) is exactly this class of defect. Recommend folding EXT-03 into AR-04's remaining slices (AR-04C/D/E/F, currently `planned`) rather than opening a ninth architecture-recovery milestone.

**EXT-04, EXT-05, EXT-06 do not cleanly fit an existing milestone.** They are a recurring pattern — "duplicated/diverging heuristic for the same semantic question" — documented as a systemic pattern in `docs/analysis/COMPLETE_ANALYSIS.md` §5.2, alongside two other instances (duplicate Kotlin lexical scanners; four independent ancestor/DFS graph traversals) that carry no severity rating individually but compound the same risk class. None of AR-04 through AR-07's `requiredOutcomes` currently name this pattern. Recommend the maintainers either scope a bounded correction under AR-04 (contract/behavior integrity is already its theme) or open it as ordinary bug-fix work outside the recovery sequence, since architecture-recovery milestones are reserved for the structural findings in the F-01..F-22 register.

**EXT-07 requires maintainer attention because it touches an already-`completed` milestone.** AR-01's own `completionEvidence` in `roadmap-architecture-recovery.yaml` states: *"A semantic mutation in capability, dependency, effect, control or topology changes the canonical graph digest and its conformance result."* EXT-07 describes a specific case — a dependency edge whose classification changes between `DECLARED_ORDERING` and `DATA_REFERENCE` — where the digest does **not** change. This report does not alter AR-01's `status: completed` field; that is a maintainer decision requiring the project's own revalidation process (a concrete invariant would need to be shown falsified and reopened as a bounded correction, per `roadmap.yaml` rule: *"A completed roadmap item remains immutable evidence unless a concrete invariant is falsified and reopened as a bounded correction."*). This report only surfaces the candidate gap for that process to evaluate.

**EXT-08 fits AR-07,** which already plans to *"Delete or consolidate lexical governance and evidence registries made redundant by module, type-system and certification boundaries"* — a diagnostic-code drift between the emitting scanner, the public catalog and the conformance fixture is exactly the kind of lexical-governance inconsistency AR-07 is scoped to clean up.

## Systemic patterns (not individual findings, cross-referenced for AR-07 scoping)

Documented in full in `docs/analysis/COMPLETE_ANALYSIS.md` §5:

1. **Governance-as-code volume.** In `adapters/`, 8 `*RoadmapLifecycleAuthority` classes are ~17.7% of the package (1,476 of 8,332 lines), with an estimated ~240 lines of literal copy-paste among them. In `conformance/`, an estimated 25–40% of volume (6,000–10,000 lines) is process-governance rather than semantic-adequacy testing. `roadmap/RoadmapStreamTransitionAuthority.kt` (1,530 lines, the single densest file in the repository) validates 13 development phases that are all already `completed` in `.flow-agent/roadmap.yaml` with no `nextItem` — it re-validates immutable history on every conformance run. This directly conflicts with the project's own architecture-constitution principle: *"Flow must avoid self-referential governance that does not measure behavior, quality or drift."* AR-07's existing scope item ("Delete or consolidate lexical governance and evidence registries made redundant by module, type-system and certification boundaries") is the natural home for a bounded reduction here; recommend adding an explicit governance-code volume budget as a requiredOutcome if AR-07's scope is revisited.
2. **Hardcoded historical evidence in Kotlin source.** Several `*RoadmapLifecycleAuthority` classes hardcode historical git SHAs, PR numbers and CI run IDs as Kotlin constants that duplicate data already present in `.flow-agent/*.yaml` — a manual-sync risk with no upside once the corresponding roadmap phase is closed.
3. **Two independent Kotlin lexical scanners** (`KotlinLexicalScanner`, `KotlinSourceBoundaryScanner`) with subtly different handling of nested string interpolation — a risk that two governance tools disagree on the same input.

None of these three items is assigned an `EXT-` ID; they are architectural-debt observations, not point defects, and are surfaced here only as scoping input for AR-07 or a future governance-budget requirement in `roadmap.yaml`'s `strategicDirection.governingPrinciples`.

## Recommended sequencing

1. Fix EXT-01 and EXT-02 immediately as a single small pull request (see the draft work package). Independent of milestone sequencing — this is the only finding in this report with a directly exploitable, unauthenticated-input attack path into generated CI/CD execution.
2. Bring EXT-03 into AR-04's active scope before AR-04 is declared complete, since it falls squarely inside AR-04's own stated purpose.
3. Route EXT-07 to maintainer revalidation against AR-01's completion evidence before treating AR-01 as closed with respect to canonical-graph digest integrity.
4. Triage EXT-04/EXT-05/EXT-06 and EXT-08 as ordinary bug-fix or AR-07-scoped work per the notes above.
5. Consider the governance-volume observations when AR-07 ("Recovery Closure") scope is finalized, since AR-07 is the milestone explicitly responsible for retiring redundant lexical governance.

---

*Report author: independent external review (Claude Code), not an activated `.flow-agent` work package. See `.flow-agent/roadmap-architecture-recovery.yaml` → `externalReviewCrossReferences` for the machine-readable cross-reference entry.*
