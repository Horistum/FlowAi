"""Native exclusive branch evidence using the existing disposable composition runner."""
import json
from condition_composition_oracle import bind_graph, bind_manifest, jenkins_envelope, require
from match_composition_oracle import bind_control_structure, expectation, projected_structure
from retry_composition_runtime import ABSENT, replace_once, run_composition


def bind(expected, manifest, graph):
    bind_graph(expected, graph)
    bind_control_structure(expected, graph)
    leaves = []

    def visit(steps):
        result = []
        for step in steps:
            require(step["materialization"]["status"] == "NATIVE", "Unmapped native work")
            if step["type"] == "action":
                require(not step["children"], "Action has children")
                leaves.append(step)
                result.append({"kind": "task", "name": step["metadata"]["resultName"]})
            elif step["type"] == "condition":
                result.append({"kind": "condition", "condition": step["params"]["condition"], "body": visit(step["children"])})
            elif step["type"] == "retry":
                require(set(step["params"]) == {"max", "delay", "backoff"}, "Unexpected retry parameters")
                result.append({"kind": "retry", "policy": dict(step["params"], max=int(step["params"]["max"])),
                               "body": visit(step["children"])})
            elif step["type"] == "loop":
                require(set(step["params"]) == {"item", "source"}, "Unexpected loop parameters")
                collection = json.loads(step["params"]["source"])
                require(type(collection) is list and all(type(v) is str for v in collection), "Invalid native collection")
                result.append({"kind": "loop", "item": step["params"]["item"], "items": collection, "body": visit(step["children"])})
            elif step["type"] == "match":
                children = step["children"]
                require(len(children) >= 3 and [c["type"] for c in children] ==
                        ["match-case"] * (len(children) - 2) + ["match-error", "match-default"] and
                        not children[-2]["children"], "Changed native match branch inventory")
                source = json.loads(step["params"]["source"])
                require(type(source) is str, "Changed native source")
                cases = []
                for branch in children[:-2]:
                    guard = json.loads(branch["params"]["condition"])
                    require(type(guard) is bool, "Changed native guard")
                    cases.append({"guard": guard, "body": visit(branch["children"])})
                result.append({"kind": "match", "source": source, "cases": cases, "default": visit(children[-1]["children"])})
            else:
                raise ValueError("Unexpected manifest control")
        return result

    require(len(manifest["jobs"]) == 1 and visit(manifest["jobs"][0]["steps"]) == projected_structure(expected["structure"]),
            "Native selection, membership or order differs from source")
    return bind_manifest(expected, {"jobs": [{"steps": leaves}]})


def prepare(inputs, output):
    contracts, variants, mappings = {}, {}, {}
    for outcome in ("success", "default", "empty", "failure", *(
            f"{direction}-{result}" for direction in ("retry-match", "match-retry") for result in ("success", "default", "empty", "failure"))):
        result = outcome.split("-")[-1]
        interaction = outcome != result
        directory = inputs / outcome
        document = json.loads((directory / "source.ast.json").read_text())
        initial = expectation(document, {"enabled": True})
        failures = {name: None for name, action in initial["tasks"].items() if action["params"]["branch"]["value"] == ABSENT}
        require(bool(failures) == (result == "failure"), "Fault declaration differs from source")
        initial = expectation(document, {"enabled": True}, failures)
        mapping = bind(initial, json.loads((directory / "manifest.json").read_text()), json.loads((directory / "canonical-graph.json").read_text()))
        baseline, calls = jenkins_envelope((directory / "Jenkinsfile").read_text(), mapping)
        by_name = {mapping[key]: value[1] for key, value in calls.items()}
        candidates = {"baseline": baseline}
        if interaction:
            if result in ("success", "default"):
                candidates["repeated-success"] = replace_once(baseline, "retry(2) {", "for (def flowReplayProbe in [1, 2]) {")
                candidates["deduplicated"] = replace_once(baseline, "['alternate', 'selected', 'selected']", "['alternate', 'selected']")
                candidates["reordered"] = replace_once(baseline, "['alternate', 'selected', 'selected']", "['selected', 'alternate', 'selected']")
            if result in ("success", "empty"):
                candidates["selected-fallthrough"] = replace_once(baseline, "else if (true)", "else if (false)")
            elif result == "default":
                candidates["default-substitution"] = replace_once(baseline, "else if (false)", "else if (true)")
            elif result == "failure":
                candidates["flattened-retry"] = replace_once(baseline, "retry(2) {", "retry(1) {")
                block = by_name["selected"]
                candidates["swallowed-failure"] = replace_once(baseline, block, "try {\n" + block + "} catch (Exception flowMutationFailure) { }\n")
        elif outcome in ("success", "empty"):
            candidates["selected-fallthrough" if outcome == "success" else "empty-fallthrough"] = replace_once(
                baseline, "\n          } else if (true) {", "\n          } else if (false) {")
        if outcome == "success":
            candidates["omitted-prefix"] = replace_once(baseline, by_name["prefix"], "")
            candidates["reordered-body"] = replace_once(replace_once(baseline, by_name["prefix"], "FLOW_MUTATION_SLOT"),
                                                        by_name["bodyTail"], by_name["prefix"]).replace("FLOW_MUTATION_SLOT", by_name["bodyTail"])
        elif outcome == "default":
            candidates["default-substitution"] = replace_once(baseline, "\n            if (false) {", "\n            if (true) {")
        elif outcome == "failure":
            block = by_name["selected"]
            candidates["swallowed-failure"] = replace_once(baseline, block, "try {\n" + block + "} catch (Exception flowMutationFailure) { }\n")
        for enabled in ((True, False) if result in ("success", "default") else (True,)):
            case = f"{outcome}-{'true' if enabled else 'false'}"
            contracts[case] = expectation(document, {"enabled": enabled}, failures)
            mappings[case] = mapping
            for name, text in candidates.items():
                if not enabled and name != "baseline":
                    continue
                variants[f"{case}-{name}"] = {"case": case, "variant": name, "text": text}
    artifacts = output / "artifacts"
    artifacts.mkdir()
    inventory = []
    for name, variant in variants.items():
        file = name + ".Jenkinsfile"
        (artifacts / file).write_text(variant["text"])
        inventory.append({"id": name, "artifact": file, "inputs": contracts[variant["case"]]["inputs"]})
    (artifacts / "runs.json").write_text(json.dumps(inventory, indent=2) + "\n")
    return contracts, mappings, variants, inventory


if __name__ == "__main__":
    run_composition(prepare, "match", "native exclusive selection with retry and finite iteration, empty/default routing, replay ownership and failure propagation",
                    ["Inert string sources and disjoint literal Boolean cases compose with fixed zero-delay retries, finite string loops, tasks and conditions",
                     "No dynamic matching, error branches, implicit result, output export, rollback, workspace isolation or cancellation equivalence claim"])
