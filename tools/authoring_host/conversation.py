"""Terminal conversation over the same durable authoring session used by JSON clients."""
import getpass
import json
import os
from pathlib import Path
import shlex
import uuid

from .contracts import canonical, loads, read, require
from .presentation import load_session, session_exit, session_view
from .session import resume, start


def add_command(commands):
    command = commands.add_parser(
        "chat", help="Describe a goal, answer questions and read the portable plan",
        description="Enter a goal and answer questions in plain text. Type :pause to resume a question round later.")
    source = command.add_mutually_exclusive_group()
    source.add_argument("goal", nargs="?", help="Goal in natural language (or enter it when prompted)")
    source.add_argument("--run", type=Path, help="Reopen a saved session")
    command.add_argument("--config", type=Path, help="Provider configuration (default: FLOW_AUTHOR_CONFIG or the local Horistum configuration)")
    command.add_argument("--out", type=Path, help="New session directory (automatically selected by default)")
    command.add_argument("--reviewer", help="Name recorded with answers (defaults to the local user)")


def _input(prompt):
    while True:
        try:
            text = input(prompt).strip()
        except (EOFError, KeyboardInterrupt):
            return None
        if text == ":pause":
            return None
        if text:
            return text
        print("Enter a value, or type :pause to leave.")


def _display(value):
    text = json.dumps(value, ensure_ascii=False, separators=(", ", ": "))
    # A provider can return arbitrary strings, including terminal control characters.
    printable = "".join(char if char.isprintable() else ascii(char)[1:-1] for char in text)
    return printable[1:-1] if isinstance(value, str) else printable


def _statements(statements, indent):
    for statement in statements:
        label = statement.get("capability", statement.get("kind", "statement"))
        print(f"{indent}{_display(statement.get('id'))}  {_display(label)}")
        children = [k for k in ("then", "otherwise", "body", "onFailure") if isinstance(statement.get(k), list)]
        for key, value in statement.items():
            if key in ("id", "capability", "kind") or key in children:
                continue
            if key == "params" and isinstance(value, dict):
                for parameter, supplied in value.items():
                    print(f"{indent}  {_display(parameter)}: {_display(supplied)}")
            else:
                print(f"{indent}  {_display(key)}: {_display(value)}")
        for key in children:
            print(f"{indent}  {key}:")
            _statements(statement[key], indent + "    ")


def show_result(view):
    print(f"Session status: {view['status']}")
    if view.get("failure"):
        if view.get("failurePhase") == "provider" or view["status"] == "provider-error":
            print("The provider did not return a usable reply for this turn. No new plan was compiled.")
        code = view.get("providerReturnCode")
        if code is None:
            print("Stopped:", _display(view["failure"]))
        elif code < 0:
            print(f"Provider process terminated by signal {-code}.")
        else:
            print(f"Provider process exited with code {code}.")
    if view.get("reason"):
        if view["status"] == "provider-cannot-plan":
            print("The model stopped before proposing a plan for the compiler.")
        print("Provider explanation:", _display(view["reason"]))
    if "candidateIntent" in view:
        intent = view["candidateIntent"]
        if view["requestFidelity"] == "unassessed":
            print("Fidelity has not been assessed. Review the operations, resources and ordering against your goal.")
        else:
            print("Request fidelity:", view["requestFidelity"])
        print("Compiler-checked plan:", _display(intent["name"]))
        for key, value in intent.items():
            if key not in ("intentVersion", "name", "kind", "workflows"):
                print(f"  {_display(key)} = {_display(value)}")
        for workflow in intent["workflows"]:
            print(f"Workflow {_display(workflow['name'])} ({_display(workflow['kind'])})")
            _statements(workflow.get("body", workflow.get("steps", [])), "  ")
        for key, value in view.get("reviewNotes", {}).items():
            print(f"{key}: {_display(value)}")
        print("Portable plan:", _display(view["planFile"]))
    for finding in view.get("diagnostics", []):
        print("Compiler diagnostic:", _display(finding))
    if view.get("reviewFile"):
        print("Compiler review:", _display(view["reviewFile"]))
    if view.get("discoveryReviewFile"):
        print("Discovery review:", _display(view["discoveryReviewFile"]))


def _xdg(variable, fallback):
    configured = Path(os.environ.get(variable, ""))
    return configured if configured.is_absolute() else Path.home() / fallback


def chat(args):
    if args.run is not None:
        require(args.config is None and args.out is None, "Use --config and --out only for a new session")
        folder = args.run.absolute()
        state = load_session(folder)
    else:
        config_file = args.config or os.environ.get("FLOW_AUTHOR_CONFIG") or (
            _xdg("XDG_CONFIG_HOME", ".config") / "horistum/authoring.json")
        require(Path(config_file).is_file(),
                "Provider configuration missing. From a FlowAi checkout, configure a local GGUF model with "
                "examples/goal-authoring/local_gguf.py configure MODEL, or select a provider file with --config.")
        config = loads(read(config_file))
        goal = args.goal if args.goal else _input("What would you like to automate? ")
        if goal is None:
            return 3
        if args.out is not None:
            folder = args.out.absolute()
        else:
            parent = _xdg("XDG_STATE_HOME", ".local/state") / "horistum/sessions"
            parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            folder = parent / uuid.uuid4().hex
        print("Session:", _display(str(folder)), flush=True)
        print("Waiting for the provider to review your goal…", flush=True)
        state = start(config, canonical({"userText": goal, "context": {}, "mode": "DRAFT"}), None,
                      folder, interactive=True)

    print("Goal:", _display(loads(read(folder / "request.json"))["userText"]))
    while state["status"] == "awaiting-user":
        # Independent criteria must be supplied through the explicit review interface.
        if state["requirementsFile"] and state["attempts"][-1]["status"] != "clarification-requested":
            print("This review requires confirmed criteria. Use flow-author answer with --requirements.")
            for question in state["pending"]:
                print(_display(question))
            return 3
        print("The provider needs a decision. Type :pause to leave the questions pending.")
        answers = []
        for question in state["pending"]:
            answer = _input(_display(question["question"]) + " ")
            if answer is None:
                print("No answers from this round were submitted. Continue with",
                      shlex.join(["flow-author", "chat", "--run", str(folder)]))
                return 3
            answers.append({"id": question["id"], "answer": answer})
        print("Submitting this round of answers…", flush=True)
        state = resume(folder, canonical({"runId": state["runId"], "reviewerId": args.reviewer or getpass.getuser(),
                                          "answers": answers}))
    show_result(session_view(folder, state))
    return session_exit(state["status"])
