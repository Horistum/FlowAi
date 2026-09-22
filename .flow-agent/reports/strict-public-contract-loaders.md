# AR-04E implementation review

Baseline: `Horistum/FlowAi` main `b51aca23ff6765bb407211981aa7f8fc88e103ac`,
tree `5976bca42b0961a87858e57127bcd5093aea6fcc`.

## Confirmed defects and corrections

- `FlowYaml.read` selected a mapper that ignored unknown properties and did not
  enable duplicate detection. Every public YAML method now uses strict binding.
- YAML/JSON readers could stop after the first document; duplicate and scalar
  policies differed between corpus JSON, bundle JSON and the shared JSON mapper.
  Production JSON reads now use `FlowJson`, with the same policy as YAML.
- Parsing had no shared full-document token budget. A bounded streaming pass now
  rejects excess depth, tokens, strings, names, numbers and UTF-8 bytes before
  object binding; public file readers use bounded capture and strict UTF-8.
- YAML aliases could be presented as literal anchor names. References now fail
  explicitly; quoted literals and the trigger loader's literal-map templates remain.
- Intent frontend capture now applies the same byte budget before recording its
  digest. General Flow source and output I/O remain AR-05 scope.

The existing Intent, module, notes, target-registry and adapter-evidence vocabulary
validators remain authoritative. No semantic-kernel serialization dependency,
second semantic model, support promotion or version change is introduced.

## Predecessor acceptance

PR #185 exact head `d379a45f489f330d062a61c0d3825e781bc3ffd6`, synthetic merge
`20c8fb23343a0c5490ffc13221890b72722155d5`, and actual merged main share the
same source tree. Flow CI 35191069466 and post-merge 35193433327 passed.
Each of the three inspected JUnit archives contains 1,707 unique tests in 299
suites, with zero failures, errors or skips. Each conformance log contains the
same ordered 253 passing checks. All input hashes from the four PR and
post-merge physical isolation proofs match the accepted source tree.

The complete inspected receipt and artifact hashes are in
`.flow-agent/evidence/semantic-identity-acceptance.json`.
Historical A/B/C receipts and test identities remain intact. D's old lifecycle
tests use a frozen implementation-phase work package; E has its own progression
and tamper tests. Whole-AR-04 completion remains pending for AR-04F.

## Validation

151 Python tooling tests, Flow Agent structure validation and context generation
passed locally. Local source bytes were verified against the upstream Git tree.
The container has no project JDK 25/Gradle cache; no local Kotlin or offline
Gradle success is claimed. Current-head and synthetic-merge compilation, full
tests, installed conformance and physical isolation must pass the existing Flow CI.
The actual candidate SHA and outcomes belong in the PR after execution, never
in a self-certifying future receipt.

## Existing branch audit

All comparisons below use the baseline above. Ahead/behind counts are commit
ancestry, not claims that an entire feature was accepted.

| Branch | Exact head | Ahead / behind | Review |
| --- | --- | --- | --- |
| `agent/ar-04c-system-identity` | `dc3cfab1ec2e16ff315f6af0b8f3a7c1ef62707c` | 0 / 10 | Historical baseline only; current C implementation is already in main via #184. |
| `agent/local-inputs-language-integrity` | `b581e82e61dccccd2deee1ee165cf3aa2037e8df` | 1 / 33 | Source/build-input transport workflow only; no missing product feature. |
| `agent/source-integrity-input-sync` | `d7c01592123df4061bec7754f027231b96761442` | 1 / 32 | Input transport workflow only. |
| `validation/semantic-identity-source-20260917` | `18968e2203b5b809bbf3a607407fd36c42d4bd8e` | 1 / 2 | Pinned source export only; not a successor implementation. |
| `claude/adoring-faraday-w9acat` (#182) | `18d01dae490d47b6fbedd9714ede0759ddc393d0` | 2 / 10 | Analysis/proposed remediation, no product implementation. Findings predate #183–185; proposed roadmap fragments must be reconciled against current state before merge. |
| `agent/ref-01-git-opts-planner` | `e2f6540db01c402683d030d5f3aaf2ce3079a39e` | 3 / 10 | Separate GitOptsIntent/Plan and planner; it does not consume the canonical compiler service and therefore cannot be claimed as the normative canonical reference. Its private YAML mapper also bypasses shared scalar/trailing/complexity rules. |
| `example/git-opts-planner-reference` | `c76f6a3daaacf18a31d6817ef6779dd9071280ca` | 4 / 10 | Uses canonical Intent and external modules, but only its compiler-level test is present. The shell bootstrap and actual installed CLI path need independent verification. |

The two GitOps branches are alternative reference designs, not two completed
roadmap goals. The external module/Intent direction preserves the established
compiler axis. The standalone planner's documentation calling it normative
overstates the evidence; an acyclic operation DAG does not validate the required
checkout/branch/change/commit/publish ordering for caller-constructed plans.

Concrete external-example defects found in source:

1. `run.sh` captures all stdout from `resolve_binary`, including a Gradle
   bootstrap's output, and subsequently treats it as one executable filename.
2. `rm -rf "$HERE/$OUT_DIR"` accepts traversal and special output paths before
   the CLI succeeds; this can remove input or unrelated files.
3. `verify.py` uses Python `assert`, so `python -O` removes its validation.
4. Building `by_source` as a dictionary hides duplicate source identities;
   subset-only checks permit unexpected extra tasks/capabilities.

These reference issues are tracked separately from AR-04E acceptance; the
architecture-recovery milestone is not closed by merging an example or analysis.
