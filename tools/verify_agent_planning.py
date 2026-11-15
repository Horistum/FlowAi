#!/usr/bin/env python3
"""Installed compiler + real MCP client proof; no model or infrastructure execution."""
import argparse
import asyncio
import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

from mcp.client import ClientSession
from mcp.client.stdio import StdioServerParameters, stdio_client
from authoring_host.contracts import canonical, loads, read, require


async def verify(root, cli):
    rows = []
    with tempfile.TemporaryDirectory(prefix='flow-agent-tools-') as temporary:
        work = Path(temporary)
        for case, capability in [('infrastructure-readiness', 'PROVISION'), ('isolated-data-recovery', 'RESTORE')]:
            proposal = loads(read(root / 'conformance/failure-regions' / (case + '.proposal.json')))
            config = dict(formatVersion=1, compiler=dict(argv=[str(cli)], contracts=str(root)),
                task=dict(request=proposal['request'], allowedEnvironment=['The isolated environment in the request'],
                          availableTools=[], constraints=['Preserve the request; do not execute the plan']),
                limits=dict(maxChecks=6, timeoutSeconds=120))
            config_path = work / (case + '.json'); config_path.write_bytes(canonical(config))
            output = work / case
            server = StdioServerParameters(command=sys.executable, args=[str(root / 'tools/agent_planning_server.py'),
                '--config', str(config_path), '--out', str(output)],
                env={k: os.environ[k] for k in ('JAVA_HOME',) if k in os.environ})
            async with stdio_client(server) as (reader, writer):
                async with ClientSession(reader, writer, read_timeout_seconds=150) as client:
                    await client.initialize()
                    async def call(tool_name, **args):
                        result = await client.call_tool(tool_name, args)
                        require(not result.is_error, f'{case}/{tool_name}: {result.content}')
                        require(isinstance(result.structured_content, dict), 'Tool omitted structured result')
                        return result.structured_content

                    task = await call('flow_task')
                    require(task['request'] == proposal['request'], 'Original goal changed')
                    catalog = await call('flow_search_capabilities', query=capability)
                    require(any(c['capability'] == capability for c in catalog['items']), 'Capability unavailable')
                    await call('flow_get_contract', contract_id='proposal-response')
                    await call('flow_get_contract', contract_id='standard.yaml')

                    async def check(response, variant, code, diagnostic=None):
                        result = await call('flow_check_plan', response=response)
                        review = result['review']
                        require(review['exitCode'] == code, f'{case}/{variant}: {review}')
                        require(review['requestFidelity'] == 'unassessed' and not review['renderAuthorized'],
                                'Draft compiler promoted independent review or execution')
                        if diagnostic:
                            require(any(d['code'] == diagnostic for d in review['diagnostics']),
                                    f'{case}/{variant} failed for an unrelated reason: {review}')
                        rows.append(dict(case=case, variant=variant, exitCode=code, stage=review['stage']))
                        return result

                    invalid = copy.deepcopy(proposal['response'])
                    invalid['normalizedIntent']['workflows'][0]['body'][0]['requires'] = ['nonexistent-operation']
                    await check(invalid, 'missing-dependency', 4, 'STRUCTURED_DEPENDENCY_SCOPE')
                    invalid = copy.deepcopy(proposal['response'])
                    invalid['normalizedIntent']['workflows'][0]['body'][-1]['body'][1]['body'][0]['capability'] = 'PROVISION'
                    await check(invalid, 'unsafe-write-retry', 4, 'RETRY_REQUIRES_READ_ONLY_OPERATION')
                    accepted = await check(proposal['response'], 'corrected', 0)
                    candidate_id = accepted['candidateId']
                    captured = await call('flow_get_artifact', candidate_id=candidate_id, name='proposal.json')
                    require(captured['content']['response'] == proposal['response'], 'Candidate was rewritten')
                    plan = await call('flow_get_artifact', candidate_id=candidate_id,
                                      name='compiler/canonical-execution-plan.json')
                    directory = output / task['sessionId'] / candidate_id
                    require(plan['content'] == loads(read(directory / 'compiler/canonical-execution-plan.json')),
                            'Agent received a substituted plan instead of the canonical artifact')
                    require(bool(accepted['review'].get('graphDigest')), 'Accepted draft omitted graph identity')
                    unknown = copy.deepcopy(proposal['response'])
                    unknown['report']['missingDecisions'] = ['Confirm the recovery ownership before proceeding']
                    await check(unknown, 'unresolved-decision', 3)

                    # A compiler-valid draft can still violate the original goal. Check it
                    # separately with the pre-existing caller oracle, never a model-authored one.
                    wrong = copy.deepcopy(proposal['response'])
                    region = wrong['normalizedIntent']['workflows'][0]['body'][-1]
                    region['disposition'] = 'recover' if region['disposition'] == 'propagate' else 'propagate'
                    wrong_result = await check(wrong, 'valid-structure-wrong-goal', 0)
                    for result, expected in ((accepted, 0), (wrong_result, 4)):
                        candidate = output / task['sessionId'] / result['candidateId'] / 'proposal.json'
                        separate = work / (case + '-' + result['candidateId'] + '-independent')
                        process = subprocess.run([str(cli), 'proposal', str(candidate), '--contracts', str(root),
                            '--requirements', str(root / 'conformance/failure-regions' / (case + '.requirements.json')),
                            '--out', str(separate)], capture_output=True, timeout=120)
                        review = loads(read(separate / 'proposal-review.json'))
                        require(process.returncode == expected and review['requestFidelity'] ==
                                ('requirements-satisfied' if expected == 0 else 'violated'),
                                'Separate original-goal review lost authority')
                        rows.append(dict(case=case, variant='independent-review', exitCode=expected,
                                         fidelity=review['requestFidelity']))
    print(json.dumps(dict(status='PASS', evidenceKind='mcp-installed-compiler', cases=rows,
                          liveModelEvaluated=False, externalWorkExecuted=False)))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', default='.')
    parser.add_argument('--cli', required=True)
    args = parser.parse_args()
    asyncio.run(verify(Path(args.root).resolve(), Path(args.cli).resolve()))
