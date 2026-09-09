# Compiler-enforced module boundaries

## Current composition (AR-03D)

The build root is an aggregate, not a residual implementation module. Fourteen
separately compiled Kotlin/JVM modules own all **370** production-source files:
**235** product files and **135** verification/tooling files. Here “production
source” means a Gradle `main` source set; it does not mean that the verification
kit is a dependency of the product. Root owns **zero** files and emits no JAR.

| Project | Owned Kotlin files | Responsibility | Direct production dependencies |
| --- | ---: | --- | --- |
| `flow-semantic-kernel` | 6 | Canonical graph, digest and semantic contracts | Standard library and annotations only |
| `flow-module-contracts` | 7 | Typed AST and decoded module-catalog contracts | Semantic kernel |
| `flow-compiler` | 56 | Validation, planning, canonical graph construction, authorization and retained safety facades | Kernel and module contracts |
| `flow-frontends` | 31 | Syntax, source capture, YAML, catalogs, scenario normalization and JSON presentation | Compiler, kernel, contracts; explicit Jackson/YAML |
| `flow-adapter-contracts` | 1 | Immutable, explicit generic AdapterCatalog | Standard library and annotations only |
| `flow-adapter-runtime` | 47 | Materialization contracts, checked authorization, lowering and binding validation | Kernel, contracts, compiler, frontends, adapter contracts |
| `flow-adapter-evidence` | 28 | Generic provider evidence and registry validation | Adapter runtime; explicit Jackson Kotlin |
| `flow-adapter-jenkins` | 7 | Jenkins generator, renderer, Groovy translation and native definitions | Adapter runtime |
| `flow-adapter-github-actions` | 13 | GitHub Actions generator, renderer, expression/trigger/continuity policies | Adapter runtime |
| `flow-adapter-tekton` | 6 | Tekton generator, renderer, when translation and native definitions | Adapter runtime |
| `flow-standard-artifacts` | 20 | Artifact contracts, provenance, public standard views and bundle verification | Kernel, adapter runtime; explicit Jackson Kotlin |
| `flow-reference-distribution` | 7 | Explicit concrete composition, reference defaults and retained facades | Runtime, evidence, standard artifacts and three concrete adapters |
| `flow-cli` | 6 | Product CLI, typed execution/presentation and plan preview | Standard artifacts, reference distribution; explicit Jackson Kotlin |
| `flow-conformance-kit` | 135 | Conformance, source governance, release tooling, reference snapshots and verification commands | Product CLI; explicit Jackson/YAML/schema tooling |
| `:` | 0 | Verification aggregation and explicit application profiles | Separate resolvable product/verification configurations; no production implementation |

The six kernel source files are byte-for-byte unchanged by AR-03D. Ownership of
existing safety/scenario/JSON files changes without rewriting their behavior.
Every product module rejects `flow-conformance-kit` on both its compile and
runtime classpaths. A local allowlist cannot authorize that reverse dependency.
Concrete adapters also remain independent of sibling adapters, CLI, reference
composition and the verification implementation.

`flow-adapter-runtime` means compiler-side materialization, not a workflow
executor. Its declared frontend dependency supplies Flow expression parsing,
notes contracts and strict YAML-backed evidence. This edge is reviewed and
retained, not hidden or mislabeled frontend-independent. The typed AST overload
of expression evaluation does not select a target parser. Kernel/compiler remain
free of frontends, serialization, concrete adapters and verification code.

## Explicit composition, not hidden defaults

`FlowCompilationService` requires a decoded `ModuleCatalog`, environment safety
policy and `IntentExpressionParser`. `FrontendCompilerComposition` supplies the
reference frontend implementation. The compiler never loads YAML or discovers a
registry by itself. All three accepted frontends continue through that compiler.

Generic materialization receives an explicit
`AdapterCatalog<TargetProjectionProvider>` and decoded module catalog. Generic
evidence receives the supplied catalog, not a built-in provider. An empty catalog
is never silently replaced by reference defaults. Concrete generation remains
behind checked projection authorization and provider-owned evidence.

AR-03D also removes the residual reference-native-catalog default from
`StandardSurface.targetSemanticsMatrix`. The artifact library cannot import
`ReferenceTargetProjections`. Callers choose one of two explicit paths:

```kotlin
// A library consumer supplies both independent catalogs.
StandardSurface.targetSemanticsMatrix(targets, nativeCatalogs)

// The reference distribution deliberately selects its reference providers.
ReferenceStandardArtifacts.targetSemanticsMatrix(rootDir)
```

The old `StandardSurface.targetSemanticsMatrix(rootDir)` construction API changes.
That is an explicit source migration, not a claim of unchanged Kotlin factory
compatibility. Target meaning, public wire versions and maturity are unchanged.
Supplying familiar target names without native definitions cannot install the
reference implementations implicitly.

## Product and verification CLI profiles

The product owns command parsing, typed results, diagnostics and presentation.
It has ten product commands: `catalog`, `diagnostics`, `flow`, `intent`, `modules`,
`normalize`, `scenario`, `scenarios`, `standard-verify` and `targets`.

Verification adds five commands: `conformance`, `reference-snapshot`,
`release-profile`, `standard-draft` and `standard-export`. Their implementations
live in `VerificationCli`, which invokes the product execution boundary with an
explicit immutable `CliCommandCatalog`. `CliCommandHandler` and `CliOutput` are
small typed host-composition ports, not discovery or authorization mechanisms.
Duplicate command names, invalid identifiers and attempts to replace product
commands fail closed, including when the requested operation is help. There is
no service loading, reflection-based command discovery, mutable global registry
or fallback to verification code.

Both hosts use the same output collector and failure mapping. An injected command
that writes evidence and then fails retains that evidence alongside the typed
failure. The collector implementation remains internal; external command handlers
can use only the presentation port. Product-only help lists only installed
commands, and requesting absent verification returns `CLI_UNKNOWN_COMMAND`.

| Build/task | Installed launcher | Profile |
| --- | --- | --- |
| `:flow-cli:installDist` | `flow-cli/build/install/flow-core/bin/flow-core` | Product only; no kit, verification classes, schema generator or test fixtures in `lib` |
| `:flow-conformance-kit:installDist` | `flow-conformance-kit/build/install/flow-conformance/bin/flow-conformance` | Product plus verification tooling |
| Root `:installDist` | `build/install/flow-core/bin/flow-core` | Historical reference/verification profile with all fifteen commands |
| Root `:installDist` | `build/install/flow-core/bin/flow-product` | Product-only classpath within the combined reference installation |

The root reference installation deliberately contains the kit in its shared
`lib` directory. It is **not** the product-only distribution. Its `flow-product`
launcher selects only product dependencies; the independent `flow-cli` package
contains no verification JAR at all.

The root `run --args=conformance` developer command is retained as an explicit
verification host. Child execution tasks have distinct names, `runProduct` and
`runVerification`, so the unqualified `run` selector cannot accidentally execute
several applications. `installDist` can still assemble all three profiles in one
Gradle graph without recompiling their shared dependencies.

The one-argument `runCli(args)` and `executeCli(args)` Kotlin functions remain
product entrypoints. Kotlin consumers that previously invoked verification
commands through them must select `executeVerificationCli(args)` from the kit,
or explicitly supply `VerificationCommands.catalog`. The historical installed
root CLI keeps those commands. Root no longer publishes an implementation JAR;
module consumers select the module providing their API.

Repository-bound commands still need repository descriptors and metadata. The
physical product proof runs installed diagnostics/help from an empty directory;
it does not claim complete working-directory independence for every command.
That broader distribution/input work remains AR-05.

## Actual source and dependency enforcement

Each module has one sorted exact manifest under `gradle/`. Existing source paths
remain under `src/main/kotlin` to preserve versioned anchors and source audits.
Sharing a directory or package grants neither duplicate compilation nor Kotlin
`internal` access. Root excludes every owned file and rejects any residual file.

`verifyProductionSourceOwnership` reconciles the **actual Gradle source sets**
with the complete filesystem partition. Missing files, overlapping ownership,
escaping paths, additional source roots, unowned child-module Kotlin and Java
production inputs fail closed. The emitted
`build/reports/module-ownership/source-ownership.json` records each actual owner,
role and relative source path. It is produced only after validation and uploaded
by both required CI jobs.

Resolved classpath gates check both compile and runtime component identities,
including transitive dependencies. They reject local JARs, composite substitutes
and test-fixture variants. Normal test tasks never reuse test outputs, even when
compilation is restored from cache. External Kotlin probes compile against the
actual module **main** classpath, not test dependencies or injected friend paths.

All historical root integration tests are now compiled by `flow-conformance-kit`.
This preserves their identities and verification-internal access without making
internal governance classes public. Root `test` aggregates all module tests and
has no test sources of its own. Module-owned compiler/frontend/adapter fixtures
remain test-only variants; no production distribution includes those JARs.

## Four physical deletion directions

Each proof copies the original build inputs and actual owned sources into an
empty workspace, records SHA-256 digests and disables the compiled-output cache.
No `.git`, prior class output, substituted implementation or fake build is copied.

1. Kernel: six actual kernel files compile and their real tests run without any
   residual product implementation.
2. Compiler: kernel/contracts/compiler compile with frontend and concrete adapter
   sources physically absent. The compiler and contract suites run; kernel tests
   are not needlessly repeated here.
3. Generic adapter: catalog/runtime/evidence compile and their suites run without
   concrete adapters, reference distribution, CLI or verification sources.
4. Product: all thirteen product modules compile, new CLI/artifact suites run and
   the product distribution is installed without any of the 135 kit sources,
   root integration suites, fixture sources or repository metadata. The proof
   validates every product classpath and actual ownership report, inspects JARs
   for missing/duplicate/verification classes, runs installed diagnostics/help
   from an empty directory and checks rejection of an unavailable verifier.

The earlier concrete-adapter compiler probes remain independent sibling-access
checks. Repeated isolation executions are not additional unique regression tests.
Python fixtures falsify orchestration and malformed evidence, not compilation;
actual Gradle builds supply the compilation proof.

```bash
python3 -m unittest discover -s tools/tests -p 'test_*.py'
python3 tools/flow_agent_validate.py
python3 tools/flow_agent_runner.py
./gradlew --offline --no-daemon --build-cache clean test installDist
./build/install/flow-core/bin/flow-core conformance
python3 tools/verify_semantic_kernel_isolation.py --offline
python3 tools/verify_compiler_isolation.py --offline
python3 tools/verify_adapter_isolation.py --offline
python3 tools/verify_product_isolation.py --offline
```

## CI cost and final evidence

Development remains local-first. A ready PR has the same two required complete
checks, `compile-test-conformance` and
`merge-candidate-compile-test-conformance`. Each tests its actual immutable
revision, assembles distributions in that same Gradle graph, then runs
**installed** reference conformance. The second Gradle startup formerly used only
to launch conformance is no longer necessary, and packaging is exercised rather
than bypassed through an uninstalled classpath.

The existing cheap selector compares full HEAD/merge Git trees. One physical
proof suite covers identical trees in the same run; distinct trees require two.
The new product proof is a fourth step in that existing prerequisite, not a new
matrix or automatic offline workflow. Full tests and installed conformance still
execute separately for HEAD and merge. A failed or skipped prerequisite fails
both required checks. There is no source-path heuristic or old receipt reuse.

Draft suppression, superseded-run cancellation, manual-only relocated offline
verification, read-only fork caches and existing timeout ceilings remain.
One new uncached product proof has a real cost; no new performance-saving
percentage is claimed without measuring actual final job durations. See
`CI_COST_POLICY.md` for aggregation and billing distinctions.

## Compatibility inventory and release repair

`.flow-agent/architecture/compiler-adapter-boundary-inventory.yaml` records
compatibility facades, all eighteen deprecated declarations in fourteen files,
actual module owners, test-only access and reviewed source/layout retention.
Mandatory tests reconcile deprecated counts and exact files with structural
Kotlin source and the actual Gradle ownership report. A new alias, changed count,
missing owner or unreviewed AR-07 removal decision fails instead of disappearing
behind a matching total. Retained lowering/composition APIs are distinguished from
removal candidates. `org.flowlang.cli.Json` keeps its qualified name but is
frontend-owned; it imports no product CLI or verification implementation.

Baseline installed `standard-draft` exposed an existing unregistered
`release-metadata-honesty-report.json` producer. AR-03D registers its real producer
and historical `0.9.7.9.7` introduction. Its existing external provenance anchors,
and the conformance manifest's source anchor, are allowed only for their owning
artifact. They are identifiers, not filesystem permissions or implementation
imports. Unrelated intent evidence cannot borrow release anchors; unknown
producers and fabricated provenance still fail. Real conformance, artifact
integrity, compliance, staged publication and bundle verification remain required.

## Lifecycle

AR-03D starts from merged PR #175, main
`a11e8b3d70fe0e6844f135b85bc9f85290084594`, accepted predecessor head
`f63966e591e7cc106c1aae57892d373693101025` and accepted run `34331743662`.
The post-merge run `34348510109` passed. Its independently built local baseline
contains 1,463 distinct Kotlin identities; preservation compares `(classname,
name)`, not merely total counts. Earlier AR-03A/B/C receipts remain historical.

Implementation and current-revision acceptance are separate. The work package
requires both final checks but never fabricates its own future SHA/run result.
Actual accepted HEAD, synthetic merge and evidence are recorded in the PR after
execution. The next post-merge transition can use those receipts to close F-10.
AR-03D contains F-20; **retirement and F-20 closure remain AR-07-owned**. AR-04 is
not activated and EF-09 remains paused. No semantic digest, parser acceptance,
public package/wire version or adapter maturity is promoted by this extraction.
