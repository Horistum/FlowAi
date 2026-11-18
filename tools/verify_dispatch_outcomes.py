"""Installed proofs shared with the dispatch fixture to reuse the same actual effects."""
import base64
import os
import subprocess
import sys

from authoring_host.contracts import canonical, loads, read, require


def outcome_settings(root, work):
    policy_path = work / "outcome-policy.json"
    policy_path.write_bytes(read(root / "conformance/planning-dispatch-outcomes/data-copy.policy.json"))
    key = work / "independent-audit.pem"
    subprocess.run(["openssl", "genpkey", "-algorithm", "ED25519", "-out", str(key)], check=True, capture_output=True)
    public = subprocess.run(["openssl", "pkey", "-in", str(key), "-pubout", "-outform", "DER"], check=True, capture_output=True).stdout
    trust_path = work / "audit-trust.json"
    trust_path.write_bytes(canonical(dict(formatVersion=1, grants=[dict(keyId="independent-audit", publicKey=base64.b64encode(public).decode(),
        scopes=["storage-test"], resourceTypes=["dataset"], kinds=["RESULT", "OWNERSHIP", "STATE"])])))
    return dict(policy=str(policy_path), trust=str(trust_path)), key


def verify_outcomes(root, work, case, database, key, variant, cli, command):
    if variant in ("crash-after-commit", "unauthorized"):
        command([*cli, "request"], 2)
        return [dict(case=variant + "-no-outcome-request", exitCode=2)]

    request = command([*cli, "request"], 0)
    require(b"retained-data" not in canonical(request) and b"data-team" not in canonical(request),
            "Observer received the expected result")
    observer = [sys.executable, str(root / "tools/tests_worker/dispatch_outcome_observer.py"), "--database", str(database), "--key", str(key)]
    def capture(request, path):
        path.write_bytes(subprocess.run(observer, input=canonical(request), capture_output=True, check=True, timeout=10).stdout)
        return path

    rows = []
    def assess(name, observations, code, diagnostic):
        output = case / name
        args = [*cli, "assess", "--observations", str(observations), "--out", str(output)]
        command(args, code)
        report = loads(read(output / "compiler/operation-outcome-review.json"))
        require(report["results"][0]["code"] == diagnostic and not report["executionAuthorized"], "Wrong outcome evidence")
        rows.append(dict(case=name, state=report["state"], diagnostic=diagnostic))
        return args

    observations = capture(request, case / "signed-outcomes.json")
    if variant == "fresh":
        args = assess("independent-copy", observations, 0, "OUTCOME_SATISFIED")
        original = read(case / "independent-copy/outcome.json")
        # An invalid Java installation would fail any compiler invocation.
        recovered = subprocess.run(args, env={**os.environ, "JAVA_HOME": str(case / "absent-jdk")},
                                   timeout=60, capture_output=True)
        require(recovered.returncode == 0 and read(case / "independent-copy/outcome.json") == original, "Outcome recovery changed the receipt")
        rows.append(dict(case="independent-copy-recovery", exitCode=0))
        changed_policy = work / "outcome-policy.json"
        policy = read(changed_policy)
        changed_policy.write_bytes(policy + b" ")
        try:
            command([*cli, "request"], 2)
        finally:
            changed_policy.write_bytes(policy)
        rows.append(dict(case="changed-original-outcome-policy", exitCode=2))
        salt = case / "dispatch/outcome-nonce-salt.json"
        saved_salt = read(salt)
        salt.write_bytes(canonical("0" * 64))
        try:
            command([*cli, "request"], 2)
        finally:
            salt.write_bytes(saved_salt)
        rows.append(dict(case="changed-runtime-commitment", exitCode=2))
        missing = capture(dict(request, decisionSha256="0" * 64), case / "uncorrelated.json")
        assess("absent-audit-event", missing, 3, "OUTCOME_OBSERVATION_MISSING")
    else:
        assess("reported-completion-wrong-data", observations, 4, "OUTCOME_POSTCONDITION_FAILED")
        assess("another-dispatch-signature", work / "fresh/signed-outcomes.json", 3, "OUTCOME_OBSERVATION_CONTEXT")
    return rows
