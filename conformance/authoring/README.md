# Request-fidelity corpus

These are manually authored deterministic fixtures, not recorded model responses. A repository test oracle authored the requirements before constructing the candidate operation definitions. The expectations and citations are reviewable in each `.requirements.json`; `corpus.json` pins their exact bytes. Reviewers must approve expectation changes explicitly in the PR. A digest catches drift; it does not authenticate a human or prevent an authorized maintainer from revising the oracle.

| Domain | Specified | Ambiguous | Explicit prohibitions | Unsupported |
| --- | --- | --- | --- | --- |
| Deployment | `deployment-specified` | `deployment-ambiguous` (held out) | `deployment-prohibitions` | `deployment-atomic` (held out) |
| Backup/restore | `backup-specified` | `restore-ambiguous` (held out) | `backup-prohibitions` (held out) | `backup-atomic` |
| Certificate maintenance | `certificate-specified` (held out) | `certificate-ambiguous` | `certificate-prohibitions` | `certificate-trust` (held out) |

There are six development and six held-out cases. Held-out here means reserved from future candidate-model prompt examples and tuning; these public fixtures are not a secret benchmark. AI-A3 must preserve the split, record any exposure/contamination and distinguish deterministic fixture tests from real-model evaluation. Neither split has been evaluated with a live model.

Every domain has four request categories. Specified cases and check-only prohibition cases can satisfy the confirmed requirements. Ambiguous cases use provisional expectations and explicit clarification. Unsupported cases preserve cross-resource atomicity or external trust requirements. The deployment prohibition case preserves the original existing-image/approval/failure-only-notification request and stays unsupported because operation-specific failure notification cannot currently be represented. Its candidate's unconditional notification must not be accepted as equivalent.

`RequestFidelityCorpus` compares capability multiplicity, workflow partitioning and dependency endpoints against all registered scenario-pack examples after discarding names and literal values. At least six cases must remain structurally distinct. It also recompiles controlled mutations (changed digest, broadened environment, removed verification, late approval and invented build) and requires their independent constraint to fail. Frontend tests additionally cover approval in another workflow, identifier renaming, dynamic parameters, stale request context/mode, provisional model extraction, malformed inputs and captured-source mutation. CLI tests cover publication and rendering gates.

The pinned requirements are the authoritative oracle for these fixtures. Candidate reports with empty questions or high confidence cannot rewrite that oracle. A requirement result is bounded by its explicit typed semantics; matching source quotes and a passing subset do not establish completeness of natural-language interpretation. See [the contract and limits](../../docs/REQUEST_FIDELITY_REVIEW.md).
