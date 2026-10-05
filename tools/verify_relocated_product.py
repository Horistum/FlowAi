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


def verify_publication(directory: Path, resources: dict[str, bytes]) -> str:
    """Independently compare installed CLI receipts with the files left on disk."""
    manifest = "artifact-integrity-report.json"
    raw = (directory / manifest).read_bytes()
    report = json.loads(raw)
    evidence = report.get("publication", {})
    if (report.get("status") != "PASS" or report.get("artifactIntegrityVersion") != "1.1"
            or evidence.get("protocol") != "staged-atomic-directory-v1"
            or evidence.get("excludedPaths") != [manifest] or evidence.get("fileDataForced") is not True):
        raise ValueError("Missing actual-byte publication receipt.")
    records = evidence.get("coveredFiles", [])
    paths = [r["path"] for r in records]
    files = [p for p in directory.rglob("*") if p.is_file()]
    if (any(p.is_symlink() for p in directory.rglob("*")) or paths != sorted(set(paths))
            or set(paths) | {manifest} != {p.relative_to(directory).as_posix() for p in files}
            or manifest in paths or report.get("requiredArtifactsExpected") != report.get("requiredArtifactsPresent")
            or not set(report.get("requiredArtifactsPresent", [])) <= set(paths)):
        raise ValueError("Publication inventory differs from actual files.")
    for record in records:
        name = record["path"]
        if not re.fullmatch(r"[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*", name) or any(p in (".", "..") for p in name.split("/")):
            raise ValueError("Unsafe publication path.")
        data = (directory / name).read_bytes()
        if len(data) != record["sizeBytes"] or hashlib.sha256(data).hexdigest() != record["sha256"]:
            raise ValueError("Publication bytes differ from receipt.")
        schema = record.get("schema", "")
        if schema and (record["validation"] != "JSON_SCHEMA" or schema not in resources
                       or hashlib.sha256(resources[schema]).hexdigest() != record.get("schemaSha256")):
            raise ValueError("Publication schema differs from installed schema.")
        if name.endswith(".json"):
            value = json.loads(data)
            version = value.get("standardVersion", value.get("publicStandardVersion", "")) if isinstance(value, dict) else ""
            if version != record.get("standardVersion"):
                raise ValueError("Publication version differs from actual JSON.")
    return hashlib.sha256(raw).hexdigest()


def verify_integrated_cases(distribution, cwd, external, resources, report_dir, prefix):
    """Exercise interacting boundaries through the installed process, including a FIFO with no writer."""
    import os

    inputs = cwd / "integrated-inputs"
    inputs.mkdir()
    authored = inputs / "authored intent.yaml"
    authored.write_bytes(resources["examples/intent/checkout-build-image.intent.yaml"])
    dash = cwd / "--authored.intent.yaml"
    dash.write_bytes(authored.read_bytes())
    text = inputs / "requirements.txt"
    text.write_text("build and test")
    malformed = inputs / "malformed.yaml"
    malformed.write_bytes(b"\xc3(")
    alias = inputs / "alias.yaml"
    alias.write_text("x: &anchor [1]\ny: *anchor\n")
    deep = inputs / "deep.yaml"
    deep.write_text("x: " + "[" * 70 + "0" + "]" * 70)
    duplicate = inputs / "duplicate.yaml"
    duplicate.write_text("x: 1\nx: 2\n")
    fifo = inputs / "input.fifo"
    os.mkfifo(fifo)
    corrupt = inputs / "corrupt contracts"
    shutil.copytree(external, corrupt)
    (corrupt / "examples/intent/build-test-deploy.intent.yaml").write_bytes(b"\xc3(")
    preserved = cwd / "integrated-preserved"
    preserved.mkdir()
    (preserved / "keep.txt").write_text("previous accepted bytes")
    link = cwd / "integrated-link"
    link.symlink_to(preserved, target_is_directory=True)
    ordinary_file = cwd / "integrated-file"
    ordinary_file.write_text("existing file")
    cases = [
        ("diagnostics", ["diagnostics", "--out=integrated diagnostics"], 0, "integrated diagnostics"),
        ("normalize", ["normalize", "--out=integrated normalized", "--app=shop", "--file", str(text)], 0, "integrated normalized"),
        ("external-intent", ["intent", "--out=integrated external", "--contracts", str(external), "--target=jenkins", "--render", "--", str(authored)], 0, "integrated external"),
        ("dash-source", ["intent", "--out=integrated dash", "--target=jenkins", "--render", "--", dash.name], 0, "integrated dash"),
        ("duplicate-option", ["intent", "--out", str(preserved), "--target=jenkins", "--target=github-actions", str(authored)], 2, None),
        ("unknown-option", ["intent", "--out", str(preserved), "--unknown", str(authored)], 2, None),
        ("missing-value", ["intent", "--out", str(preserved), "--target"], 2, None),
        ("alias", ["intent", "--out", str(preserved), str(alias)], 2, None),
        ("depth", ["intent", "--out", str(preserved), str(deep)], 2, None),
        ("duplicate-key", ["intent", "--out", str(preserved), str(duplicate)], 2, None),
        ("malformed-intent", ["intent", "--out", str(preserved), str(malformed)], 2, None),
        ("fifo", ["normalize", "--out", str(preserved), "--file", str(fifo)], 2, None),
        ("symlink-output", ["diagnostics", "--out", str(link)], 2, None),
        ("file-output", ["diagnostics", "--out", str(ordinary_file)], 2, None),
        ("corrupt-default", ["intent", "--out", str(preserved), "--contracts", str(corrupt)], 2, None),
        ("incomplete-bundle", ["standard-verify", "--out", str(preserved), "--bundle=integrated diagnostics"], 2, None),
    ]
    runs, publications = {}, {}
    for name, args, expected, output in cases:
        # A blocked FIFO regression must fail promptly; no background writer masks the defect.
        result = subprocess.run(["bash", str(distribution / "bin/flow-core"), *args], cwd=cwd,
                                capture_output=True, text=True, check=False, timeout=15 if name == "fifo" else 60)
        key = f"{prefix}-integrated-{name}"
        (report_dir / f"{key}.stdout").write_text(result.stdout)
        (report_dir / f"{key}.stderr").write_text(result.stderr)
        if result.returncode != expected or result.stderr:
            raise ValueError(f"Integrated CLI {key} failed: {result.returncode}: {result.stdout[-1500:]} {result.stderr}")
        document = sections(result.stdout)
        if expected == 2:
            failure = document.get("CLI DIAGNOSTIC FAILURE", {})
            if failure.get("code") != "CLI_INVALID_INPUT" or not 0 < len(failure.get("message", "")) <= 2048:
                raise ValueError(f"Integrated failure has no bounded typed diagnostic: {key}")
            if name == "fifo" and "INPUT_FILE_TYPE" not in failure["message"]:
                raise ValueError("FIFO was not rejected at the finite-file boundary.")
        if output:
            publications[key] = verify_publication(cwd / output, resources)
        if name in ("external-intent", "dash-source"):
            outcome = document["CLI TARGET OUTCOME"]
            if outcome["outcome"] != "EXECUTABLE" or outcome["renderAuthorized"] is not True:
                raise ValueError("Integrated option parsing changed Jenkins authorization.")
            records = document["FLOW CONTRACT RESOURCE PROVENANCE"]
            expected_origin = "EXTERNAL" if name == "external-intent" else "CLASSPATH"
            if len(records) != len(resources) or {r["path"] for r in records} != set(resources) or any(
                    r["origin"] != expected_origin or r["sha256"] != hashlib.sha256(resources[r["path"]]).hexdigest() for r in records):
                raise ValueError("Integrated publication selected incorrect contract bytes.")
        if ({p.name: p.read_bytes() for p in preserved.iterdir()} != {"keep.txt": b"previous accepted bytes"}
                or ordinary_file.read_bytes() != b"existing file" or not link.is_symlink()
                or list(cwd.glob(".*.staging-*"))):
            raise ValueError("Integrated rejection changed prior output or leaked staging.")
        runs[key] = {"exitCode": result.returncode, "stdoutSha256": hashlib.sha256(result.stdout.encode()).hexdigest()}
    return {"runs": runs, "publicationManifestHashes": publications,
            "cases": [name for name, *_ in cases], "rejectedCases": sum(code == 2 for _, _, code, _ in cases)}


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
        oversized = root / "oversized.source"
        with oversized.open("wb") as stream:
            stream.truncate(8 * 1024 * 1024 + 1)
        malformed = root / "malformed.txt"
        malformed.write_bytes(b"\xc3(")
        oversized_contracts = root / "oversized contracts"
        shutil.copytree(external, oversized_contracts)
        with (oversized_contracts / "modules/standard.yaml").open("wb") as stream:
            stream.truncate(8 * 1024 * 1024 + 1)
        runs = {}
        baselines = {}
        publications = {}
        integrated = {}
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
                         ("missing-external", ["intent", "--contracts", str(invalid), "--out", "forbidden"], 2),
                         ("limit-intent", ["intent", str(oversized), "--out", "forbidden"], 2),
                         ("limit-flow", ["flow", str(oversized)], 2),
                         ("limit-normalize", ["normalize", "--file", str(oversized), "--out", "forbidden"], 2),
                         ("limit-contracts", ["resources", "--contracts", str(oversized_contracts)], 2),
                         ("malformed-normalize", ["normalize", "--file", str(malformed), "--out", "forbidden"], 2)]
                for target in ("jenkins", "github-actions"):
                    cases.append((target, ["intent", str(authored), "--target", target, "--render", "--out", target], 0 if target == "jenkins" else 3))
                cases.append(("existing-output", ["diagnostics", "--out", "jenkins"], 2))
                previous_output = None
                for name, args, exit_code in cases:
                    result = subprocess.run(["bash", str(distribution / "bin/flow-core"), *args], cwd=cwd,
                                            capture_output=True, text=True, check=False, timeout=60)
                    key = f"{form}-{context}-{name}"
                    (report_dir / f"{key}.stdout").write_text(result.stdout)
                    (report_dir / f"{key}.stderr").write_text(result.stderr)
                    if result.returncode != exit_code or result.stderr:
                        raise ValueError(f"Relocated CLI {key} failed: exit={result.returncode}; {result.stdout[-1500:]} {result.stderr}")
                    rejected = name in ("missing-external", "malformed-normalize", "existing-output") or name.startswith("limit-")
                    if not rejected:
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
                    if rejected:
                        expected_code = "CLI_LIMIT_EXCEEDED" if name.startswith("limit-") else "CLI_INVALID_INPUT"
                        failure = sections(result.stdout).get("CLI DIAGNOSTIC FAILURE", {})
                        if (cwd / "forbidden").exists() or failure.get("code") != expected_code:
                            raise ValueError("Invalid or oversized input was not rejected before output writes.")
                        if name.startswith("limit-") and (len(result.stdout.encode()) > 4096 or len(failure.get("message", "")) > 2048):
                            raise ValueError("Limit rejection produced an oversized diagnostic.")
                    if name in ("jenkins", "github-actions"):
                        publications[key] = verify_publication(cwd / name, resources)
                        if (cwd / name / "execution-plan.json").read_bytes() != (cwd / name / "canonical-execution-plan.json").read_bytes():
                            raise ValueError("Public execution-plan export differs from the schema-owned canonical model.")
                        if name == "jenkins":
                            previous_output = contents(cwd / name)
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
                    if name == "existing-output":
                        if contents(cwd / "jenkins") != previous_output or list(cwd.glob(".*.staging-*")):
                            raise ValueError("Rejected repeat publication changed a verified output or leaked staging.")
                    digest = hashlib.sha256(result.stdout.encode()).hexdigest()
                    # Only malformed input diagnostics contain invocation-specific paths.
                    if not rejected:
                        if name in baselines and baselines[name] != digest:
                            raise ValueError(f"Relocation or working directory changed {name} output.")
                        baselines[name] = digest
                    runs[key] = {"exitCode": result.returncode, "stdoutSha256": digest}
                integrated[f"{form}-{context}"] = verify_integrated_cases(
                    distribution, cwd, external, resources, report_dir, f"{form}-{context}")
        return {"integratedIntegrity": integrated, "installAndZipBytesIdentical": True, "resourceCount": len(resources),
                "stagedResourceInputsDeletedBeforeLaunch": True, "publicationManifestHashes": publications,
                "existingOutputsPreserved": 4, "runs": runs}
