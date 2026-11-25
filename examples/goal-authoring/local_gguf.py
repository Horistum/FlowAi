#!/usr/bin/env python3
"""Configure or run a data-only local model for the installed Horistum authoring host."""
import argparse
import hashlib
from importlib.metadata import version
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def model_identity(path):
    split = re.fullmatch(r"(.+)-(\d{5})-of-(\d{5})\.gguf", path.name)
    if split is None:
        return "sha256:" + sha256(path)
    stem, index, total = split.groups()
    if index != "00001" or int(total) < 1:
        raise ValueError("Select the first file of the split GGUF model.")
    digest = hashlib.sha256()
    for number in range(1, int(total) + 1):
        shard = path.with_name(f"{stem}-{number:05d}-of-{total}.gguf")
        digest.update(bytes.fromhex(sha256(shard)))
    return "sha256-shards:" + digest.hexdigest()


def configure(args):
    source = Path(__file__).resolve()
    root = source.parents[2]
    compiler = root / "flow-cli/build/install/flow-core/bin/flow-core"
    if not compiler.is_file():
        raise ValueError("Build the product with ./gradlew :flow-cli:installDist first.")
    model = args.model.resolve(strict=True)
    if not model.is_file():
        raise ValueError("Select a regular GGUF file.")
    backend = version("llama-cpp-python")
    commit = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
    model_id = model_identity(model)
    config = {"formatVersion": 1,
              "provider": {"id": "local-gguf", "model": model_id, "version": "llama-cpp-python/" + backend,
                           "evidenceKind": "live", "credentialEnv": [],
                           "argv": [sys.executable, str(source), "generate", str(model)]},
              "compiler": {"argv": [str(compiler)], "commit": commit, "contracts": str(root)},
              "budget": {"maxInputTokensPerCall": 150000, "maxOutputTokensPerCall": 2048,
                         "maxCalls": 3, "maxRepairs": 1, "maxTotalTokens": 456144,
                         "inputMicrousdPerMillionTokens": 0, "outputMicrousdPerMillionTokens": 0,
                         "maxCostMicrousd": 0, "timeoutSeconds": 900}}
    payload = json.dumps(config, indent=2) + "\n"
    args.out.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=args.out.parent, mode="w", encoding="utf-8") as out:
        out.write(payload)
        out.flush()
        os.fsync(out.fileno())
        os.link(out.name, args.out)
    print("Configuration saved to", args.out)


def model_messages(payload):
    """All goals and decision turns use the same complete public contract."""
    inputs = payload["input"].copy()
    request = inputs.pop("request")
    def compact(value):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    return [{"role": "system", "content": payload["instructions"] +
             "\nReturn a single JSON object. Response schema:\n" + compact(payload["output_schema"])},
            {"role": "user", "content": compact({**inputs, "request": request})}]


def generate(model):
    from llama_cpp import Llama
    payload = json.load(sys.stdin)
    model_id = model_identity(model)
    if payload["schema"] != "command-reasoning/v2" or payload["model"] != model_id:
        raise ValueError("Provider protocol or GGUF digest changed.")
    messages = model_messages(payload)
    llm = Llama(model_path=str(model), n_ctx=16384, n_threads=min(4, os.cpu_count() or 1),
                n_threads_batch=min(4, os.cpu_count() or 1), verbose=False)
    answer = llm.create_chat_completion(messages, response_format={"type": "json_object"},
                                        temperature=0, seed=42, max_tokens=2048)
    result = json.loads(answer["choices"][0]["message"]["content"])
    usage = answer["usage"]
    print(json.dumps({"schema": "command-reasoning/v2", "result": result,
                      "usage": {"input_tokens": usage["prompt_tokens"], "output_tokens": usage["completion_tokens"]},
                      "provider": {"id": "local-gguf", "model": model_id, "request_id": answer["id"]}}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    setup = commands.add_parser("configure")
    setup.add_argument("model", type=Path)
    config_home = Path(os.environ.get("XDG_CONFIG_HOME", ""))
    default_config = (config_home if config_home.is_absolute() else Path.home() / ".config") / "horistum/authoring.json"
    setup.add_argument("--out", type=Path, default=default_config)
    inference = commands.add_parser("generate")
    inference.add_argument("model", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "configure":
            configure(args)
        else:
            generate(args.model)
    except FileExistsError:
        parser.exit(2, "The configuration path already exists. Select a new path.\n")
    except (ImportError, OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        if args.command == "configure":
            parser.exit(2, f"Configuration failed: {error}\n")
        parser.exit(2, "Local model inference failed. Check the model, context length and runtime.\n")


if __name__ == "__main__":
    main()
