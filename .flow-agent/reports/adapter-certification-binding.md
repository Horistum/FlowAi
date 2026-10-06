# AR-06B: canonical certification scenario binding

AR-06A admitted byte-consistent candidate evidence, but source, graph, artifact
and subject-to-scenario associations remained caller assertions. AR-06B adds a
compiler/rendering-bound path in `flow-adapter-evidence`. It requires an intact
compilation-unit authorization, checks the original source digest, generates the
manifest through the existing materialization pipeline and captures the actual
executable rendering. Compatibility-only plans and review artifacts cannot enter
this path. Captured bytes and subject sets are immutable to callers.

Bound admission checks exact scenario identities and input references, derives
semantic requirements and structural occurrences from the authorized graph and
native leaves from the generated manifest, then checks exact per-subject scenario
associations. It delegates run identity, runtime, byte budget and mutant polarity
to AR-06A. External evidence storage cannot replace captured inputs.

## Accepted predecessor and activation

PR #197 closes AR-05. PR #198 supplies AR-06A. Both are merged into actual main
`8972f0f583c2db7c4f8643d6a1c835149ae99c1f`. Flow CI #3336 validates the final
PR #198 head and synthetic merge; push CI #3337 (37404887172) validates actual
main. All three archives contain the same 1859 tests and 274 conformance checks,
with zero failures, errors or skips. Eight archive hashes and both source-bound
isolation receipts were independently checked. The immutable activation evidence
records these observations; the lifecycle validator pins its bytes before parsing.

AR-06 is active with AR-06A accepted and AR-06B selected. Historical AR-05 receipts
and all 22 finding identities and owners remain enforced through predecessor
replay. No AR-06 finding closes, AR-07 stays planned and EF-09 stays paused.
Historical tests retain their original pre-activation assertions through a
separate predecessor fixture.

## Candidate verification and limits

New tests exercise real Jenkins and GitHub Actions compilation/rendering,
substituted source/input references, compatibility-plan rejection, immutable
snapshots, omitted/invented occurrence coverage, per-scenario associations,
failed execution evidence and activation receipt/pointer mutations. Generic
compiler probes run under physical adapter isolation. Existing standalone
conformance identities are unchanged; this internal contract does not change a
public standard or artifact version.

Current-candidate compilation, tests, standalone conformance and source-deletion
isolation require fresh exact-head and synthetic-merge Flow CI. Final observed
results belong in the PR and are not self-attested here.

Occurrence coverage is a necessary scope constraint, not proof of semantic
adequacy or executed behavior. Test observations are synthetic contract fixtures;
they certify no real runtime. Callers still own trusted adapter composition,
implementation identity, runner authentication, observation normalization and
behavioral scenario design. Public support/maturity projections are unchanged.
The next bounded work must supply adapter-owned behavioral scenarios and actual
independent execution before any portable-execution claim.
