"""Exercise both actual distribution forms with only installed resources and authored inputs."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

RESOURCE_INVENTORY = Path("gradle/reference-contract-resources.txt")
RESOURCE_INPUTS = Path("contract-inputs")
PREFIX = "flow/reference-contracts/"


def resource_paths(root: Path) -> list[Path]:
    paths = (root / RESOURCE_INVENTORY).read_text().splitlines()
    if not paths or paths != sorted(set(paths)) or any(
        not re.fullmatch(r"[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+", path)
        or any(part in (".", "..") for part in path.split("/")) for path in paths
    ):
        raise ValueError("Resource inventory must contain unique, sorted, safe relative paths.")
    return [Path(path) for path in paths]


def packaged_resources(distribution: Path) -> dict[str, bytes]:
    resources = {}
    for jar in (distribution / "lib").glob("*.jar"):
        with zipfile.ZipFile(jar) as archive:
            for name in archive.namelist():
                if name.startswith(PREFIX) and not name.endswith("/"):
                    path = name.removeprefix(PREFIX)
                    if path in resources:
                        raise ValueError(f"Duplicate packaged resource: {path}")
                    resources[path] = archive.read(name)
    raw_index = resources.pop("index.tsv", b"").decode()
    entries = [line.split("\t") for line in raw_index.splitlines()]
    if not entries or any(len(row) != 2 for row in entries):
        raise ValueError("Missing or invalid packaged resource index.")
    paths = [row[1] for row in entries]
    if paths != sorted(set(paths)) or set(paths) != set(resources):
        raise ValueError("Packaged resources differ from their complete index.")
    for digest, path in entries:
        if hashlib.sha256(resources[path]).hexdigest() != digest:
            raise ValueError(f"Packaged resource hash mismatch: {path}")
    return resources


def sections(stdout: str) -> dict:
    parts = re.split(r"^===== (.*?) =====\n", stdout, flags=re.MULTILINE)
    if parts[0] or len(parts) % 2 == 0:
        raise ValueError("Expected structured CLI sections.")
    titles = parts[1::2]
    if len(titles) != len(set(titles)):
        raise ValueError("Duplicate CLI section.")
    return {parts[i]: parts[i + 1] if parts[i].startswith(("RENDERED EXECUTABLE TARGET OUTPUT: ", "RENDERED NON-EXECUTABLE REVIEW EVIDENCE: "))
            else json.loads(parts[i + 1]) for i in range(1, len(parts), 2)}


def verify_relocated_product(isolated: Path, install: Path, report_dir: Path) -> dict:
    archives = list((isolated / "flow-cli/build/distributions").glob("*.zip"))
    if len(archives) != 1:
        raise ValueError("Expected one freshly built product distZip.")
    with tempfile.TemporaryDirectory(prefix="flow-relocated-product-") as temporary:
        root = Path(temporary)
        installed = root / "relocated install with spaces"
        shutil.copytree(isolated / install, installed)
        with zipfile.ZipFile(archives[0]) as archive:
            for name in archive.namelist():
                if Path(name).is_absolute() or ".." in Path(name).parts:
                    raise ValueError("Distribution ZIP escapes its destination.")
            archive.extractall(root / "unzipped")
        unzipped = list((root / "unzipped").iterdir())
        if len(unzipped) != 1 or not (unzipped[0] / "bin/flow-core").is_file():
            raise ValueError("Distribution ZIP has no unique product launcher.")
        def contents(directory):
            return {p.relative_to(directory).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                    for p in directory.rglob("*") if p.is_file()}
        if contents(installed) != contents(unzipped[0]):
            raise ValueError("installDist and distZip bytes differ.")
        resources = packaged_resources(installed)
        expected = resource_paths(isolated)
        if set(resources) != {p.as_posix() for p in expected}:
            raise ValueError("Installed resource inventory differs from authored inventory.")
        for path in expected:
            if resources[path.as_posix()] != (isolated / RESOURCE_INPUTS / path).read_bytes():
                raise ValueError(f"Installed resource differs from build input: {path}")
        # Delete the staged build resource tree before either moved product is launched.
        shutil.rmtree(isolated / RESOURCE_INPUTS)
        external = root / "external contracts"
        for path, data in resources.items():
            target = external / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        authored = root / "authored.intent.yaml"
        authored.write_bytes(resources["examples/intent/checkout-build-image.intent.yaml"])
        flow = root / "authored.flow"
        flow.write_bytes(resources["examples/hello.flow"])
        invalid = root / "incomplete contracts"
        invalid.mkdir()
        runs = {}
        baselines = {}
        for form, distribution in (("installDist", installed), ("distZip", unzipped[0])):
            for context in ("empty", "misleading"):
                cwd = root / form / context
                cwd.mkdir(parents=True)
                if context == "misleading":
                    for path in ("modules/standard.yaml", "targets/builtin-targets.yaml",
                                 "examples/intent/build-test-deploy.intent.yaml"):
                        trap = cwd / path
                        trap.parent.mkdir(parents=True, exist_ok=True)
                        trap.write_text("invalid: [unclosed")
                cases = [("resources", ["resources"], 0), ("modules", ["modules"], 0),
                         ("targets", ["targets"], 0), ("default-intent", ["intent"], 0),
                         ("flow", ["flow", str(flow)], 0),
                         ("normalize", ["normalize", "build and test", "--app", "shop"], 0),
                         ("maintenance", ["normalize", "Run Kubernetes maintenance in namespace payments with dry-run.", "--environment", "prod"], 0),
                         ("external", ["resources", "--contracts", str(external)], 0),
                         ("missing-external", ["intent", "--contracts", str(invalid), "--out", "forbidden"], 2)]
                for target in ("jenkins", "github-actions"):
                    cases.append((target, ["intent", str(authored), "--target", target, "--render", "--out", target], 0 if target == "jenkins" else 3))
                for name, args, exit_code in cases:
                    result = subprocess.run(["bash", str(distribution / "bin/flow-core"), *args], cwd=cwd,
                                            capture_output=True, text=True, check=False, timeout=60)
                    key = f"{form}-{context}-{name}"
                    (report_dir / f"{key}.stdout").write_text(result.stdout)
                    (report_dir / f"{key}.stderr").write_text(result.stderr)
                    if result.returncode != exit_code or result.stderr:
                        raise ValueError(f"Relocated CLI {key} failed: exit={result.returncode}; {result.stdout[-1500:]} {result.stderr}")
                    if name != "missing-external":
                        document = sections(result.stdout)
                        records = document["FLOW CONTRACT RESOURCE PROVENANCE"]
                        if {r["path"] for r in records} != set(resources) or len(records) != len(resources):
                            raise ValueError("CLI did not record every selected resource exactly once.")
                        for record in records:
                            if (record["origin"] != ("EXTERNAL" if name == "external" else "CLASSPATH")
                                    or record["sha256"] != hashlib.sha256(resources[record["path"]]).hexdigest()):
                                raise ValueError("CLI resource provenance does not bind the selected bytes.")
                        if name == "targets" and document["FLOW TARGET MATURITY REPORT"]["status"] != "PASS":
                            raise ValueError("Relocation invalidated adapter maturity evidence.")
                    if name == "missing-external" and ((cwd / "forbidden").exists() or "CLI_INVALID_INPUT" not in result.stdout):
                        raise ValueError("Incomplete override was not rejected before output writes.")
                    if name in ("jenkins", "github-actions"):
                        executable = name == "jenkins"
                        artifact = cwd / name / ("Jenkinsfile" if executable else "flow-github-actions-review.yaml")
                        if not artifact.is_file():
                            raise ValueError(f"Relocated {name} did not produce its executable artifact.")
                        title = "RENDERED EXECUTABLE TARGET OUTPUT" if executable else "RENDERED NON-EXECUTABLE REVIEW EVIDENCE"
                        outcome = document["CLI TARGET OUTCOME"]
                        if outcome["outcome"] != ("EXECUTABLE" if executable else "REVIEW_ONLY") or outcome["renderAuthorized"] != executable:
                            raise ValueError("Relocation changed target authorization.")
                        if not executable and (cwd / name / "github-actions.yml").exists():
                            raise ValueError("Review-only evidence was mislabeled as executable GitHub Actions syntax.")
                        displayed = document[f"{title}: {artifact.name}"].removesuffix("\n")
                        if artifact.read_text() != displayed + ("" if displayed.endswith("\n") else "\n"):
                            raise ValueError(f"Relocated {name} wrote different artifact bytes from its presentation.")
                    digest = hashlib.sha256(result.stdout.encode()).hexdigest()
                    # Only malformed input diagnostics contain invocation-specific paths.
                    if name != "missing-external":
                        if name in baselines and baselines[name] != digest:
                            raise ValueError(f"Relocation or working directory changed {name} output.")
                        baselines[name] = digest
                    runs[key] = {"exitCode": result.returncode, "stdoutSha256": digest}
        return {"installAndZipBytesIdentical": True, "resourceCount": len(resources),
                "stagedResourceInputsDeletedBeforeLaunch": True, "runs": runs}
