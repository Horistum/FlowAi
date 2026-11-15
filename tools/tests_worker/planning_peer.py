"""Scripted app-server peer for transport tests; never a model or reasoning proof."""
import json
import os
from pathlib import Path
import sys
import tomllib

args = sys.argv[1:]
if "--version" in args:
    print("codex-cli planning-fixture"); raise SystemExit
if "--help" in args:
    print("--strict-config --stdio --experimental generate-json-schema"); raise SystemExit
if "generate-json-schema" in args:
    target = Path(args[args.index("--out") + 1]); target.mkdir()
    keys = ("dynamicTools", "inputSchema", "threadId", "turnId", "outputSchema", "approvalPolicy",
            "sandboxPolicy", "permissions", "experimentalApi", "contentItems")
    (target / "schema.json").write_text(json.dumps({"properties": {key: {} for key in keys}}))
    raise SystemExit

assert "app-server" in args and "--strict-config" in args and "--stdio" in args
assert Path.cwd() == Path(os.environ["HOME"])
assert not any(name in os.environ for name in ("OPENAI_API_KEY", "GH_TOKEN", "SSH_AUTH_SOCK"))
config = {}
for index, value in enumerate(args):
    if value != "-c": continue
    key, raw = args[index + 1].split("=", 1); current = config
    for part in key.split(".")[:-1]: current = current.setdefault(part, {})
    current[key.split(".")[-1]] = tomllib.loads("value=" + raw)["value"]
assert config["mcp_servers"] == {} and not config["features"]["shell_tool"]
home = Path(os.environ["CODEX_HOME"]); home.mkdir(parents=True, exist_ok=True)


def send(value):
    print(json.dumps(value), flush=True)


def answer(request, value):
    send(dict(id=request["id"], result=value))


for line in sys.stdin:
    request = json.loads(line); method = request.get("method"); params = request.get("params", {})
    if method == "initialize": answer(request, {})
    elif method == "initialized": pass
    elif method == "config/read": answer(request, {"config": config})
    elif method == "permissionProfile/list": answer(request, {"data": [{"id": "worker_broker", "allowed": True}]})
    elif method == "thread/start":
        tools = {tool["name"] for tool in params["dynamicTools"]}
        assert tools == {"flow_task", "flow_observe", "flow_search_capabilities", "flow_get_contract", "flow_check_plan", "flow_get_artifact"}
        answer(request, {"thread": {"id": "planning-thread"}, "instructionSources": []})
    elif method == "turn/start":
        assert params["permissions"] == "worker_broker" and params["environments"] == []
        counter = home / "turn-count"
        counter.write_text(str(int(counter.read_text()) + 1 if counter.exists() else 1))
        answer(request, {"turn": {"id": "planning-turn", "status": "inProgress"}})
        transcript = json.loads(Path(args[args.index("--transcript") + 1]).read_text())
        results = {}

        def resolve(value):
            if isinstance(value, dict):
                if set(value) == {"ref"}:
                    result = results
                    for part in value["ref"].split("."): result = result[part]
                    return result
                return {key: resolve(child) for key, child in value.items()}
            if isinstance(value, list): return [resolve(child) for child in value]
            return value

        for index, step in enumerate(transcript.get("steps", [])):
            if "crash" in step: raise SystemExit(8)
            if "write" in step:
                Path(step["write"]).write_text(step["text"]); continue
            if "item" in step:
                send({"method": "item/started", "params": {"item": {"type": step["item"]}}}); continue
            arguments = resolve(step["arguments"])
            call_id = step.get("callId", "call-" + str(index))
            send({"id": 1000 + index, "method": "item/tool/call", "params": {
                "threadId": "planning-thread", "turnId": "planning-turn", "callId": call_id,
                "tool": step["tool"], "arguments": arguments}})
            reply = json.loads(sys.stdin.readline())["result"]
            assert reply["success"] == step.get("success", True)
            result = json.loads(reply["contentItems"][0]["text"])
            if "exitCode" in step: assert result["review"]["exitCode"] == step["exitCode"], result
            results[step.get("id", call_id)] = result
        final = resolve(transcript.get("final", {"candidateId": ""}))
        send({"method": "item/completed", "params": {"threadId": "planning-thread", "turnId": "planning-turn",
            "item": {"id": "final", "type": "agentMessage", "phase": "final_answer", "text": json.dumps(final)}}})
        send({"method": "thread/tokenUsage/updated", "params": {"tokenUsage": {"total": {"inputTokens": 10, "outputTokens": 4}}}})
        send({"method": "turn/completed", "params": {"threadId": "planning-thread", "turn": {
            "id": "planning-turn", "status": "completed", "error": None}}})
