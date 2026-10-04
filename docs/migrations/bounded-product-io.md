# Bounded product inputs and outputs (AR-05C)

The installed product now applies one fixed `InputLimits` policy. Limits cannot be
raised by command-line options or environment variables. Byte limits count UTF-8
bytes, including whitespace, JSON escaping and the final artifact newline.

| Boundary | Maximum |
| --- | --- |
| Source file, inline text, contract or adapter witness | 8 MiB |
| Source capture / contract UTF-8 | Strict; malformed input is rejected |
| JSON/YAML nesting | 64 levels |
| JSON/YAML tokens; Flow lexer tokens including EOF | 200,000 |
| Entries in each JSON/YAML map or sequence | 10,000 |
| Contract string / field name / number length | 1,048,576 / 1,024 / 128 characters |
| YAML alias references | 0 |
| Flow statement nesting | 128 levels |
| Files per resource snapshot or descriptor directory; CLI arguments | 256 |
| Selected resource snapshot / descriptor catalog bytes | 32 MiB |
| CLI arguments combined | 8 MiB |
| Encoded artifact or presentation item | 8 MiB |
| Complete artifact batch / CLI presentation | 32 MiB and 256 items |
| CLI diagnostic message and echoed command | 2,048 characters each |

Directory enumeration also counts ignored entries, so a large irrelevant directory
cannot bypass the file-count budget. Resource snapshots bound the complete selected
inventory before loaders process it. Separate library catalog loads use their own
aggregate budget. Existing strict parser validation, duplicate detection and alias
rejection remain enabled. `ContractReadLimits` is a compatibility view of the same
central policy. Core and compiler modules acquire no filesystem dependency.

Reads consume at most the selected byte limit plus one sentinel byte, rather than
trusting file length metadata. All product source entry points enforce limits before
parsing; source receipts continue to hash the original captured bytes. Serialization
writes to a bounded stream and stops before an overflowing write. CLI artifact batches
encode and validate every entry before creating a destination or replacing a file.
Output-byte rejection therefore leaves existing output files unchanged.

I/O budget failures produce `CLI_LIMIT_EXCEEDED` and exit 2, with a small diagnostic
presentation and no artifact receipt. Existing parser grammar and complexity errors
retain their strict contract diagnostics and exit 2. UTF-8 errors are invalid input.
The product does not silently truncate inputs, generated artifacts or source evidence;
only displayed error messages and echoed command names are shortened.

This slice does not provide rollback for filesystem failures or atomic publication.
AR-05D owns staging, actual-byte integrity receipts and atomic publication; AR-05E owns
independent integrated closure. Verification-host release assembly is outside this
installed-product slice. No finding is closed and no adapter support is promoted here.
