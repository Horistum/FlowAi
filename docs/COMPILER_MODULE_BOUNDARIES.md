# Compiler-enforced module boundaries

## Current production composition

The distribution project `:` composes four separately compiled Kotlin/JVM
modules. They have their own production classpaths, class outputs, JARs and test
suites. A shared package name does not confer Kotlin `internal` access across
these compilations.

| Project | Owned production files | Responsibility | Production dependencies |
| --- | ---: | --- | --- |
| `flow-semantic-kernel` | 6 | Canonical graph, semantic digest, control, effect, topology and state-lifetime contracts | Kotlin standard library and annotations only |
| `flow-module-contracts` | 7 | Typed AST, decoded module contracts, read-only catalog, lowering metadata and expression-syntax port | Kernel and standard library |
| `flow-compiler` | 54 | Validation, availability, lowering, planning, canonical graph construction and authorization | Kernel, module contracts and standard library |
| `flow-frontends` | 23 | Flow/Intent/reviewed-proposal frontends, source capture, parsing, YAML and default-input loading | Compiler, contracts, kernel and explicit Jackson/YAML dependencies |
| `:` | 251 | Current CLI, concrete adapters, materialization, conformance and distribution composition | The four modules and remaining product dependencies |

The graph is acyclic: frontends depend on the compiler, not the other way round.
Neither the compiler nor module contracts have FlowParser, ExpressionParser,
ModuleRegistry, Jackson, a concrete target provider, the CLI or conformance on
their production classpath. Compiler semantics still validate closed policy
vocabulary; removing the Flow syntax implementation does not remove semantic
validation of typed input values.

This is the compiler/frontend extraction slice, AR-03B. Concrete adapter modules
and final conformance/distribution separation remain AR-03C/D work. Their sources
are not disguised as compiler dependencies to make the build pass.

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
those manifests and the complete 341-file production tree. Missing files,
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
./gradlew --offline --no-daemon --build-cache run --args=conformance
```

The kernel proof copies its actual build, six sources and tests into an empty
project without residual production sources, then executes clean tests without a
build cache. The compiler proof copies the actual production build and exactly
67 kernel/contract/compiler sources, with no frontend, concrete adapter, root
integration sources or compiled outputs. It compiles the kernel dependency and
runs the compiler and contract suites, without repeating the kernel suite a
third time. Both emit input SHA-256 fingerprints, JUnit evidence and production
classpath reports. Dependency-cache reuse is allowed; build-output reuse is not.

The isolation property in the production settings selects the corresponding
projects and rejects any residual source tree. The proofs do not synthesize a
replacement Gradle build or inject fake production classes. Compiler tests cover
real parsed-source and Intent compilation, explicit decoded module contracts,
rejection, semantic mutation and unauthorized API access.

Only final exact-head and synthetic-merge Flow CI jobs run automatically for a
ready PR. Both publish root and all four module test reports and both isolation
proofs. Draft/branch-push suppression, cancellation of obsolete runs and manual-
only full relocated offline verification are unchanged. See `CI_COST_POLICY.md`.
The new compiler proof is inside those existing jobs, not a new workflow or
another full product/conformance build.

## Lifecycle evidence

AR-03A's historical activation, implementation and offline receipts are retained.
AR-03B starts from merged PR #173, main
`0f96ef60e2a180ab1e7aa6d0f5d8e2b0003c8047`, with successful post-merge Flow CI #3242.
Its code status is tracked separately from acceptance: `implemented` does not
manufacture a CI result. The PR's current immutable HEAD and synthetic-merge
checks own final acceptance. Their run/job IDs are recorded in the PR only after
execution, avoiding a self-referential receipt or another paid documentation-only
build. The next transition must verify the accepted merged predecessor again.

AR-03 remains active; F-10 and F-20 remain open, AR-03C is next but not activated,
AR-04 is not activated and EF-09 stays paused. Completing this slice does not
claim final adapter extraction, runtime execution or expanded target maturity.
