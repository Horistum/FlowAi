# Agent planning tools

An existing agent can now ask FlowAi for capability contracts, compile a draft,
inspect the canonical result and revise the draft in the same session. The server
uses the official MCP stdio transport. It delegates all plan semantics to the
installed `flow-core proposal` command; it contains no scenario-to-plan mapping.

## Start a session

Use Python 3.11+ on Linux and an installed Flow CLI from this checkout:

```sh
python3 -m venv /path/to/flow-agent-tools-venv
/path/to/flow-agent-tools-venv/bin/pip install -r tools/requirements-agent-planning.txt
./gradlew :flow-cli:installDist
```

The operator writes a configuration containing the original request and public
boundaries. Paths below are absolute examples to replace with actual paths.

```json
{
  "formatVersion": 1,
  "compiler": {
    "argv": ["/path/to/FlowAi/flow-cli/build/install/flow-core/bin/flow-core"],
    "contracts": "/path/to/FlowAi"
  },
  "task": {
    "request": {
      "userText": "Plan an isolated recovery rehearsal for the orders database. Discover the available recovery points and validation tools before choosing the method.",
      "mode": "DRAFT"
    },
    "allowedEnvironment": ["Read the recovery inventory; target the isolated rehearsal environment"],
    "availableTools": ["The agent host provides a read-only inventory reader"],
    "constraints": ["Never modify production", "Retain the source recovery point", "Ask about unresolved ownership"]
  },
  "limits": {"maxChecks": 8, "timeoutSeconds": 120}
}
```

Register this command in the existing agent's MCP client configuration:

```json
{
  "mcpServers": {
    "flow-planning": {
      "command": "/path/to/flow-agent-tools-venv/bin/python",
      "args": [
        "/path/to/FlowAi/tools/agent_planning_server.py",
        "--config", "/path/to/task.json",
        "--out", "/path/to/private-planning-sessions"
      ],
      "env": {"JAVA_HOME": "/path/to/jdk-25"}
    }
  }
}
```

Client configuration syntax varies; the executable and arguments are the portable
contract. Each launch creates a new session under `--out`, so reconnecting does not
overwrite prior drafts. `flow_task` returns its session ID. The current brokered
control-plane worker disables MCP; using this server there requires an explicit
host integration. No automatic integration or live model evaluation is claimed.

## Agent workflow

| Tool | Actual behavior |
| --- | --- |
| `flow_task` | Returns the fixed request, public boundaries, contract IDs and remaining draft checks. |
| `flow_search_capabilities` | Searches the installed compiler's catalog, with pagination and exact parameter/design-question information. |
| `flow_get_contract` | Returns the public proposal-response schema or an exact named module contract. |
| `flow_check_plan` | Compiles a complete `normalizedIntent` and `report`; returns compiler diagnostics, review items, graph identity and artifact IDs. |
| `flow_get_artifact` | Returns a captured proposal or canonical plan after verifying its digest. |

Start with the goal, then investigate through the host's authorized tools. Use the
catalog to identify supported operations and questions still needing answers. Keep
observations separate from interpretations. Express missing decisions and unknowns
in the existing proposal report. Send a draft to `flow_check_plan`, inspect its
errors or canonical plan, and submit a complete corrected response when needed.
There is no required solution table or fixed sequence of infrastructure/data steps.

The task's environment/tool descriptions are caller context, **not permissions
created or enforced for another agent host**. That host must enforce its own tools,
credentials and allowed environment. This server has no environment exploration,
shell, approval, rendering or execution tool. Agent calls cannot choose process
paths, replace the original request or supply independent requirements.

## Separate review

Every check retains `SESSION/draft-NNN/proposal.json`, compiler artifacts and a
`result.json` with exact input/compiler/contract identities. Invalid plans remain
available alongside repairs. Tool errors and compiler process failures consume a
reserved check but never become evidence of a semantic rejection.

Draft checks intentionally omit independent requirements. A compiler-accepted draft
therefore still reports `requestFidelity: unassessed`. The caller reviews the exact
captured candidate with separately authored constraints:

```sh
/path/to/flow-core proposal /path/to/SESSION/draft-003/proposal.json \
  --contracts /path/to/FlowAi \
  --requirements /path/to/caller-requirements.json \
  --out /path/to/new-independent-review
```

The requirements document must retain the original request. Natural-language goal
satisfaction, observation truth and successful runtime recovery need additional
independent evidence. Neither the proposing agent's report nor canonical compiler
acceptance supplies that evidence.

## Verification

`tools/tests/test_planning_tools.py` tests boundaries and receipt handling.
`tools/tests_mcp/test_agent_planning_stdio.py` uses a real MCP client/server process.
`tools/verify_agent_planning.py --cli ...` runs infrastructure and data-recovery
repair sequences through MCP against the installed compiler, then independently
checks a valid and a goal-violating candidate. The existing failure-region corpus
supplies test candidates only; production tools never load it. These are protocol
and compiler tests, not evidence of autonomous model reasoning or executed recovery.
