# Input and approval value integrity — A-06

## Verified baseline and scope

PR #247 is merged at `147521a68c06f04de944b6966cf76c48fc4593ce`. Its A-03 boundary rejects global declarations; it does not implement global initialization. The maintainer authorized the next correction, A-06, selected by recovery roadmap revision 61. The work package was committed before production changes.

Production inspection confirmed the audit: option members were filtered to strings, input defaults were not generally typed, out-of-domain defaults only warned and approval planning discarded all parameters except `message`. Intent option parsing and its separate preservation-evidence normalizer both split on commas. Intent approval messages passed through a text conversion.

## Implemented behavior

- One AST admission owner defines input types, static default compatibility, non-blank string option domains and approval parameter/message admission. Validation emits errors; direct planning throws a typed exception with the same code and location. Blank typed-AST input kinds reach that diagnostic instead of crashing availability type construction.
- Dynamic defaults, nested expressions, non-finite or unrenderable integer-tagged defaults, unknown types and discarded option values are rejected. Supported structured defaults retain `defaultExpression`. An absent default remains absent.
- Approval parameters other than `message` are rejected in every structural and failure region. Intent lowering carries all authored parameters into that boundary and retains message expression kinds. A null message cannot trigger a generated replacement.
- Intent options and their preservation evidence use one parser-based lowering helper. Bare labels remain supported; quoted commas survive; typed literal members and blank labels fail admission. No filtered member can silently disappear.
- Approval message rendering removes exactly one pair of enclosing quotes. Evidence records message rendering transformations while preserving the existing message wire representation.
- Four standalone checks, source ownership manifests and the independent compiler-entrypoint inventory are updated. The historical audit is unchanged.

See [the public contract](../../docs/INPUT_APPROVAL_VALUE_INTEGRITY.md) for the type vocabulary, diagnostic codes and migration rules.

## Validation at report creation

The supported Kotlin 2.4.10 / Gradle 9.5.0 / JDK 25 toolchain passed all **7 new compiler tests** and **8 new frontend integration tests**. The integration suite also executed all **4 new standalone conformance checks** successfully. Tests cover audit mutations, direct/internal planning, unsupported nested or unreachable approval fields, input defaults, options with quoted commas, message templates and escaped quotes, graph serialization/digest differences, independent workflows and reviewed AI proposals.

The **257 Python tests** and Flow Agent validation passed. A stale local incremental compiler cache required a compiler-module clean; the clean compilation passed. Initial regression runs exposed test metadata expectations and the second option normalization path; the corrected targeted run passed.

The full JVM suite, installed standalone conformance, module isolation, exact-head CI, synthetic merge-candidate CI and adapter runtime certification must be verified for the final PR head. Final results and head/tree bindings belong in the PR validation record so that recording a passing run does not create another untested source revision. This report does not claim those pending checks have already passed.

## Roadmap disposition and limits

Recovery revision 62 and release state 121 record A-06 as a candidate pending current-revision CI. A-05's architecture entry page and active-roadmap routing are the next bounded documentation correction; facade retirement and AI confidence work remain AR-07-owned. A-02 typed data operations and A-03 global initialization remain open behind explicit rejection. General availability-state compaction also remains open.

AR-06 stays active, AR-07 planned and EF-09 paused. This correction does not add dynamic input initialization, approver identity, timeouts, quorum, target implementation support or signed portfolio scenarios. Existing contract versions and CI timeouts are unchanged.
