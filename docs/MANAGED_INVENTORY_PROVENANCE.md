# Managed inventory collection and provenance (S3.2)

S3.1 proves which workspace bytes supplied a declared operation parameter. S3.2
optionally requires those bytes to belong to a recently collected, signed inventory
for an independently authorized system, scope and original request. The existing
compiler still owns plan meaning and parameter fidelity.

This is an external collection and authoring boundary, not a workflow executor.
A collector signature authenticates its issuer's collection claim. It does not
prove that an upstream service returned uncached data, that the export contains all
resources, or that the remote state remains unchanged when a plan is later used.

## Authority and sequence

1. The operator supplies the original request, a reviewed collector configuration,
   an independent Ed25519 public-key grant and the corresponding protected signing
   key. The authoring provider cannot choose or change these inputs.
2. `prepare` creates a random capture challenge bound to the canonical original
   request, managed system, scope and a bounded validity window.
3. `collect` reserves a new output directory durably, then invokes the configured
   collector exactly once. The collector receives the capture request on stdin and
   only explicitly named credentials. It does not receive the signing key.
4. After a successful bounded response, the collection host checks scope and request
   identity, records the actual process interval, hashes the returned file bytes,
   signs a manifest and atomically publishes a private export.
5. Authoring authenticates that manifest against the independently configured grant.
   It checks every exported file and the entire exported directory structure before
   accepting any observations. The model still chooses directory listings and reads.
   Each delivered observation is also compared directly with the retained signed
   manifest, so a transient substituted read cannot pass just because surrounding
   validation reads saw the original file.
6. The host repeats provenance, age and source checks before provider dispatch,
   after responses, on clarification, and before and after compilation. Expired or
   changed provenance invalidates dependent facts/findings and terminates the run.
   The final binding result retains the post-compilation provenance assessment.

No second semantic evaluator, resource graph, target implementation or model-selected
shell command is introduced. The collector is a transport for source documents;
the same discovery predicates and compiler constraints consume different inventories.

## Operator workflow

The original request is the same JSON document passed to the authoring host:

```sh
python3 tools/managed_inventory.py prepare \
  --request request.json \
  --system managed-platform \
  --scope environments/development \
  --lifetime-seconds 900 \
  --out capture-request.json
```

A collector configuration selects an existing, trusted read-only exporter. All argv
entries are operator-owned, and the executable must be absolute:

```json
{
  "formatVersion": 1,
  "argv": ["/usr/bin/python3", "/opt/organization/export_inventory.py"],
  "credentialEnv": ["INVENTORY_API_TOKEN"],
  "timeoutSeconds": 60
}
```

An independent `trust.json` contains `issuerId`, `keyId`, `publicKey`, `system` and
`scope`. `publicKey` is base64 of the 44-byte Ed25519 SubjectPublicKeyInfo DER public
key. The issuer and key IDs use the existing lowercase investigation identifier
syntax. The system and scope are exact strings, not prefixes or wildcards. Obtain
and review this grant separately from the export: accepting a key from an untrusted
manifest would remove the authentication boundary.

```sh
python3 tools/managed_inventory.py collect \
  --capture-request capture-request.json \
  --collector-config collector.json \
  --trust trust.json \
  --private-key /secure/inventory-collector.pem \
  --max-age-seconds 900 \
  --max-clock-skew-seconds 5 \
  --out /var/lib/horistum/captures/capture-001
```

The destination must not exist. A failed or interrupted collection cannot be retried
in that directory. A new capture is an explicit new operation with a fresh challenge.
The private key must be a single-link regular file with no group/other permissions;
it is never retained in collection output or passed to the collector. OpenSSL with
Ed25519 support is required. The collector process runs without a shell, in an empty
temporary working directory, with a bounded timeout and combined output limit.

On success, stdout contains `inventoryRoot` and `inventory`. Add them to the selected
root of an existing investigation v2 configuration:

```python
configuration["investigation"]["roots"][0]["path"] = collected["inventoryRoot"]
configuration["investigation"]["roots"][0]["inventory"] = collected["inventory"]
```

The existing root ID, human-readable scope label, budgets and caller-owned
`bindingRequirements` remain required. See [S3.1 configuration](SYSTEM_DISCOVERY.md).
The root label is operator prose; the signed `system` and `scope` are separately
validated against the trust grant. The attestation file must be outside every
discovery root. The collection command's output carries the grant supplied by the
operator; it does not discover or approve a new trust key.

## Collector process protocol

Stdin is a canonical JSON capture request with exactly:

| Field | Meaning |
| --- | --- |
| `formatVersion` | Integer `1` |
| `challenge` | Fresh 32-byte random value encoded as 64 lowercase hex characters |
| `requestSha256` | SHA-256 of the canonical original authoring request |
| `system`, `scope` | Independently authorized managed-system identity and scope |
| `issuedAt`, `expiresAt` | UTC timestamps; lifetime is positive and at most one hour |

The collector must independently establish which system and scope it read. Merely
echoing an input scope does not make the source belong to that scope. Its successful
stdout response has these fields:

```json
{
  "formatVersion": 1,
  "captureRequestSha256": "<sha256 of canonical capture request>",
  "system": "managed-platform",
  "scope": "environments/development",
  "sourceRevision": "optional-source-owned-revision",
  "files": [
    {"path": "resources/current.json", "text": "{\"resources\":[]}"}
  ]
}
```

Canonical JSON uses UTF-8, sorted object keys, compact separators, unescaped Unicode,
no non-finite numbers and one final newline. Duplicate JSON keys are rejected.
`sourceRevision` is optional, signed and opaque; it is not a globally monotonic
sequence or an independently verified upstream revision.

Source documents may have different schemas and contain ordinary untrusted text.
There is no domain name switch, prewritten proposal or expected resource address in
this protocol. File paths are normalized relative paths. Traversal, duplicates,
file/directory collisions, symlinks and special files cannot enter the published
export. Empty directories are not part of this file export protocol.

Limits are 128 files, 64 KiB per file and 256 KiB total file content. The configured
collector timeout is at most 120 seconds and the transport caps combined stdout and
stderr at 2,000,000 bytes. Authoring's tighter per-source/listing budgets still apply.
Manifest authentication reads the bounded full export, including files the model
has not requested. This private validation I/O is separate from the existing quota
on observations actually delivered to the provider.

## Signature, freshness and retained evidence

The envelope contains only `formatVersion: 1`, `keyId`, base64 `payload` and base64
`signature`. The canonical signed payload identifies the issuer, key, system, scope,
capture-request digest, collection start/end timestamps, collector-configuration
digest, exact raw-response digest and sorted file paths/hashes/byte counts. It may
include `sourceRevision`. The payload is bounded to 64 KiB and its envelope to 128 KiB.

Ed25519 signs `Horistum managed inventory\0v1\0`, followed by a four-byte big-endian
key-ID byte length, the ASCII key ID and the canonical payload bytes. Operation
observation signatures use another domain and cannot authenticate an inventory.

Freshness is measured from **collection start**, not file modification time or a
new verification timestamp. The host requires a valid capture-request window and
enforces `maxAgeSeconds` (1–3600) and `maxClockSkewSeconds` (0–60). The collection
cannot complete after its request expires. Future or reversed collection intervals
are rejected. Verification also detects a clock rollback beyond the allowed skew
relative to the session's initial check. Host and collector clocks are trusted.

A fresh challenge prevents reuse under a different capture request; it is not a
global nonce registry. Operators must use a new prepared request for a new capture.
A trusted collector can still return cached or inaccurate source data. Neither the
signature nor the observed process interval establishes upstream freshness.

The collection retains its input request/configuration/grant, exact response, signed
attestation, exported files, hash manifest and durable attempt state. The signing key
and credential values are not retained. The collector-configuration digest identifies
configuration bytes; it is not an attestation of executable code identity.

The authoring ledger retains exact raw attestation bytes under `inventoryAttestations`.
Provider context omits these raw envelopes and their file inventory. Each managed root
instead exposes a receipt identifying the issuer, scope, trust/policy/capture hashes,
collection interval and verification deadline. Observations reference its attestation
digest; extracted facts retain their original source attribution. The derived binding
assessment links each bound value to this provenance. The final compiler binding
result includes a new `inventoryValidation` assessment from after compilation.

File mutation, a changed manifest, an extra or missing export entry, a missing source,
or expiry invalidates the entire affected root and its dependent evidence. There is
no in-place refresh or automatic recollection, and restoring old bytes does not revive
an invalidated session. Independently granted roots keep their own provenance.

## Compatibility and remaining boundaries

The optional root `inventory` field has its own version 1 policy. Investigation
protocol v2, ordinary authoring and pinned-source investigation v1 retain their
contracts. Existing v2 sessions without managed provenance keep host version 3;
managed roots produce host version 4. Both can resume under their frozen configuration.
Adding provenance, changing trust or refreshing a capture requires a new session.

Configured providers and collectors are trusted operator infrastructure. The process
boundary does not sandbox them or establish that their credentials are read-only;
the operator must supply an appropriate exporter and least-privilege access. Grant
revocation, production key management, remote source authenticity/completeness, atomic
multi-file snapshots and live-agent generalization remain separate responsibilities.
Accepted authoring evidence is valid at its recorded check, not indefinite execution
permission. No cloud operation, support promotion or S3/AI-A3 closure is claimed.

## Verification

```sh
python3 -m unittest discover -s tools/tests -p 'test_inventory_provenance.py'
python3 tools/verify_managed_inventory.py \
  --cli flow-cli/build/install/flow-core/bin/flow-core
```

The process proof reads an independently populated SQLite source in read-only mode,
collects signed exports and uses the actual installed compiler across infrastructure,
data and credential/policy inventories. Its 12 cases cover accepted bindings,
cross-scope admission rejection, changed exports and expiry after a successful
compilation. The provider is a deterministic protocol fixture, not a live model.
The standard installed-host proof runs this suite in addition to all earlier suites.

Next: independently reviewed live discovery trials with a configured provider and
held-out inputs, keeping the frozen AI-A3 exposure and acceptance gates intact.
