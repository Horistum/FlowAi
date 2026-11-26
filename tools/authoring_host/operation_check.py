"""Check a saved portable candidate against the caller's operation expectations."""
from dataclasses import dataclass
import getpass
import json
from pathlib import Path
import tempfile

from .contracts import canonical, digest, loads, read, require
from .operation_expectations import expected_checks
from .presentation import describe_selector, display_value, load_session, proposed_arguments
from .target_output import export_result
from .transport import compiler_environment, invoke, read_capability_catalog


def add_command(commands):
    parser = commands.add_parser("check", help="Check operations, literal arguments and order in a saved plan")
    parser.add_argument("--only", action="store_true", help="Require exactly the listed operation occurrences")
    parser.add_argument("--run", type=Path, required=True, help="Existing flow-author session directory")
    _review_arguments(parser)
    export = commands.add_parser("export", help="Review a proposal and write its compiler-authorized target file")
    export.set_defaults(only=True)
    source = export.add_mutually_exclusive_group(required=True)
    source.add_argument("--run", type=Path, help="Existing flow-author session directory")
    source.add_argument("--proposal", type=Path, help="Proposal JSON file, including its original request")
    export.add_argument("--target", required=True, help="Compiler target identifier")
    export.add_argument("--out", type=Path, required=True, help="Directory for the target file")
    _review_arguments(export)


def _review_arguments(parser):
    parser.add_argument("--expect", action="append", nargs="+", metavar="TERM", default=[],
                        help="Required capability followed by optional name=value arguments. Repeat for more operations")
    parser.add_argument("--forbid", action="append", default=[], metavar="CAPABILITY",
                        help="Reject a plan containing this operation. Repeat for additional exclusions")
    parser.add_argument("--in-order", action="store_true", help="Require each listed operation to precede the next")


def _show_findings(rules, results, mode, intent):
    mismatched = set()
    for rule, result in zip(rules, results):
        check, state = rule["check"], result["state"]
        if mode == "retained":
            print(state, "requirement:", display_value(rule["id"]), display_value(check["kind"]))
        elif check["kind"] == "before":
            print(state, "order:", describe_selector(check["before"]), "before", describe_selector(check["after"]))
        elif check["kind"] == "forbid-capability":
            if mode != "complete" or state != "SATISFIED":
                print(state, "excluded operation:", check["capability"])
        else:
            label = f"expected count {check['maximum']}:" if "maximum" in check else "required operation:"
            print(state, label, describe_selector(check["selector"]))
            if state != "SATISFIED" and check["selector"]["parameters"]:
                mismatched.add(check["selector"]["capability"])
    if mismatched and intent:
        for args in proposed_arguments(intent, mismatched):
            print("Proposed arguments:", args)


@dataclass(frozen=True)
class ReviewInputs:
    proposal: bytes
    criteria: bytes
    mode: str
    saved_graph: str | None = None
    session_status: str | None = None


def _expected_criteria(args, compiler, request, folder=None):
    catalog = (read_capability_catalog([str(compiler)], 120) if args.only else
               loads(read(folder / "contracts.json"))["capabilityCatalog"])
    checks = expected_checks(args.expect, args.forbid, args.in_order, catalog, args.only)
    rules = [{"id": f"check-{i}", "sourceQuote": request["userText"], "check": c} for i, c in enumerate(checks, 1)]
    return canonical({"formatVersion": 2, "request": request, "origin": "REVIEWER_AUTHORED",
                      "reviewerId": getpass.getuser(), "confirmation": "CONFIRMED", "requirements": rules})


def _saved_inputs(args, compiler):
    folder = args.run.absolute()
    require((folder / "state.json").is_file(), "Specify a saved flow-author session with --run")
    state = load_session(folder)
    attempt = state["attempts"][-1] if state["attempts"] else {}
    graph = attempt.get("review", {}).get("graphDigest")
    require(graph and attempt["review"].get("semanticAcceptance") == "accepted",
            "This session has no compiler-admitted candidate to check")
    name = attempt["directory"] + "/proposal.json"
    require(name in state["files"] and Path(attempt["directory"]).name == attempt["directory"],
            "Missing retained proposal")
    proposal = read(folder / name)
    require(digest(proposal) == state["files"][name], "Retained proposal changed")
    request = loads(read(folder / "request.json"))
    candidate = loads(proposal)
    require(candidate["request"] == request, "Retained proposal and session refer to different requests")
    exporting = args.command == "export"
    if exporting:
        require(state["status"] in ("accepted", "plan-ready-for-review", "target-blocked"),
                "Resolve the outstanding session review before exporting a target file")
        require(not args.out.resolve().is_relative_to(folder.resolve()), "Choose an output directory outside the session")
    retained = attempt.get("requirementsFile") if exporting else None
    if retained:
        require(not (args.expect or args.forbid or args.in_order),
                "This session already has independent criteria. Export it without replacing the saved requirements")
        receipt_name = attempt["directory"] + "/review/request-fidelity-review.json"
        require(retained in state["files"] and receipt_name in state["files"], "Missing original requirements or receipt")
        criteria = read(folder / retained)
        original = loads(read(folder / receipt_name))
        require(digest(criteria) == state["files"][retained] == original["requirementsSource"]["sha256"]
                and original["graphDigest"] == graph, "Retained requirements changed")
        return ReviewInputs(proposal, criteria, "retained", graph, state["status"])
    require(not exporting or not state["requirementsFile"], "Missing the criteria for this attempt")
    criteria = _expected_criteria(args, compiler, request, folder)
    return ReviewInputs(proposal, criteria, "complete" if args.only else "listed", graph, state["status"])


def run(args, compiler):
    require(compiler.is_file(), "Use the installed flow-author beside flow-core")
    exporting = args.command == "export"
    if args.run is not None:
        inputs = _saved_inputs(args, compiler)
    else:
        proposal = read(args.proposal)
        criteria = _expected_criteria(args, compiler, loads(proposal)["request"])
        inputs = ReviewInputs(proposal, criteria, "complete")
    rules = loads(inputs.criteria)["requirements"]
    # The current installation supplies the executable. Session data only supplies inputs.
    with tempfile.TemporaryDirectory(prefix="flow-check-") as work:
        root = Path(work)
        source, criteria, out = root / "proposal.json", root / "requirements.json", root / "review"
        source.write_bytes(inputs.proposal)
        criteria.write_bytes(inputs.criteria)
        command = [str(compiler), "proposal", str(source), "--requirements", str(criteria), "--out", str(out)]
        if exporting:
            command.extend(["--target", args.target, "--render"])
        process = invoke(command, b"", 120, compiler_environment())
        require(not process["failure"] and (out / "proposal-review.json").is_file(),
                "The installed compiler did not complete the review")
        review = loads(read(out / "proposal-review.json"))
        require(review.get("exitCode") == process["returncode"], "Compiler review and process status disagree")
        if review["stage"] == "INPUT" and review["exitCode"] == 2:
            for issue in review["diagnostics"]:
                print("Compiler input error:", display_value(issue["code"]), display_value(issue["field"]))
            return 2
        graph = review.get("graphDigest")
        require(review.get("inputSource", {}).get("sha256") == digest(inputs.proposal),
                "The compiler reviewed a different proposal")
        require(inputs.saved_graph is None or graph == inputs.saved_graph, "The installed compiler could not reproduce the saved graph")
        report = loads(read(out / "request-fidelity-review.json"))
        require(report["graphDigest"] == graph and report["requirementsSource"]["sha256"] == digest(inputs.criteria) and
                [r["id"] for r in report["requirements"]] == [r["id"] for r in rules], "Incomplete operation review")
        if inputs.session_status:
            print("Saved session status:", json.dumps(inputs.session_status))
        intent = loads(inputs.proposal)["response"]["normalizedIntent"] if graph else None
        _show_findings(rules, report["requirements"], inputs.mode, intent)
        if graph:
            print("Checked the complete list of catalog operations." if inputs.mode == "complete"
                  else "Checked the listed expectations on the saved plan.")
        else:
            print("The compiler rejected the proposal before operation review.")
        if exporting:
            export_result(args.target, args.out, out, review, report)
        return process["returncode"]
