# Version Implementation Prompt Template

Use this prompt when asking an AI assistant to implement the next Flow version.

```text
Use the Flow Roadmap Development Agent.

Use the latest project package as the source of truth.
Follow `.flow-agent` rules strictly.

Implement the next roadmap version only.

Required sequence:
1. Read release state and roadmap.
2. Identify the next version.
3. Create a work package.
4. Perform architecture and forbidden-direction checks.
5. Inspect the current code.
6. Propose a minimal patch plan.
7. Implement the code.
8. Add or update tests.
9. Add or update conformance vectors if needed.
10. Update documentation and release notes.
11. Run offline validation.
12. Produce release report.
13. Package the result.

Hard constraints:
- Do not introduce runtime execution.
- Do not introduce SDK-first architecture.
- Do not introduce plugin lifecycle.
- Do not introduce target-specific public DSL.
- Do not introduce Jenkins-specific language drift.
- Do not bend code only to satisfy tests.
- Keep repository text in English.
```
