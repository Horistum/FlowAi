# AR-06L candidate: authenticated adapter certification portfolio

The runtime workflow now produces one inspectable reference adapter portfolio from
all ten existing Jenkins assessments. Every AR-06 construct category plus native
checkout is listed for each reference adapter. Each covered pair references its
bounded scenarios; missing evidence stays explicit. Scenario identity, adapter
version and implementation digest, runtime prerequisites and all baseline/mutant
observations remain intact in the JSON output.

A dependent CI job uses producer-owned proof/trust hashes from the same workflow
run. It reconstructs compiler-bound inputs and native observations, checks signatures,
reruns admission and compares regenerated views/matrices against published bytes.
It rejects incomplete inventories, stale revisions, source/artifact drift,
substituted keys/signatures and modified runtime results. Serialized success reports
are never admission authority. Limits and symlink checks bound file ingestion;
output publication occurs only after all inputs pass.

This completes the aggregate diagnostic view missing from AR-06K. It does not
promote public support or maturity, merge separate adapter versions into general
certification, or establish portable execution. The current runtime catalog still
covers Jenkins only. Core semantics and concrete renderers are unchanged.

## Predecessor boundary

PR #208 merged as `c6149ff9ba87f88e25cdc0905cfb86cb935f7756`.
PR #208 exact head `c4296223218b049a605fb49ea96054d440e889f7` and merge
candidate `3ccbfb275e186445febe5974dcbd9edc1d12585e` both passed 1,987
tests in 343 suites and 274 ordered conformance checks in CI 38024240411.
Their entire source tree equals merged main (`333c177f4b6295e3498ad5cb9baee8bf63a3ed46`).
Main CI 38038690853 passed all four physical isolation proofs (769 input hashes);
its compile/test job was still running when this immutable receipt was created
and is not claimed as passed. Main runtime CI 38038690752 passed all six jobs.
Independently verified archives contain ten scenarios, 32 valid Ed25519
observations and ten behavior matrix pairs. Seven main archives and four PR
validation archives matched GitHub SHA-256 metadata and their exact revisions.

The immutable baseline receipt is
`.flow-agent/evidence/adapter-certification-portfolio-baseline.json`
(SHA-256 `88f7114db5cb1e52a81ddb02c645cfe77fcb74cbed13c151d41a6019b51a3f94`). It binds the AR-06K to AR-06L
selection transition while preserving all historical receipts.

## Validation

All 161 Python tooling tests and structure/context checks passed locally. Both offline and online
Gradle attempts cannot resolve the pinned Kotlin 2.4.10 plugin in this environment;
no local Kotlin pass is claimed. The exact published head and synthetic merge
candidate require their own full CI, conformance, physical isolation and all six
runtime jobs plus the new dependent portfolio job. Candidate CI results belong in
the PR; this source does not claim its own future validation.

## Remaining scope

AR-06 remains active. Equivalent scenarios on another execution model and remaining
behavioral coverage precede any broad support declaration. AR-07 stays planned,
EF-09 paused and F-20 AR-07-owned. Package and public contract versions are unchanged.
