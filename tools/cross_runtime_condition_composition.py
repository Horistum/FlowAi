#!/usr/bin/env python3
"""Run the shared compiled composition on disposable Jenkins and compare native evidence.

This external CI harness owns execution. Nothing in the product depends on it.
"""
from datetime import datetime
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import urllib.request
import uuid

from condition_composition_oracle import (bind_graph, bind_manifest, expectation, jenkins_envelope,
                                          receipts, require, sha, verify_files, verify_observations)


def artifacts(generated, mapping):
    envelope, calls = jenkins_envelope(generated, mapping)
    by_name = {name: identity for identity, name in mapping.items()}
    # Explicit falsifying changes for this shared fixture, never production applicability rules.
    guard = "if ((params.enabled && true)) {"
    require(envelope.count(guard) == 1, "Guard mutation must match exactly one live call site")
    join, tail = calls[by_name["join"]][1], calls[by_name["tail"]][1]
    require("FLOW_SWAP_SENTINEL" not in envelope and envelope.count(join) == envelope.count(tail) == 1,
            "Ordering mutation is ambiguous")
    reordered = envelope.replace(join, "FLOW_SWAP_SENTINEL").replace(tail, join).replace("FLOW_SWAP_SENTINEL", tail)
    call = calls[by_name["join"]][0]
    omitted = envelope.replace(join, join.replace(call, "          echo 'omitted native action'\n"))
    return {"baseline": envelope, "inverted-guard": envelope.replace(guard, "if (!(params.enabled && true)) {"),
            "omitted-successor": omitted, "reordered-successor": reordered}


def github_observations(expected, mapping, native, jobs):
    observed = []
    require(set(mapping) <= set(native), "Incomplete native GitHub inventory")
    for identity, name in mapping.items():
        status = "success" if name in expected["active"] else "skipped"
        rows = [j for j in jobs if j["name"] == identity]
        require(len(rows) == 1, "Missing or duplicated GitHub API job")
        job = rows[0]
        require(job["status"] == "completed" and job["conclusion"] == native[identity]["result"] == status,
                f"Native GitHub job {identity}: expected {status}, API {job['status']}/{job['conclusion']}, "
                f"native result {native[identity]['result']}")
        outputs = native[identity].get("outputs", {})
        if status == "success":
            require(set(outputs) == {"revision"}, "Unexpected native GitHub outputs")
            observed.append({"task": name, "result": status, "revision": outputs["revision"],
                             "start": datetime.fromisoformat(job["started_at"]).timestamp(),
                             "end": datetime.fromisoformat(job["completed_at"]).timestamp()})
        else:
            require(outputs == {}, "Skipped native job supplied observations")
    verify_observations(expected, observed)
    return observed


def jenkins_observations(expected, mapping, run, artifact):
    require(run["result"] == "SUCCESS" and run["finished"] is True and run["inputs"] == expected["inputs"],
            "Native Jenkins run did not complete with the declared inputs")
    require(run["artifactSha256"] == sha(artifact.encode()), "Executed Jenkins artifact differs")
    observations = []
    nodes = set()
    for row in run["observations"]:
        require(row["task"] in mapping and row["node"] not in nodes, "Unknown task or duplicate native invocation")
        nodes.add(row["node"])
        observations.append({key: row[key] for key in ("result", "revision", "start", "end")} |
                            {"task": mapping[row["task"]]})
    return observations


def api_jobs(repository, run_id, attempt, token):
    jobs = []
    for page in range(1, 101):
        request = urllib.request.Request(
            f"https://api.github.com/repos/{repository}/actions/runs/{run_id}/attempts/{attempt}/jobs?per_page=100&page={page}",
            headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json",
                     "X-GitHub-Api-Version": "2022-11-28"})
        with urllib.request.urlopen(request, timeout=30) as response:
            data = json.load(response)
        jobs.extend(data["jobs"])
        if len(jobs) == data["total_count"]:
            require(len({j["id"] for j in jobs}) == len(jobs), "Duplicate API jobs")
            return jobs
    raise ValueError("GitHub job inventory exceeds the observation budget")


def completed_jobs(fetch_jobs, required_names, attempts=13, delay_seconds=5):
    """Wait only for required native jobs to become observable, never retry a final verdict."""
    require(required_names and attempts > 0 and delay_seconds >= 0, "Invalid GitHub observation budget")
    for observation in range(attempts):
        jobs = fetch_jobs()
        pending = []
        for name in sorted(required_names):
            matches = [job for job in jobs if job["name"] == name]
            require(len(matches) <= 1, f"Duplicated GitHub API job: {name}")
            if not matches:
                pending.append(f"{name}: missing")
                continue
            job = matches[0]
            require(job["status"] in ("queued", "in_progress", "completed"),
                    f"Unknown GitHub API job status for {name}: {job['status']}")
            if job["status"] != "completed" or job["conclusion"] is None:
                pending.append(f"{name}: {job['status']}/{job['conclusion']}")
        if not pending:
            return jobs
        if observation + 1 < attempts:
            print(f"Waiting for final GitHub API observations ({observation + 1}/{attempts}): " +
                  "; ".join(pending), flush=True)
            time.sleep(delay_seconds)
    raise ValueError("GitHub job observations did not become final within the retry budget: " + "; ".join(pending))


def compare(root, inputs, output, revision, workflow_revision, run_id, attempt, fetch_jobs):
    """Prepare source-bound contracts; no adapter output supplies an expected selected branch."""
    require(not output.exists(), "Evidence directory must be fresh")
    output.mkdir(parents=True)
    documents = []
    for value in ("true", "false"):
        directory = inputs / f"github-condition-composition-{value}"
        proof = json.loads((directory / "proof.json").read_text())
        verify_files(directory, proof)
        require(proof["status"] == "passed" and proof["sourceRevision"] == revision and
                proof["workflowRevision"] == workflow_revision and proof["runId"] == run_id and
                proof["attempt"] == attempt and proof["enabled"] is (value == "true"), "Mismatched input provenance")
        shutil.copytree(directory, output / "inputs" / value)
        documents.append(directory)
    names = ("fixture.ast.json", "canonical-graph.json", "Jenkinsfile", "github-actions.yml",
             "jenkins.manifest.json", "github-actions.manifest.json", "execution-envelope.yml")
    for name in names:
        require(documents[0].joinpath(name).read_bytes() == documents[1].joinpath(name).read_bytes(),
                "The native runs did not consume identical source, graph and projections")
    document = json.loads((documents[0] / "fixture.ast.json").read_text())
    graph = json.loads((documents[0] / "canonical-graph.json").read_text())
    manifests = {target: json.loads((documents[0] / f"{target}.manifest.json").read_text())
                 for target in ("jenkins", "github-actions")}
    required_mapping = bind_manifest(expectation(document, {"enabled": True}), manifests["github-actions"])
    required_names = {f"github-condition-composition ({value}) / {identity}"
                      for value in ("true", "false") for identity in required_mapping}
    jobs = completed_jobs(fetch_jobs, required_names)
    mapping = bind_manifest(expectation(document, {"enabled": True}), manifests["jenkins"])
    generated = (documents[0] / "Jenkinsfile").read_text()
    variants = artifacts(generated, mapping)
    directory = output / "artifacts"
    directory.mkdir()
    for name, source in variants.items():
        (directory / f"{name}.Jenkinsfile").write_text(source)
    runs, contracts, observations = [], {}, {}
    for value, source in zip(("true", "false"), documents):
        expected = expectation(document, {"enabled": value == "true"})
        bind_graph(expected, graph)
        github_mapping = bind_manifest(expected, manifests["github-actions"])
        require(bind_manifest(expected, manifests["jenkins"]) == mapping, "Unstable task binding")
        prefix = f"github-condition-composition ({value}) / "
        native_jobs = [dict(job, name=job["name"][len(prefix):]) for job in jobs if job["name"].startswith(prefix)]
        require(native_jobs and all(j["head_sha"] == revision and j["run_id"] == int(run_id) for j in native_jobs),
                "Native GitHub API revision differs")
        native = json.loads((source / "native-job-results.json").read_text())
        observations[value] = github_observations(expected, github_mapping, native, native_jobs)
        contracts[value] = expected
        for name in variants:
            runs.append({"id": f"{value}-{name}", "artifact": f"{name}.Jenkinsfile", "inputs": expected["inputs"]})
    (directory / "runs.json").write_text(json.dumps(runs, indent=2) + "\n")
    (output / "github-jobs.json").write_text(json.dumps(jobs, indent=2) + "\n")
    return contracts, mapping, variants, runs, observations


def assess(contracts, mapping, variants, inventory, runtime, github):
    require(runtime["status"] == "completed" and runtime["jenkins"] == "2.580.1" and
            runtime["java"].startswith("25") and runtime["plugins"]["git"] == "5.10.1",
            "Unexpected native runtime")
    runs = runtime["runs"]
    require([r["id"] for r in runs] == [r["id"] for r in inventory], "Native run inventory differs")
    cases = []
    for value, expected in contracts.items():
        mutants, baseline = {}, None
        for variant, artifact in variants.items():
            run = next(r for r in runs if r["id"] == f"{value}-{variant}")
            observed = jenkins_observations(expected, mapping, run, artifact)
            if variant == "baseline":
                selected = verify_observations(expected, observed)
                require(selected == verify_observations(expected, github[value]), "Native selected task sets differ")
                baseline = observed
            else:
                try:
                    verify_observations(expected, observed)
                except ValueError as rejection:
                    mutants[variant] = str(rejection)
                else:
                    raise ValueError(f"Native mutation {variant} did not falsify the observation contract")
        cases.append({"inputs": expected["inputs"], "selectedTasks": sorted(expected["active"]),
                      "authoredOrdering": sorted([list(edge) for edge in expected["edges"]]),
                      "jenkins": baseline, "githubActions": github[value], "rejectedNativeMutants": mutants})
    return cases


def main():
    require(len(sys.argv) == 4, "Expected repository root, GitHub evidence directory and fresh output directory")
    root, inputs, output = [Path(p).resolve() for p in sys.argv[1:]]
    revision = os.environ["EXPECTED_SHA"]
    require(subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip() == revision,
            "Runner source revision differs")
    workflow = os.environ["WORKFLOW_REVISION"]
    run_id, attempt = os.environ["GITHUB_RUN_ID"], os.environ["GITHUB_RUN_ATTEMPT"]
    fetch_jobs = lambda: api_jobs(os.environ["GITHUB_REPOSITORY"], run_id, attempt, os.environ["GH_TOKEN"])
    contracts, mapping, variants, inventory, github = compare(root, inputs, output, revision, workflow, run_id, attempt, fetch_jobs)
    container = "flow-composition-" + uuid.uuid4().hex
    tag = container + ":local"
    fixture = root / "flow-adapter-jenkins/src/runtimeTest"
    runtime_directory = output / "runtime"
    runtime_directory.mkdir(mode=0o777)
    runtime_directory.chmod(0o777)

    def process(label, command, timeout):
        log = output / f"{label}.log"
        with log.open("wb") as stream:
            result = subprocess.run(command, cwd=root, stdout=stream, stderr=subprocess.STDOUT, timeout=timeout)
        require(log.stat().st_size <= 8 * 1024 * 1024, "Runtime log exceeds budget")
        require(result.returncode == 0, f"{label} failed: {log.read_text()[-4000:]}")
        return log.read_text().strip()

    try:
        process("image-build", ["docker", "build", "--tag", tag, str(fixture)], 600)
        image = process("image-identity", ["docker", "image", "inspect", "--format", "{{.Id}}", tag], 30)
        process("controller", ["docker", "run", "--rm", "--name", container, "--network", "bridge", "--memory", "3g", "--cpus", "2",
            "--mount", f"type=bind,src={output / 'artifacts'},dst=/artifacts,readonly",
            "--mount", f"type=bind,src={runtime_directory},dst=/evidence",
            "--mount", f"type=bind,src={fixture / 'composition-observer.groovy'},dst=/usr/share/jenkins/ref/init.groovy.d/composition.groovy,readonly",
            image], 900)
        data = runtime_directory / "runtime.json"
        require(0 < data.stat().st_size <= 1024 * 1024, "Native evidence exceeds budget")
        cases = assess(contracts, mapping, variants, inventory, json.loads(data.read_text()), github)
        proof = {"status": "passed", "sourceRevision": revision, "workflowRevision": workflow,
                 "runId": run_id, "attempt": attempt, "imageId": image,
                 "claim": "bounded native branch-selection and authored-order agreement in checked observation envelopes",
                 "cases": cases, "files": receipts(output), "publicSupportPromoted": False, "portableExecution": False,
                 "limits": ["One shared parsed-AST composition with both Boolean assignments",
                            "Jenkins observation stages wrap unchanged native calls; native SCM callbacks read each completed checkout",
                            "Independent sibling concurrency, workspaces, value transport and injected failure/cancellation equivalence are not established",
                            "CI runners, compiler preparation, SCM callbacks and GitHub job API/output observations are trusted",
                            "Separate regression evidence; not added to the signed certification portfolio"]}
        (output / "proof.json").write_text(json.dumps(proof, indent=2) + "\n")
        print("PASS: both native runtimes agree on authored branches and ordering; six Jenkins mutations rejected")
    finally:
        for command in (["docker", "rm", "--force", container], ["docker", "image", "rm", tag]):
            subprocess.run(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30, check=False)


if __name__ == "__main__":
    main()
