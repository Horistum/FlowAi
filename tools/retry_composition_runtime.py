"""Run source-derived retry composition obligations on disposable native Jenkins."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import uuid

from condition_composition_oracle import bind_graph, jenkins_envelope, receipts, require, sha
from retry_composition_oracle import bind_control_structure, expectation, verify_observations

ABSENT = "0" * 40


def bind(expected, manifest, graph):
    bind_graph(expected, graph)
    bind_control_structure(expected, graph)
    leaves, policies = [], []
    def visit(steps):
        for step in steps:
            require(step["materialization"]["status"] == "NATIVE", "Unmapped native work")
            if step["type"] == "action":
                leaves.append(step)
            elif step["type"] in ("condition", "retry"):
                if step["type"] == "retry":
                    policies.append(dict(step["params"], max=int(step["params"]["max"])))
                visit(step["children"])
            else:
                raise ValueError("Unexpected manifest control")
    for job in manifest["jobs"]:
        visit(job["steps"])
    mapping = {s["id"]: s["metadata"]["resultName"] for s in leaves}
    require(len(mapping) == len(leaves) == len(set(mapping.values())) and set(mapping.values()) == set(expected["tasks"]),
            "Native identities differ from source")
    for step in leaves:
        action = expected["tasks"][mapping[step["id"]]]
        require((step["module"], step["action"]) == (action["module"], action["action"]) and
                all(v["type"] == "StringLiteral" for v in action["params"].values()) and
                step["params"] == {k: v["value"] for k, v in action["params"].items()}, "Native leaf parameters differ")
    require(policies == expected["policies"], "Native retry policies differ")
    return mapping


def replace_once(text, before, after):
    require(text.count(before) == 1, "Ambiguous native mutation")
    return text.replace(before, after)


def prepare(inputs, output):
    contracts, variants, mappings = {}, {}, {}
    for outcome in ("success", "failure"):
        directory = inputs / outcome
        document = json.loads((directory / "source.ast.json").read_text())
        initial = expectation(document, {"enabled": True})
        failures = {name: None for name, action in initial["tasks"].items() if action["params"]["branch"]["value"] == ABSENT}
        require(bool(failures) == (outcome == "failure"), "Fault declaration differs from source")
        initial = expectation(document, {"enabled": True}, failures)
        mapping = bind(initial, json.loads((directory / "manifest.json").read_text()),
                       json.loads((directory / "canonical-graph.json").read_text()))
        generated = (directory / "Jenkinsfile").read_text()
        baseline, calls = jenkins_envelope(generated, mapping)
        by_name = {mapping[key]: value[1] for key, value in calls.items()}
        candidates = {"baseline": baseline}
        if outcome == "failure":
            candidates["flattened-outer"] = replace_once(baseline, "retry(2) {", "retry(1) {")
            candidates["flattened-inner"] = replace_once(baseline, "retry(3) {", "retry(1) {")
            candidates["excess-attempts"] = replace_once(baseline, "retry(3) {", "retry(4) {")
        else:
            candidates["omitted-prefix"] = replace_once(baseline, by_name["prefix"], "")
            candidates["omitted-tail"] = replace_once(baseline, by_name["bodyTail"], "")
            candidates["repeated-success"] = replace_once(baseline, "retry(2) {", "for (def flowReplayProbe in [1, 2]) {")
        for enabled in ((True, False) if outcome == "success" else (True,)):
            case = f"{outcome}-{'true' if enabled else 'false'}"
            contracts[case] = expectation(document, {"enabled": enabled}, failures)
            mappings[case] = mapping
            for name, text in candidates.items():
                if not enabled and name != "baseline":
                    continue
                variants[f"{case}-{name}"] = {"case": case, "variant": name, "text": text}
    artifacts = output / "artifacts"
    artifacts.mkdir()
    inventory = []
    for name, variant in variants.items():
        file = name + ".Jenkinsfile"
        (artifacts / file).write_text(variant["text"])
        inventory.append({"id": name, "artifact": file, "inputs": contracts[variant["case"]]["inputs"]})
    (artifacts / "runs.json").write_text(json.dumps(inventory, indent=2) + "\n")
    return contracts, mappings, variants, inventory


def assess(contracts, mappings, variants, inventory, runtime):
    require(runtime["status"] == "completed" and runtime["jenkins"] == "2.580.1" and runtime["java"].startswith("25") and
            runtime["plugins"]["git"] == "5.10.1", "Unexpected native runtime")
    require([r["id"] for r in runtime["runs"]] == [r["id"] for r in inventory], "Runtime inventory differs")
    cases = []
    for run in runtime["runs"]:
        variant = variants[run["id"]]
        expected, mapping = contracts[variant["case"]], mappings[variant["case"]]
        require(run["finished"] is True and run["inputs"] == expected["inputs"] and
                run["artifactSha256"] == sha(variant["text"].encode()), "Executed source or inputs differ")
        require(all(row["task"] in mapping for row in run["observations"]), "Unbound native observation")
        require(len({row["node"] for row in run["observations"]}) == len(run["observations"]),
                "Duplicate native invocation identity")
        require(all(row["result"] != "failure" or "Couldn't find any revision to build" in (row.get("error") or "")
                    for row in run["observations"]), "Unexpected native failure origin")
        observations = [dict(row, task=mapping[row["task"]]) for row in run["observations"]]
        rejection = None
        try:
            verify_observations(expected, observations, run["result"])
        except ValueError as error:
            rejection = str(error)
        require((rejection is None) == (variant["variant"] == "baseline"), "Baseline failed or native mutant survived")
        cases.append({"id": run["id"], "result": run["result"], "observations": observations,
                      "expectedTrace": expected["trace"], "rejection": rejection})
    return cases


def main():
    require(len(sys.argv) == 4, "Expected repository root, prepared inputs and fresh output directory")
    root, inputs, output = [Path(p).resolve() for p in sys.argv[1:]]
    revision = os.environ["EXPECTED_SHA"]
    require(subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip() == revision, "Source revision differs")
    require(not output.exists(), "Output must be fresh")
    output.mkdir()
    shutil.copytree(inputs, output / "inputs")
    contracts, mappings, variants, inventory = prepare(inputs, output)
    fixture = root / "flow-adapter-jenkins/src/runtimeTest"
    runtime = output / "runtime"
    runtime.mkdir(); runtime.chmod(0o777)
    container = "flow-retry-composition-" + uuid.uuid4().hex
    tag = container + ":local"
    def process(label, command, timeout):
        path = output / (label + ".log")
        with path.open("wb") as stream:
            result = subprocess.run(command, cwd=root, stdout=stream, stderr=subprocess.STDOUT, timeout=timeout)
        require(path.stat().st_size <= 8 * 1024 * 1024, "Runtime log exceeds budget")
        require(result.returncode == 0, f"{label} failed: {path.read_text()[-4000:]}")
        return path.read_text().strip()
    try:
        process("image-build", ["docker", "build", "--tag", tag, str(fixture)], 600)
        image = process("image-identity", ["docker", "image", "inspect", "--format", "{{.Id}}", tag], 30)
        process("controller", ["docker", "run", "--rm", "--name", container, "--network", "bridge", "--memory", "3g", "--cpus", "2",
            "--mount", f"type=bind,src={output / 'artifacts'},dst=/artifacts,readonly",
            "--mount", f"type=bind,src={runtime},dst=/evidence",
            "--mount", f"type=bind,src={fixture / 'composition-observer.groovy'},dst=/usr/share/jenkins/ref/init.groovy.d/retry.groovy,readonly",
            image], 1200)
        data = runtime / "runtime.json"
        require(0 < data.stat().st_size <= 1024 * 1024, "Native evidence exceeds budget")
        cases = assess(contracts, mappings, variants, inventory, json.loads(data.read_text()))
        proof = {"status": "passed", "sourceRevision": revision, "workflowRevision": os.environ["WORKFLOW_REVISION"],
                 "runId": os.environ["GITHUB_RUN_ID"], "attempt": os.environ["GITHUB_RUN_ATTEMPT"], "imageId": image,
                 "cases": cases, "files": receipts(output), "publicSupportPromoted": False, "portableExecution": False,
                 "claim": "native whole-body retry replay, early success, nested exhaustion and failure routing",
                 "limits": ["Fixed zero-delay sequential tasks, conditions and nested retry only",
                            "Native invocation evidence covers checkout; other leaf mappings remain independently gated",
                            "No rollback, fresh workspace, cancellation or general state isolation claim",
                            "Observation stages wrap unchanged native calls; CI, compiler and SCM callbacks are trusted",
                            "Separate regression evidence, not an addition to the signed certification portfolio"]}
        (output / "proof.json").write_text(json.dumps(proof, indent=2) + "\n")
        print("PASS: three native retry baselines and six falsifying mutations")
    finally:
        for command in (["docker", "rm", "--force", container], ["docker", "image", "rm", tag]):
            subprocess.run(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30, check=False)


if __name__ == "__main__":
    main()
