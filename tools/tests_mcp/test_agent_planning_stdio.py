"""Real stdio MCP transport with a deterministic compiler process peer."""
import asyncio
from pathlib import Path
import sys
import tempfile
import unittest

sys.path[:0] = [str(Path(__file__).resolve().parents[1]), str(Path(__file__).resolve().parents[1] / 'tests')]
from mcp.client import ClientSession
from mcp.client.stdio import StdioServerParameters, stdio_client
from authoring_host.contracts import canonical
from test_planning_tools import ROOT, configuration, response


class PlanningStdioTests(unittest.TestCase):
    def test_real_client_discovers_tools_repairs_and_reads_canonical_artifact(self):
        asyncio.run(self.exercise())

    async def exercise(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            config = root / 'config.json'; config.write_bytes(canonical(configuration()))
            server = StdioServerParameters(command=sys.executable, args=[str(ROOT / 'tools/agent_planning_server.py'),
                '--config', str(config), '--out', str(root / 'session')])
            async with stdio_client(server) as (reader, writer):
                async with ClientSession(reader, writer, read_timeout_seconds=20) as client:
                    await client.initialize()
                    listed = await client.list_tools()
                    self.assertEqual({'flow_task', 'flow_search_capabilities', 'flow_get_contract',
                                      'flow_check_plan', 'flow_get_artifact'}, {t.name for t in listed.tools})
                    # Invalid input is a recoverable tool error, not a broken connection.
                    invalid = await client.call_tool('flow_get_contract', {'contract_id': '../config.json'})
                    self.assertTrue(invalid.is_error)
                    task = await client.call_tool('flow_task', {})
                    self.assertEqual(configuration()['task']['request'], task.structured_content['request'])
                    for tool, args in [('flow_search_capabilities', {'query': 'verify'}),
                                       ('flow_get_contract', {'contract_id': 'proposal-response'})]:
                        self.assertFalse((await client.call_tool(tool, args)).is_error)
                    rejected = await client.call_tool('flow_check_plan', {'response': response('reject')})
                    self.assertFalse(rejected.is_error)
                    self.assertEqual(4, rejected.structured_content['review']['exitCode'])
                    accepted = await client.call_tool('flow_check_plan', {'response': response()})
                    self.assertFalse(accepted.is_error)
                    result = accepted.structured_content
                    self.assertEqual(0, result['review']['exitCode'])
                    plan = await client.call_tool('flow_get_artifact', dict(candidate_id=result['candidateId'],
                        name='compiler/canonical-execution-plan.json'))
                    self.assertFalse(plan.is_error)
                    self.assertEqual({'testOnly': True}, plan.structured_content['content'])
                    # A proposing agent cannot install its own independent requirements.
                    injected = await client.call_tool('flow_check_plan',
                        {'response': dict(response(), requirements={'confirmation': 'CONFIRMED'})})
                    self.assertTrue(injected.is_error)
                    self.assertEqual(1, (await client.call_tool('flow_task', {})).structured_content['checksRemaining'])


if __name__ == '__main__':
    unittest.main()
