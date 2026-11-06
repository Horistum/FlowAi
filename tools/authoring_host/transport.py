"""Bounded one-shot processes. Providers are trusted data-only operator infrastructure."""
from __future__ import annotations

import os
from pathlib import Path
import selectors
import signal
import subprocess
import tempfile
import time

from .contracts import MAX_BYTES, canonical, digest, loads, require


def credentials(config):
    result = {}
    for name in config["provider"]["credentialEnv"]:
        value = os.environ.get(name, "")
        require(bool(value) and "\n" not in value and "\0" not in value, "Missing or invalid provider credential")
        result[name] = value
    return result


def invoke(argv, data, timeout, environment):
    """One attempt; capped combined stdout/stderr, empty cwd, no shell or inherited credentials."""
    with tempfile.TemporaryDirectory(prefix="flow-authoring-") as temp:
        with tempfile.TemporaryFile() as stdin:
            stdin.write(data)
            stdin.seek(0)
            env = {"PATH": os.defpath, "LANG": "C.UTF-8", "HOME": temp, **environment}
            started = time.monotonic()
            process = subprocess.Popen(argv, stdin=stdin, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                       cwd=temp, env=env, start_new_session=True)
            output, count, failure = bytearray(), 0, None
            with selectors.DefaultSelector() as selector:
                selector.register(process.stdout, selectors.EVENT_READ)
                selector.register(process.stderr, selectors.EVENT_READ)
                try:
                    while selector.get_map():
                        remaining = timeout - (time.monotonic() - started)
                        if remaining <= 0:
                            failure = "process-timeout"
                            break
                        for key, _ in selector.select(min(remaining, 0.1)):
                            chunk = os.read(key.fd, 65536)
                            if not chunk:
                                selector.unregister(key.fileobj)
                                continue
                            count += len(chunk)
                            if key.fileobj is process.stdout:
                                output.extend(chunk[:max(0, MAX_BYTES - len(output))])
                            if count > MAX_BYTES:
                                failure = "process-output-limit"
                                break
                        if failure:
                            break
                    if not failure:
                        try:
                            process.wait(timeout=max(0.01, timeout - (time.monotonic() - started)))
                        except subprocess.TimeoutExpired:
                            failure = "process-timeout"
                finally:
                    # Also retire descendants retaining pipes or outliving their parent.
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    process.wait()
                    process.stdout.close()
                    process.stderr.close()
            return {"stdout": bytes(output), "returncode": process.returncode,
                    "failure": failure, "latencyMs": round((time.monotonic() - started) * 1000)}


def compiler_fingerprint(config):
    executable = Path(config["compiler"]["argv"][0]).resolve()
    # Installed JVM launchers load adjacent distribution jars. Hash those as well.
    files = [executable, *sorted((executable.parent.parent / "lib").glob("*.jar")),
             *(Path(a) for a in config["compiler"]["argv"][1:] if Path(a).is_absolute() and Path(a).is_file())]
    import hashlib
    result = {}
    for file in files:
        h = hashlib.sha256()
        with file.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                h.update(chunk)
        result[str(file)] = h.hexdigest()
    return digest(canonical(result))


def compiler_environment():
    return {k: os.environ[k] for k in ("JAVA_HOME",) if k in os.environ}


def capability_catalog(config):
    result = invoke(config["compiler"]["argv"] + ["catalog"], b"", config["budget"]["timeoutSeconds"],
                    compiler_environment())
    marker = b"===== FLOW STANDARD INTENT CATALOG =====\n"
    require(result["returncode"] == 0 and not result["failure"] and result["stdout"].startswith(marker),
            "Compiler did not publish the capability catalog")
    value = loads(result["stdout"][len(marker):])
    require(isinstance(value, list) and bool(value), "Empty capability catalog")
    return value
