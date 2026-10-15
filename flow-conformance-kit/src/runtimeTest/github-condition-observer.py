import hashlib
import json
import os
import pathlib
import re
import subprocess

workspace = pathlib.Path(os.environ["GITHUB_WORKSPACE"]).resolve(strict=True)
repository = workspace / ".git"
marker = workspace / ".flow-agent/work-packages/behavioral-adapter-certification.yaml"
revision = digest = None
if repository.exists():
    assert repository.is_dir() and not repository.is_symlink()
    revision = subprocess.check_output(
        ["git", "-C", str(workspace), "rev-parse", "HEAD"], text=True, timeout=30
    ).strip()
    assert re.fullmatch(r"[0-9a-f]{40}", revision)
    assert marker.is_file() and marker.resolve(strict=True).is_relative_to(workspace)
    data = marker.read_bytes()
    assert 1 <= len(data) <= 65536
    digest = hashlib.sha256(data).hexdigest()
else:
    assert not list(workspace.iterdir()), "Expected an empty workspace"
observation = json.dumps({"revision": revision, "markerSha256": digest}, separators=(",", ":"))
image = os.environ["ImageVersion"]
assert re.fullmatch(r"[A-Za-z0-9._-]{1,128}", image)
with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
    output.write("observation=" + observation + "\nrunner-image=" + image + "\n")
