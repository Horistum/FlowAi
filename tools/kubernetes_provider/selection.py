"""Bind the approved one-write provider to the installed compiler's selected plan."""
from pathlib import Path

from authoring_host.contracts import MAX_BYTES, digest, keys, loads, require
from authoring_host.inventory_provenance import read_regular


def pinned(path, expected):
    raw = read_regular(path, MAX_BYTES)
    require(digest(raw) == expected, "Reviewed artifact digest mismatch")
    return loads(raw)


def sync_node(plan):
    """Only a single, unconditional sync with its resolved manual approval is implemented."""
    require(plan["planVersion"] == "2.4" and plan["controlDecision"]["status"] == "ALLOWED" and
            not any(plan[key] for key in ("inputs", "triggers", "assumptions", "targetHints", "controlRequirements")) and
            not plan["sourceIntent"]["policies"] and not plan["sourceIntent"]["systems"] and
            plan["sourceIntent"]["failure"] == dict(notify=False, rollback=False, stopOnError=True),
            "Unsupported plan controls")
    nodes = plan["nodes"]
    approvals = [n for n in nodes if n["kind"] == "approval"]
    tasks = [n for n in nodes if n["kind"] == "task" and n.get("semanticCapability") == "SYNC"]
    require(len(nodes) == 2 and len(approvals) == len(tasks) == 1, "Expected one unconditional approved sync")
    approval, task = approvals[0], tasks[0]
    require(all(not n["assumptions"] and not n["targetHints"] and n["outputs"] == [n["resultName"]] for n in nodes) and
            (task["module"], task["action"], task["target"]) == ("standard", "execute", "standard"),
            "Unsupported operation binding or output")
    keys(approval["inputs"], ("message", "mode"))
    require(approval["inputs"].get("mode") == "manual" and not approval["dependencies"] and
            task["dependencies"] == [approval["id"]], "Missing manual approval dependency")
    relations = plan["dependencyRelations"]
    require(len(relations) == 1 and relations[0]["sourceNodeId"] == approval["id"] and
            relations[0]["targetNodeId"] == task["id"] and relations[0]["kind"] == "ORDERING" and
            relations[0]["resolution"] == "RESOLVED", "Approval dependency is not resolved")
    effects = task["effectModel"]
    require(len(effects) == 1 and effects[0]["sourceCapability"] == "SYNC" and
            effects[0]["domain"] == "INFRASTRUCTURE_STATE" and effects[0]["operation"] == "UPSERT" and
            effects[0]["resource"] == "desired.state" and not task["destructive"], "Unsupported sync effect")
    return task


def desired_value(config, pins, api):
    proposal_path = Path(config["proposal"])
    plan_path = config.get("canonicalPlan", proposal_path.parent / "compiler/canonical-execution-plan.json")
    node = sync_node(pinned(plan_path, pins["canonicalPlanSha256"]))
    proposal = pinned(proposal_path, pins["proposalSha256"])
    intent = proposal["response"]["normalizedIntent"]
    # Compiler nodes carry sourceId. Read typed operands from the exact reviewed input
    # without parsing rendered Flow expressions or deciding topology from source order.
    require(len(intent["workflows"]) == 1, "Expected a single workflow")
    workflow, = intent["workflows"]
    collection = "steps" if intent["intentVersion"] == "2.0" else "body"
    keys(workflow, ("name", "kind", collection))
    require(not any(step.get("produces") or step.get("uses") for step in workflow[collection]),
            "Authored outputs and explicit module bindings require another runtime")
    matches = [step for step in workflow[collection] if step.get("id") == node["sourceId"]]
    require(len(matches) == 1 and matches[0]["capability"] == "SYNC", "Missing unique reviewed sync source")
    op, = matches
    params = op["params"]
    keys(params, ("destination", "mode", "source"))
    # Source and mode are restricted ASCII literals. The compiler binds the structured
    # destination independently in its precondition report for this same unique SYNC.
    inputs = node["inputs"]
    keys(inputs, ("source", "mode", "destination"), ("flow", "description", "capability", "operation"))
    raw = read_regular(config["source"], 65536)
    require(params["source"] == "sha256:" + digest(raw) and params["destination"] == api.resource and
            params["mode"] in api.profile.modes and inputs["mode"] == f'"{params["mode"]}"' and
            inputs["source"] == f'"{params["source"]}"', "Reviewed sync operands differ from the commissioned operation")
    return api.profile.desired(loads(raw))
