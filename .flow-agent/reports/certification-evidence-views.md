# AR-06F candidate: evidence-derived bounded certification views

Runtime archives already contain authenticated observations, but consumers must
manually reconstruct their scope. This slice generates a deterministic JSON view
and a readable Markdown matrix from the same frozen inputs that pass authenticated,
compiler-bound admission. Failed admission produces no view. A caller cannot pass
an authored successful report to bypass the admission path.

The matrix includes every semantic occurrence, structural construct and native
leaf in the assessed inventory. Uncovered rows remain `NOT_OBSERVED`; this does
not mean the target cannot implement them. Covered rows name their exact bounded
scenarios. Source, graph, artifact, expected and observed digests, runtime
prerequisites, runner identity/key fingerprint and limitations remain visible.
Native leaf observations cannot promote general semantic support or structural
equivalence. The JSON is an internal diagnostic format, not a public Flow schema
or an authorization token.

Both real Jenkins jobs publish the generated Markdown in their Actions summary
and archive both formats. The runtime proof records each file's SHA-256. The
private signing key remains outside the runtime and archived evidence. Inputs are
snapshotted before resolver callbacks; every exposed collection is immutable.
Markdown escapes provider-controlled text while JSON preserves its literal value.

## Independently inspected predecessor

PR #200 and #202 are merged. Main `8ca589b2ab90298bb515a1e55180f5d4c3e3e0a3`
passed Flow CI [37757809160](https://github.com/Horistum/FlowAi/actions/runs/37757809160)
and runtime workflow [37757809195](https://github.com/Horistum/FlowAi/actions/runs/37757809195).
The five downloaded archives match GitHub digest metadata. Their contents contain
1,913 tests in 331 suites with no failures, errors or skips, 274 passing conformance
checks, all four physical isolation proofs and 767 verified source-input hashes.
Both native Jenkins scenarios retain their exact outcomes and all six observation
signatures verify against the independently retained assessment trust declarations.

The immutable baseline is `.flow-agent/evidence/adapter-runtime-view-baseline.json`
(SHA-256 `fdbfbe150d4df3fa90213527adf9aca58243c31215d21ca67d041bc475b66183`).
The existing lifecycle gate selects AR-06F and replays earlier acceptance boundaries;
no additional authority class is introduced. This is a merged implementation
baseline, not formal AR-06 completion or validation of this new revision. Earlier
independent AR-06A/B acceptance receipts remain unchanged.

## Candidate validation and limits

Nine view tests cover exact coverage/run inventories, failure-scenario scope,
invalid signatures and missing runs, timeouts/surviving mutants/unavailable bytes,
invented structural coverage, deterministic ordering, resolver mutation, immutable
outputs and display escaping. Three lifecycle tests preserve the observed baseline,
candidate/acceptance distinction and historical finding gates. Existing historical
test identities remain intact.

Current exact-head and synthetic-merge CI, physical isolation and both real runtime
jobs must pass. Results belong in the PR; predecessor CI is not reused as current
validation. Local validation completed on the implementation commit `476872ae` using a
checksum-verified Temurin JDK 25+36 and Gradle 9.5.0:

- Production and test compilation passed; all 38 targeted tests in six suites
  passed without failures, errors or skips, including all 12 additions.
- The installed distribution passed all 274 standalone conformance checks,
  preserving every predecessor check identity and its order.
- All 157 tooling tests, structure/context generation and whitespace checks passed.

The initial push was blocked by automatic approval review. The maintainer
explicitly authorized publishing this branch and creating its PR on 2026-10-09.
Full exact-head/merge-candidate tests, physical isolation and both real Jenkins
jobs remain required on the final PR revision; their observed results belong in
the PR. Local synthetic observation tests do not replace those real runtime jobs.

AR-06 findings remain open, EF-09 remains paused, public support and versions do
not change. This step does not replace the existing public support registries:
that requires broader behavior evidence, explicit semantic adequacy and equivalent
execution on a second materially different adapter. A native-leaf occurrence,
structural occurrence and bounded executable-reference maturity remain separate
claims; this view never infers the latter from either of the former.
