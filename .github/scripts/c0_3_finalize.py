from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one occurrence in {path}, found {count}: {old!r}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


completion = '''completionBoundary:
  status: passed
  workflow: "Flow CI"
  runNumber: 2750
  runId: 30992449903
  exactHead: "dd1b049b0c8e768a43f19da4280fe80f57d7e27f"
  mergeCandidate: "08a82daa8d7827216482b40779e183a7a96f0a1a"'''

work_package = ".flow-agent/work-packages/C0.3-semantic-equivalence-rules.yaml"
replace_once(work_package, "status: active", "status: complete")
replace_once(work_package, "completionBoundary: {}", completion)

conformance = ".flow-agent/roadmap-conformance.yaml"
replace_once(
    conformance,
    '''currentDecision:
  completedItem: "C0.2"
  completedItemName: "Abstract Topology Matrix"
  nextItem: "C0.3"
  nextItemName: "Semantic Equivalence Rules"
  decisionBasis: "Flow CI #2679, run 30913933725, passed C0.2 validation on exact head 4edb2b1440966bdc794d4f001021367654c8b4e7 and synthetic merge candidate 2ec62d58b5f7e1944e11c8c31354cdf0366770a7. C0.2 is complete and C0.3 is the adjacent active conformance item."''',
    '''currentDecision:
  completedItem: "C0.3"
  completedItemName: "Semantic Equivalence Rules"
  nextItem: "C0.4"
  nextItemName: "Adapter Profile Evidence"
  decisionBasis: "Flow CI #2750, run 30992449903, passed the distinct C0.3 completion boundary on exact head dd1b049b0c8e768a43f19da4280fe80f57d7e27f and synthetic merge candidate 08a82daa8d7827216482b40779e183a7a96f0a1a. C0.3 is complete and C0.4 is the adjacent active conformance item."'''
)
replace_once(
    conformance,
    '''  - version: "C0.3"
    name: "Semantic Equivalence Rules"
    type: "equivalence-evidence"
    status: next
    purpose: "Define observable equivalence for results and continuity."
    dependsOnCore: "0.9.7.10"
    dependsOnConformance: "C0.2"
    completionEvidence:
      - "Required observations remain equivalent across implementations."''',
    '''  - version: "C0.3"
    name: "Semantic Equivalence Rules"
    type: "equivalence-evidence"
    status: completed
    purpose: "Define observable equivalence for results and continuity."
    dependsOnCore: "0.9.7.10"
    dependsOnConformance: "C0.2"
    completionEvidence:
      - "Required semantic effects, result identities, result values and continuity observations are derived before implementation comparison."
      - "Missing, weakened, unknown, contradictory, duplicate and undeclared observations fail closed."
      - "Jenkins and GitHub Actions prove the same observations through production manifests, continuity evidence and rendering receipts without defining universal meaning."
      - "Flow CI #2743 passed the implementation boundary and Flow CI #2750 passed the distinct completion boundary."'''
)
replace_once(
    conformance,
    '''  - version: "C0.4"
    name: "Adapter Profile Evidence"
    type: "adapter-evidence"
    status: planned''',
    '''  - version: "C0.4"
    name: "Adapter Profile Evidence"
    type: "adapter-evidence"
    status: next'''
)

roadmap = ".flow-agent/roadmap.yaml"
replace_once(roadmap, 'currentTrack: "conformance-semantic-equivalence"', 'currentTrack: "conformance-adapter-profile-evidence"')
replace_once(
    roadmap,
    '''  nextItem: "C0.3"
  nextItemName: "Semantic Equivalence Rules"
  nextItemStream: "conformance"
  decisionBasis: "Flow CI #2679, run 30913933725, passed the distinct C0.2 completion boundary on exact head 4edb2b1440966bdc794d4f001021367654c8b4e7 and synthetic merge candidate 2ec62d58b5f7e1944e11c8c31354cdf0366770a7. C0.2 is complete and C0.3 is selected as the active conformance item."''',
    '''  nextItem: "C0.4"
  nextItemName: "Adapter Profile Evidence"
  nextItemStream: "conformance"
  decisionBasis: "Flow CI #2750, run 30992449903, passed the distinct C0.3 completion boundary on exact head dd1b049b0c8e768a43f19da4280fe80f57d7e27f and synthetic merge candidate 08a82daa8d7827216482b40779e183a7a96f0a1a. C0.3 is complete and C0.4 is selected as the active conformance item."'''
)

release = ".flow-agent/release-state.yaml"
replace_once(release, '  activeTrack: "conformance-semantic-equivalence"', '  activeTrack: "conformance-adapter-profile-evidence"')
replace_once(
    release,
    '''  nextItem: "C0.3"
  nextItemName: "Semantic Equivalence Rules"''',
    '''  nextItem: "C0.4"
  nextItemName: "Adapter Profile Evidence"'''
)
release_path = Path(release)
release_text = release_path.read_text(encoding="utf-8")
old_boundary = "passed-on-c0.2-completion-boundary"
boundary_count = release_text.count(old_boundary)
if boundary_count != 10:
    raise SystemExit(f"Expected 10 C0.2 validation boundary references, found {boundary_count}")
release_text = release_text.replace(old_boundary, "passed-on-c0.3-completion-boundary")
old_source = '  validationSource: "Flow CI #2679, run 30913933725, passed exact C0.2 completion head 4edb2b1440966bdc794d4f001021367654c8b4e7 and synthetic merge candidate 2ec62d58b5f7e1944e11c8c31354cdf0366770a7."'
new_source = '  validationSource: "Flow CI #2750, run 30992449903, passed exact C0.3 completion head dd1b049b0c8e768a43f19da4280fe80f57d7e27f and synthetic merge candidate 08a82daa8d7827216482b40779e183a7a96f0a1a."'
if release_text.count(old_source) != 1:
    raise SystemExit("Expected exactly one previous validationSource")
release_text = release_text.replace(old_source, new_source, 1)
old_note = '    - "C0.3 Semantic Equivalence Rules is the active conformance item."'
new_note = '''    - "C0.3 Semantic Equivalence Rules is complete with distinct Flow CI #2743 implementation and #2750 completion boundaries."
    - "C0.4 Adapter Profile Evidence is the active conformance item."'''
if release_text.count(old_note) != 1:
    raise SystemExit("Expected exactly one active C0.3 note")
release_path.write_text(release_text.replace(old_note, new_note, 1), encoding="utf-8")

lifecycle_test = "src/test/kotlin/SemanticEquivalenceRoadmapLifecycleAuthorityTests.kt"
replace_once(lifecycle_test, "fun repositoryIsOneValidValidatingBoundary()", "fun repositoryIsOneValidCompletedBoundary()")
replace_once(
    lifecycle_test,
    "assertEquals(SemanticEquivalenceLifecyclePhase.VALIDATING, report.phase)",
    "assertEquals(SemanticEquivalenceLifecyclePhase.COMPLETED, report.phase)"
)

transition_test = "src/test/kotlin/RoadmapStreamTransitionAuthorityTests.kt"
replace_once(transition_test, "fun repositoryActivatesC03AfterCompletedC02()", "fun repositoryActivatesC04AfterCompletedC03()")
replace_once(
    transition_test,
    "expected = RoadmapTransitionPhase.C0_3_ACTIVE,",
    "expected = RoadmapTransitionPhase.C0_4_ACTIVE,"
)

Path(".flow-agent/c0-3-finalization.pending").write_text(
    "C0.3 completion metadata staged from Flow CI #2750. Delete this marker through the GitHub connector to trigger final exact-head and merge-candidate validation.\n",
    encoding="utf-8"
)

for disposable in (
    Path(".github/workflows/c0-3-finalize-and-cleanup.yml"),
    Path(".github/scripts/c0_3_finalize.py"),
):
    if not disposable.is_file():
        raise SystemExit(f"One-shot file is unexpectedly missing: {disposable}")
    disposable.unlink()
