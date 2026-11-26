"""Publish only a rendered executable identified by the installed adapter's receipt."""
import os
from pathlib import Path
import tempfile

from .contracts import HostError, digest, loads, read, require
from .presentation import display_value


def export_result(target, destination, review_dir, review, fidelity):
    require(review.get("target") == target and review["renderRequested"] is True, "Compiler target receipt mismatch")
    if review["exitCode"] != 0:
        print("Target file withheld:", display_value(review["targetDisposition"]))
        if "target-render-readiness.json" in review["artifacts"]:
            readiness = loads(read(review_dir / "target-render-readiness.json"))
            for finding in readiness["findings"]:
                print(display_value(finding["nodeId"]), display_value(finding["reason"]))
        else:
            for issue in review["diagnostics"]:
                print(display_value(issue["code"]), display_value(issue["message"]))
        return
    require(review["semanticAcceptance"] == "accepted" and review.get("graphDigest") and review["renderAuthorized"] is True
            and review["requestFidelity"] == "requirements-satisfied"
            and fidelity["state"] == "REQUIREMENTS_SATISFIED" and "target-artifact-evidence.json" in review["artifacts"],
            "The compiler did not authorize an executable for the reviewed requirements")
    receipt = loads(read(review_dir / "target-artifact-evidence.json"))
    name = receipt["artifactFileName"]
    require(receipt["target"] == target and receipt["kind"] == "EXECUTABLE_TARGET" and receipt["renderMode"] == "EXECUTABLE"
            and name in review["artifacts"] and Path(name).name == name and name not in ("", ".", "..") and "\\" not in name,
            "Invalid executable artifact receipt")
    content = (review_dir / name).read_bytes()
    require(digest(content) == receipt["artifactSha256"], "Rendered file differs from the adapter receipt")
    try:
        output = _publish(destination, name, content)
    except FileExistsError:
        raise HostError(f"Output already exists in {display_value(str(destination))}. Choose a different directory.") from None
    except OSError as error:
        raise HostError(f"Cannot publish to {display_value(str(destination))}: {error.strerror}.") from None
    print("Target file:", display_value(str(output.absolute())))


def _publish(destination, name, content):
    destination.mkdir(parents=True, exist_ok=True)
    output = destination / name
    # Linking a fully written sibling cannot replace an existing file, even under concurrent exports.
    with tempfile.NamedTemporaryFile(dir=destination, prefix=".flow-export-") as staged:
        staged.write(content)
        staged.flush()
        os.fsync(staged.fileno())
        os.link(staged.name, output)
    return output
