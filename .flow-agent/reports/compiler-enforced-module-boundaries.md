# Compiler-enforced module boundaries

## Accepted integrated closure

AR-03 and F-10 are complete after independently verified PR #177 and actual
merged main `0a863eb10f60a64945f9657b0fcf090714ab543a`. The exact PR HEAD and
original merge passed Flow CI #3259; the actual merged tree, including Horistum
documentation, passed Flow CI #3261. Immutable detailed acceptance is in
`.flow-agent/evidence/compiler-module-acceptance.json`, bound by SHA-256 from
the work package. All 1,512 Kotlin test identities, 242 conformance checks and
four physical proofs are preserved. F-20 is contained, not retired; AR-07 owns
its removal. The separately owned AR-04 activation is described in
`language-contract-type-identity-integrity.md`.

The following implementation sections describe their historical revisions and
pending-at-publication state. They do not override the accepted closure above.

## Historical AR-03D integrated product/verification boundary

The exact predecessor is merged PR #175, main
`a11e8b3d70fe0e6844f135b85bc9f85290084594`, tree
`d3a265f3571b55b306c3814c92b131fb3c22d306`. Its accepted head is
`f63966e591e7cc106c1aae57892d373693101025`, accepted run `34331743662`;
the independently checked post-merge run `34348510109` also passed.
The baseline was rebuilt locally with JDK 25, Gradle 9.5.0 and Kotlin 2.4.10,
including complete tests, conformance and installation. It contains 1,463
unique Kotlin test identities and 130 Python tests, not the older AR-03B totals.

### Implemented boundary

The root now has no implementation sources and no JAR. Fourteen actual Gradle
modules own the complete 370-file source partition: 235 product files and 135
verification/tooling files. Standard artifact contracts, product CLI and
conformance/release tooling have distinct compiler outputs, classpaths and
application profiles. The six semantic-kernel files remain byte-for-byte unchanged.
The original integration tests are owned by the kit without widening internal
visibility, changing their identities or introducing production friend paths.

The product has no compile/runtime dependency on the verification kit. A static,
immutable typed command catalog breaks the CLI/conformance cycle: the kit supplies
five verification commands to the same product execution/presentation boundary.
Invalid, duplicate or shadowing command registrations fail closed; no global
registration, discovery or plugin lifecycle is introduced. Failures retain
previously collected presentation evidence. Product-only help is truthful.

Root's historical `flow-core` launcher remains an explicit reference/verification
profile. `flow-cli:installDist` is the independent product-only distribution;
`flow-conformance-kit:installDist` is the verification application. Root also
exposes `flow-product` with a product-only classpath. The combined installation
contains verification libraries deliberately and is not represented as a pure
product package. Distinct child execution task names prevent accidental multiple
applications from an unqualified Gradle `run` command.

StandardSurface no longer chooses a hidden reference native-catalog default.
Its caller supplies both catalogs; ReferenceStandardArtifacts is the explicit
reference composition. The generic library cannot import that composition and
an empty native catalog stays empty even for familiar target names. The old
root-directory factory signature has an explicit migration, not fictitious
source compatibility.

### Integrated proofs and cost control

The existing three physical deletion proofs remain mandatory. A fourth builds,
tests and installs all thirteen product modules after physically omitting the
kit, root integration tests, fixture sources, metadata and prior class outputs.
It inspects actual resolved classpaths and source ownership, verifies installed
JAR/class uniqueness and absence of verification classes, and executes installed
product diagnostics/help from an empty directory. An unavailable verification
command must be rejected. This is not a claim that all repository-dependent CLI
commands are working-directory independent; that broader work remains AR-05.

The added proof is inside the existing tree-deduplicated prerequisite, not a new
full-validation matrix. Both exact HEAD and synthetic merge still run all tests.
They now assemble distributions in that same Gradle graph and invoke installed
reference conformance directly, avoiding the second Gradle launch and exercising
real packaging. Local iteration, draft gating, cancellation, read-only fork
caches, manual-only full offline portability and timeout ceilings remain.
Actual runner costs belong to the final CI run; no saving percentage is assumed.

### Compatibility and pre-existing release failure

The updated inventory reviews all eighteen deprecated declarations in fourteen
actual owned files, the retained compatibility facades, test-only variants and
source/layout retention. Tests compare structural Kotlin annotations with exact
files/counts and actual Gradle owners. F-20 is contained here, not retired:
compatibility removal and its final closure remain AR-07-owned. Historical
statements below referring to integrated F-10/F-20 closure must not be read as
permission to close F-20 in AR-03D.

Baseline installed `standard-draft` failed because the existing release metadata
report had no registered artifact producer. The bounded repair registers its real
producer and historical introduction, and admits its exact provenance anchors
only for that artifact. Unknown producer names, fabricated references and release
anchors on unrelated intent evidence remain rejected. No conformance, integrity,
compliance or publication gate is bypassed to obtain a successful release.

### Acceptance

The work package records implemented scope and current-revision check
requirements, not an invented future result. Final local/CI counts, accepted
immutable HEAD/merge identities and independent artifact checks are recorded in
the PR after execution. Every baseline identity must remain, and isolated repeats
are never added to the unique test total. The next post-merge transition can use
those actual receipts to close F-10. AR-04 is not activated; EF-09 remains paused.

Design and commands: `docs/COMPILER_MODULE_BOUNDARIES.md`.
Cost policy: `docs/CI_COST_POLICY.md`.
Migration inventory: `.flow-agent/architecture/compiler-adapter-boundary-inventory.yaml`.

## Historical AR-03C adapter extraction

### Final CI orchestration correction

Flow CI #3251 at `3161446ab5f22c682c67a33715c493d9004cae67` passed both
complete Kotlin test steps and the kernel/compiler deletion proofs. Each job
then reached its 20-minute ceiling during the third generic-adapter proof;
standalone conformance and final artifact upload were not executed in that run.
That canceled run is not completion evidence.

The correction keeps the original two required check names, independent complete
HEAD/merge test and conformance execution, all three real source-deletion proofs,
and all external compiler probes. It gives physical isolation a separate bounded
job and selects one proof suite per complete Git tree. Identical HEAD/merge trees
share only this same-run file-based proof; differing trees remain independent.
A failed/missing selection or proof explicitly fails both required checks. No
prior success receipt, build-output cache, source filter or test exclusion is
used to replace a proof. Normal job ceilings remain 20 minutes.

The selector is covered by real-Git regression tests, including changed file
modes/metadata, malformed merge parents, shallow checkout, dirty source trees and
rejected symbolic or shell-shaped identifiers. Workflow shell tests cover all
prerequisite failure states and every expensive step's failure propagation.
Current complete validation and runner durations are recorded against the final
HEAD in PR #175, not inferred from the canceled predecessor.


The predecessor is merged PR #174, main
`c8ca99273a3cc7380c840d4075ad0b57361b86e2`. Accepted exact-head run
`34224886497` contains 1,424 distinct Kotlin regression identities. Those
identities, rather than historical AR-03A counts, are this slice's preservation
floor. AR-03 remains active and does not close F-10/F-20 until integrated AR-03D.

The extraction provides independently compiled catalog contracts, neutral
materialization SPI, generic adapter evidence, three concrete target modules and
reference composition. Generic authorities now receive explicit adapter catalogs;
materialization also receives a decoded ModuleCatalog instead of loading one
implicitly. Reference factories own default provider/continuity selection and the
historical portfolio composition anchor. Each concrete module owns its generator,
renderer, expression syntax, native definitions and target-specific continuity.

Production projection authorization constructors remain internal. Inventoried
compatibility factories issue requests that still cross all existing gates.
Test-only fixture variants preserve white-box mutation coverage without widening
production integrity owners. Two original diagnostic-code tests are owned by the
runtime module with unchanged identities and assertions. Native projection and
control evidence anchors follow real moved declarations rather than being waived.
The frozen control-source re-pin has an inverse-byte migration regression and
a tamper rejection test; no status, capability or limitation is changed. The
explicit adapter lifecycle retains earlier receipts and rejects future acceptance
claims or premature integrated closure.
The strict alias resource is frontend-owned, removing a dependency on root's
resource packaging for independently used frontends.

Independent Kotlin compiler probes cover positive API use, missing explicit
inputs, forbidden module imports, private authorizations, fixture leakage,
provider relabeling and absent-provider fallback. The physical generic-adapter
proof copies the actual Gradle files, declared sources, frontend alias resource
and test inputs into an empty directory without any concrete/reference/root
implementation; it performs clean compilation without the build cache (offline locally, with dependency resolution permitted for cold CI).
Existing kernel/compiler proofs remain separate. Python tests falsify missing,
failed, skipped, duplicate and stale evidence rather than substituting mocked
compilation for the real proof.

Validation is local-first on actual Temurin JDK 25 and resolved Gradle/Kotlin
inputs. Final acceptance requires complete local tests, all baseline identities,
all three physical proofs, tooling/structure checks, standalone conformance and
a usable installed CLI. Both final GitHub exact-head and synthetic-merge checks
must independently pass. The PR records actual results after execution; no future
green receipt is invented here. All module reports are uploaded. Draft gating,
manual-only full offline verification and both required check identities remain.
Physical proofs now have their own tree-deduplicated prerequisite job budget.
Temporary development/publishing transport is absent from the final source tree.

See `docs/COMPILER_MODULE_BOUNDARIES.md` for the current dependency graph and
`.flow-agent/architecture/compiler-adapter-boundary-inventory.yaml` for every compatibility
owner/removal decision. The materialization edge still explicitly depends on
frontend notes/syntax; the residual root still owns CLI/conformance/release
composition. These are recorded integrated boundaries, not hidden compiler
or concrete-adapter dependencies. AR-03D is next; AR-04 remains inactive and EF-09
paused. Public package/wire versions, canonical meaning and target maturity do
not change.

## Historical AR-03B compiler/frontend implementation

AR-03B follows merged PR #173: main
`0f96ef60e2a180ab1e7aa6d0f5d8e2b0003c8047`, tree
`7a8080266e645b4cb334ee584c939f38114de906`. The post-merge Flow CI #3242 run
`34216542159`, exact job `102029519945`, succeeded. That baseline's 1,392 distinct
Kotlin test identities were independently compared with the local offline build
before changes. They are the regression-preservation floor for this slice.

Three real modules now join the existing semantic kernel: module contracts,
compiler and frontends. An exhaustive source partition assigns 6/7/54/23 files
respectively and leaves 251 distribution-owned files. Resolved compile and runtime
classpath gates prevent reverse product/adapter/frontend dependencies. Production
classpath variants also reject test fixtures, local files and composite substitutes.

Compiler construction now receives a neutral decoded ModuleCatalog, an explicit
safety policy and an expression parser port. Loading defaults, syntax and strict
single-snapshot source capture live outside semantic compilation. There is no
fallback loading registry inside the compiler. Original compiler meaning, public
wire versions and target support are unchanged.

Authorization constructors and rich graph builders remain compiler-internal.
Materializers use validated public readers; their result constructors and copy
operations remain internal. Conformance verifies mutations against existing
authorization rather than manufacturing a new internal authorization. A
non-authorizing inspection view supports existing graph/binding diagnostics.
White-box fixtures are test-only Gradle variants, not production friend-paths.

The new tests compile independent consumers against actual module production
classpaths. They cover positive API use, forbidden imports, internal constructors,
sealed types, guarded copies and fixture leakage. Standalone compiler semantics
cover typed source, Intent expressions, decoded action contracts, unknown-module
rejection, unchanged source bytes with changed meaning, and compatibility-origin
restrictions. Distribution regressions check distinct class origins and one
runtime definition per owned class. The original four capture/parity tests moved
to the frontend test suite without changing their identities or assertions.

A physical compiler proof builds exactly 67 kernel/contract/compiler production
sources with frontend, adapter and residual product sources absent. It uses the
actual production Gradle files, no replacement build or compiled-output cache.
The existing standalone kernel proof remains. The compiler proof compiles the
kernel dependency without rerunning its already separately proven suite. Python
fixtures verify fail-closed orchestration and JUnit evidence handling; they do
not stand in for actual Kotlin compilation.

The UTF-8/source-capture audit follows its new frontend owner. Its mutation test
also verifies a valid starting fixture so an unrelated missing file cannot fake
a successful negative test. Direct-pipeline auditing recognizes both the old
constructors and the new factory calls, including an aliased factory.

### Validation and current-revision acceptance

Local development uses the resolved Gradle 9.5.0 / Kotlin 2.4.10 inputs and Temurin
JDK 25, with repeated compilation and test iterations outside GitHub Actions.
Final acceptance requires complete tests, identity comparison, standalone
conformance, both physical proofs and a usable installed distribution. The PR
records actual final counts, commit/tree identity, run/job IDs and outcomes only
after those executions. Test reports from isolated repetitions are not added to
the unique regression count.

Code state `implemented` is separate from CI acceptance. No future green CI
receipt is committed into the tree whose CI has not yet run, and no extra paid
metadata-only build is required to insert its own future commit SHA. The work
package requires the two current-revision CI check identities. Historical kernel
receipts below remain untouched and cannot prove the new compiler implementation.

The CI cost policy is retained: no automatic branch-push builds, draft gating,
obsolete-run cancellation, and manual-only relocated offline proof. The new
compiler proof belongs to each existing final validation job and is not an
additional full product build or a new workflow. Every module's JUnit and
classpath evidence is published. Temporary local-input transport is absent from
the final source tree.

AR-03 remains active. F-10/F-20 remain open; AR-03C is the next unactivated slice,
AR-03D stays planned, AR-04 is not activated and EF-09 remains paused. Module-aware
ownership is implemented; physical source relocation remains an explicit AR-03D
review, not a silent source-audit exemption. See `docs/COMPILER_MODULE_BOUNDARIES.md`.

## Historical AR-03A report

The following sections describe their recorded kernel revisions and the CI policy
at those revisions. They are retained evidence, not claims that the former
automatic offline matrix or its timeout settings remain the current policy.

### Baseline and activation

AR-02 was merged as PR #172, commit `c5c37283d99a47a6e387213dccfda2927ec6acd2`, tree `a3d4da21b128397e5401326d185ef459633b5ce4`. Its exact-head and synthetic-merge CI passed 1,367 Kotlin tests each. The local source tree was verified against that Git tree before modification.

The separate AR-03 activation commit `9412d4c94b0cad6a36c887d6d53b3d21f9d2d55b` passed Flow CI #3234, run `34190794340`, on the exact head and synthetic merge `0d16202ea51e6feaf4cc408f85e7a301a84eabb1`. Both jobs passed 1,374 Kotlin tests, Flow Agent validation and standalone conformance. Downloaded JUnit XML confirmed zero failures, errors or skips. Historical AR-02 receipts and completion metadata were not rewritten to authorize its successor.

### Implemented kernel slice

`flow-semantic-kernel` is a real Gradle project with independent compilation, JAR, compile/runtime classpaths and tests. Its six source files are unchanged in content and retain their original paths. One manifest partitions ownership: the application no longer compiles those files. Production classpath gates admit only the Kotlin standard library and JetBrains annotations, with no product, adapter, conformance or serialization dependencies. A composition regression verifies that the application consumes the separate kernel output.

Eight boundary tests include a successful external public-API compilation and compiler rejection of residual compiler, adapter, conformance and Jackson references, internal digest construction and external sealed-contract extension. Four behavioral tests exercise semantic digest identity and fail-closed graph, control and topology contracts. The physical isolation script rebuilds the same production kernel with all residual production sources and outputs absent, no substituted build or friend paths, and no build cache. It emits exact input fingerprints and machine-readable reports.

Separate compilation exposed seven cross-module smart-cast sites in three conformance sources. Stable local values preserve their existing predicates without changing kernel APIs. One historical regression suite now calls the public digest computer rather than its internal deprecated alias; its test identities and semantic assertions are retained.

Both normal CI jobs now include kernel reports and the physical isolation proof. Offline workflow triggers and its input manifest include the new module. Offline verification disables the build cache: `clean` alone can otherwise restore outputs copied from the prepared home. Root tests also re-execute instead of reusing classpath-only results because they inspect live repository metadata and source inventories.

### Validation and completion discipline

A real Temurin JDK 25.0.2 and resolved Gradle 9.5.0 / Kotlin 2.4.10 dependency cache were obtained for local testing. Local kernel compilation and all 12 kernel tests passed offline. Full product, conformance, baseline identity comparison and physical isolation results are recorded only after execution; CI implementation and offline receipts are admitted only after their actual results exist. No green activation run is reused as implementation evidence.

The kernel's own completion transition requires distinct implementation CI and a separate offline proof for the same head and synthetic merge. Full AR-03 completion remains pending. Compiler/frontend separation, concrete adapters and conformance/distribution composition remain future work, F-10/F-20 remain open, AR-04 is not activated and EF-09 stays paused. Public package and artifact versions, canonical graph semantics and target maturity do not change.

Source-layout migration debt and its owners are documented in `docs/COMPILER_MODULE_BOUNDARIES.md`: module-aware source discovery belongs to AR-03B, with an explicit relocation or reviewed retention decision at AR-03D and final AR-07 audit. Retained paths are not a governance exemption or a claim of full compiler extraction.

### Completed kernel slice and implementation evidence

AR-03A is complete. Its implementation revision is
`8d754c9eef6ca2a53d0a9ababc37868317512242`; the independently tested synthetic merge is
`5d1252bf9eb87b0492b32c6e1f05af2e2569c5a8`. These receipts describe completed runs,
not a future documentation commit or the earlier activation boundary.

- [Flow CI #3239](https://github.com/milank78git/FlowAi/actions/runs/34200961012):
  exact-head job `101979381310` and merge-candidate job `101979381044` both passed.
  Each executed 1,380 root and 12 kernel tests, with zero failures, errors or
  skips, plus 53 Python tooling tests, physical kernel isolation and standalone
  conformance. The isolated kernel's repeated 12 tests are not counted as extra
  unique tests.
- Downloaded JUnit archives from both jobs independently preserve every one of
  the 1,367 baseline `(classname, name)` identities. Neither test removal nor
  replacement by unrelated new tests was used to meet the regression floor.
- [Flow Offline Proof #16](https://github.com/milank78git/FlowAi/actions/runs/34200960933):
  exact-head job `101979380734` and merge-candidate job `101979380635` both passed
  preparation, relocated offline verification, all 1,392 Kotlin tests and
  conformance, and uploaded the explicit input manifests.

The failed preceding proof had a 20-minute **whole-job** limit covering two
complete clean builds. Passing tests did not prevent cancellation when the total
budget expired. Preparation and verification now have separate 20-minute step
limits, with a 50-minute job safety limit for both phases, setup and evidence
upload. Both full proof jobs then passed. Raising the job ceiling is not a
performance improvement and does not hide a failed test.

The workflow retains exact-head and synthetic-merge revision guards, uses the
script's actual `bash tools/offline_gradle_build.sh prepare` and `verify` entry
points, and preserves `--offline --no-build-cache`, complete tests and
conformance. Thirteen new tooling regressions cover phase separation, budgets,
revision guards, evidence upload and real shell entry-point behavior with
controlled Java/Gradle fixtures. Those fixtures test orchestration and failure
propagation; the actual compilation proof is the successful JDK 25 CI above.

Only AR-03A is closed. AR-03 stays active, its full completion boundary stays
pending, F-10 and F-20 stay open, and AR-03B is the next unactivated slice.
AR-04 is not activated and EF-09 remains paused. No public contract or semantic
meaning is changed by this completion transition.
