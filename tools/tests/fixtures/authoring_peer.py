"""Deterministic process peers for host control-flow tests; never live model evidence."""
import hashlib
import json
import os
from pathlib import Path
import sys
import time


def compiler():
    if sys.argv[2:] == ["catalog"]:
        print('===== FLOW STANDARD INTENT CATALOG =====\n[{"capability":"VERIFY"}]')
        return 0
    args = sys.argv[2:]
    data = Path(args[1]).read_bytes()
    proposal = json.loads(data)
    requirements = json.loads(Path(args[args.index("--requirements") + 1]).read_bytes())
    out = Path(args[args.index("--out") + 1])
    out.mkdir()
    response = proposal["response"]
    marker = response.get("normalizedIntent", {}).get("marker", "reject")
    code, semantic, fidelity, stage = 0, "accepted", "requirements-satisfied", "CANONICALIZATION"
    if marker in ("reject", "reject-again"):
        code, semantic, fidelity, stage = 4, "rejected", "not-evaluated", "INTENT_VALIDATION"
    elif marker == "violate":
        code, fidelity, stage = 4, "violated", "REQUEST_FIDELITY"
    elif marker == "false-success":
        fidelity = "unassessed"
    elif any(i["check"]["kind"] == "unsupported" for i in requirements["requirements"]):
        code, fidelity, stage = 4, "unsupported", "REQUEST_FIDELITY"
    elif requirements["confirmation"] != "CONFIRMED":
        code, fidelity, stage = 3, "review-required", "REQUEST_FIDELITY"
    elif "--render" in args:
        code, stage = 3, "MATERIALIZATION"
    report = {"formatVersion": 2, "exitCode": code, "stage": stage, "semanticAcceptance": semantic,
              "requestFidelity": fidelity, "inputSource": {"sha256": hashlib.sha256(data).hexdigest()},
              "reviewItems": [], "diagnostics": [] if code != 4 else [
                  {"code": "TEST_COMPILER_REJECTION", "field": "$.response.normalizedIntent", "message": "Invalid test intent"}],
              "renderAuthorized": False, "targetDisposition": "review_only" if "--render" in args else "not-requested"}
    (out / "proposal-review.json").write_text(json.dumps(report))
    return code


def provider():
    request = json.load(sys.stdin)
    source, counter = Path(sys.argv[2]), Path(sys.argv[3])
    index = int(counter.read_text()) if counter.exists() else 0
    counter.write_text(str(index + 1))
    cases = json.loads(source.read_text())
    value = cases[min(index, len(cases) - 1)]
    if value == "hang":
        time.sleep(20)
    if value == "large":
        print("x" * 2_000_001)
        return 0
    if value == "fail":
        print("provider private diagnostic", file=sys.stderr)
        return 9
    if value == "invalid":
        print("not-json")
        return 0
    if value == "leak":
        secret = os.environ["TEST_API_KEY"]
        print('{"escaped":"' + ''.join('\\u%04x' % ord(c) for c in secret) + '"}')
        return 0
    if value == "environment":
        assert "UNRELATED_API_KEY" not in os.environ
        assert os.listdir() == []
        assert os.environ["HOME"] == os.getcwd()
        value = {"marker": "accept"}
    usage = value.pop("usage", {"input_tokens": 10, "output_tokens": 20})
    identity = value.pop("provider", {"id": "test-provider", "model": request["model"], "request_id": str(index)})
    result = value.get("response", {"normalizedIntent": value, "report": {"openQuestions": []}})
    print(json.dumps({"schema": "command-reasoning/v2", "result": result, "usage": usage, "provider": identity}))
    return 0


if __name__ == "__main__":
    raise SystemExit(compiler() if sys.argv[1] == "compiler" else provider())
