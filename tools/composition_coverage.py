#!/usr/bin/env python3
"""Replay CI-bound composition observations and reconcile them with signed coverage.

This verification-only consumer starts no target runtime. Successful producer job
outputs pin input bytes; existing compiler entry points and independent oracles
remain the owners of meaning. CI-bound observations are not signed assessments.
"""
import argparse
import html
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import tempfile

from condition_composition_oracle import require, sha


COMPOSITIONS = {
    "cross-runtime-condition-composition": ("GitHubCondition", "CONDITIONS", ("github-actions", "jenkins")),
    "retry-composition-runtime": ("Retry", "RETRY", ("jenkins",)),
    "loop-composition-runtime": ("Loop", "LOOPS", ("jenkins",)),
    "match-composition-runtime": ("Match", "MATCH", ("jenkins",)),
    "parallel-composition-runtime": ("Parallel", "PARALLELISM", ("jenkins",)),
    "github-workspace-composition": ("GitHubWorkspace", "ARTIFACTS_WORKSPACES", ("github-actions",)),
}
PORTFOLIO = "adapter-certification-portfolio"
PROVENANCE = ("sourceRevision", "workflowRevision", "runId", "attempt")
MAX_FILE = 8 * 1024 * 1024
MAX_ARCHIVE = 64 * 1024 * 1024


def decode(data):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, f"Duplicate JSON key: {key}")
            result[key] = value
        return result

    def invalid(value):
        raise ValueError(f"Non-finite JSON number: {value}")

    return json.loads(data, object_pairs_hook=unique, parse_constant=invalid)


def encode(value):
    return (json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + "\n").encode()


def context(environment):
    result = dict(zip(PROVENANCE, (environment["EXPECTED_SHA"], environment["WORKFLOW_REVISION"],
                                  environment["GITHUB_RUN_ID"], environment["GITHUB_RUN_ATTEMPT"])))
    require(all(re.fullmatch("[0-9a-f]{40}", result[key]) for key in PROVENANCE[:2]) and
            all(re.fullmatch("[1-9][0-9]*", result[key]) for key in PROVENANCE[2:]), "Invalid CI provenance")
    return result


def snapshot(directory):
    """Bound and freeze files before any compiler, oracle or publication I/O."""
    require(directory.is_dir() and directory.resolve() == directory.absolute(), "Unsafe evidence directory")
    result, total, entries = {}, 0, 0
    for path in sorted(directory.rglob("*")):
        entries += 1
        require(entries <= 2048 and not path.is_symlink(), "Excessive inventory or symbolic evidence path")
        mode = path.stat().st_mode
        if stat.S_ISDIR(mode):
            continue
        require(stat.S_ISREG(mode), "Evidence must contain regular files only")
        with path.open("rb") as stream:
            data = stream.read(MAX_FILE + 1)
        total += len(data)
        require(len(data) <= MAX_FILE and total <= MAX_ARCHIVE, "Evidence exceeds byte budget")
        name = path.relative_to(directory).as_posix()
        require("\\" not in name and all(ord(c) >= 32 for c in name), "Unsafe evidence name")
        if name.endswith(".json"):
            decode(data)
        result[name] = data
    require(result, "Missing evidence files")
    return result


def verify_inventory(files, document, entrypoint, size_field):
    inventory = document["files"]
    require(isinstance(inventory, dict) and set(inventory) == set(files) - {entrypoint}, "Evidence inventory differs")
    for name, receipt in inventory.items():
        require(receipt == {"sha256": sha(files[name]), size_field: len(files[name])}, "Evidence hash or size differs")


def receipt(artifact, files, expected):
    require(artifact in COMPOSITIONS or artifact == PORTFOLIO, "Unknown evidence producer")
    entrypoint = "publication.json" if artifact == PORTFOLIO else "proof.json"
    document = decode(files[entrypoint])
    require(document["sourceRevision"] == expected["sourceRevision"], "Stale source revision")
    if artifact == PORTFOLIO:
        require(document["formatVersion"] == 1, "Unsupported portfolio publication")
        require(set(files) == {"publication.json", "portfolio.json", "portfolio.md", "target-coverage.json", "target-coverage.md"},
                "Portfolio file inventory differs")
        verify_inventory(files, document, entrypoint, "sizeBytes")
    else:
        if artifact == "github-workspace-composition":
            from workspace_probe_evidence import native_context
            native_context(document, expected)
        else:
            require(all(document[key] == expected[key] for key in PROVENANCE), "Composition provenance differs")
        require(document["status"] == "passed" and document["publicSupportPromoted"] is False and
                document["portableExecution"] is False, "Composition claim exceeds its scope")
        if artifact == "github-workspace-composition":
            import verify_github_workspace_composition as workspace
            require(type(document["formatVersion"]) is int and document["formatVersion"] == 1 and document["runtimeKind"] == workspace.RUNTIME_KIND and
                    set(document) == set(PROVENANCE) | {"formatVersion", "status", "runtimeKind", "scope", "claim",
                        "limits", "cases", "files", "publicSupportPromoted", "portableExecution"},
                    "Workspace proof contract differs")
            require(set(files) == workspace.INPUT_FILES | {"expected.json", "observations.json", "github-jobs.json", "proof.json"},
                    "Workspace archive inventory differs")
        else:
            require(re.fullmatch("sha256:[0-9a-f]{64}", document["imageId"]) and
                    files["image-identity.log"].decode().strip() == document["imageId"], "Runtime image identity differs")
        verify_inventory(files, document, entrypoint, "bytes")
    native_receipt = ({"nativeRunId": document["runId"], "nativeAttempt": document["attempt"]}
                      if artifact == "github-workspace-composition" else {})
    return expected | native_receipt | {"artifact": artifact, "entrypoint": entrypoint, "sha256": sha(files[entrypoint])}


def admit(inputs, needs, expected):
    producers = set(COMPOSITIONS) | {PORTFOLIO}
    require(isinstance(needs, dict) and set(needs) == producers, "Producer job inventory differs")
    require(set(inputs) == producers, "Downloaded artifact inventory differs")
    admitted, pinned = {}, {}
    for artifact in sorted(producers):
        job = needs[artifact]
        require(job["result"] == "success", f"Producer {artifact} did not succeed")
        owner_receipt = decode(job["outputs"]["receipt"])
        files = snapshot(inputs[artifact])
        require(owner_receipt == receipt(artifact, files, expected), f"Producer-pinned bytes or provenance differ: {artifact}")
        admitted[artifact], pinned[artifact] = files, owner_receipt
    return admitted, pinned


def write_tree(directory, files):
    directory.mkdir(parents=True, exist_ok=False)
    for name, data in files.items():
        require(not name.startswith("/") and ".." not in name.split("/") and "\\" not in name, "Unsafe output path")
        path = directory / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)


def subtree(files, prefix):
    return {name[len(prefix):]: data for name, data in files.items() if name.startswith(prefix)}


def same_inputs(archived, compiled):
    require(archived == compiled, "Archived inputs differ from current compiler output")


def replay(artifact, files, compiled, workspace, expected, fetch_jobs, source_root=None, fetch_probe_run=None, repository=None):
    """Rebuild envelopes/mutants and derive cases from native records, never PASS flags."""
    import cross_runtime_condition_composition as condition
    import retry_composition_runtime as retry
    import loop_composition_runtime as loop
    import match_composition_runtime as match
    import parallel_composition_runtime as parallel

    proof = decode(files["proof.json"])
    if artifact == "github-workspace-composition":
        import verify_github_workspace_composition as github_workspace
        require(source_root is not None, "Workspace replay requires current source bytes")
        require(set(compiled) == github_workspace.INPUT_FILES, "Workspace compiler input inventory differs")
        same_inputs({name: files[name] for name in compiled}, compiled)
        from workspace_probe_evidence import native_context, authenticate
        native = native_context(proof, expected)
        require(fetch_probe_run is not None and repository is not None, "Workspace replay requires authenticated probe run")
        jobs = fetch_jobs()
        authenticate(fetch_probe_run(), jobs, native, repository)
        assessment, authenticated = github_workspace.assess(files, github_workspace.inventory(source_root), native, jobs)
        require(decode(files["github-jobs.json"]) == authenticated, "Archived GitHub jobs differ from same-attempt API")
        require(all(proof[key] == value for key, value in assessment.items()), "Published workspace claim differs from independent replay")
        return {"artifact": artifact, "construct": COMPOSITIONS[artifact][1], "targets": list(COMPOSITIONS[artifact][2]),
                "evidenceClass": "CI_BOUND_COMPOSITION_OBSERVATIONS", "proofSha256": sha(files["proof.json"]),
                **assessment, "compilerInputDigests": {name: sha(data) for name, data in sorted(compiled.items())}}
    native = decode(files["runtime/runtime.json"])
    output = workspace / "replay"
    inputs = workspace / "compiled"
    if artifact == "cross-runtime-condition-composition":
        for value in ("true", "false"):
            archived = subtree(files, f"inputs/{value}/")
            require(set(compiled) <= set(archived), "Missing compiled condition inputs")
            same_inputs({name: archived[name] for name in compiled}, compiled)
            write_tree(inputs / f"github-condition-composition-{value}", archived)
        contracts, mapping, variants, inventory, github = condition.compare(
            workspace, inputs, output, expected["sourceRevision"], expected["workflowRevision"],
            expected["runId"], expected["attempt"], fetch_jobs)
        cases = condition.assess(contracts, mapping, variants, inventory, native, github)
    else:
        same_inputs(subtree(files, "inputs/"), compiled)
        write_tree(inputs, compiled)
        output.mkdir()
        if artifact == "parallel-composition-runtime":
            contracts, variants, inventory = parallel.prepare(inputs, output)
            cases = parallel.assess(contracts, variants, inventory, native, decode(files["fixture-revisions.json"]))
        else:
            prepare = {"retry-composition-runtime": retry.prepare, "loop-composition-runtime": loop.prepare,
                       "match-composition-runtime": match.prepare}[artifact]
            contracts, mappings, variants, inventory = prepare(inputs, output)
            cases = retry.assess(contracts, mappings, variants, inventory, native)
    require(snapshot(output / "artifacts") == subtree(files, "artifacts/"), "Archived native envelope or mutant differs")
    require(cases == proof["cases"], "Published cases differ from independent native replay")
    return {"artifact": artifact, "construct": COMPOSITIONS[artifact][1], "targets": list(COMPOSITIONS[artifact][2]),
            "evidenceClass": "CI_BOUND_COMPOSITION_OBSERVATIONS", "proofSha256": sha(files["proof.json"]),
            "imageId": proof["imageId"], "claim": proof["claim"], "limits": proof["limits"], "cases": cases,
            "compilerInputDigests": {name: sha(data) for name, data in sorted(compiled.items())}}


def derive(coverage_files, compositions, pinned, expected):
    portfolio = decode(coverage_files["portfolio.json"])
    coverage = decode(coverage_files["target-coverage.json"])
    require(coverage["portfolioSha256"] == sha(coverage_files["portfolio.json"]), "Coverage belongs to another portfolio")
    for document in (portfolio, coverage):
        require(document["sourceRevision"] == expected["sourceRevision"] and document["publicSupportPromoted"] is False and
                document["portableExecution"] is False, "Signed publication provenance or scope differs")
    require([item["artifact"] for item in compositions] == sorted(COMPOSITIONS), "Composition admission inventory differs")
    targets = {target["target"] for target in coverage["targets"]}
    constructs = {row["construct"] for row in coverage["rows"]}
    require(all(set(item["targets"]) <= targets and item["construct"] in constructs for item in compositions),
            "Composition has no declared target/construct row")
    require(len(coverage["rows"]) == len(targets) * len(constructs) and
            len({(row["target"], row["construct"]) for row in coverage["rows"]}) == len(coverage["rows"]),
            "Signed coverage matrix is incomplete or duplicated")
    rows = []
    for row in coverage["rows"]:
        ids = [item["artifact"] for item in compositions if row["target"] in item["targets"] and row["construct"] == item["construct"]]
        rows.append({"target": row["target"], "construct": row["construct"],
                     "signedPortfolioStatus": row["status"], "signedScenarioIds": row["scenarioIds"],
                     "compositionStatus": "BOUNDED_CI_COMPOSITION_OBSERVED" if ids else "NO_COMPOSITION_EVIDENCE",
                     "compositionIds": ids, "targetWideCertificationEstablished": False})
    return expected | {"formatVersion": 1, "scope": "reconciled-signed-and-ci-bound-target-coverage",
                       "publicSupportPromoted": False, "portableExecution": False,
                       "signedPortfolioSha256": sha(coverage_files["portfolio.json"]),
                       "signedCoverageSha256": sha(coverage_files["target-coverage.json"]),
                       "producerReceipts": pinned, "targetCount": len(targets), "constructCount": len(constructs),
                       "rows": rows, "compositions": compositions,
                       "trustBoundary": ["Successful same-attempt GitHub producer outputs pin all archive entrypoints",
                                         "Current compiler inputs and envelopes are regenerated before native replay",
                                         "CI runners, compiler, native observers and GitHub API remain trusted",
                                         "Composition observations are not signed portfolio assessments",
                                         "Missing evidence is not an unsupported-feature declaration",
                                         "Bounded observations do not establish target-wide support or portable execution"]}


def markdown(report):
    def cell(value):
        return html.escape(str(value), quote=True).replace("|", "&#124;").replace("`", "&#96;").replace("\n", " ").replace("\r", " ")
    lines = ["# Reconciled composition coverage", "", f"Source revision: {report['sourceRevision']}.", "",
             "Signed portfolio scenarios and replayed CI-bound composition observations remain separate evidence classes.",
             "Missing evidence stays visible. No target-wide support or portable execution is promoted.", "",
             "| Target | Construct | Signed portfolio | CI-bound composition |",
             "| --- | --- | --- | --- |"]
    lines += ["| " + " | ".join(map(cell, (r["target"], r["construct"], r["signedPortfolioStatus"],
              ", ".join(r["compositionIds"]) or r["compositionStatus"]))) + " |" for r in report["rows"]]
    lines += ["", "## Evidence scope and remaining limits", ""]
    for item in report["compositions"]:
        lines += [f"### {cell(item['artifact'])}", "", cell(item["claim"]), ""]
        lines += ["- " + cell(limit) for limit in item["limits"]] + [""]
    lines += ["## Trust boundary", ""] + ["- " + cell(limit) for limit in report["trustBoundary"]]
    return "\n".join(lines) + "\n"


def publish(output, report, signed_files):
    require(not output.exists(), "Publication output must be fresh")
    output.parent.mkdir(parents=True, exist_ok=True)
    documents = {("signed-publication.json" if name == "publication.json" else name): data
                 for name, data in signed_files.items()}
    documents |= {"composition-coverage.json": encode(report), "composition-coverage.md": markdown(report).encode()}
    stage = Path(tempfile.mkdtemp(prefix=".composition-", dir=output.parent))
    try:
        for name, data in documents.items():
            (stage / name).write_bytes(data)
        actual = snapshot(stage)
        require(actual == documents, "Written publication bytes differ")
        manifest = {key: report[key] for key in PROVENANCE} | {
            "formatVersion": 1, "files": {name: {"sha256": sha(data), "bytes": len(data)} for name, data in sorted(actual.items())}}
        (stage / "publication.json").write_bytes(encode(manifest))
        require(decode((stage / "publication.json").read_bytes()) == manifest, "Written publication manifest differs")
        stage.rename(output)
    finally:
        if stage.exists():
            shutil.rmtree(stage)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    pin = commands.add_parser("receipt")
    pin.add_argument("artifact", choices=sorted(set(COMPOSITIONS) | {PORTFOLIO}))
    pin.add_argument("directory", type=Path)
    check = commands.add_parser("publish")
    check.add_argument("root", type=Path)
    check.add_argument("inputs", type=Path)
    check.add_argument("output", type=Path)
    args = parser.parse_args()
    expected = context(os.environ)
    root = args.root.absolute() if args.command == "publish" else Path.cwd()
    require(subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip() == expected["sourceRevision"],
            "Checkout differs from the admitted source revision")
    if args.command == "receipt":
        result = receipt(args.artifact, snapshot(args.directory.absolute()), expected)
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
            stream.write("receipt=" + json.dumps(result, separators=(",", ":")) + "\n")
        return
    needs = os.environ["FLOW_COMPOSITION_JOB_RECEIPTS"]
    require(len(needs.encode()) <= 65536, "Producer receipts exceed byte budget")
    inputs = args.inputs.absolute()
    admitted, pinned = admit({path.name: path for path in inputs.iterdir()}, decode(needs), expected)
    from cross_runtime_condition_composition import api_jobs
    fetch_jobs = lambda: api_jobs(os.environ["GITHUB_REPOSITORY"], expected["runId"], expected["attempt"], os.environ["GH_TOKEN"])
    results = []
    with tempfile.TemporaryDirectory(prefix="flow-composition-replay-") as temporary:
        scratch = Path(temporary)
        for artifact, (prefix, _, _) in sorted(COMPOSITIONS.items()):
            workspace = scratch / artifact
            workspace.mkdir()
            compiled = workspace / "current"
            arguments = [str(root)]
            if prefix in ("GitHubCondition", "GitHubWorkspace"):
                name = "github-workspace-composition.yml" if prefix == "GitHubWorkspace" else "github-condition-composition.yml"
                arguments.append(str(root / ".github/workflows" / name))
            arguments.append(str(compiled))
            if prefix == "GitHubWorkspace":
                arguments.append(expected["sourceRevision"])
            subprocess.run(["java", "-cp", str(root / "flow-conformance-kit/build/install/flow-conformance/lib/*"),
                            f"org.flowlang.conformance.{prefix}CompositionCli", *arguments], cwd=root, check=True, timeout=180)
            if prefix == "GitHubWorkspace":
                from workspace_probe_evidence import native_context, get
                native = native_context(decode(admitted[artifact]["proof.json"]), expected)
                repository, token = os.environ["GITHUB_REPOSITORY"], os.environ["GH_TOKEN"]
                native_jobs = lambda: api_jobs(repository, native["runId"], native["attempt"], token)
                native_run = lambda: get(repository, f'runs/{native["runId"]}', token)
                results.append(replay(artifact, admitted[artifact], snapshot(compiled), workspace, expected,
                                      native_jobs, root, native_run, repository))
            else:
                results.append(replay(artifact, admitted[artifact], snapshot(compiled), workspace, expected, fetch_jobs, root))
        report = derive(admitted[PORTFOLIO], results, pinned, expected)
        publish(args.output.absolute(), report, admitted[PORTFOLIO])
    print(f"PASS: replayed {len(results)} composition archives; reconciled {len(report['rows'])} coverage rows without support promotion")


if __name__ == "__main__":
    main()
