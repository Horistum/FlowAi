"""Scripted app-server peer for transport tests; never a model or reasoning proof."""
import hashlib
import json
import os
from pathlib import Path
import sys
import tomllib
import uuid

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


def json_digest(value):
    raw = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False) + "\n"
    return hashlib.sha256(raw.encode()).hexdigest()


for line in sys.stdin:
    request = json.loads(line); method = request.get("method"); params = request.get("params", {})
    if method == "initialize": answer(request, {})
    elif method == "initialized": pass
    elif method == "config/read": answer(request, {"config": config})
    elif method == "permissionProfile/list": answer(request, {"data": [{"id": "worker_broker", "allowed": True}]})
    elif method == "thread/start":
        thread_id = str(uuid.uuid4())
        model = params["model"]
        tools = {tool["name"] for tool in params["dynamicTools"]}
        assert tools == {"flow_task", "flow_observe", "flow_search_capabilities", "flow_get_contract", "flow_check_plan", "flow_get_artifact"}
        answer(request, {"thread": {"id": thread_id}, "instructionSources": []})
    elif method == "turn/start":
        turn_id = str(uuid.uuid4())
        checked_responses = []
        assert params["permissions"] == "worker_broker" and params["environments"] == []
        counter = home / "turn-count"
        counter.write_text(str(int(counter.read_text()) + 1 if counter.exists() else 1))
        answer(request, {"turn": {"id": turn_id, "status": "inProgress"}})
        transcript = json.loads(Path(args[args.index("--transcript") + 1]).read_text())
        private_canary = transcript.get("privateCanary")
        assert not private_canary or private_canary not in json.dumps(params)
        if "cases" in transcript:
            send({"id": 999, "method": "item/tool/call", "params": {
                "threadId": thread_id, "turnId": turn_id, "callId": "select-fixture",
                "tool": "flow_task", "arguments": {}}})
            reply = json.loads(sys.stdin.readline())["result"]
            task = json.loads(reply["contentItems"][0]["text"])
            assert not private_canary or private_canary not in json.dumps(task)
            last_observations = task["observationsSha256"]
            transcript = transcript["cases"][task["request"]["userText"]]
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
                "threadId": thread_id, "turnId": turn_id, "callId": call_id,
                "tool": step["tool"], "arguments": arguments}})
            reply = json.loads(sys.stdin.readline())["result"]
            assert reply["success"] == step.get("success", True)
            result = json.loads(reply["contentItems"][0]["text"])
            assert not private_canary or private_canary not in json.dumps(result)
            if "exitCode" in step: assert result["review"]["exitCode"] == step["exitCode"], result
            if step["tool"] == "flow_check_plan" and result.get("candidateId"):
                checked_responses.append(dict(responseSha256=json_digest(arguments["response"]),
                                              observationsSha256=result["observationsSha256"]))
            if "observationsSha256" in result:
                last_observations = result["observationsSha256"]
            results[step.get("id", call_id)] = result
        final = resolve(transcript.get("final", {"candidateId": ""}))
        if "--turn-records" in args:
            # Test provider side of the exchange. No access to host receipts or independent reviews.
            path = Path(args[args.index("--turn-records") + 1])
            record = dict(threadId=thread_id, turnId=turn_id, model=model, taskSha256=json_digest({k: task[k] for k in
                          ("request", "allowedEnvironment", "availableTools", "constraints")}),
                          drafts=checked_responses, observationsSha256=last_observations, candidateId=final["candidateId"] or None,
                          latencyMs=None, costMicrousd=None)
            with path.open("a") as stream:
                print(json.dumps(record), file=stream)
        send({"method": "item/completed", "params": {"threadId": thread_id, "turnId": turn_id,
            "item": {"id": "final", "type": "agentMessage", "phase": "final_answer", "text": json.dumps(final)}}})
        send({"method": "thread/tokenUsage/updated", "params": {"tokenUsage": {"total": {"inputTokens": 10, "outputTokens": 4}}}})
        send({"method": "turn/completed", "params": {"threadId": thread_id, "turn": {
            "id": turn_id, "status": "completed", "error": None}}})
