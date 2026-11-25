"""Check a saved portable candidate against the caller's operation expectations."""
import getpass
from pathlib import Path
import tempfile

from .contracts import canonical, digest, loads, read, require
from .presentation import load_session
from .transport import compiler_environment, invoke


def add_command(commands):
    parser = commands.add_parser("check", help="Check operations, literal arguments and order in a saved plan")
    parser.add_argument("--run", type=Path, required=True, help="Existing flow-author session directory")
    parser.add_argument("--expect", action="append", nargs="+", metavar="TERM", default=[],
                        help="Required capability followed by optional name=value arguments. Repeat for more operations")
    parser.add_argument("--forbid", action="append", default=[], metavar="CAPABILITY",
                        help="Reject a plan containing this operation. Repeat for additional exclusions")
    parser.add_argument("--in-order", action="store_true", help="Require each listed operation to precede the next")


def expected_checks(terms, forbidden, ordered, catalog):
    by_name = {entry["capability"]: entry for entry in catalog}
    require(terms or forbidden, "Supply a required operation with --expect or an excluded one with --forbid")
    count = len(terms) + len(forbidden) + (len(terms) - 1 if ordered else 0)
    require(count <= 256, "Specify at most 256 checks")
    require(not ordered or len(terms) >= 2, "--in-order requires at least two operations")
    checks, selectors = [], []
    for name, *arguments in terms:
        capability = name.upper()
        require(capability in by_name, f"Unknown operation {ascii(name)}. Use flow-core catalog to list capabilities.")
        entry = by_name[capability]
        fields = set(entry["requiredParams"]) | set(entry["optionalParams"])
        parameters = {}
        for argument in arguments:
            key, sep, value = argument.partition("=")
            require(sep and key in fields and key not in parameters,
                    f"Expected a distinct catalog parameter name=value for {capability}. Available fields: {', '.join(sorted(fields))}")
            parameters[key] = value
        selector = {"capability": capability, "parameters": parameters}
        require(selector not in selectors, "A repeated expectation needs distinct arguments to identify the operation")
        selectors.append(selector)
        checks.append({"kind": "capability-count", "selector": selector, "minimum": 1})
    if ordered:
        checks.extend({"kind": "before", "before": earlier, "after": later}
                      for earlier, later in zip(selectors, selectors[1:]))
    excluded = [name.upper() for name in forbidden]
    require(len(set(excluded)) == len(excluded), "Duplicate excluded operation")
    for capability in excluded:
        require(capability in by_name, f"Unknown excluded operation {ascii(capability)}. Use flow-core catalog to list capabilities.")
        require(all(s["capability"] != capability for s in selectors), "An operation cannot be both required and excluded")
        checks.append({"kind": "forbid-capability", "capability": capability})
    return checks


def run(args, compiler):
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
    catalog = loads(read(folder / "contracts.json"))["capabilityCatalog"]
    request = loads(read(folder / "request.json"))
    require(loads(proposal)["request"] == request, "Retained proposal and session refer to different requests")
    checks = expected_checks(args.expect, args.forbid, args.in_order, catalog)
    rules = [{"id": f"check-{i}", "sourceQuote": request["userText"], "check": c} for i, c in enumerate(checks, 1)]
    requirements = {"formatVersion": 2, "request": request, "origin": "REVIEWER_AUTHORED",
                    "reviewerId": getpass.getuser(), "confirmation": "CONFIRMED", "requirements": rules}
    require(compiler.is_file(), "Run check with the installed flow-author beside flow-core")
    # The current installation supplies the executable. Session data only supplies inputs.
    with tempfile.TemporaryDirectory(prefix="flow-check-") as work:
        root = Path(work)
        source, criteria, out = root / "proposal.json", root / "requirements.json", root / "review"
        source.write_bytes(proposal)
        criteria.write_bytes(canonical(requirements))
        process = invoke([str(compiler), "proposal", str(source), "--requirements", str(criteria),
                          "--out", str(out)], b"", 120, compiler_environment())
        require(not process["failure"] and (out / "proposal-review.json").is_file(),
                "The installed compiler did not complete the review")
        review = loads(read(out / "proposal-review.json"))
        require(review.get("exitCode") == process["returncode"] and review.get("graphDigest") == graph and
                review.get("inputSource", {}).get("sha256") == digest(canonical(loads(proposal))),
                "The installed compiler could not reproduce the saved plan")
        report = loads(read(out / "request-fidelity-review.json"))
        require(report["graphDigest"] == graph and report["requirementsSource"]["sha256"] == digest(criteria.read_bytes()) and
                [r["id"] for r in report["requirements"]] == [r["id"] for r in rules], "Incomplete operation review")
        return [(r["state"], c) for r, c in zip(report["requirements"], checks)], process["returncode"]
