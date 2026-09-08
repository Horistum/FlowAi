# Compiler-enforced module boundaries

## Current boundary

The application project `:` depends on `:flow-semantic-kernel`. The kernel is a
separate Kotlin/JVM compilation, JAR, production classpath and test runtime. It
has no dependency on the application project, concrete adapters, frontends,
conformance, Jackson or the CLI. Only the Kotlin standard library and its
annotation dependency are admitted to its production compile and runtime
configurations. Its public qualified names and implementation package version
remain unchanged.

The kernel owns the canonical execution graph and digest, state lifetime,
control contracts, semantic effects and execution-topology contracts. Graph
construction, frontend lowering, binding catalogs, authorization orchestration,
semantic validation, planning, target implementations and conformance remain in
the residual application. Kernel extraction does not mean the entire compiler
is already independent of concrete adapters.

## Source ownership and migration debt

`gradle/semantic-kernel-sources.txt` is the single exact source-ownership manifest.
The six listed files keep their existing `src/main/kotlin` paths during this
first bounded cut. Gradle includes them only in the kernel source set and
excludes them from application compilation. The ownership task compares the
actual Gradle source sets with the manifest and the complete production Kotlin
source tree; overlap, omissions, extra source roots, globs and escaping paths
fail. New unowned files under the module's production directory also fail rather
than being silently ignored.

Retained paths preserve the existing source inventories, architecture checks and
historical evidence references. They do not share a compiler invocation or
friend paths. `SemanticKernelCompositionTests` checks that the application loads
graph and digest classes from a different compiled output than its compiler
service. No production file was copied into a second location.

This shared source-root layout is explicit migration debt, owned by the compiler
and module-contract extraction slice (AR-03B), with final removal or a reviewed
retention decision required by integrated module closure (AR-03D). Physical
relocation must first make every source inventory and governance scan
module-aware; moving files while silently losing checks is not an acceptable
cleanup. Full recovery closure (AR-07) audits this decision. There is no blanket
source-governance exception.

## Executable evidence

The normal `clean test` task graph builds the kernel before the application and
runs both projects' tests. Calling the explicit root `:test` also depends on the
kernel suite. The production classpath gate examines both compile and runtime
artifact component identities, rejecting product projects, local file
substitutes and non-standard-library modules.

Kernel tests launch a separate Kotlin compiler process with only the actual
kernel production classpath. A legitimate public graph/digest consumer must
compile. Imports of the residual compiler, concrete adapters, conformance and
Jackson must fail. Same-package and alias syntax do not bypass this boundary.
The compiler also rejects access to the internal digest byte factory and
extension of the sealed graph contract from another module. Behavioral tests
retain neutral graph identity, fail-closed control and topology semantics.

`python3 tools/verify_semantic_kernel_isolation.py --offline` copies the actual
production Gradle build, wrapper, ownership manifest, exactly six owned source
files and actual kernel tests into an empty temporary project. All residual
production sources, adapters, root tests and project outputs are absent. It
executes the kernel clean test without a build cache and writes source/build
SHA-256 fingerprints, JUnit results and the resolved production classpath to
`ci-logs/kernel-isolation/`. An already resolved dependency cache is required for
`--offline`; omitting it permits normal public dependency resolution.

Both exact-head and synthetic-merge Flow CI execute this isolated proof after
the full suite. CI artifacts include both projects' reports and the isolation
proof. Changes to module build inputs also trigger the existing independently
prepared, separately copied offline build proof. Its input manifest now includes
the module build and ownership manifest.

## Scope of completion

AR-03A completes this kernel boundary only after distinct activation,
implementation and matching offline CI evidence exists. AR-03 remains active;
F-10 and F-20 stay open. AR-03B is the next planned slice, not silently activated.
Frontend/compiler separation, adapter contracts and extraction, conformance and
distribution composition, and deletion of arbitrary adapters while building the
entire compiler are not claimed by this cut. No graph digest, parser behavior,
public artifact version or target certification changes are included.
