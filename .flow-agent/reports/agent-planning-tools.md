# AI-A3-S3.3 — callable planning tools

The selected correction exposes a real MCP stdio interface for existing agents.
Agents can inspect compiler capabilities/contracts, submit an incomplete or invalid
draft, receive canonical diagnostics, repair the draft and inspect its actual plan.
The host's environment tools and reasoning remain outside Flow Core.

Production changes are `agent_planning_server.py` and
`authoring_host/planning_tools.py`. They reuse the existing strict input helpers,
bounded compiler process transport and canonical CLI. No second semantic evaluator,
model runtime, workflow executor or scenario mapping is added. The unmerged goal
case generator and model-authored decision-table layer are removed from this PR.
The pre-canonical discovery receipt correction is retained with regression coverage.

The caller fixes the request, public environment/tool boundaries, executable,
contract root and check limits before starting the server. Agent tools cannot
replace those values, submit independent requirements or approve/execute a plan.
Draft artifacts are immutable by identity and checks can be repeated with corrections.
Compiler acceptance is explicitly unassessed for goal fidelity. A separate caller
review uses the exact captured candidate and independently authored requirements.

Local validation: 495 stdlib tooling tests ran; 483 passed and 12 existing deployed
observer tests could not start their Unix-socket service in this workspace. The
real MCP stdio test passed with SDK 2.3.0. Structure validation passed. Installed
compiler and full exact-head/merge-candidate validation are required in GitHub CI;
no local JVM pass is claimed.

The installed proof covers two domains, canonical dependency and retry rejection,
corrected plans, unresolved decisions, canonical artifact retrieval, and a
structurally valid wrong-goal draft rejected only by separate independent review.
All candidates are deterministic test inputs. Live model generalization, native
control-plane MCP integration, environmental observation authority and actual
runtime recovery remain open. S3, AI-A3, AR-06 and AR-07 stay open; EF-09 stays paused.
