# AR-06Z — shared literal condition certification

PR #222 merged into `b54b9eb954437b7e6fd7a17d605f78461a0d5961`, preserving its independently verified tree. The immutable baseline records the prior PR receipts and the separately observed main workflow state. The maintainer authorized the next roadmap slice.

## Candidate behavior

Two identical Flow Source fixtures, `if true` and `if false`, each guard one checkout of the existing immutable public repository revision. Both providers must preserve identical source and canonical graph bytes. Jenkins executes complete generated Jenkinsfiles; GitHub Actions executes the generated job guard and checkout steps inside an exact checked envelope. No input, else, nested condition, join or downstream dependency is included.

The true fixture has omitted-checkout and inverted-condition mutants. The false fixture has flattened-condition and inverted-condition mutants. Each mutant must differ observably from its baseline. The independent oracle checks actual Git revision and fixture-file SHA-256. Jenkins also records native checkout counts and errors. GitHub retains real job results: true/inverted and false/baseline must be skipped with no outputs, while all four other jobs must succeed with measured workspace observations.

A successful preflight binds compiler output, the exact executed workflow, observer code and native job inventory before native execution. A separate collector establishes its ephemeral Ed25519 key before admitting same-workflow outputs; native jobs never receive that key. Every signature binds the preflight challenge, source/workflow revisions, execution-envelope digest and raw job-result digest (including executed runner images). The key stays in memory and is never uploaded. Runtime hosts, GitHub job outputs and the collector remain trusted; signatures do not prove their honesty.

The portfolio replays all 34 assessments and 116 signatures, retains all previous scenarios, and compares both new condition pairs independently. This adds bounded GitHub condition evidence while keeping the registry's partial condition support and every public-support/portable-execution flag unchanged. GitHub's condition catalog entry is plan scoped.

## Validation gate

Fresh exact-head and synthetic-merge Flow CI, every native producer, both condition producers and complete portfolio replay are required. Counts above describe the required candidate inventory, not completed CI receipts. Exact current-revision results will be recorded in the PR after execution.

Protocol tests cover source/graph identity, changed guards and observation envelopes, missing/failed/cancelled/unexpectedly skipped jobs, malformed or stale outputs, wrong immutable oracles, signatures and historical lifecycle tampering. Unit fixtures are synthetic and never stand in for provider execution.

AR-06 remains active. AR-07 remains planned, EF-09 paused and F-20 owned by AR-07. No findings, milestone, package version or generic support are promoted by this candidate.

The reference distribution now composes `GitHubLiteralConditionProjectionScope`: executable capability evidence is granted only to an authorized plan containing a single literal condition and a single checkout, without inputs, else/nested branches, workflow handlers or successors. The registry itself stays partial; missing provider evidence fails closed.
