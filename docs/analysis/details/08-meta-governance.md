# FlowAi / Horistum — Analýza č. 8: Meta vrstva (CLI, architecture governance, standard/artifact model, roadmap tracking)

Rozsah: `architecture/`, `standard/`, `artifacts/`, `roadmap/`, `release/`, `distribution/`, `notes/`, `materialization/`, `verification/`, `identity/`, `serialization/`, `preview/`, `continuity/`, `frontend/`, `cli/` — 66 souborů, 13 241 řádků (ověřeno `wc -l`, odpovídá zadání). Všech 66 souborů přečteno celých.

---

## 1. Přehled každého balíčku

**`architecture/`** (9 souborů, 2467 ř.) — Governance vrstva "governance nad governance": vlastní lexer Kotlinu (`KotlinLexicalScanner`, `KotlinSourceBoundaryScanner`), skener zakázaných architektonických směrů (forbidden-directions, drift score), katalog "Authority" typů, CI/CD-bias inventář a delta-analyzer porovnávající `StandardModel` snapshoty mezi release. Je to největší a nejsofistikovanější balíček v rozsahu.

**`standard/`** (9 souborů, 1308 ř.) — Centrální registr veřejného standardu: `StandardModel` (55 checků + 15 artefaktů), diagnostic catalog (stabilní kódy), intent/capability katalogy, schema-ownership index. Jde o "single source of truth", ze kterého se odvozuje většina reportů v `artifacts/`.

**`artifacts/`** (14 souborů, 2230 ř.) — Generátory veřejných JSON reportů (public-standard-surface, compatibility-policy, reference-intent-corpus, target-semantics-matrix, standard-export-bundle/manifest, bundle verifier...). Čistě odvozené z `StandardModel`, bez vlastního stavu kromě staticky zapsaných dat (referenční scénáře, safety matice).

**`roadmap/`** (3 soubory, 2078 ř.) — Ruční state-machine validátory postupu interního roadmap procesu (`.flow-agent/*.yaml`). Obsahuje `RoadmapStreamTransitionAuthority` (1530 ř., najhutnější soubor v repozitáři) a `ToolchainModernizationLifecycle` (469 ř.), obě s natvrdo zapsanými git SHA, PR čísly a CI run ID historických milníků.

**`release/`** (4 soubory, 1356 ř.) — Honestní reconciliace release metadat (verze balíčku, standardu, artefaktových kontraktů) napříč `build.gradle.kts`, `.flow-agent/*.yaml` a `REPORT.md`; sestavuje a publikuje finální release bundle (`StandardReleaseAssembly`) s atomickým zápisem přes staging adresář.

**`distribution/`** (3 soubory, 239 ř.) — Kompoziční kořen "reference" distribuce: jediné místo, kde se generické evidence-autority svazují s konkrétními adaptéry (Jenkins/GitHub Actions/Tekton).

**`notes/`** (4 soubory, 353 ř.) — Loader a validátor "notes" balíčků (YAML popisy doménové/kapabilitní/safety sémantiky) s ochranou proti path-traversal a cyklickým závislostem.

**`materialization/`** (3 soubory, 695 ř.) — Typované "explicit target selection" API (žádný implicitní výběr cíle) + obecný model materializačního vyjednávání (MATERIALIZABLE/ADAPTER_REQUIRED/BLOCKED/...).

**`verification/`** (1 soubor, 166 ř.) — Alternativní CLI `main()` (verifikační hostitel), který kombinuje produktové příkazy s vývojářskými/conformance příkazy.

**`identity/`** (1 soubor, 95 ř.) — Elegantní kolizně-bezpečný generátor ID (čitelný base-id, SHA-256 suffix jen při kolizi).

**`serialization/`** (1 soubor, 102 ř.) — Jediný YAML parsing boundary (Jackson/YAMLMapper) pro celý repozitář, se striktním i shovívavým režimem.

**`preview/`** (1 soubor, 29 ř.) — Read-only near-textová preview exekučního plánu (výslovně "not a runtime executor").

**`continuity/`** (1 soubor, 19 ř.) — Jeden enum (`StateLifetime`).

**`frontend/`** (6 souborů, 370 ř.) — Tenké vstupní adaptéry do sdíleného `FlowCompilationService` (Flow Source, Intent YAML, Reviewed-AI-proposal) se sjednoceným zachycením zdrojových bajtů (`CompilationSourceCapture`) a hlubokým "defensive snapshot" kopírováním před hashováním.

**`cli/`** (6 souborů, 1734 ř.) — Skutečný CLI vstupní bod (`HonestFlowCli.kt`, 966 ř.) + evidence-autorita pro cílové materializace (`CliTargetEvidenceAuthority`, 361 ř.) + typovaný výsledkový model příkazů (`CliExecution.kt`).

---

## 2. Inventář souborů a tříd

### architecture/

| Soubor | Ř. | Klíčové typy |
|---|---|---|
| `ArchitectureDelta.kt` | 235 | `StandardModelSnapshot` (data class + companion `current()`/`fromYaml()`), `StandardCheckSnapshot`, `StandardArtifactSnapshot`, `ArchitectureDeltaIssue`, `StandardModelDeltaReport`, **`ArchitectureDeltaAnalyzer`** — porovnává dva `StandardModel` snapshoty (aktuální vs. baseline YAML) a hlídá, že veřejný povrch neroste rychleji než evidence-backed checky. |
| `ArchitectureGovernance.kt` | 481 | `ArchitectureGovernanceFileStatus/IssueStatus/...`, **`ArchitectureGovernanceAnalyzer`** — kontroluje existenci a obsah governance souborů (`docs/ARCHITECTURE_CONSTITUTION.md`, `standard/architecture/forbidden-directions.yaml`, `drift-score.yaml`...), skenuje CELÝ aktivní Kotlin zdroj na zakázané symboly a počítá "drift score" (negative-signal-only model). |
| `ArchitectureGovernanceIntegrity.kt` | 497 | `ArchitectureGovernanceIntegrityIssue/Report`, `InvalidArchitectureGovernanceException`, **`ArchitectureGovernanceIntegrityAuthority`** (validuje `drift-score.yaml` katalog proti pevně zakódované sadě povinných baseline/negative signálů a proti výstupu `ArchitectureGovernanceAnalyzer`), **`GovernedArchitectureAnalyzer`** (fail-closed kompozice obou). |
| `ArchitectureReadinessBaselineAnalyzer.kt` | 153 | **`ArchitectureReadinessBaselineAnalyzer`** — počítá "AR0.2 baseline" metriky (počet typů, Authority tříd, conformance checků) lexikální regex analýzou zdrojového stromu. |
| `AuthorityResponsibilityCatalog.kt` | 254 | **`AuthorityResponsibilityCatalog`** — objevuje všechny `*Authority` třídy/objekty v produkčním zdroji (regex `AUTHORITY_DEFINITION`), ověřuje unikátnost jmen, počítá volající soubory a porovnává s ručně psaným `standard/architecture/authority-responsibilities.yaml`. |
| `CiCdBiasInventory.kt` | 412 | `CiCdBiasTerm/Evidence/...`, **`CiCdBiasInventoryAnalyzer`** — lexikálně klasifikuje výskyty konkrétních CI/CD/infra jmen (Jenkins, Kubernetes, Docker, Terraform, PostgreSQL...) napříč CELÝM repozitářem a rozlišuje "actionable" (kód) vs. popisný text. |
| `KotlinLexicalScanner.kt` | 275 | **`KotlinLexicalScanner`** (internal object) — ruční stavový lexer Kotlinu rozlišující kód/řetězce/interpolace/komentáře (bloky i řádkové), plně rekurzivní pro `${...}`. |
| `KotlinSourceBoundaryScanner.kt` | 149 | **`KotlinSourceBoundaryScanner`** (internal object) — DRUHÝ, nezávislý stavový automat dělající prakticky totéž co `KotlinLexicalScanner` (odstranění komentářů/řetězců), ale s jiným API (`containsSymbol`). |
| `SemanticSerializationBoundary.kt` | 11 | `SemanticSerializationBoundary` — statický seznam zakázaných tokenů (Jackson/SnakeYAML) pro "sémantické" balíčky; používá se **pouze** z testů, ne z produkčního kódu. |

### standard/

| Soubor | Ř. | Klíčové typy |
|---|---|---|
| `StandardModel.kt` | 625 | `GateKind` (11 kategorií), `StandardCheckScope`, `ArtifactVisibility`, `StandardCheck`, `StandardArtifact`, **`StandardModel`** (object) — 55 `StandardCheck` položek + 15 `StandardArtifact` položek, `wellFormednessIssues()` (self-konzistence), `releaseProfileChecks()`, `candidateChecks()`, `stableArtifacts()` atd. — centrální autorita celého veřejného standardu. |
| `StandardDiagnosticCatalog.kt` | 144 | `StandardDiagnosticCode`, `StandardDiagnosticCatalogReport`, **`StandardDiagnosticCatalog`** — 95 pevně zapsaných diagnostických kódů (`report()`, `markdown()`). |
| `FlowStandardVersions.kt` | 50 | **`FlowStandardVersions`** (object) — verzovací konstanty (`IMPLEMENTATION_PACKAGE_VERSION="0.9.5"`, `FLOW_STANDARD_VERSION="0.8.0"`, per-artefakt kontraktové verze), `FlowVersionBoundary`. |
| `PublishedSchemaContracts.kt` | 127 | `PublishedSchemaAcceptanceKind`, `PublishedSchemaContract`, **`PublishedSchemaContracts`** — vlastnický index ~63 publikovaných JSON schémat vs. skutečné soubory v `schemas/` (`coverageIssues()`). |
| `ScenarioPackQualityAnalyzer.kt` | 10 | Pouze komentář — žádný kód, žádná třída/funkce (dead marker soubor, viz §3). |
| `StandardCapabilityContracts.kt` | 100 | `StandardCapabilityContract`, **`StandardCapabilityContracts`** — vykonatelné kontrakty (required/optional parametry) pro každou `StandardCapability`. |
| `DiagnosticCoverage.kt` | 70 | `ObservedDiagnosticCode`, `DiagnosticCoverageUnknownCode`, `DiagnosticCoverageReport`, **`DiagnosticCoverageAnalyzer`** — porovnává volajícím dodaný seznam pozorovaných kódů s katalogem (nevolá se automaticky proti celému běhu, viz §3 F1). |
| `CoreContractCheck.kt` | 55 | `CoreContractCheckReport`, **`CoreContractCheck`** — kontrola přítomnosti 11 požadovaných artefaktů + duplicit. |
| `StandardIntentCatalog.kt` | 127 | `StandardCapabilityDefinition`, **`StandardIntentCatalog`** — 30 definic kapabilit (kategorie, maturita, popis, moduly). |

### artifacts/

| Soubor | Ř. | Klíčové typy |
|---|---|---|
| `StandardSurface.kt` | 606 | ~20 report data classes + **`StandardSurface`** (object) — generuje `publicSurface()`, `compatibilityMigrationPolicy()`, `referenceIntentCorpus()` (20 ručně psaných scénářů), `referenceCorpusExecutionHarness()`, `requiredClarificationContract()`, `safetyPolicyMatrix()` (6 položek), `executionPlanSemanticInvariants()`, `aiInputTrustBoundary()`, `standardExampleBundle()`, `compatibilityPromiseRules()`, `targetSemanticsMatrix()`, `standardExportBundle()`, `conformanceLevels()`, `standardExportManifest()`. |
| `FlowArtifactBundle.kt` | 284 | `FlowArtifactRole`, `FlowArtifactEntry`, `FlowArtifactBundleReport`, **`FlowArtifactBundleAnalyzer`** — popisuje pipeline artefaktů pro `intent`/`normalize`/multi-workflow běhy (`intentBundle`, `workflowPlanSetBundle`, `normalizationBundle`). |
| `PublicStandardDraft.kt` | 229 | ~10 report tříd + **`PublicStandardDraft`** (object) — `freeze()`, `compatibilityPolicy()`, `referenceCorpus()`, **`negativeCorpus()`** (16 negativních conformance případů), `targetConformanceProfile()`, `standardIndex()`, `conformanceSuite()`, `draft()`. |
| `StandardBundleVerifier.kt` | 200 | `StandardBundleVerificationCheck/Report`, **`StandardBundleVerifier`** — striktně (Jackson `FAIL_ON_UNKNOWN_PROPERTIES` + duplicitní klíče) parsuje exportovaný bundle a ověřuje 9 kontrol (dokumenty, JSON artefakty, schémata, adresáře, evidence, release gates, stabilní povrch, verze). |
| `ArtifactContractAuthority.kt` | 179 | `ArtifactContractDefinition`, **`ArtifactContractAuthority`** — fail-closed mapování artefakt→producer/introducedIn (přes 40 pevně zapsaných položek); `missingEvidence()` ověřuje provenience grafu. |
| `StandardSurfaceStatusAuthority.kt` | 170 | **`StandardSurfaceStatusAuthority`** (object) — čistě funkční "status z obsahu, ne literál" pro 8 typů reportů. |
| `TargetSemanticsAuthority.kt` | 112 | **`TargetSemanticsAuthority`** — staví `target-semantics-matrix` výhradně z registry/adapter evidence (žádný "positive claim" bez důkazu). |
| `ArtifactIntegrity.kt` | 108 | `ArtifactIntegrityIssue/VersionObservation/Report`, **`ArtifactIntegrityAnalyzer`** — 4 kategorie chyb (missing required, missing schema, version mismatch, diagnostic coverage failed). |
| `StandardReleaseProfile.kt` | 104 | `ReleaseRequirement`, `ReleaseGateClassification`, `StandardReleaseProfileReport`, **`StandardReleaseProfile`** — 23 `ReleaseRequirement` položek odvozených z `StandardModel`. |
| `StandardContractIndex.kt` | 60 | `StandardContractEntry/Report`, **`StandardContractIndexAnalyzer`**. |
| `StandardCompliance.kt` | 60 | `ComplianceGate`, `StandardComplianceReport`, **`StandardComplianceAnalyzer`** — 7 compliance gates. |
| `ArtifactEvidence.kt` | 43 | `ArtifactEvidenceEntry/Report`, **`ArtifactEvidenceAnalyzer`**. |
| `ConformanceManifestReport.kt` | 38 | Pouze data classes (`ConformanceAreaSummary`, `ConformanceVectorEntry`, `ConformanceSchemaEntry`, `ConformanceManifestReport`) — žádná logika. |
| `StandardArtifactRegistry.kt` | 37 | **`StandardArtifactRegistry`** — `@Deprecated` facade nad `StandardModel`; **v celém repozitáři (vč. testů) nikým nepoužívaný** (mrtvý kód, viz §3 F8). |

### roadmap/

| Soubor | Ř. | Klíčové typy |
|---|---|---|
| `RoadmapStreamTransitionAuthority.kt` | 1530 | `RoadmapTransitionPhase` (13 hodnot), `RoadmapStreamTransitionReport`, **`RoadmapStreamTransitionAuthority`** — obří ruční state-machine (13 fází: C0.1.1→A1.0→C0.2→C0.3→C0.4→AR0.1→C1.0→C1.0_COMPLETE→SI-01.1→SI-02→SEMANTIC_INTEGRITY→ARCHITECTURE_COMPLETE→INVALID), s ~15 privátními `validateXPhase()` funkcemi a natvrdo zapsanými git SHA/PR/CI-run konstantami (viz §3 F2). |
| `ToolchainModernizationLifecycle.kt` | 469 | `ToolchainModernizationPhase`, `ToolchainModernizationLifecycleReport`, **`ToolchainModernizationLifecycle`** — validuje migraci Kotlin 1.9.24→2.4.10, Gradle 8.10.2→9.5.0, JDK 21→25 podle skutečného obsahu `build.gradle.kts`/`gradle-wrapper.properties` regexy. |
| `WorkflowBoundaryEvidence.kt` | 79 | **`WorkflowBoundaryEvidence`** (data class) — kanonická reprezentace jednoho "Flow CI" run boundary (`structurallyValid`, `sameBoundary()`, `distinctFrom()`, `follows()`). Sdíleno napříč `roadmap/` a `release/`. |

### release/

| Soubor | Ř. | Klíčové typy |
|---|---|---|
| `ReleaseMetadataHonesty.kt` | 514 | `ReleaseMetadataHonestyCheck/Report`, **`ReleaseMetadataHonestyAuthority`** — ~45 `equal`/`boolean`/`contains` kontrol napříč `release-state.yaml`, `roadmap.yaml`, `roadmap-core-v0.9.7.9.yaml`, `build.gradle.kts`, `REPORT.md`, `CHANGELOG-*.md`; `requireValid()` (fail-closed `require`). |
| `StandardReleaseAssembly.kt` | 351 | `StandardReleaseAssembly` (data class s `writeTo()`), **`StandardReleaseAssemblyAuthority`** — `assemble()` (spouští CELOU conformance suitu + skládá 24 artefaktů), `writeValidatedDraft()`, `publishValidatedBundle()` (staging adresář + atomický `Files.move` s rollbackem při chybě). |
| `SemanticClosureAuthority.kt` | 264 | `SemanticClosureCheck/Report`, **`SemanticClosureAuthority`** — 9 finálních "closure" kontrol (checklist-exact, no-active-corrections, prior-items-complete, release-metadata-honest, required-checks-present/pass, no-failed-conformance, version-boundary-unchanged, reference-evidence-live). |
| `ClosureEvidenceBoundaryAuthority.kt` | 227 | `ClosureWorkflowEvidence`, `ClosureEvidenceBoundaryInput/Check/Report`, **`ClosureEvidenceBoundaryAuthority`** — 5 kontrol pro CORRECTION_REQUIRED/READY/CLOSED fáze closure evidence. |

### distribution/

| Soubor | Ř. | Klíčové typy |
|---|---|---|
| `ReferenceAdapterEvidence.kt` | 189 | **`ReferenceAdapterEvidence`** (object) — 13 factory funkcí (`promotion`, `portfolio`, `authorizedRendering`, `trigger`, `triggerIntegrity`, `executionGate`, `continuity`, `continuityIntegrity`, `scopedSupportIntegrity`, `control`, `topology`, `renderingIntegrity`, `rendering`) — čistě kompoziční kořen. |
| `ReferenceTargetProjections.kt` | 35 | **`ReferenceTargetProjections`** (object) — jediné místo, kde se registrují konkrétní Jenkins/GitHubActions/Tekton generátory+renderery. |
| `ReferenceStandardArtifacts.kt` | 15 | **`ReferenceStandardArtifacts`** — `targetSemanticsMatrix()` wrapper. |

### notes/

| Soubor | Ř. | Klíčové typy |
|---|---|---|
| `CanonicalNotesPackageLoader.kt` | 181 | **`CanonicalNotesPackageLoader`** (+ `ContractException`) — načítá `standard/notes/packages.yaml`, ověřuje path-traversal ochranu (`canonicalFile.startsWith`), detekci cyklů závislostí (DFS), duplicitní ID. |
| `NotesPackageModel.kt` | 66 | `NotesPackageKind` (7 hodnot), `NotesPackageDependency/Boundary/Contract`, `NotesPackageContractStatus/Issue/Report`. |
| `NotesPackageValidation.kt` | 98 | **`NotesPackageContractValidator`** — validuje ID formát, duplicity, závislosti (vč. self-dependency), 8 zakázaných frází ("runtime executor", "sdk api"...), ownership pravidla. |
| `StandardNotesPackageContracts.kt` | 8 | **`StandardNotesPackageContracts`** — jednořádkový wrapper. |

### materialization/

| Soubor | Ř. | Klíčové typy |
|---|---|---|
| `TargetSelection.kt` | 411 | `TargetSelectionOrigin`, `ExplicitConfigurationSource` (sealed: `ReferenceSnapshot`/`ConformanceCheck`/`TestFixture`), `TargetSelectionEvidence`, `ExplicitTargetSelection` (sealed interface), `TargetSelectionDecision` (sealed), **`TargetSelectionAuthority`** (object — `fromCliOption`, `fromIntentDeclaration`, `fromReferenceSnapshot`, `requireSelected`...), **`TargetMaterializationRequest`**, **`TargetDiagnosticMaterializationRequest`**, `TargetSelectionEvidenceReport`, 3 vlastní výjimky. |
| `MaterializationNegotiation.kt` | 237 | `MaterializationStatus` (5), `MaterializationEvidenceKind` (8), `MaterializationEvidence/Decision/Negotiation`, `MaterializationNegotiationStatus/Issue/Report`, **`MaterializationNegotiationValidator`**, **`StandardMaterializationNegotiations`** (baseline dat). |
| `CompatibilityMaterializationBoundary.kt` | 47 | **`CompatibilityMaterializationBoundary`** (`@Deprecated`, ale aktivně používaný 8 volajícími v `conformance/`, viz §4). |

### verification/, identity/, serialization/, preview/, continuity/

| Soubor | Ř. | Klíčové typy |
|---|---|---|
| `VerificationCli.kt` | 166 | `main()`, `executeVerificationCli()`, **`VerificationCommands`** (5 příkazů — viz §2 CLI), `runReleaseProfileCommand`, `runStandardDraftCommand`, `runStandardExportCommand`, `runConformance`, `runReferenceSnapshot`, `parseOption` (duplicitní, viz §3 F6), `writeJson`. |
| `CollisionSafeIdentityAuthority.kt` | 95 | `SemanticDuplicatePolicy`, `CollisionSafeIdentityCandidate/Assignment`, **`CollisionSafeIdentityAuthority`** — `assign()` s SHA-256 suffixem při kolizi. |
| `FlowYaml.kt` | 102 | **`FlowYaml`** (object) — `readMap`/`read`/`readStrict`, `FlowYamlException`. |
| `PlanPreview.kt` | 29 | **`PlanPreview`**, `PlanPreviewReport/Entry`. |
| `StateLifetime.kt` | 19 | `StateLifetime` enum (`WORKFLOW`/`DURABLE`). |

### frontend/

| Soubor | Ř. | Klíčové typy |
|---|---|---|
| `CompilationSourceCapture.kt` | 84 | `CapturedCompilationSource<T>`, **`CompilationSourceCapture`** (internal object) — `capture`, `captureText`, `captureBytes`, striktní UTF-8 dekodér (`CodingErrorAction.REPORT`). |
| `FrontendCompilerComposition.kt` | 30 | **`FrontendCompilerComposition`** — 4 factory funkce (`compiler`, `intentPlanner`, `flowValidator`, `safetyValidator`). |
| `ai/ReviewedAiProposalFrontend.kt` | 172 | `ReviewedAiProposal`, **`ReviewedAiProposalFrontend`**, `ReviewedAiProposalSourceView` + Encoder — hluboké "snapshot" kopírování (10+ extension funkcí) před hashováním, aby mutace poskytovatelova objektu nemohla změnit již zaznamenanou provenience. |
| `intent/IntentYamlFrontend.kt` | 43 | **`IntentYamlFrontend`** — `load`, `compile`, `compileText`. |
| `intent/FlowIntentExpressionParser.kt` | 10 | **`FlowIntentExpressionParser`** (object, implementuje `IntentExpressionParser`). |
| `source/FlowSourceFrontend.kt` | 31 | **`FlowSourceFrontend`**. |

### cli/ — VŠECHNY podporované příkazy

| Soubor | Ř. | Klíčové typy |
|---|---|---|
| `Json.kt` | 13 | **`Json`** — sdílený Jackson `ObjectMapper` (NON_NULL, indent). |
| `honest/CliCommandCatalog.kt` | 37 | `CliCommandHandler` (fun interface), **`CliCommandCatalog`** — `requireDisjoint()` (host nemůže přepsat produktové příkazy), validace jmen regexem. |
| `honest/CliExecution.kt` | 226 | `CliArtifactRole` (5), `CliArtifact`, `CliPresentationItem` (sealed), `CliOutput`/`CliOutputCollector`, `CliDiagnosticCode` (5), `CliProcessExit` (5 — SUCCESS=0, INVALID_INPUT=2, REVIEW_REQUIRED=3, BLOCKED=4, INTERNAL_ERROR=70), `CliExecutionDiagnostic`, **`CliExecutionResult`** (sealed: `Help`/`Completed`/`TargetNeutral`/`Targeted`/`Rejected`, každý s vlastními invarianty v `init{}`), `CliTypedFailure`, **`CliPresenter`**. |
| `honest/CliTargetEvidenceAuthority.kt` | 361 | `CliRenderedArtifact`, `CliTargetEvidenceOutcome` (3), `CliTargetDiagnostic`, `CliTargetEvidence`, **`CliTargetEvidenceAuthority`** — spojuje control/continuity/trigger/rendering evidence do jednoho koherentního výsledku, s fallback-diagnostikou při "expected target blocker" výjimkách. |
| `honest/HonestFlowCli.kt` | 966 | `main()`, `runCli()` (2×), `executeCli()` (2×), **příkazy**: `runIntentCommand`, `runNormalizeCommand`, `runMultiWorkflowIntentCompilation`, `runDiagnosticsCommand`, `runStandardVerifyCommand` + ~15 pomocných funkcí (`writeMinimalBundle`, `writeIntentArtifacts`, ...). |
| `honest/StandardCliCommands.kt` | 131 | **`StandardCliCommands`** (internal object) — 6 příkazů. |

**Kompletní seznam CLI příkazů a jejich argumentů** (sloučeno `HonestFlowCli` produktová sada + `StandardCliCommands` + `VerificationCommands`, dle skutečného zdrojového kódu):

| Příkaz | Zdroj | Pozicionální argument | Volby |
|---|---|---|---|
| `intent` | HonestFlowCli | cesta k `.intent.yaml` (default `examples/intent/build-test-deploy.intent.yaml`) | `--target <name>`, `--strict`/`--fail-on-unsupported`, `--render`, `--out <dir>` |
| `normalize` | HonestFlowCli | volný text (spojená ne-`--` slova) | `--file <path>` (alternativa k textu), `--target <name>`, `--app <name>`, `--environment <name>`, `--repo <url>`, `--channel <name>`, `--strict`/`--fail-on-unsupported`, `--explain`, `--repair` (jinak `DRAFT`), `--render`, `--lower`/`--pipeline`, `--out <dir>` |
| `diagnostics` | HonestFlowCli | — | `--out <dir>` |
| `standard-verify` | HonestFlowCli | cesta k bundle (fallback z `--bundle`) | `--bundle <dir>` (povinný), `--out <dir>` |
| `flow` | StandardCliCommands | cesta k `.flow` souboru (povinný, jinak `error()`) | žádné (**bez `--out`** — viz §3 F7) |
| `catalog` | StandardCliCommands | — | `--markdown` |
| `targets` | StandardCliCommands | — | žádné |
| `modules` | StandardCliCommands | — | žádné |
| `scenarios` | StandardCliCommands | — | `--markdown` |
| `scenario` | StandardCliCommands | scenario pack id (povinný) | `--examples` |
| `conformance` | VerificationCommands (jen `flow-core`/`./gradlew run`) | — | `--out <dir>` |
| `reference-snapshot` | VerificationCommands | — | `--intent <path>` nebo první ne-`--` arg, `--out <dir>` (default `conformance/snapshots/build-test-deploy`), `--scenario-id <id>`, `--targets <a,b,c>` |
| `release-profile` | VerificationCommands | — | `--out <dir>` |
| `standard-draft` | VerificationCommands | — | `--out <dir>` |
| `standard-export` | VerificationCommands | — | `--out <dir>` (default `dist/flow-standard-<verze>`) |

Poznámka: produkt má **dvě distribuce** (`build.gradle.kts:47-68`): `flow-product` (jen `HonestFlowCliKt`, 10 příkazů) a `flow-core`/reference verifikační host (`VerificationCliKt`, 15 příkazů = 10 + 5 verifikačních). Toto oddělení je záměrné a architektonicky zdravé (viz §4).

---

## 3. Zjištěné chyby a nedostatky

### F1 — [STŘEDNÍ] Diagnostický kód emitovaný governance skenerem chybí v publikovaném katalogu
**`src/main/kotlin/org/flowlang/architecture/ArchitectureGovernance.kt:469`** emituje `code = "ARCHITECTURE_FORBIDDEN_SYMBOL_IN_SOURCE"`, ale **`src/main/kotlin/org/flowlang/standard/StandardDiagnosticCatalog.kt:120`** deklaruje jako stabilní veřejný kód `"ARCHITECTURE_FORBIDDEN_TERM_IN_SOURCE"` — jiný string. Stejný (špatný) kód `ARCHITECTURE_FORBIDDEN_TERM_IN_SOURCE` je navíc citován jako `expectedDiagnostic` v negativním conformance případu **`src/main/kotlin/org/flowlang/artifacts/PublicStandardDraft.kt:169`**.
**Scénář selhání:** Pokud by `ArchitectureGovernanceReport.issues` byly skutečně napojeny na `DiagnosticCoverageAnalyzer` (přes `ObservedDiagnosticCode`), kód `ARCHITECTURE_FORBIDDEN_SYMBOL_IN_SOURCE` by byl nahlášen jako "unknown diagnostic code" (`DIAGNOSTIC_CODE_UNKNOWN`), protože katalog zná jen `..._TERM_IN_SOURCE`. Obráceně: negativní conformance případ `architecture-sdk-drift` v `negative-conformance-corpus.json` popisuje kód, který **nikdy skutečně neemituje žádný produkční kód** — je to "mrtvý" fixture, ne skutečně testovaný scénář, což je přesně to, co projekt jinde nazývá "silent semantic fallback"/"diagnostic honesty" porušení, jemuž se snaží aktivně předcházet.
**Návrh opravy:** Sjednotit název na jednu variantu (doporučeno `ARCHITECTURE_FORBIDDEN_SYMBOL_IN_SOURCE`, protože přesněji popisuje, že jde o symbol, ne libovolný "term") ve všech třech místech a přidat automatizovanou kontrolu (např. rozšíření `DiagnosticCoverageAnalyzer`/testu), která by podobný drift v budoucnu odhalila strojově.

Dodatečně jsem zjistil obdobný, byť nepotvrzený drift u dvou dalších diagnostických kódů: `SafetyPolicyMatrixEntry.blockingDiagnostic` hodnoty `"SECRET_REQUIRES_SUBJECT"` a `"CERTIFICATE_REQUIRES_SUBJECT"` (`src/main/kotlin/org/flowlang/artifacts/StandardSurface.kt:338,340`, citované i v `PublicStandardDraft.kt:171-172`) rovněž **nejsou** v `StandardDiagnosticCatalog.codes` — žádná automatizovaná kontrola v rozsahu tuto konzistenci nevynucuje (`IntentSafetyChecks.kt` mimo rozsah ověřuje jen pokrytí negativním corpusem, ne proti katalogu).

### F2 — [ARCHITEKTONICKY ZÁVAŽNÉ / STŘEDNÍ] Historické git SHA a CI run ID natvrdo zapsané v produkčním Kotlin zdroji
**`src/main/kotlin/org/flowlang/roadmap/RoadmapStreamTransitionAuthority.kt:1502-1509`**:
```kotlin
private const val SI011_RUN_NUMBER = 2892
private const val SI011_RUN_ID = 31389256988L
private const val SI011_HEAD = "e94beb34dd6a134bd00d29dfb9416393650cea35"
private const val SI011_MERGE_CANDIDATE = "ee7e32c93ee4a3701b6ee30d97b2a69346a3de52"
private const val SI011_MERGE_COMMIT = "44ac499a466378604ec3823719d15953505fd4f8"
private const val SI01_HEAD = "a35e6175bdd6500afddedc0bba3ed627cc8500f3"
private const val SI01_MERGE_CANDIDATE = "8c7c87213e696317327c4947eefb4cddc096fd28"
private const val SI01_MERGE_COMMIT = "1026c980d19b697f8d53576af1f79df08c49517e"
```
a obdobně `.../release/ReleaseMetadataHonesty.kt` (verze "0.9.5"/"0.8.0" pevně zapsané), `.../release/SemanticClosureAuthority.kt:240-249` (`CERTIFIED_VERSION_BOUNDARY`).
**Scénář selhání:** Toto není bug v klasickém smyslu (testy dnes procházejí), ale je to **anti-pattern typu "instance na milník"**: každý budoucí roadmap milník vyžaduje ručně zapsat 6 nových konstant (SHA-40, run number, run ID) přímo do `.kt` souboru a rekompilovat celý projekt — přestože identická data (viz `.flow-agent/roadmap-semantic-integrity.yaml`, blok `historicalEvents`) už existují jako YAML. Riziko: jediný překlep v SHA (40 hex znaků) při ručním psaní vede k neinformativnímu "boundary must remain X" selhání bez ukazatele na to, kde je nesoulad. `RoadmapStreamTransitionAuthority.kt` má **1530 řádků** a je to nejhutnější soubor v celém zkoumaném rozsahu.
**Návrh opravy:** Přesunout tyto konstanty do `.flow-agent/*.yaml` (odpovídající "historicalEvents" struktura tam ostatně už existuje a částečně duplikuje tato data!) a nechat Kotlin kód jen ověřovat, že se hodnoty YAML shodují s tím, co bylo skutečně publikováno, místo aby YAML i .kt byly dvě samostatně udržované kopie téže pravdy.

### F3 — [NÍZKÁ/INFORMATIVNÍ] `--out` u CLI příkazů nevaliduje cestu (potenciální path traversal / přepsání souborů mimo repo)
Výskyty: **`HonestFlowCli.kt:186,331,879,900`**, **`VerificationCli.kt:62,96,119,132`**, `StandardCliCommands` (dead, viz F7). Všechny volají přímo `File(out)`/`File(outputPath)` bez normalizace, whitelisty nebo kontroly, že výsledná cesta zůstává uvnitř očekávaného kořene (na rozdíl od `notes/CanonicalNotesPackageLoader.kt:27-31`, který stejnou třídu problému řeší správně přes `canonicalFile.path.startsWith(repositoryRoot.path)`).
**Scénář selhání:** `./gradlew run --args="intent x.yaml --out ../../../etc/cron.d"` (nebo absolutní cesta) zapíše `standard-version.txt`, `flow-artifact-bundle.json` atd. kamkoli, kam má proces oprávnění zapisovat. V lokálně spouštěném CLI nástroji, kde uživatel ovládá své vlastní argumenty, to není klasická bezpečnostní chyba (útočník, který kontroluje argumenty, má už ekvivalentní přístup) — je to ale relevantní, pokud tento CLI kdy poběží jako součást automatizovaného pipeline s částečně nedůvěryhodným vstupem (např. `--out` sestavené z uživatelského PR popisku).
**Návrh opravy:** Sjednotit `--out` handling do jedné sdílené utility (viz i F6 duplicita `parseOption`), která provede `canonicalFile` kontrolu vůči `File(".").canonicalFile`, analogicky k již existujícímu vzoru v `CanonicalNotesPackageLoader`.

### F4 — [STŘEDNÍ] `.first { }` bez `orElse`/`firstOrNull` — riziko neinformativní `NoSuchElementException`
**`src/main/kotlin/org/flowlang/artifacts/StandardReleaseProfile.kt:97`**:
```kotlin
private fun surfaceRequirement(id: String, artifactName: String, description: String): ReleaseRequirement {
    val artifact = StandardModel.artifacts.first { it.artifact == artifactName }
    ...
```
**Scénář selhání:** Pokud někdo v budoucnu přejmenuje/odstraní artefakt v `StandardModel.artifacts`, ale zapomene na odpovídající `surfaceRequirement(...)` volání v `releaseRequirements()` (volané z 8 míst pro `standard.public-surface`, `standard.compatibility-migration`, `standard.reference-intent-corpus`, `target.semantics-matrix`, `standard.export-bundle`, `conformance.levels`, `standard.export-manifest`, `standard.draft`), `StandardReleaseProfile.report()` spadne s obecnou `NoSuchElementException: Collection contains no element matching the predicate.` bez zmínky, který artefakt chybí — a tato funkce je volaná při KAŽDÉM `standard-export`/`release-profile`/`standard-draft` běhu.
**Návrh opravy:** `StandardModel.artifacts.firstOrNull { it.artifact == artifactName } ?: error("Unknown artifact '$artifactName' referenced by release profile surface requirement.")`.

### F5 — [NÍZKÁ] Nesourodá klasifikace interní chyby jako `INVALID_INPUT`
**`src/main/kotlin/org/flowlang/cli/honest/HonestFlowCli.kt:562-564`**:
```kotlin
requireNotNull(rendered.evidence) {
    "Produced adapter artifact '${rendered.fileName}' has no rendering evidence receipt."
}
```
`requireNotNull` vyhazuje `IllegalArgumentException`, což `executeCli`'s catch blok (řádek 156) mapuje na `CliDiagnosticCode.INVALID_INPUT` (exit 2). Chybějící evidence receipt je ale interní invariant adaptéru/rendering vrstvy, ne chybný vstup uživatele — správně by měl vést k `INTERNAL_ERROR`/`INTEGRITY_BLOCKED` (podobně jako jinde v kódu `error(...)` → `IllegalStateException` → `INTEGRITY_BLOCKED`).
**Návrh opravy:** Nahradit `requireNotNull` za `checkNotNull` (vyhazuje `IllegalStateException`), což automaticky zajistí konzistentní mapování na `CliDiagnosticCode.INTEGRITY_BLOCKED`.

### F6 — [NÍZKÁ, údržba] Trojitá duplikace `parseOption()` a párové `moduleRegistry()`/`targetRegistry()` v CLI balíčku
`parseOption` je identicky nakopírováno v **`HonestFlowCli.kt:921`**, **`StandardCliCommands.kt:117`** a **`VerificationCli.kt:153`**. `moduleRegistry()`/`targetRegistry()` jsou identicky nakopírovány v **`HonestFlowCli.kt:912-919`** a **`StandardCliCommands.kt:108-115`**.
**Scénář selhání:** Není to funkční bug, ale zvyšuje riziko, že budoucí oprava (např. lepší chybová hláška, podpora `=` syntaxe, path-traversal fix z F3) bude aplikována jen v jedné ze tří kopií.
**Návrh opravy:** Přesunout do sdíleného `internal object CliArgs` v `cli/honest/` balíčku.

### F7 — [NÍZKÁ, mrtvý kód] Nepoužité privátní funkce v `StandardCliCommands.kt`
**`src/main/kotlin/org/flowlang/cli/honest/StandardCliCommands.kt:117-130`** — `parseOption()` a `writeJson()` jsou deklarované, ale **žádný z příkazů `flow`/`catalog`/`targets`/`modules`/`scenarios`/`scenario` je nevolá** (ověřeno grepem). To zároveň znamená, že tyto příkazy (na rozdíl od `intent`/`normalize`/`diagnostics`/`standard-verify`) **nepodporují `--out`** k perzistenci výstupu — patrně pozůstatek po odstraněné funkcionalitě.
**Návrh opravy:** Buď dokončit `--out` podporu pro `catalog`/`targets`/`modules`/`scenarios` (konzistence s ostatními příkazy), nebo funkce smazat.

### F8 — [NÍZKÁ, mrtvý kód] `StandardArtifactRegistry` — `@Deprecated` facade bez jediného volajícího
**`src/main/kotlin/org/flowlang/artifacts/StandardArtifactRegistry.kt`** (celý soubor, 37 ř.) je komentován jako "zachováno kvůli migračnímu oknu pro externí volající", ale grep přes `src/main/kotlin` i `src/test/kotlin` neukazuje **žádné** volání odkudkoliv v repozitáři.
**Návrh opravy:** Bezpečné k odstranění, nebo pokud existují opravdu externí (mimo-repo) konzumenti, zdokumentovat to explicitně (např. v CHANGELOGu) místo spoléhání na komentář v kódu.

### F9 — [NÍZKÁ, duplicitní implementace] Dva nezávislé Kotlin lexery dělající totéž
**`architecture/KotlinLexicalScanner.kt`** (275 ř., span-based) a **`architecture/KotlinSourceBoundaryScanner.kt`** (149 ř., string-rewriting) obě implementují ruční stavové automaty pro rozpoznání Kotlin komentářů/řetězců/raw-stringů/char-literálů — se stejným účelem (odlišit strukturální kód od textu), ale nesdílejí žádný kód a mají odlišné hraniční chování (např. `KotlinLexicalScanner` rekurzivně skenuje `${...}` interpolace jako kód s plnohodnotným vnořeným stavovým automatem, zatímco `KotlinSourceBoundaryScanner.structuralSource()` ve stavu `STRING` `${...}` vůbec nerozpoznává a pouze nahrazuje znaky mezerami).
**Scénář selhání (teoretický, nízké riziko):** `CiCdBiasInventoryAnalyzer` používá `KotlinLexicalScanner`, zatímco `ArchitectureGovernanceAnalyzer`/`sourceFilesContaining()` používá `KotlinSourceBoundaryScanner.containsSymbol()`. Pokud by zdrojový soubor obsahoval složitou string-interpolaci s vnořenými uvozovkami, oba skenery by mohly dát **různý** výsledek pro tentýž vstup — governance kontrola v jednom nástroji by "viděla" zakázaný symbol a v druhém ne.
**Návrh opravy:** Sjednotit na jeden lexer (např. `KotlinSourceBoundaryScanner.containsSymbol` implementovat nad výstupem `KotlinLexicalScanner.scan()` filtrovaným na `CODE` spans).

### F10 — [INFORMATIVNÍ] Bezpečnostně korektní vzory, které stojí za zmínku (kontrola, ne bug)
- `notes/CanonicalNotesPackageLoader.kt:27-31` — správná ochrana proti path traversal (`canonicalFile.startsWith(repositoryRoot)`).
- `release/StandardReleaseAssembly.kt:320-350` — bezpečný staging+atomic-move vzor s rollbackem při selhání (žádné částečné/poškozené publikování).
- `serialization/FlowYaml.kt` — striktní YAML parsing (duplicitní klíče, neznámé vlastnosti) pro veškerá evidence data.
- Žádné `TODO`/`FIXME`/`HACK`, žádné `printStackTrace()`, jediný `!!` v celém rozsahu (`release/ClosureEvidenceBoundaryAuthority.kt:183`) je logicky bezpečný (chráněný `&&` short-circuitem přes `structurallyValid`).

---

## 4. Architektonická pozorování

### Je "architecture governance" balíček (a jeho satelity v `roadmap/`/`release/`) přiměřený, nebo nadměrný?

**Je aktivně provázaný, ne osiřelý.** Ověřil jsem, že `ArchitectureGovernanceAnalyzer`, `GovernedArchitectureAnalyzer`, `ArchitectureDeltaAnalyzer`, `ArchitectureReadinessBaselineAnalyzer` i `CiCdBiasInventoryAnalyzer` jsou reálně volané z `conformance/ClosureBlockingIntegrityChecks.kt`, `SemanticBoundaryChecks.kt`, `DeltaPurposeChecks.kt`, `PlanningReadinessChecks.kt`, `ArchitectureCoherenceChecks.kt` — tzn. běží při každém `./gradlew run --args="conformance"`. Podobně `RoadmapStreamTransitionAuthority`/`ToolchainModernizationLifecycle` jsou volané z `ToolchainModernizationConformanceRunner.kt`, `AbstractTopologyMatrixRoadmapLifecycleAuthority.kt`, `RealWorldCorpusConformanceChecks.kt`.

**Ale míra je objektivně nadměrná vůči hodnotě, kterou přináší:**

1. **`architecture/` samo o sobě (2467 ř.) obsahuje DVA nezávislé Kotlin-lexery** (F9) místo jednoho sdíleného — to je typický symptom organicky rostlého, nikdy neuklizeného kódu, ne záměrného designu.
2. **`roadmap/` (2078 ř., z toho 1530 v jediném souboru) validuje výhradně UZAVŘENÉ historické fáze.** Ověřil jsem přímo v `.flow-agent/roadmap.yaml` (řádky 209-234): `currentDecision.nextItem: ""`, `closureItemStatus: "completed"`, `completedEnablingMilestone: "TOOLCHAIN-MODERNIZATION"` — projekt je dnes v úplně jiné, novější fázi (`.flow-agent/roadmap-post-toolchain.yaml`, aktivní item `AR-04`, roadmap `roadmap-architecture-recovery.yaml`), kterou validuje **jiná, nová třída v jiném balíčku** (`org.flowlang.conformance.WorkflowSemanticsRecoveryLifecycle`, mimo zkoumaný rozsah). Jinými slovy: `RoadmapStreamTransitionAuthority` a `ToolchainModernizationLifecycle` jsou **1999 řádků trvale běžícího ověřování něčeho, co se už nikdy nezmění** (13, resp. 2 fáze, obě definitivně uzavřené), a přesto se spouští při každém conformance běhu navěky. Nejde o mrtvý kód (běží a projde), ale o mrtvou váhu — čistě archivní pojistka v podobě plnohodnotné, drahé na údržbu state-machine s natvrdo zapsanými SHA (F2), místo jednoduššího "frozen snapshot hash" testu.
3. **Vzorec se opakuje bez abstrakce.** Každá nová fáze roadmapy (C0.1.1 → ... → SI-02 → TOOLCHAIN-MODERNIZATION → teď AR-04) dostala svou vlastní, ručně psanou stovky-řádků-dlouhou validační třídu s téměř identickou strukturou (`requireSelectedFocus`, `requireReleaseFocus`, `requireDistinctEvidence`, `WorkflowBoundaryEvidence` porovnání). Nikde nevznikla obecná "phase transition" abstrakce, kterou by šlo parametrizovat — což znamená, že náklady na údržbu roadmap-governance rostou lineárně s počtem milníků a nikdy neklesají, protože staré fáze zůstávají navěky aktivní.

**Doporučení:** `architecture/` (samotná governance nad kódem) je přiměřená velikosti projektu, který explicitně deklaruje "architektonickou integritu" jako svůj hlavní produkt. `roadmap/` by ale mělo po definitivním uzavření fáze (status `completed` a žádný navazující `nextItem`) přejít z "živé, plně strukturální validace" na jednoduchý **immutable hash/snapshot check** (např. jedno SHA-256 nad zamčeným YAML blokem), místo aby 1530 řádků Kotlinu s desítkami `require`/`error` volání běželo navěky nad daty, která se z definice už nemohou změnit.

### Jak souvisí `roadmap/`/`release/` balíčky s `.flow-agent/` YAML soubory?

Vztah je **jednosměrný a striktně "kód čte YAML jako fakta, YAML sám o sobě nic neprosazuje"**:

- `roadmap/RoadmapStreamTransitionAuthority` čte `.flow-agent/roadmap.yaml`, `roadmap-adapters.yaml`, `roadmap-conformance.yaml`, `roadmap-architecture.yaml`, `release-state.yaml`, `.flow-agent/work-packages/*.yaml` — a **ověřuje konzistenci mezi nimi** (stejná fáze, stejné completedItem, stejné SHA) bez toho, aby cokoliv zapisoval zpět. Je to čistě read-only cross-file integrity gate.
- `release/ReleaseMetadataHonestyAuthority` dělá totéž pro `release-state.yaml` vs. `roadmap.yaml` vs. `build.gradle.kts` vs. `REPORT.md` vs. `CHANGELOG-*.md` — sjednocuje "co je publikovaná verze" napříč pěti různými zdroji pravdy.
- `release/SemanticClosureAuthority` a `release/ClosureEvidenceBoundaryAuthority` čtou konkrétní `.flow-agent/work-packages/v0.9.7.10-bounded-semantic-closure-gate.yaml` a `roadmap-core-v0.9.7.9.yaml`.
- `.flow-agent/` samotné YAML soubory (`roadmap.yaml` 235 ř., `roadmap-architecture-recovery.yaml` 372 ř., `roadmap-semantic-integrity.yaml` 223 ř. atd. — 24 YAML souborů, ~230KB) jsou udržovány ručně (přímo edituje agent/vývojář) a slouží jako **jediný zápisový mechanismus stavu**; Kotlin kód v `roadmap/`/`release/` je čistě čtecí/ověřovací vrstva.

Toto je funkčně smysluplný oddělený "control plane" (YAML = stav, Kotlin = integrity gate), ALE cena za tuto architekturu je vysoká: `.flow-agent/roadmap-semantic-integrity.yaml` obsahuje `historicalEvents` blok s (dle F2) **stejnými** git SHA/PR/run-ID, které jsou navíc nezávisle nakopírované jako `const val` v `RoadmapStreamTransitionAuthority.kt`. Je to dvojí zdroj pravdy pro identická data, kde Kotlin kód místo "čti YAML a ověř proti externí realitě" dělá "čti YAML A porovnej proti druhé, ručně synchronizované kopii v .kt souboru".

### Další pozorování

- **`distribution/` je vzorový composition-root pattern** — jediné místo, kde se generické `Authority` třídy svazují s konkrétními Jenkins/GitHub Actions/Tekton implementacemi, což umožňuje `standard/`/`artifacts/`/`architecture/` zůstat cílově-neutrální. To odpovídá README-deklarovanému principu ("AI does not directly generate arbitrary Jenkinsfiles...").
- **Dvojí distribuce (`flow-product` vs. `flow-core`)** (build.gradle.kts:47-68) je dobrá praxe — udržuje produktový CLI povrch (10 příkazů) odděleně od vývojářského/verifikačního (15 příkazů), takže "co uživatel produktu vidí" ⊄ "co si vývojář může spustit lokálně".
- **`StandardModel` jako single source of truth** (625 ř., 55 checků + 15 artefaktů) je čistý a dobře strukturovaný — `artifacts/` balíček z něj věrně odvozuje 14 souborů reportů bez duplicitní logiky. Toto je nejlépe navržená část zkoumaného rozsahu.
- **`@Deprecated`, ale živé migrace jsou zdokumentované nekonzistentně** (`CompatibilityMaterializationBoundary`, cíl "AR-07", aktivně používaný 8 volajícími; `StandardArtifactRegistry` bez cíle a bez volajících — nekonzistence v tom, jak se deprecations spravují: jedna má trackovaný cílový milník, druhá je čistě opuštěná).

---

## 5. Statistická tabulka

| Soubor | Řádků | Top-level tříd/objektů/interfaců (přibl.) | Veřejných funkcí (přibl.) |
|---|---:|---:|---:|
| architecture/ArchitectureDelta.kt | 235 | 6 | 3 |
| architecture/ArchitectureGovernance.kt | 481 | 8 | 1 |
| architecture/ArchitectureGovernanceIntegrity.kt | 497 | 6 | 3 |
| architecture/ArchitectureReadinessBaselineAnalyzer.kt | 153 | 2 | 1 |
| architecture/AuthorityResponsibilityCatalog.kt | 254 | 4 | 1 |
| architecture/CiCdBiasInventory.kt | 412 | 6 | 2 |
| architecture/KotlinLexicalScanner.kt | 275 | 4 | 4 |
| architecture/KotlinSourceBoundaryScanner.kt | 149 | 2 | 1 |
| architecture/SemanticSerializationBoundary.kt | 11 | 1 | 0 |
| standard/StandardModel.kt | 625 | 6 | 19 |
| standard/StandardDiagnosticCatalog.kt | 144 | 3 | 2 |
| standard/FlowStandardVersions.kt | 50 | 2 | 1 |
| standard/PublishedSchemaContracts.kt | 127 | 3 | 1 |
| standard/ScenarioPackQualityAnalyzer.kt | 10 | 0 | 0 |
| standard/StandardCapabilityContracts.kt | 100 | 2 | 1 |
| standard/DiagnosticCoverage.kt | 70 | 4 | 1 |
| standard/CoreContractCheck.kt | 55 | 2 | 1 |
| standard/StandardIntentCatalog.kt | 127 | 2 | 1 |
| artifacts/StandardSurface.kt | 606 | 22 | 15 |
| artifacts/FlowArtifactBundle.kt | 284 | 4 | 5 |
| artifacts/PublicStandardDraft.kt | 229 | 14 | 8 |
| artifacts/StandardBundleVerifier.kt | 200 | 3 | 1 |
| artifacts/ArtifactContractAuthority.kt | 179 | 2 | 2 |
| artifacts/StandardSurfaceStatusAuthority.kt | 170 | 1 | 8 |
| artifacts/TargetSemanticsAuthority.kt | 112 | 1 | 1 |
| artifacts/ArtifactIntegrity.kt | 108 | 4 | 1 |
| artifacts/StandardReleaseProfile.kt | 104 | 4 | 2 |
| artifacts/StandardContractIndex.kt | 60 | 3 | 1 |
| artifacts/StandardCompliance.kt | 60 | 3 | 1 |
| artifacts/ArtifactEvidence.kt | 43 | 3 | 1 |
| artifacts/ConformanceManifestReport.kt | 38 | 4 | 0 |
| artifacts/StandardArtifactRegistry.kt | 37 | 1 | 6 |
| roadmap/RoadmapStreamTransitionAuthority.kt | 1530 | 3 | 1 |
| roadmap/ToolchainModernizationLifecycle.kt | 469 | 5 | 2 |
| roadmap/WorkflowBoundaryEvidence.kt | 79 | 1 | 5 |
| release/ReleaseMetadataHonesty.kt | 514 | 3 | 5 |
| release/StandardReleaseAssembly.kt | 351 | 2 | 4 |
| release/SemanticClosureAuthority.kt | 264 | 4 | 2 |
| release/ClosureEvidenceBoundaryAuthority.kt | 227 | 5 | 4 |
| distribution/reference/ReferenceAdapterEvidence.kt | 189 | 1 | 13 |
| distribution/reference/ReferenceTargetProjections.kt | 35 | 1 | 1 |
| distribution/reference/ReferenceStandardArtifacts.kt | 15 | 1 | 1 |
| notes/CanonicalNotesPackageLoader.kt | 181 | 2 | 2 |
| notes/NotesPackageValidation.kt | 98 | 1 | 2 |
| notes/NotesPackageModel.kt | 66 | 7 | 1 |
| notes/StandardNotesPackageContracts.kt | 8 | 1 | 1 |
| materialization/TargetSelection.kt | 411 | 19 | 8 |
| materialization/MaterializationNegotiation.kt | 237 | 10 | 3 |
| materialization/CompatibilityMaterializationBoundary.kt | 47 | 1 | 4 |
| verification/VerificationCli.kt | 166 | 1 | 2 |
| identity/CollisionSafeIdentityAuthority.kt | 95 | 4 | 1 |
| serialization/FlowYaml.kt | 102 | 2 | 6 |
| preview/PlanPreview.kt | 29 | 3 | 1 |
| continuity/StateLifetime.kt | 19 | 1 | 1 |
| frontend/ai/ReviewedAiProposalFrontend.kt | 172 | 4 | 2 |
| frontend/CompilationSourceCapture.kt | 84 | 2 | 3 |
| frontend/intent/IntentYamlFrontend.kt | 43 | 1 | 4 |
| frontend/source/FlowSourceFrontend.kt | 31 | 1 | 1 |
| frontend/FrontendCompilerComposition.kt | 30 | 1 | 4 |
| frontend/intent/FlowIntentExpressionParser.kt | 10 | 1 | 0 |
| cli/honest/HonestFlowCli.kt | 966 | 7 | 5 |
| cli/honest/CliTargetEvidenceAuthority.kt | 361 | 5 | 2 |
| cli/honest/CliExecution.kt | 226 | 19 | 4 |
| cli/honest/StandardCliCommands.kt | 131 | 1 | 1 |
| cli/honest/CliCommandCatalog.kt | 37 | 1 | 4 |
| cli/Json.kt | 13 | 1 | 0 |
| **CELKEM (66 souborů)** | **13 241** | — | — |

---

## Shrnutí

Prošel jsem všech 66 souborů (13 241 řádků) meta vrstvy FlowAi/Horistum celých. Kód je disciplinovaný — žádné TODO/FIXME/HACK, jediný force-unwrap (bezpečný), žádné `printStackTrace`, dobrá path-traversal ochrana v `notes/` a bezpečný atomic-move publish v `release/`.

Nalezl jsem 10 konkrétních nedostatků, žádný kritický:
- **1 středně závažný funkční bug**: diagnostický kód `ARCHITECTURE_FORBIDDEN_SYMBOL_IN_SOURCE` (skutečně emitovaný v `architecture/ArchitectureGovernance.kt:469`) neodpovídá kódu `ARCHITECTURE_FORBIDDEN_TERM_IN_SOURCE` deklarovanému v katalogu (`standard/StandardDiagnosticCatalog.kt:120`) a citovanému v negativním conformance fixture (`artifacts/PublicStandardDraft.kt:169`) — reálný "diagnostic honesty" drift v projektu, který si na diagnostic honesty explicitně zakládá. Podobný, nepotvrzený drift jsem našel i u `SECRET_REQUIRES_SUBJECT`/`CERTIFICATE_REQUIRES_SUBJECT`.
- **1 středně závažné riziko křehkosti**: `.first { }` bez fallbacku v `artifacts/StandardReleaseProfile.kt:97` může při budoucím přejmenování artefaktu shodit `release-profile`/`standard-export` s neinformativní výjimkou.
- **6 nízkých nálezů**: trojitá duplikace `parseOption()` v CLI (`HonestFlowCli.kt`, `StandardCliCommands.kt`, `VerificationCli.kt`), nevalidovaná `--out` cesta na 6+ místech (nízké riziko — lokální CLI), nepoužité `parseOption`/`writeJson` v `StandardCliCommands.kt` (chybí `--out` podpora pro `catalog`/`targets`/`modules`/`scenarios`), zcela mrtvý `StandardArtifactRegistry.kt` (37 ř., nikým nepoužívaný), dva nezávislé Kotlin-lexery se stejným účelem (`KotlinLexicalScanner` vs. `KotlinSourceBoundaryScanner`), nesprávná diagnostická klasifikace `requireNotNull` v `HonestFlowCli.kt:562`.
- **2 architektonická pozorování s vysokou váhou**: `roadmap/` balíček (2078 ř., z toho 1530 v jediném souboru `RoadmapStreamTransitionAuthority.kt`) natvrdo zapisuje historické git SHA/PR/CI-run čísla jako Kotlin konstanty a navěky ověřuje fáze, které jsou v `.flow-agent/roadmap.yaml` už definitivně uzavřené (aktuální práce běží přes zcela jinou třídu mimo tento balíček); je to funkčně smysluplný, ale nákladný a neabstrahovaný vzorec, který se s každým dalším milníkem opakuje bez zmenšení.

Klíčové soubory k prioritní opravě: `src/main/kotlin/org/flowlang/architecture/ArchitectureGovernance.kt` (F1), `src/main/kotlin/org/flowlang/standard/StandardDiagnosticCatalog.kt` (F1), `src/main/kotlin/org/flowlang/artifacts/PublicStandardDraft.kt` (F1), `src/main/kotlin/org/flowlang/artifacts/StandardReleaseProfile.kt` (F4), `src/main/kotlin/org/flowlang/roadmap/RoadmapStreamTransitionAuthority.kt` (F2, architektonické).
