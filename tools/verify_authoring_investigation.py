#!/usr/bin/env python3
"""Two-domain investigation proof with the installed compiler and a deterministic peer."""
import argparse
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile

from authoring_host.contracts import canonical, digest, loads, read, require, verify_inventory
from authoring_host.session import start
from authoring_host.investigation import load


def verify(root, cli):
    root, cli = Path(root).resolve(), Path(cli).resolve()
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    rows = []
    with tempfile.TemporaryDirectory(prefix="flow-investigation-proof-") as temporary:
        work = Path(temporary)
        source, counter, replies = work / "source.json", work / "counter", work / "replies.json"
        config = {"formatVersion": 1, "provider": {"id": "test-provider", "model": "fixture-only", "version": "peer/1",
            "evidenceKind": "deterministic-test", "credentialEnv": [],
            "argv": [sys.executable, str(root / "tools/tests/fixtures/authoring_peer.py"), "provider", str(replies), str(counter)]},
            "compiler": {"argv": [str(cli)], "commit": commit, "contracts": str(root)},
            "budget": {"maxInputTokensPerCall": 150000, "maxOutputTokensPerCall": 8192, "maxTotalTokens": 474576,
                       "maxCostMicrousd": 1000000, "inputMicrousdPerMillionTokens": 1000000,
                       "outputMicrousdPerMillionTokens": 4000000, "timeoutSeconds": 120},
            "investigation": {"formatVersion": 1, "sources": [], "maxSteps": 2, "maxSourceBytes": 4096,
                              "maxTotalSourceBytes": 4096, "maxRecords": 16}}
        # These authoring inputs remain unchanged in the frozen corpus. This is a separate
        # transport/compilation experiment, never a held-out quality measurement.
        for case, capability, parameter, forbidden in (
                ("deployment-specified", "DEPLOY", "image", "BUILD_IMAGE"),
                ("backup-prohibitions", "VERIFY", "resource", "RESTORE")):
            proposal = loads(read(root / f"conformance/authoring/{case}.proposal.json"))
            requirements = loads(read(root / f"conformance/authoring/{case}.requirements.json"))
            if case == "deployment-specified":
                proposal["request"]["userText"] += " Do not build another image."
                requirements["request"] = copy.deepcopy(proposal["request"])
                requirements["requirements"].append({"id": "no-build", "sourceQuote": "Do not build another image",
                    "check": {"kind": "forbid-capability", "capability": forbidden}})
            template = copy.deepcopy(proposal["response"])
            step = next(s for s in template["normalizedIntent"]["workflows"][0]["steps"] if s["capability"] == capability)
            expected = step["params"][parameter]
            step["params"][parameter] = {"fromObservation": "value"}
            for variant in ("matching", "conflicting", "forbidden"):
                source.write_bytes(canonical({"value": expected if variant != "conflicting" else "unexpected-resource"}))
                config["investigation"]["sources"] = [{"id": "configuration", "path": str(source),
                    "sha256": digest(source.read_bytes()), "scope": case + " selected local configuration"}]
                candidate = copy.deepcopy(template)
                if variant == "forbidden":
                    extra = ({"id": "forbidden", "capability": "BUILD_IMAGE", "params": {"image": "unexpected"}, "requires": []}
                             if forbidden == "BUILD_IMAGE" else
                             {"id": "forbidden", "capability": "RESTORE", "params": {"subject": "ledger",
                              "recoveryPoint": "archive-store"}, "requires": []})
                    candidate["normalizedIntent"]["workflows"][0]["steps"].append(extra)
                replies.write_bytes(canonical([{"observationTemplate": candidate}]))
                counter.unlink(missing_ok=True)
                directory = work / (case + "-" + variant)
                state = start(config, canonical(proposal["request"]), canonical(requirements), directory)
                verify_inventory(directory, state)
                require(len(state["attempts"]) == 2 and state["investigationSteps"] == 1,
                        "Investigation did not use the shared bounded read/propose cycle")
                ledger = load(directory, state)
                require(len(ledger["observations"]) == 1 and ledger["findings"][0]["attribution"] == "model-inferred",
                        "Missing observation or incorrect finding authority")
                emitted = loads(read(directory / "attempt-001/proposal.json"))
                supplied = next(s for s in emitted["response"]["normalizedIntent"]["workflows"][0]["steps"]
                                if s["capability"] == capability)["params"][parameter]
                require(supplied == loads(source.read_bytes())["value"], "Proposal did not consume observed data")
                wanted = "accepted" if variant == "matching" else "fidelity-violation"
                require(state["status"] == wanted, "Evidence bypassed or failed independent compiler/fidelity review")
                prompt = read(directory / "attempt-001/provider-request.json")
                require(requirements["reviewerId"].encode() not in prompt and b'"requestFidelity"' not in prompt,
                        "Independent reviewer expectations leaked to the provider")
                rows.append({"case": case, "variant": variant, "status": state["status"]})
    print(json.dumps({"status": "PASS", "evidenceKind": "deterministic-test", "investigations": rows,
                      "liveModelEvaluated": False, "targetSupportPromoted": False}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    parser.add_argument("--cli", required=True)
    args = parser.parse_args()
    verify(args.root, args.cli)
