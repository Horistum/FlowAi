# Compiler-enforced module boundaries

## Current production composition (AR-03C)

The residual root project composes eleven separately compiled Kotlin/JVM modules.
Each has its own production classpath, output, JAR and test suite. Sharing an
existing package or source directory does not grant Kotlin `internal` access.

| Project | Owned Kotlin files | Responsibility | Direct production dependencies |
| --- | ---: | --- | --- |
| `flow-semantic-kernel` | 6 | Canonical graph, digest, effect/control/topology contracts | Standard library and annotations only |
| `flow-module-contracts` | 7 | Typed AST and decoded module-catalog contracts | Semantic kernel |
| `flow-compiler` | 54 | Validation, planning, canonical graph construction and authorization | Kernel and module contracts |
| `flow-frontends` | 23 | Syntax, source capture, YAML and default-input loading | Compiler, kernel, contracts; explicit Jackson/YAML |
| `flow-adapter-contracts` | 1 | Immutable, explicit generic AdapterCatalog | Standard library and annotations only |
| `flow-adapter-runtime` | 47 | Neutral materialization SPI, checked authorization, lowering and binding validation | Kernel, contracts, compiler, frontends, adapter contracts |
| `flow-adapter-evidence` | 28 | Generic provider evidence and target-registry validation | Adapter runtime; explicit Jackson Kotlin |
| `flow-adapter-jenkins` | 7 | Generator, renderer, Groovy translation and native definitions | Adapter runtime |
| `flow-adapter-github-actions` | 13 | Generator, renderer, expression/trigger/continuity policies and native definitions | Adapter runtime |
| `flow-adapter-tekton` | 6 | Generator, renderer, when translation and native definitions | Adapter runtime |
| `flow-reference-distribution` | 6 | Concrete composition, reference defaults and retained facades | Runtime, evidence and three concrete adapters |
| `:` | 169 | Residual CLI, conformance, release and architecture governance | Explicit reference distribution and shared product modules |

The dependency graph has no reverse concrete-adapter edges. Removing every
concrete adapter leaves the kernel and compiler compilable; generic adapter
materialization and evidence also compile after those sources are physically
omitted. Every concrete adapter compiles without siblings, evidence composition,
reference distribution, CLI or conformance on its production classpath.

`flow-adapter-runtime` names compiler-side materialization, not a workflow
executor. Its frontend dependency currently supplies expression parsing and
strict notes/policy decoding. This bounded edge is explicit, not an assertion
that all product modules are frontend-independent. Kernel/compiler classpaths
remain free of frontend, serialization, concrete adapter and conformance code.
The residual root/conformance/CLI split and integrated F-10/F-20 closure remain
AR-03D work.

Generic materialization requires an explicit `AdapterCatalog<TargetProjectionProvider>`
and a decoded `ModuleCatalog`. Evidence authorities likewise require the supplied
catalog; no generic authority chooses a built-in provider. Capability matrices
receive the distribution's required-target set explicitly. Reference defaults
are selected in `ReferenceTargetProjections` and `ReferenceAdapterEvidence` only.
An injected empty catalog is never replaced by the reference registry.

The strict source-alias YAML retains its repository path but is packaged only by
`flow-frontends`, not root. Standalone frontend consumers therefore have the same
source compatibility data as the installed distribution, with no duplicate
resource on the classpath. The physical generic-adapter proof includes this exact
resource as a fingerprinted input.

## Inputs and explicit composition

`ModuleCatalog` provides already decoded `FlowModule` contracts. It has no loader,
filesystem lookup or default descriptor discovery. `ModuleRegistry` implements
that interface in the frontend module; its existing loading and lookup semantics
are retained.

`FlowCompilationService` now requires three explicit inputs:

```kotlin
FlowCompilationService(moduleCatalog, environmentSafetyPolicy, expressionParser)
```

`IntentExpressionParser` is a typed port returning the existing expression AST.
The Flow implementation lives in the frontend module. Raw-source requests whose
AST is already parsed do not invoke that port. Intent expression lowering uses
the supplied parser and preserves its existing rejection behavior. The policy
object is decoded outside the compiler instead of obtaining YAML-backed defaults
from a validator constructor.

Product entrypoints use `FrontendCompilerComposition.compiler(registry)` for the
existing distribution defaults. Its `intentPlanner`, `flowValidator` and
`safetyValidator` factories replace corresponding implicit-loading constructor
calls. This is an explicit source-level construction migration, not a silent
fallback to reflection, service loading or a global registry inside the compiler.
Public package/artifact versions and wire contracts are unchanged.

`CompilationInput` variants are untrusted parsed requests, not authorizations.
The compiler still executes validation, availability analysis, graph building,
semantic digest computation and graph-derived projection before acceptance.
The ordinary product routes remain the three inventoried frontends.

## Authorization and source capture

Source capture belongs to frontends. Strict UTF-8 decoding and the single-read
source snapshot are unchanged. `CapturedCompilationSource` has an internal
constructor and no public copy operation. Corpus preflight may retain and compile
a captured Intent source without rereading a changed file. The source audit and
its mutation regression now inspect the frontend-owned capture implementation.

`CompilationAuthorization` constructors and rich graph-building factories remain
internal to the compiler. Downstream materializers use the existing guarded task
and failure-policy readers through narrow public APIs. Their result constructors
and data-class copies remain internal. An external same-package consumer is
compiled to prove it cannot construct an authorization or copy an authorized view.

`inspectionView()` first checks integrity and exposes diagnostic graph/binding
data; it does not issue or replace authorization. `requireMatchingGraph` verifies
an externally retained candidate against the original authorization's bindings,
projection and digest. Mutation evidence no longer instantiates an internal
compiler authorization from conformance. Compatibility-plan entrypoints always
retain `COMPATIBILITY_PLAN` origin and cannot claim source or proposal review.

White-box regression fixtures use Gradle's test-fixtures variants for their own
module's internal helpers and depth limits. There are no manually supplied
cross-production friend paths. Fixture capabilities are explicitly rejected on
production classpaths and fixture JARs are not distribution dependencies.

## Exhaustive source ownership

Each module has one sorted exact manifest under `gradle/`. Existing production
source paths remain under `src/main/kotlin` to preserve source inventories and
historical evidence. No source file is copied into a second production module.
The root compilation excludes the union of owned module sources.

`verifyProductionSourceOwnership` compares the actual Gradle source sets with
those manifests and the complete 367-file production tree. Missing files,
overlap, escaping paths, additional source roots, unowned child-module sources
and unreviewed Java production inputs fail closed. The original kernel ownership
gate also sees all non-kernel source sets rather than only the residual root.

Every production compile depends on ownership and resolved-classpath gates.
Classpath checks inspect both compile and runtime component identities, reject
local files/composite substitutions and test-fixture variants, and admit only
explicit project/library identities. External Kotlin compiler probes use the
actual **main** runtime classpath, never the test runtime or a friend path.

Physical relocation remains explicit migration debt. AR-03B supplies exhaustive
module-aware Gradle ownership; existing source scanners still inspect the retained
shared source root. AR-03D must either migrate every scanner before relocation or
record a reviewed retention decision. AR-07 audits that decision. No blanket
source-governance exception is introduced.

## Executable evidence and cost controls

The complete suite is available through `clean test` or root `:test`; all module
suites are dependencies of the root test task. All test tasks disable cached
results and up-to-date substitution, while normal CI may cache compilation.
The distribution composition tests check distinct class origins and a single
runtime definition of owned classes.

```bash
python3 -m unittest discover -s tools/tests -p 'test_*.py'
python3 tools/flow_agent_validate.py
python3 tools/flow_agent_runner.py
./gradlew --offline --no-daemon --build-cache clean test
python3 tools/verify_semantic_kernel_isolation.py --offline
python3 tools/verify_compiler_isolation.py --offline
python3 tools/verify_adapter_isolation.py --offline
./gradlew --offline --no-daemon --build-cache run --args=conformance
```

The kernel proof copies its actual build, six sources and tests into an empty
project without residual production sources, then executes clean tests without a
build cache. The compiler proof copies the actual production build and exactly
67 kernel/contract/compiler sources, with no frontend, concrete adapter, root
integration sources or compiled outputs. It compiles the kernel dependency and
runs the compiler and contract suites, without repeating the kernel suite a
third time. The adapter proof additionally rebuilds catalog, runtime and evidence tests with
all concrete and reference sources absent. These proofs emit input SHA-256 fingerprints, JUnit evidence and production
classpath reports. Dependency-cache reuse is allowed; build-output reuse is not.

The isolation property in the production settings selects the corresponding
projects and rejects any residual source tree. The proofs do not synthesize a
replacement Gradle build or inject fake production classes. Compiler tests cover
real parsed-source and Intent compilation, explicit decoded module contracts,
rejection, semantic mutation and unauthorized API access.

Only final exact-head and synthetic-merge Flow CI jobs run automatically for a
ready PR. Both publish root and all eleven module test reports and all three isolation
proofs. Draft/branch-push suppression, cancellation of obsolete runs and manual-
only full relocated offline verification are unchanged. See `CI_COST_POLICY.md`.
The compiler and adapter proofs are inside those existing jobs, not additional
workflows or repeated full product/conformance builds.

## Compatibility and visibility

`TargetProjectionAuthorization` remains internal to the generic adapter runtime;
independent Kotlin compilation rejects direct construction. Raw compatibility
requests retain their original validation order before graph authorization, so
normalization cannot erase a malformed retained field before it is diagnosed.
`CompatibilityMaterializationBoundary` is an explicitly deprecated source bridge
for retained CLI/conformance consumers, not a constructor for authorization.
Product frontends continue to use `CompilationUnit` factories. This bridge is
public across the extraction boundary and must not be mistaken for a completed
retirement of raw-plan compatibility.

White-box tests use module-owned test-fixture variants. Two existing diagnostic
code tests moved into the runtime test module without changing class/method
identities or assertions. Receipt and materialization mutation fixtures invoke
the real internal validators; no production friend-path or duplicate compiler
input is introduced. `GitHubActionsProjectionInspection` exposes only read-only
condition/trigger observations, leaving the implementation helpers internal.

Every temporary facade and compatibility entry has an owner, rationale and AR-07
exit condition in `.flow-agent/architecture/compiler-adapter-boundary-inventory.yaml`.
Stable lowering APIs are distinguished from removal candidates. The unused
global payload registry and platform-switch enum are removed now rather than
preserved as additional authorities. Concrete payload wire values are unchanged.
The CI/CD vocabulary inventory recognizes exactly the reviewed concrete reference
composition file; a neighbouring distribution file still fails semantic-health
checks. Module classpath enforcement is independent of that lexical inventory.

## Lifecycle evidence

AR-03A's historical receipts and AR-03B's report remain preserved. AR-03C starts
from merged PR #174, main `c8ca99273a3cc7380c840d4075ad0b57361b86e2`.
The accepted predecessor head `536a2d661e69b5b710242866d4b246c977cfca70`
passed Flow CI #3243 (run `34224886497`). Its downloaded exact-head JUnit archive
contains 1,424 distinct identities; preservation compares `(classname, name)`,
not a replacement count. Repeated isolated tests are not extra unique tests.

The frozen control-evidence source is re-pinned only for eleven reviewed
implementation reference occurrences (eight unique moves).
`ControlEvidenceReferenceMigrationTests` reverses those moves and recovers the
exact accepted historical SHA-256; a later unreviewed byte still fails the source
integrity gate. Lifecycle tests admit the explicit adapter slice while retaining
compiler/kernel predecessor receipts and rejecting premature integrated closure.

Implementation status is separate from current-revision acceptance. The PR's
actual immutable HEAD and synthetic merge must both pass all checks. Their
run/job identifiers and independently checked JUnit totals are recorded in the
PR after execution, not fabricated inside a self-referential future receipt.

AR-03 remains active; F-10 and F-20 remain open. AR-03D is next, AR-04 is not
activated and EF-09 stays paused. This extraction does not change semantic
digests, parser acceptance, public wire versions or adapter support/maturity.
