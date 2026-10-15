# AR-06AB — Jenkins secret condition binding certification

PR #224 merged into `dbeb803267eec8a5f2dc4d1203534ff48d5b8f5e`, preserving its independently verified tree. The immutable baseline keeps exact PR receipts and separately observed main workflow status.

## Production correction

Secret expressions previously read the authored name directly from `env`, while Jenkins credential bindings used `FLOW_SECRET_` names. The renderer now uses one mapping for both. Condition secrets are collected from parsed expression nodes, so both supported expression spellings retain identities and literal text resembling a secret reference cannot manufacture a credential binding. The combined identities cross the existing collision gate. Other adapters keep their existing collection contract.

## Bounded native evidence

One compiler-rendered condition compares two named Jenkins string credentials and checks non-nullness before a local native checkout. The disposable controller provisions equal random values for the two identities and a distinct third value. The original Jenkinsfile executes unchanged. Omitted-binding and substituted-binding artifacts must skip checkout. Independent native observations retain checkout count, workspace marker and persisted string-binding identities/environment variables. Complete build logs are scanned within a one-MiB budget for the exact fixture canaries; a leak fails before the log is archived. Values never enter source artifacts, oracle bytes, signatures or published views.

The existing pinned disposable Jenkins image and credential-plugin versions are recorded as prerequisites; this is functional evidence, not a plugin security assessment. The controller has no external network, published ports, user credentials or production jobs. Runtime hosts and transport remain trusted.

The authenticated matrix categorizes this one scenario as SECRETS and the complete portfolio replays it alongside every predecessor. Publication becomes 35 assessments, 119 signed runs and 13 bounded target/construct pairs out of 72 declared rows, only after all native gates pass.

## Validation and limits

Fresh exact-head and synthetic-merge Flow CI, all native producers and complete authenticated portfolio replay are mandatory. Current results belong in the PR after execution. Unit tests use synthetic signed fixtures and do not replace native evidence.

No general secret masking, exfiltration resistance, authorization, rotation, external secret store, least-privilege lifetime, cross-job secret transfer or value/state continuity is certified. No generic support, public syntax/version, historical acceptance, portable-execution or finding closure changes. AR-06 remains active; AR-07 planned, EF-09 paused and F-20 AR-07-owned.
