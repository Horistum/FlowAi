from pathlib import Path

path = Path("CHANGELOG.md")
text = path.read_text()
heading = "## Unreleased - v0.9.7 correction track"
if heading in text:
    raise SystemExit(0)

marker = "## Unreleased - v0.9.6 architecture foundation"
section = """## Unreleased - v0.9.7 correction track

### v0.9.7.9.1 Evidence and Control Integrity Repair

#### Added

- Added task-scoped approval reachability evidence and fail-closed execution planning for unresolved dynamic controls.
- Added an independent source contradiction gate so explicit backup denial cannot become synthesized positive backup evidence.
- Added descriptor-driven propagation tests for module-required capabilities and Argo CD system configuration.
- Added focused negative coverage for free-form runtime command parameters before canonical lowering.

#### Changed

- Reopened v0.9.7.9 as correction-required after proving that its lowering report certified a discarded TEST command as preserved.
- Standard capability contracts now reject unrepresentable runtime command text instead of carrying it as an opaque escape hatch.
- FlowPlanner now consumes module-declared required capabilities and schema-declared system configuration instead of product-name branches.
- The build-test-deploy reference scenario now uses one consistent unconditional authored approval, and its complete snapshot bundle is regenerated from the canonical pipeline.

#### Removed

- Removed the dead module `targetImplications` model and report surface while retaining descriptor-boundary rejection.
- Removed the unused legacy portable-shell error-handler lowering overload.
- Removed silent control-obligation deduplication that could hide lossy ID collisions.

#### Validation boundary

- Flow CI run `1872` passed clean compilation, the complete test suite, standalone conformance and reference snapshot honesty on implementation head `1689d9400192dd0efbfba9b4d80ee6ab5ec06cdc`.
- The remaining audit findings continue as bounded work items v0.9.7.9.2 through v0.9.7.9.7; the v0.9.7 closure gate remains blocked.

"""
if marker not in text:
    raise SystemExit(f"Missing changelog insertion marker: {marker}")

path.write_text(text.replace(marker, section + marker, 1))
