#!/usr/bin/env python3
"""MCP stdio entrypoint for existing agents; compiler and task are operator-selected."""
from __future__ import annotations

import argparse
from pathlib import Path
import sys
import uuid
from typing import Any

from authoring_host.contracts import HostError, loads, read
from authoring_host.planning_tools import PlanningTools


def create_server(planning):
    # Optional transport dependency: ordinary compiler/host commands remain stdlib-only.
    from mcp.server import MCPServer
    from mcp.server.mcpserver.exceptions import ToolError

    server = MCPServer("FlowAi planning", version="1", instructions=(
        "Start with flow_task. Use your host's authorized tools to investigate the permitted environment. "
        "Observations are evidence; interpretations remain hypotheses. Search capabilities and inspect contracts, "
        "then submit complete drafts to flow_check_plan. Use compiler diagnostics and canonical artifacts to "
        "repair dependencies, branches and recovery. Preserve unresolved questions in the proposal report. "
        "Compiler acceptance proves plan consistency only. Caller constraints, goal satisfaction, environmental "
        "claims and runtime outcomes require separate review. These tools cannot execute or approve a plan."))

    def call(function, *args):
        try:
            return function(*args)
        except HostError as error:
            raise ToolError(str(error)) from None
        except (OSError, ValueError, TypeError, RecursionError):
            raise ToolError("Planning tool failed; inspect operator logs and captured candidate") from None

    @server.tool(structured_output=True)
    def flow_task() -> dict[str, Any]:
        """Read the fixed original goal, allowed environment, host tools and constraints."""
        return call(planning.task)

    @server.tool(structured_output=True)
    def flow_search_capabilities(query: str = "", offset: int = 0, limit: int = 10) -> dict[str, Any]:
        """Search compiler capability contracts; empty query lists them with pagination."""
        return call(planning.capabilities, query, offset, limit)

    @server.tool(structured_output=True)
    def flow_get_contract(contract_id: str) -> dict[str, Any]:
        """Read proposal-response JSON schema or a module YAML named by flow_task."""
        return call(planning.contract, contract_id)

    @server.tool(structured_output=True)
    def flow_check_plan(response: dict) -> dict[str, Any]:
        """Compile normalizedIntent and report; return diagnostics and retrievable canonical artifacts.

        Supply only the response, never a replacement request or independent requirements.
        A rejected draft can be corrected in another call. No rendering or execution occurs.
        """
        return call(planning.check, response)

    @server.tool(structured_output=True)
    def flow_get_artifact(candidate_id: str, name: str) -> dict[str, Any]:
        """Read an exact captured proposal or compiler plan listed by flow_check_plan."""
        return call(planning.artifact, candidate_id, name)

    return server


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path, help="Private directory containing a new session for each server launch")
    args = parser.parse_args()
    try:
        args.out.mkdir(mode=0o700, parents=True, exist_ok=True)
        planning = PlanningTools(loads(read(args.config)), args.out / ("session-" + uuid.uuid4().hex))
        create_server(planning).run(transport="stdio")
    except (HostError, OSError, ValueError) as error:
        print(f"Planning server startup failed: {type(error).__name__}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
