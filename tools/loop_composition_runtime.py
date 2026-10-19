"""Native finite iteration evidence using the shared disposable composition observer."""
import json
from condition_composition_oracle import bind_graph, bind_manifest, jenkins_envelope, require
from loop_composition_oracle import bind_control_structure, expectation
from retry_composition_runtime import ABSENT, replace_once, run_composition


def bind(expected, manifest, graph):
    bind_graph(expected, graph)
    bind_control_structure(expected, graph)
    leaves, loops = [], []
    def visit(steps):
        for step in steps:
            require(step["materialization"]["status"] == "NATIVE", "Unmapped native work")
            if step["type"] == "action":
                require(not step["children"], "Action has children")
                leaves.append(step)
            elif step["type"] in ("condition", "loop"):
                if step["type"] == "loop":
                    require(set(step["params"]) == {"item", "source"}, "Unexpected loop parameters")
                    loops.append({"item": step["params"]["item"], "items": json.loads(step["params"]["source"])})
                visit(step["children"])
            else:
                raise ValueError("Unexpected manifest control")
    for job in manifest["jobs"]:
        visit(job["steps"])
    mapping = bind_manifest(expected, {"jobs": [{"steps": leaves}]})
    require(loops == expected["loops"], "Native loop collections or bindings differ")
    return mapping


def prepare(inputs, output):
    contracts, variants, mappings = {}, {}, {}
    for outcome in ("success", "failure", "empty"):
        directory = inputs / outcome
        document = json.loads((directory / "source.ast.json").read_text())
        initial = expectation(document, {"enabled": True})
        failures = {name: None for name, action in initial["tasks"].items() if action["params"]["branch"]["value"] == ABSENT}
        require(bool(failures) == (outcome == "failure"), "Fault declaration differs from source")
        initial = expectation(document, {"enabled": True}, failures)
        mapping = bind(initial, json.loads((directory / "manifest.json").read_text()), json.loads((directory / "canonical-graph.json").read_text()))
        baseline, calls = jenkins_envelope((directory / "Jenkinsfile").read_text(), mapping)
        by_name = {mapping[key]: value[1] for key, value in calls.items()}
        candidates = {"baseline": baseline}
        if outcome == "success":
            candidates["deduplicated"] = replace_once(baseline, "['selected', 'selected', 'alternate']", "['selected', 'alternate']")
            candidates["reordered"] = replace_once(baseline, "['selected', 'selected', 'alternate']", "['alternate', 'selected', 'selected']")
            candidates["flattened-inner"] = replace_once(baseline, "['one', 'two']", "['one']")
            candidates["omitted-prefix"] = replace_once(baseline, by_name["prefix"], "")
        elif outcome == "empty":
            candidates["invented-iteration"] = replace_once(baseline, "for (def outer in [])", "for (def outer in ['selected'])")
        else:
            block = by_name["selected"]
            candidates["swallowed-failure"] = replace_once(baseline, block, "try {\n" + block + "} catch (Exception flowMutationFailure) { }\n")
        for enabled in ((True, False) if outcome == "success" else (True,)):
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
    run_composition(prepare, "loop", "native finite sequential iteration, multiplicity, lexical nesting, empty collections and failure routing",
                    ["Literal string collections with sequential tasks, conditions and nested loops only",
                     "No loop-output export, rollback, workspace isolation or cancellation equivalence claim"])
