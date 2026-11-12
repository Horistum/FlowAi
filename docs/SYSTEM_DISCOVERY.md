# Scoped workspace discovery and dependent evidence (AI-A3-S3.1)

The external authoring host can explore an operator-granted directory instead of
receiving a preselected list of answer files. A provider chooses bounded directory
listings and file reads from the original request and prior observations. It can
follow references found in those bytes, extract exact JSON values and record
inferences and unknowns before submitting a proposal to the existing compiler.

This is the first S3 slice. It provides discovery operations and evidence validity,
not a general agent framework, provider-specific inventory adapter or execution graph.
The existing request requirements remain independent, frozen and hidden from the
provider. The compiler still owns plan acceptance and target authorization.

## Configuration and migration

Keep the outer host configuration at version 1. Select investigation version 2 to
replace pinned `sources` with nonoverlapping `roots`. Versions cannot be mixed.
Existing configurations without investigation and investigation version 1 retain
their previous contracts and receipts. Discovery uses `bounded-authoring-host/3`
and `authoring-investigation/2`; outcome versions and observer protocols are unchanged.

```json
{
  "investigation": {
    "formatVersion": 2,
    "roots": [
      {
        "id": "environment",
        "path": "/absolute/nonsecret/environment-export",
        "scope": "Selected development environment inventory"
      }
    ],
    "maxSteps": 10,
    "maxSourceBytes": 16384,
    "maxTotalSourceBytes": 131072,
    "maxRecords": 96,
    "maxEntries": 128
  }
}
```

Granting a root authorizes disclosure of selected names and UTF-8 file content below
it to the configured provider. Use a deliberately prepared nonsecret workspace or
inventory export, and place the session output outside every granted root. No file
names or digests need to be chosen in advance. Root paths stay in private retained
configuration; the provider receives root IDs, declared scope and directory identity.
Root identity is pinned at initialization and checked throughout the session.

Merge this member into the [existing host configuration](BOUNDED_AUTHORING_HOST.md).
Use the same `tools/author_proposal.py run` and `resume` commands. Every discovery turn
consumes the existing call, token and cost reservations. No live provider is enabled
implicitly, and the frozen authoring corpus still rejects additional source exposure.

## Observation and extraction protocol

An investigation response requests up to eight observations. Paths are relative to
a granted root; `.` identifies its root directory. The host returns complete bounded
listings or exact file text, with selector, scope attribution, byte count, SHA-256,
observation ID, timestamp and attempt attribution. Repeating an unchanged observation
reuses its retained record and cannot manufacture progress.

```json
{
  "kind": "investigate",
  "observe": [{"kind": "list", "root": "environment", "path": "."}],
  "extractFacts": [],
  "findings": [],
  "unknowns": [{
    "id": "resource-location",
    "question": "Which observed resource belongs to the requested scope?",
    "category": "fact",
    "blocking": true,
    "status": "open",
    "evidenceRefs": []
  }]
}
```

A subsequent `read` can follow a discovered path. `extractFacts` selects an exact
JSON path from an **already delivered** read observation. It cannot provide the
value, invent an observation ID or cite a read requested in the same response.
String path components select object members; integer components select array
indices. Missing members are errors, not an inferred null or proof of absence.

```json
{
  "kind": "investigate",
  "observe": [],
  "extractFacts": [{
    "id": "resource-owner",
    "observationId": "COPY_THE_DELIVERED_OBSERVATION_ID",
    "path": ["resources", 0, "owner"]
  }],
  "findings": [],
  "unknowns": []
}
```

The host publishes `fact:resource-owner` with the exact JSON value, observation ID,
path, attempt and validity. Boolean, numeric, null and structured values remain
distinct. Fact IDs cannot be repointed. JSON must satisfy the existing strict reader,
including duplicate-key rejection; extracted values are limited to 4 KiB.

| Record | Attribution and meaning |
| --- | --- |
| Observation | `host-observed-bytes`: exact selected file bytes or complete directory entries |
| Extracted fact | `host-extracted-json-value`: a value present at a retained JSON path |
| Finding | `model-inferred`: interpretation citing observations, facts or earlier findings |
| Technical unknown | Open or model-addressed, with retained supporting references |
| Decision answer | `caller-answer-not-authenticated`: explicit caller response; never model approval |

A JSON export saying that a resource is ready does not prove provider truth, a valid
signature, freshness of the export's upstream source or current cloud state. Reading
an outcome report as JSON does not reauthenticate it. Existing outcome/observer
assessment remains the authority for authenticated run evidence.

`kind: propose` replaces `observe` with the complete `response` and still includes
`extractFacts`, `findings` and `unknowns`. Open blocking technical unknowns stop before
compilation; caller decisions use the existing clarification/resume boundary.

## Changes and invalidation

Before provider dispatch, after its reply, before compilation and after compilation,
the host reopens retained selectors and compares their content. Capture also checks
for mutation during each read/list. It does not scan or claim knowledge of unread
files. Listings represent names and entry kinds, not the contents of every child.

Changed, missing or newly inaccessible evidence ends the session as `source-changed`.
A new immutable ledger revision marks the changed observations, dependent extracted
facts and transitive findings as `invalidated`. Dependent technical unknowns reopen;
unaffected records keep their provenance and validity. Earlier ledger revisions are
retained. Restoring old bytes cannot revive an already invalidated ledger.

The terminal session cannot dispatch another provider call, resume into new evidence
or accept a proposal. Start a new investigation with independently retained inputs.
If change occurs during compiler execution, compiler output may be retained for
inspection, but the session is not accepted. Consumers must check the terminal session
status, not a convenient intermediate compiler result.

This detects observed changes at validation boundaries. It is not an atomic snapshot
of several live files, continuous monitoring, or a guarantee against changes after
acceptance or a malicious storage owner restoring data between checks.

## Bounds and verification

There are at most eight roots, 16 investigation steps, eight observations per action,
16 fact extractions per action and 128 total observation/fact/finding/unknown records.
Limits also bound paths, directory entries, per-source bytes, total retained bytes
and the complete ledger. Over-limit listings fail instead of truncating evidence.
Descriptor-relative traversal rejects symlinks in roots and paths, special files and
multiply linked regular files. The host exposes no shell, network or write operation.
These are host access boundaries, not an OS sandbox for the operator-configured
provider executable or protection from hostile mounts/storage administrators.

```sh
python3 -m unittest discover -s tools/tests -p 'test_authoring_discovery.py'
./gradlew :flow-cli:installDist
python3 tools/verify_system_discovery.py --cli flow-cli/build/install/flow-core/bin/flow-core
```

The installed proof uses a deterministic process agent that knows a small JSON
inventory shape. It receives no source filenames, expected address, proposal template
or independent requirements. Random filenames, two layouts and infrastructure/data/
credential inventories exercise request selection, listing, dependency reads, typed
extraction and proposal compilation. Twenty-four variants cover acceptance, substituted
addresses, unresolved prerequisites and changed source evidence. These fixtures measure
the mechanism and compiler integration, not live-model reasoning or prompt-injection
resistance. All twelve installed-host suites run in both full CI jobs.

Next: managed-inventory observation contracts with explicit upstream provenance and
freshness, and independently reviewed live-agent discovery across unfamiliar formats.
S3, AI-A3, AR-06/AR-07 and F-16/F-20 remain open; EF-09 remains paused.
