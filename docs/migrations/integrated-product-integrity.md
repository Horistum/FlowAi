# Integrated installed-product integrity (AR-05E)

AR-05E validates the composition of the accepted CLI parser, contract distribution,
I/O budgets and atomic artifact publisher. It does not change public versions,
compiler semantics, adapter capabilities or target authorization.

## Installed-process matrix

The existing physical product-isolation job builds `installDist` and `distZip`
without verification sources, moves both distributions and deletes staged contract
inputs. Each distribution runs from both an empty working directory and one with
misleading repository-shaped files. In addition to the 68 existing invocations,
16 composed cases run in each of those four environments (132 invocations total):

- Diagnostics and normalization publish schema/byte-verified manifests to paths with spaces.
- Explicit external contract snapshots compose with options preceding the source,
  equals-form option values, rendering and the positional `--` separator.
- A source whose name starts with `--` remains an authored source after the separator.
- Duplicate options, unknown options and missing values fail before output mutation.
- YAML aliases, excessive nesting, duplicate keys and malformed UTF-8 are rejected.
- A malformed external default Intent cannot fall back to a classpath resource.
- A named pipe with no writer is rejected before opening it, under a process timeout.
- Symbolic-link and regular-file output destinations remain unchanged.
- An incomplete standard bundle cannot produce a persisted verification report.

Every rejection checks its typed diagnostic, message bound, preserved sentinel
bytes and absence of staging directories. Successful publications are independently
checked from their actual files, manifest hashes, declared schemas and versions.
The receipt records each process result and stdout hash. The original target tests
continue to require executable Jenkins output and non-executable GitHub Actions
review evidence.

## Regular-file input boundary

A byte budget cannot prevent opening a named pipe from waiting for a writer.
`BoundedIo.readBytes(File)` now requires a regular file before opening it. Directories,
missing files, FIFOs and devices produce `INPUT_FILE_TYPE`, surfaced by the CLI as
`CLI_INVALID_INPUT` with exit code 2. The explicit `InputStream` overload retains its
streaming semantics. This does not promise time bounds for arbitrary filesystems
or protection against hostile concurrent replacement of an input path.

## Acceptance and closure

The immutable AR-05D acceptance receipt binds independently inspected PR #194 and
actual merged-main evidence. Historical acceptance receipts are preserved byte for
byte. The AR-05E candidate must pass fresh exact-head and synthetic-merge CI, all
physical isolation proofs and full test/conformance inventory comparison.

This implementation does not attest its own future CI or close AR-05. After it is
merged, accept its actual-main result and record independent validation before the
completion transition closes F-03/F-05/F-19/F-22. AR-06 and EF-09 remain inactive.
