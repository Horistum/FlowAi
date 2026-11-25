#!/usr/bin/env python3
"""Configure or run a data-only local model for the installed Horistum authoring host."""
import argparse
import hashlib
from http.client import HTTPException
from importlib.metadata import version
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
from typing import NamedTuple
from urllib.request import urlopen


class ExampleModel(NamedTuple):
    filename: str
    url: str
    size: int
    sha256: str


EXAMPLES = {
    "qwen3.5-2b": ExampleModel("Qwen3.5-2B-Q4_K_M.gguf",
        "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/f6d5376be1edb4d416d56da11e5397a961aca8ae/Qwen3.5-2B-Q4_K_M.gguf",
        1_280_835_840, "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223"),
    "qwen3.5-4b": ExampleModel("Qwen3.5-4B-Q4_K_M.gguf",
        "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/e87f176479d0855a907a41277aca2f8ee7a09523/Qwen3.5-4B-Q4_K_M.gguf",
        2_740_937_888, "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4"),
}
DEFAULT_EXAMPLE = "qwen3.5-2b"


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


def download_example(example):
    cache = Path(os.environ.get("XDG_CACHE_HOME", ""))
    directory = (cache if cache.is_absolute() else Path.home() / ".cache") / "horistum/models"
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    model = directory / example.filename
    if model.is_file() and model.stat().st_size == example.size and sha256(model) == example.sha256:
        return model

    print(f"Downloading {example.filename} ({example.size / 1e9:.2f} GB) to {directory} ...", file=sys.stderr, flush=True)
    # Publish verified bytes atomically so interrupted or concurrent first runs
    # never expose a partial model to a saved provider configuration.
    with tempfile.TemporaryDirectory(dir=directory) as staging:
        temporary = Path(staging) / example.filename
        digest = hashlib.sha256()
        with urlopen(example.url, timeout=60) as response, temporary.open("wb") as output:
            size = 0
            for block in iter(lambda: response.read(1024 * 1024), b""):
                size += len(block)
                if size > example.size:
                    raise ValueError("The example model download exceeds its published size.")
                digest.update(block)
                output.write(block)
        if size != example.size or digest.hexdigest() != example.sha256:
            raise ValueError("The example model download did not match its published size and SHA-256. Retry the download.")
        os.replace(temporary, model)
    return model


def configure(args):
    source = Path(__file__).resolve()
    root = source.parents[2]
    compiler = root / "flow-cli/build/install/flow-core/bin/flow-core"
    if not compiler.is_file():
        raise ValueError("Build the product with ./gradlew :flow-cli:installDist first.")
    backend = version("llama-cpp-python")
    commit = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
    if args.download_example:
        example = EXAMPLES[args.download_example]
        model = download_example(example)
        model_id = "sha256:" + example.sha256
    else:
        model = args.model.expanduser().resolve()
        if not model.is_file():
            raise ValueError(f"GGUF model file not found at {str(model)!r}. Supply an existing .gguf file "
                             "or use configure --download-example to download the sample model.")
        model_id = model_identity(model)
    argv = [sys.executable, str(source), "generate", str(model)]
    version_label = "llama-cpp-python/" + backend
    profile = args.profile or ("operations" if args.download_example else "full")
    if profile == "operations":
        argv.extend(["--profile", "operations"])
        version_label += "/operations-v2"
    config = {"formatVersion": 1,
              "provider": {"id": "local-gguf", "model": model_id, "version": version_label,
                           "evidenceKind": "live", "credentialEnv": [],
                           "argv": argv},
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
    print("Model:", model.name, "| Profile:", profile)
    print("Start a chat with", shlex.join(["flow-author", "chat", "--config", str(args.out.absolute())]))


def model_messages(payload):
    """All goals and decision turns use the same complete public contract."""
    inputs = payload["input"].copy()
    request = inputs.pop("request")
    def compact(value):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    return [{"role": "system", "content": payload["instructions"] +
             "\nReturn a single JSON object. Response schema:\n" + compact(payload["output_schema"])},
            {"role": "user", "content": compact({**inputs, "request": request})}]


def generate(model, profile):
    from llama_cpp import Llama, LlamaGrammar
    payload = json.load(sys.stdin)
    model_id = model_identity(model)
    if payload["schema"] != "command-reasoning/v2" or payload["model"] != model_id:
        raise ValueError("Provider protocol or GGUF digest changed.")
    dialogue = None
    if profile == "operations":
        from operations_profile import OperationDialogue
        dialogue = OperationDialogue(payload)
        decoding = {"grammar": LlamaGrammar.from_json_schema(json.dumps(dialogue.schema()), verbose=False)}
        messages = dialogue.messages()
    else:
        decoding = {"response_format": {"type": "json_object"}}
        messages = model_messages(payload)
    llm = Llama(model_path=str(model), n_ctx=16384, n_threads=min(4, os.cpu_count() or 1),
                n_threads_batch=min(4, os.cpu_count() or 1), verbose=False)
    answer = llm.create_chat_completion(messages, **decoding,
                                        temperature=0, seed=42, max_tokens=2048)
    result = json.loads(answer["choices"][0]["message"]["content"])
    if dialogue is not None:
        if answer["choices"][0]["finish_reason"] == "length":
            raise ValueError("Incomplete operation reply.")
        result = dialogue.reply(result)
    usage = answer["usage"]
    print(json.dumps({"schema": "command-reasoning/v2", "result": result,
                      "usage": {"input_tokens": usage["prompt_tokens"], "output_tokens": usage["completion_tokens"]},
                      "provider": {"id": "local-gguf", "model": model_id, "request_id": answer["id"]}}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    setup = commands.add_parser("configure")
    model_choice = setup.add_mutually_exclusive_group(required=True)
    model_choice.add_argument("model", nargs="?", type=Path, help="Existing GGUF model file (first shard for split models)")
    model_choice.add_argument("--download-example", nargs="?", choices=tuple(EXAMPLES), const=DEFAULT_EXAMPLE,
                              help="Download and verify local GGUF weights (default: qwen3.5-2b, 1.28 GB)")
    config_home = Path(os.environ.get("XDG_CONFIG_HOME", ""))
    default_config = (config_home if config_home.is_absolute() else Path.home() / ".config") / "horistum/authoring.json"
    setup.add_argument("--out", type=Path, default=default_config)
    inference = commands.add_parser("generate")
    inference.add_argument("model", type=Path)
    setup.add_argument("--profile", choices=("full", "operations"),
                       help="Defaults to operations for a downloaded example and full for an existing model file")
    inference.add_argument("--profile", choices=("full", "operations"), default="full")
    args = parser.parse_args()
    try:
        if args.command == "configure":
            configure(args)
        else:
            generate(args.model, args.profile)
    except FileExistsError:
        parser.exit(2, "The configuration path already exists. Select a new path.\n")
    except (ImportError, OSError, HTTPException, ValueError, KeyError, subprocess.CalledProcessError) as error:
        if args.command == "configure":
            parser.exit(2, f"Configuration failed: {error}\n")
        parser.exit(2, "Local model inference failed. Check the model, context length and runtime.\n")


if __name__ == "__main__":
    main()
