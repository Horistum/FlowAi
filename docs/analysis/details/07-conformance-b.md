# FlowAi `org.flowlang.conformance` — Subset B Analysis (54 files)

Scope: `/home/user/FlowAi/src/main/kotlin/org/flowlang/conformance/`, all 54 files listed in the task, read in full (~12,311 of the package's 24,629 lines).

---

## 1. Přehled — co tento subset dělá

Tento subset tvoří jádro "honesty / falsification / lifecycle-authority" vrstvy Flow conformance frameworku. Funkčně se dělí do šesti proudů:

1. **"Externí falsifikace" (EF-0x)** — `DatabaseMigrationRecoveryFalsification`, `IncidentRemediationFalsification`, `DataOrchestrationFalsification`, `InfrastructureLifecycleFalsification`, `HumanApprovalChangeControlFalsification`. Každý načte kurátorovanou sadu "externích" případů (odvozených z reálných repozitářů, mimo tento subset), přeloží doménový popis do typovaných požadavků a ptá se, zda současný `IntentCapabilityValidator` / `CanonicalIntentMeaning` dokáže zachovat sémantiku beze ztráty. Výsledek je buď `REPRESENTABLE`, nebo `MODEL_GAP` — nikdy "PASS/FAIL" v obvyklém smyslu, což je záměrně poctivý design (mezera v modelu není bug v conformance).
2. **Baseline verifikátory** (`BackupRestoreBaseline`, `IncidentRemediationBaseline`, `SecretRotationBaseline`, `HumanApprovalChangeControlBaseline`) — srovnávají živé vyhodnocení EF-0x proti zamrzlé historické evidenci (YAML baseline), aby nikdo tiše "nevylepšil" nebo neregresoval dřívější zjištění bez explicitní autorizace.
3. **Roadmap/lifecycle-authority třídy** (`BoundedDomainCorpusRoadmapLifecycleAuthority`, `AdapterProfileEvidenceRoadmapLifecycleAuthority`, `LanguageContractIntegrityLifecycle`) — validují, že interní `.flow-agent/*.yaml` roadmap dokumenty (proces-governance, ne kód) jsou ve vzájemně konzistentním stavu (fáze IMPLEMENTING/VALIDATING/COMPLETED, návaznost na CI evidence atd.).
4. **Adapter conformance rodina** (`AdapterControlConformanceChecks`, `AdapterTriggerConformanceChecks`, `AdapterBindingConformanceChecks`, `AdapterContinuityConformanceChecks`, `AdapterExecutableContinuityConformanceChecks`, `AdapterPortfolioConformanceChecks`, `AdapterProfileEvidenceAuthority`) — ověřují, že tvrzení o podpoře cílových platforem (Jenkins, GitHub Actions, Tekton, …) jsou skutečně podložená kódem/testy a že "unsupported"/"unknown" nejsou tiše povyšovány na "supported".
5. **Sémantická ekvivalence / abstraktní topologie** (`SemanticEquivalenceContracts`, `SemanticEquivalencePlanFactory`, `SemanticObservationAuthority`, `SemanticEquivalenceConformanceChecks`, `AbstractTopologyMatrix*`) — dokazují, že přejmenování implementačních detailů (modul/akce/target) nemění pozorovatelnou sémantiku plánu, a že topologické požadavky (paralelismus, retry, kontinuita) jsou odvozeny nezávisle na cílové platformě.
6. **"Core pipeline" / release-honesty kontroly** (`CorePipelineSnapshotChecks`, `CliReleaseHonestyChecks`, `ScenarioAndPlanChecks`, `PlanningReadinessChecks`, `ArchitectureCoherenceChecks`, `DecisionAndArtifactChecks`, `ProjectionSurfaceChecks`, `IntentSafetyChecks`, `DeltaPurposeChecks`, `SchemaScenarioCatalogChecks`, `ExportManifestVerifierChecks`, `PurposeCoverage`, `ReferenceCorpusExecutionHarness`) — end-to-end regresní důkazy, že intent → AST → plán → cílová platforma pipeline dělá přesně to, co veřejně dokumentovaný standard slibuje, včetně JSON schema smoke testů (`JsonSchemaSmokeValidator`), verzovaných "gate" kontrol (v0.3.x–v0.9.x) a manifestové/exportní integrity.

Sdílená infrastruktura: `ConformanceCheckSupport` (base class s `runCheck`, pipeline buildery, hardcoded seznamy check-ID), `RealWorldCorpusRunner`/`RealWorldCorpusConformanceChecks`/`RealWorldPolarityAuthority`/`OperationalDomainCorpusLoader`/`RealWorldCorpusSerialization` (načítání a vyhodnocení "reálného světa" korpusu scénářů), `ConformanceVectorIndex`, `ConformanceSuiteInventory`, `ConformanceQualityGates`, `ConformanceReports` (datové typy a agregace).

---

## 2. Inventář souborů a tříd

### Velké/klíčové soubory (plný popis)

**`ConformanceCheckSupport.kt`** (420 ř.) — `internal abstract class ConformanceCheckSupport` + `data class PipelineArtifacts`. Sdílený kontext pro ~15 dalších *ConformanceChecks tříd: `buildPipeline()` (intent→AST→plán→manifest→render), `runCheck(name, body)` (catch `Throwable` → `ConformanceCheck(name, false, message)`), `standardBundleFixture()` (vytváří dočasný adresář — **bez úklidu**, viz §3), `activeStandardAtLeast`/`versionAtLeast` (semver porovnání), `hasDependencyCycle` (DFS detekce cyklu), a dva **hardcoded seznamy check-ID** (`postVectorIndexChecks()` ~16 položek, `allRunnerChecksForVectorIndex()` ~64 položek).

**`HumanApprovalChangeControlFalsification.kt`** (841 ř., největší soubor) — `enum HumanApprovalChangeControlRequirement` (9 hodnot), 3 datové třídy, `class HumanApprovalChangeControlFalsification` s 9 klasifikačními metodami (`approvalRequirementDeclaration`, `orderedApprovalGate`, `approvalDecisionState`, `authorizedApproverSet`, `approvalQuorum`, `separationOfDuties`, `revisionBoundApproval`, `wholeChangesetCoverage`, `revisionBoundExecution`). Veřejné API: `evaluate(): HumanApprovalChangeControlFalsificationReport`.

**`InfrastructureLifecycleFalsification.kt`** (603 ř.) — `enum InfrastructureLifecycleRequirement` (8 hodnot), analogická struktura pro PROVISION/DEPROVISION sémantiku (create-before-destroy, destruction-prohibition, retain-on-removal, …). Veřejné API: `evaluate()`.

**`AdapterProfileEvidenceAuthority.kt`** (510 ř.) — `class AdapterProfileEvidenceAuthority` (hlavní `analyze()`) + `object AdapterProfileReportIntegrityAuthority` (validuje, že "unsupported"/"unknown" indexy přesně odpovídají skutečným claims). Ověřuje SHA-256 otisky zamrzlých zdrojových souborů, verze dokumentů, pokrytí dimenzí (topology/control/continuity/rendering) napříč cíli.

**`DataOrchestrationFalsification.kt`** (496 ř.) — `enum DataOrchestrationRequirement` (8 hodnot). Pozoruhodné: `dataAssetDependency()` a `conditionalBranching()` **vždy** vrací `MODEL_GAP` bez ohledu na vypočtenou strukturální booleovskou proměnnou (viz §3 bod 5) — záměrně zdokumentovaná trvalá mezera v modelu.

**`RealWorldCorpusRunner.kt`** (434 ř.) — `class RealWorldCorpusRunner`: `evaluate()`, `evaluateMutations()`, `evaluateIntent()` (privátní), `comparePlan()`, `assessTarget()`. Řídí celý intent→plán→cíl pipeline pro každý testovací případ.

**`IncidentRemediationFalsification.kt`** (409 ř.) — `enum IncidentRemediationRequirement` (6 hodnot). Struktura identická s Data-Orchestration/Infrastructure-Lifecycle/HumanApproval — sdílené `ExternalFalsificationOutcome` enum (definované v `DatabaseMigrationRecoveryFalsification.kt`).

**`AdapterExecutableContinuityConformanceChecks.kt`** (382 ř.) — `class AdapterExecutableContinuityConformanceChecks` (5 kontrol) + `data class ExecutableContinuityConformanceInventory` + `class AdapterExecutableContinuityConformanceRunner`. Ověřuje, že GitHub Actions "workspace continuity" podpora je úzce ohraničená na jeden referenční scénář.

**`SemanticEquivalenceContracts.kt`** (346 ř.) — 4 enumy + 6 datových tříd + `object SemanticObservationIdentity` (SHA-256 fingerprint) + `object SemanticEquivalenceLoader`. Čistě datový/kontraktní soubor.

**`AdapterControlConformanceChecks.kt`** (308 ř.) a **`AdapterTriggerConformanceChecks.kt`** (308 ř.) — strukturně analogické: `runtimeAuthorityErrors()`, `unsupportedDemotionErrors()`/`noApproximationErrors()`, `platformSeparationErrors()`/`reviewEvidenceErrors()`. Testují chování runtime "authority" objektů na syntetických `ExecutionPlan` fixtures.

**`DatabaseMigrationRecoveryFalsification.kt`** (304 ř.) — `enum DatabaseMigrationRecoveryRequirement` (4 hodnoty) + definuje sdílený `enum ExternalFalsificationOutcome { REPRESENTABLE, MODEL_GAP }`. Obsahuje jediné dva force-unwrapy (`!!`) v celém subsetu.

**`AdapterProfileEvidenceRoadmapLifecycleAuthority.kt`** (294 ř.) — `enum AdapterProfileEvidenceLifecyclePhase`, `data class AdapterProfileWorkflowEvidence`, `data class AdapterProfileActivationEvidence`, `class AdapterProfileEvidenceRoadmapLifecycleAuthority` s `analyze()`/`evaluate()`. Čistě procesní logika.

**`AbstractTopologyMatrixAuthority.kt`** (293 ř.) — `data class AbstractTopologyMatrixReport` + `class AbstractTopologyMatrixAuthority` s `analyze()`, `schemaErrors()`, `coverageErrors()`, `polarityErrors()` (mutation-testing MISSING/PARTIAL/UNSUPPORTED/UNKNOWN/CONTRADICTORY), `independenceErrors()`, `concreteReferenceErrors()`, `boundaryErrors()`.

**`OperationalDomainCorpusLoader.kt`** (291 ř.) — `class OperationalDomainCorpusLoader` s explicitní path-traversal ochranou (`resolveRootFile`/`resolveCorpusDirectory`/`requiredFile` vždy `canonicalFile.toPath().startsWith(...)`) — dobrá bezpečnostní praxe.

**`PlanningReadinessChecks.kt`** (288 ř.) — 7 kontrol (v0.3.4–v0.3.8, plus `checkProviderBackedApprovalAndTopologyIdentity`, `checkDerivedModelAndGovernanceIntegrity`). Obsahuje "lossy slugging" ochranu (duplicitní workflow názvy nesmí kolabovat na stejné ID).

**`RealWorldCorpusConformanceChecks.kt`** (283 ř.) — orchestruje ~10 kontrol nad `RealWorldCorpusRunner` (integrity, domain-coverage, representability, dynamic-boundary, mutation polarity per-case).

**`AdapterContinuityConformanceChecks.kt`** (268 ř.) — analogická `AdapterControlConformanceChecks`, ale pro WORKSPACE/VALUE/STATE kontinuitu.

**`CorePipelineSnapshotChecks.kt`** (265 ř.) — 9 kontrol. `checkEndToEndSnapshotContent()` regeneruje referenční snapshoty do dočasného adresáře a srovnává s commitnutými — **správně** používá `try/finally { deleteRecursively() }`.

**`ReferenceCorpusExecutionHarness.kt`** (249 ř.) — `execute()`/`assertPass()`/`executeScenario()` (private). Chytá `Throwable` per-scénář → `actualStatus = "ERROR"`, pragmatické pro agregační report.

**`JsonSchemaSmokeValidator.kt`** (248 ř.) — `object JsonSchemaSmokeValidator`. Vlastní implementace podmnožiny JSON Schema — fail-closed na neznámé klíčové slovo schématu.

**`SemanticObservationAuthority.kt`** (234 ř.) — `object SemanticObservationAuthority` s `requirementsFor()`, `fullyPreserved()`, `assess()`. Odvozuje sémantické požadavky čistě z `ExecutionPlan`.

**`AdapterBindingConformanceChecks.kt`** (229 ř.) — 5 kontrol (lifecycle, evidence, runtime-binding-authority, effect-provenance, polarity).

**`LanguageContractIntegrityLifecycle.kt`** (215 ř.) — `internal object`, extrémní příklad "governance jako kód": **hardcoded PR číslo (178), commit SHA, workflow run ID (34493567009), job ID, počty testů (1590/151/246/1544)** jako Kotlin konstanty. Architektonicky nejextrémnější příklad "bureaucracy-as-code" v subsetu.

**`AdapterPortfolioConformanceChecks.kt`** (208 ř.) — `coreDependencyErrors()` prochází zdrojové adresáře jádra a hlídá, že žádný neimportuje `org.flowlang.adapters.portfolio` — architektonická hranice vynucená greppem za běhu testu.

### Menší soubory (stručněji)

- **`HumanApprovalChangeControlBaseline.kt`** (205 ř.), **`BackupRestoreBaseline.kt`** (179 ř.), **`IncidentRemediationBaseline.kt`** (179 ř.), **`SecretRotationBaseline.kt`** (148 ř.) — **čtyři téměř identické** baseline verifikátory, liší se jen EF-ID prefixem a typy. Klasický copy-paste.
- **`PurposeCoverage.kt`** (196 ř.) — `PurposeCoverageAnalyzer.analyze()`; strukturální (ne ratio-based) pass/fail.
- **`IntentSafetyChecks.kt`** (187 ř.) — 5 kontrol v0.6.1–v0.6.5.
- **`ProjectionSurfaceChecks.kt`** (174 ř.) — 4 kontroly v0.4.4–v0.4.7.
- **`AbstractTopologyMatrixContracts.kt`** (170 ř.) — 2 enumy + 3 datové třídy + `object AbstractTopologyMatrixLoader`.
- **`ArchitectureCoherenceChecks.kt`** (162 ř.) — 2 velké kontroly (v0.7.1, v0.7.3) s desítkami dílčích `require()`.
- **`ScenarioAndPlanChecks.kt`** (158 ř.) — 6 kontrol.
- **`TargetNeutralConformanceFixture.kt`** (157 ř.) — staví "target-neutral" evidenci pro schema/manifest testy.
- **`SemanticEquivalencePlanFactory.kt`** (153 ř.) — generuje syntetické `ExecutionPlan` fixtures.
- **`DecisionAndArtifactChecks.kt`** (148 ř.) — 5 kontrol v0.3.9–v0.3.13.
- **`CliReleaseHonestyChecks.kt`** (139 ř.) — jedna velká kontrola ověřující honest CLI entrypoint a review-only artefakty.
- **`AbstractTopologyMatrixPlanFactory.kt`** (127 ř.) — analogie `SemanticEquivalencePlanFactory` pro topologické fixtures.
- **`BoundedDomainCorpusRoadmapLifecycleAuthority.kt`** (123 ř.) — C0.1 fázová validace.
- **`AbstractTopologyMatrixConformanceChecks.kt`** (118 ř.) a **`SemanticEquivalenceConformanceChecks.kt`** (117 ř.) — **téměř identická dvojčata** (C0.2 vs. C0.3).
- **`DeltaPurposeChecks.kt`** (118 ř.) — 2 kontroly (v0.7.4, v0.7.5).
- **`ExportManifestVerifierChecks.kt`** (114 ř.) — 2 kontroly; druhá aktivně **korumpuje** kopie bundle fixtures a ověřuje, že verifikátor správně selže — dobrý negativní test.
- **`SchemaScenarioCatalogChecks.kt`** (103 ř.) — ~45 volání `JsonSchemaSmokeValidator.validate` napříč veřejnými JSON artefakty.
- **`ExternalCorpusContracts.kt`** (88 ř.) — čistě datové třídy, 0 top-level `fun`.
- **`ConformanceVectorIndex.kt`** (83 ř.) — prochází `conformance/**/*.yaml`, hledá `kind: FlowConformanceVector`.
- **`ConformanceQualityGates.kt`** (55 ř.) — 2 gates.
- **`RealWorldPolarityAuthority.kt`** (55 ř.) — `classify()`/`compare()`.
- **`ConformanceSuiteInventory.kt`** (53 ř.) — `init{}` validace + `companion.load()`.
- **`RealWorldCorpusSerialization.kt`** (42 ř.) — striktní Jackson `ObjectMapper`.
- **`ClosureEvidenceBoundaryChecks.kt`** (25 ř.) — nejmenší netriviální soubor.
- **`ConformanceReports.kt`** (9 ř.) — nejmenší soubor: `ConformanceSummary` + `ConformanceCheck`, základní typy použité všude jinde.

---

## 3. Zjištěné chyby a nedostatky

| # | Umístění | Závažnost | Popis / scénář selhání | Návrh opravy |
|---|---|---|---|---|
| 1 | `ConformanceCheckSupport.kt:222-251` (`standardBundleFixture()`) | **Střední** | Vytvoří dočasný adresář v `java.io.tmpdir`, zapíše desítky placeholder souborů, ale **nikde v této funkci není `deleteRecursively()`** — na rozdíl od téměř identického vzoru jinde v témže souboru (`CorePipelineSnapshotChecks`, `AdapterExecutableContinuityConformanceChecks`), kde je `try/finally { deleteRecursively() }` důsledně použito. Opakované spouštění CI zaplňuje `/tmp` osiřelými adresáři. | Zabalit volání do `try/finally { dir.deleteRecursively() }`. |
| 2 | `ConformanceCheckSupport.kt:280-364` (`postVectorIndexChecks()`, `allRunnerChecksForVectorIndex()`) | **Střední** | Dva hardcoded seznamy ~80 řetězcových check-ID udržované ručně mimo kompilátorem kontrolovanou vazbu na skutečná `runCheck("…")` volání. Riziko tichého driftu (chybějící/duchové ID) detekovaného jen za běhu. | Generovat seznam přes centrální `ConformanceCheckRegistry` místo literálu. |
| 3 | `BackupRestoreBaseline.kt`, `IncidentRemediationBaseline.kt`, `SecretRotationBaseline.kt`, `HumanApprovalChangeControlBaseline.kt` (~700 ř. celkem) | **Střední** (údržba) | Čtyři strukturně identické baseline verifikátory (liší se jen EF-prefixem, `*Requirement` typem a cestami). Oprava logiky v jednom se musí ručně replikovat ve zbylých třech — reálné riziko driftu. | Extrahovat generický `ExternalFalsificationBaselineVerifier<TFact, TReport, TFinding>`. |
| 4 | `DatabaseMigrationRecoveryFalsification.kt`, `IncidentRemediationFalsification.kt`, `DataOrchestrationFalsification.kt`, `InfrastructureLifecycleFalsification.kt`, `HumanApprovalChangeControlFalsification.kt` (~2650 ř. celkem) | **Nízká–Střední** | Sdílené lešení (`loadAssessment()`, `readStrict()`, `requireUnique()`, `outcome()`) přepsáno v každém souboru téměř identicky (~80–100 ř./soubor duplikace), jen doménová `classify()` se liší přirozeně. | Vytáhnout `ExternalFalsificationEvaluatorSupport`. |
| 5 | `DataOrchestrationFalsification.kt:250-257, 351-359` | **Nízká** (čitelnost) | `dataAssetDependency()` a `conditionalBranching()` — obě větve if/else vracejí **stejný** `MODEL_GAP` výsledek, liší se jen zprávou; vypočtená booleovská proměnná neovlivní outcome. Záměr (trvalá modelová mezera), ale matoucí bez komentáře. | Přidat vysvětlující komentář nebo zjednodušit na jediný `return`. |
| 6 | `DatabaseMigrationRecoveryFalsification.kt:150,152` | **Nízká** | `fact.semanticValue!!` — jediný force-unwrap v subsetu. Bezpečný za aktuální kontroly pořadí volání, ale křehký při refaktoringu. | `requireNotNull(...) { "…" }` místo `!!`. |
| 7 | `LanguageContractIntegrityLifecycle.kt:14-33, 189-199` | **Nízká–Střední** (křehkost) | Hardcoded PR číslo, 5 commit SHA, workflow run ID, počty testů přímo jako Kotlin konstanty v produkčním zdroji — nepřenositelné mimo přesně jeden bod historie repa. | Přesunout do `.flow-agent/evidence/*.yaml`, v Kotlinu ponechat jen validační logiku. |
| 8 | `ConformanceCheckSupport.kt:399-404`, `ConformanceQualityGates.kt:49-54`, a ~189 míst s `runCatching` | **Nízká** | `catch (t: Throwable)` chytá i `Error` (OOM, StackOverflow) a tiše je promění na "FAIL: message", což může maskovat vážný provozní problém jako běžné selhání kontroly. | Chytat `Exception`, ne `Throwable`. |
| 9 | `AbstractTopologyMatrixConformanceChecks.kt` / `SemanticEquivalenceConformanceChecks.kt` | **Nízká** | Téměř identická dvojčata C0.2 vs C0.3 (stejná struktura `*ConformanceChecks`/`*ConformanceInventory`/`*ConformanceRunner`/`categoryCheck()`). | Sdílet generický `RoadmapVersionedConformanceRunner<TAuthority>`. |
| 10 | `ArchitectureCoherenceChecks.kt`, `IntentSafetyChecks.kt`, `PlanningReadinessChecks.kt` a další | **Nízká** (reportingová neúplnost) | Jednotlivé `runCheck(...)` bloky s 15–25 `require()` za sebou — `require()` selže při prvním, zbylé se nikdy nevyhodnotí v daném běhu, takže se chyby odhalují postupně "jedna po druhé". | Sbírat chyby do `buildList` místo řetězu `require()`. |
| 11 | `RealWorldCorpusConformanceChecks.kt`, `AbstractTopologyMatrixConformanceRunner`, `SemanticEquivalenceConformanceRunner`, `AdapterConformanceRunner`, `AdapterExecutableContinuityConformanceRunner` | **Nízká** | Vzor "načti YAML inventář → porovnej s produced names → INVENTORY_CHECK" přepsán nezávisle nejméně pětkrát s drobně odlišným parsováním. | Sjednotit do `InventoryExactnessAuthority`. |
| 12 | `ExternalCorpusContracts.kt` | Informativní | 0 top-level funkcí — čistě datový soubor, správně, jen poznámka. | — |

**Co jsem nenašel** (přestože bylo explicitně požadováno hledat): žádná kontrola nevrací PASS bez skutečné validace (každý `runCheck`/`check()` blok má reálné `require()`); **0 výskytů** TODO/FIXME/HACK/XXX; mimo bod 6 žádný force-unwrap. Subset je v tomto ohledu nezvykle disciplinovaný.

---

## 4. Architektonická pozorování

**Přiměřená QA investice, nebo bloated proces-governance kód? Obojí, ve velmi nerovnoměrném poměru.**

- **EF-0x falsifikační rodina** a **sémantická ekvivalence/topologie** jsou legitimní, neobvykle promyšlená QA investice: modelují explicitně **co** je sémanticky zachováváno (efekt, identita výsledku, kontinuita) nezávisle na implementačních detailech a honestně rozlišují "nepodporováno naší platformou" (`MODEL_GAP`, evidence) od "conformance regrese" (`FAIL`).
- **Adapter-conformance rodina** je funkčně podobně hodnotná — aktivně hlídá, že "unsupported" se nikdy tiše nezmění na "supported" (`AdapterProfileReportIntegrityAuthority`, `unsupportedDemotionErrors`, `profileOnlyDemotionErrors`) a že bounded/scoped evidence (A1.0 GitHub Actions) se nešíří mimo svůj úzký rámec.
- **Roadmap/lifecycle-authority rodina** je naproti tomu **čistá procesní byrokracie zakódovaná jako testovatelný Kotlin** — validuje, že interní `.flow-agent/*.yaml` dokumenty jsou konzistentní, ne že produkt funguje. `LanguageContractIntegrityLifecycle.kt` je extrémní příklad s natvrdo zapsaným PR číslem, 5 commit SHA, workflow run ID a přesnými počty testů jako produkční Kotlin konstanty — hodnota takového kódu je diskutabilní, je to v podstatě administrativní checklist zakódovaný jako testovatelná třída, nákladný na údržbu bez vztahu k chování produktu.
- **Duplikace napříč doménami** (baseline verifikátory, EF-0x scaffolding, C0.2/C0.3 dvojčata, pětinásobně přepsaný "inventory exactness" vzor) naznačuje, že balíček rostl kopírováním existujících souborů místo budování sdílených abstrakcí — reálný technický dluh při celkových 108 souborech/24 629 řádcích balíčku.
- **`JsonSchemaSmokeValidator`** (249 ř. vlastní JSON Schema implementace) je pochopitelné, ale nákladné "not invented here" oproti existujícím knihovnám — kompenzováno konzistentní fail-closed politikou.
- Shrnutí: QA investice je nadprůměrně promyšlená tam, kde jde o produktovou sémantiku (co se stane s uživatelovým intentem), ale degraduje do bloated bureaucracy tam, kde jde o vlastní vývojový proces týmu.

---

## 5. Statistická tabulka

| Soubor | Řádky | Tříd/objektů/enumů | Veř. funkcí (odhad) |
|---|---:|---:|---:|
| HumanApprovalChangeControlFalsification.kt | 841 | 6 | 1 |
| InfrastructureLifecycleFalsification.kt | 603 | 6 | 1 |
| AdapterProfileEvidenceAuthority.kt | 510 | 2 | 2 |
| DataOrchestrationFalsification.kt | 496 | 6 | 1 |
| RealWorldCorpusRunner.kt | 434 | 1 | 4 |
| ConformanceCheckSupport.kt | 420 | 2 | 37 |
| IncidentRemediationFalsification.kt | 409 | 6 | 1 |
| AdapterExecutableContinuityConformanceChecks.kt | 382 | 3 | 3 |
| SemanticEquivalenceContracts.kt | 346 | 17 | 7 |
| AdapterControlConformanceChecks.kt | 308 | 1 | 1 |
| AdapterTriggerConformanceChecks.kt | 308 | 1 | 1 |
| DatabaseMigrationRecoveryFalsification.kt | 304 | 7 | 1 |
| AdapterProfileEvidenceRoadmapLifecycleAuthority.kt | 294 | 6 | 5 |
| AbstractTopologyMatrixAuthority.kt | 293 | 2 | 1 |
| OperationalDomainCorpusLoader.kt | 291 | 1 | 1 |
| PlanningReadinessChecks.kt | 288 | 1 | 1 |
| RealWorldCorpusConformanceChecks.kt | 283 | 1 | 1 |
| AdapterContinuityConformanceChecks.kt | 268 | 1 | 1 |
| CorePipelineSnapshotChecks.kt | 265 | 1 | 1 |
| ReferenceCorpusExecutionHarness.kt | 249 | 3 | 2 |
| JsonSchemaSmokeValidator.kt | 248 | 1 | 1 |
| SemanticObservationAuthority.kt | 234 | 1 | 3 |
| AdapterBindingConformanceChecks.kt | 229 | 1 | 1 |
| LanguageContractIntegrityLifecycle.kt | 215 | 1 | 1 |
| AdapterPortfolioConformanceChecks.kt | 208 | 3 | 3 |
| HumanApprovalChangeControlBaseline.kt | 205 | 5 | 2 |
| PurposeCoverage.kt | 196 | 3 | 1 |
| IntentSafetyChecks.kt | 187 | 1 | 1 |
| BackupRestoreBaseline.kt | 179 | 5 | 2 |
| IncidentRemediationBaseline.kt | 179 | 5 | 2 |
| ProjectionSurfaceChecks.kt | 174 | 1 | 2 |
| AbstractTopologyMatrixContracts.kt | 170 | 6 | 3 |
| ArchitectureCoherenceChecks.kt | 162 | 1 | 1 |
| ScenarioAndPlanChecks.kt | 158 | 1 | 1 |
| TargetNeutralConformanceFixture.kt | 157 | 2 | 12 |
| SemanticEquivalencePlanFactory.kt | 153 | 1 | 1 |
| DecisionAndArtifactChecks.kt | 148 | 1 | 1 |
| SecretRotationBaseline.kt | 148 | 5 | 2 |
| CliReleaseHonestyChecks.kt | 139 | 1 | 1 |
| AbstractTopologyMatrixPlanFactory.kt | 127 | 1 | 1 |
| BoundedDomainCorpusRoadmapLifecycleAuthority.kt | 123 | 2 | 1 |
| AbstractTopologyMatrixConformanceChecks.kt | 118 | 3 | 3 |
| DeltaPurposeChecks.kt | 118 | 1 | 1 |
| SemanticEquivalenceConformanceChecks.kt | 117 | 3 | 3 |
| ExportManifestVerifierChecks.kt | 114 | 1 | 1 |
| SchemaScenarioCatalogChecks.kt | 103 | 1 | 1 |
| ExternalCorpusContracts.kt | 88 | 13 | 0 |
| ConformanceVectorIndex.kt | 83 | 3 | 1 |
| ConformanceQualityGates.kt | 55 | 2 | 1 |
| RealWorldPolarityAuthority.kt | 55 | 3 | 2 |
| ConformanceSuiteInventory.kt | 53 | 1 | 1 |
| RealWorldCorpusSerialization.kt | 42 | 1 | 2 |
| ClosureEvidenceBoundaryChecks.kt | 25 | 1 | 1 |
| ConformanceReports.kt | 9 | 2 | 0 |
| **Součet (54 souborů)** | **12 311** | **~145** | **~140** |

---

## Shrnutí

Přečteno všech 54 souborů (12 311 řádků) z balíčku `org.flowlang.conformance`. Subset tvoří šest proudů: EF-0x "externí falsifikace" (~2 650 ř.), jejich baseline verifikátory, roadmap/lifecycle-authority třídy (procesní governance), adapter-conformance rodina, sémantická ekvivalence/abstraktní topologie a core-pipeline/release-honesty kontroly.

Kód je nezvykle disciplinovaný: **žádná kontrola nevrací PASS bez skutečné validace**, **0 TODO/FIXME/HACK**, jen **2 force-unwrapy** v celém subsetu (oba bezpečné). Nenalezena žádná kritická nebo vysoce závažná chyba.

Zjištěno 12 nedostatků, žádný Critical/High:
- **Střední (3):** chybějící úklid dočasného adresáře v `ConformanceCheckSupport.standardBundleFixture()` (potenciální leak v CI); dva ručně udržované seznamy ~80 hardcoded check-ID v `ConformanceCheckSupport`; čtyřnásobná copy-paste duplikace baseline-verifikátorů (Backup/Incident/Secret/HumanApproval, ~700 ř.).
- **Nízká (9):** duplikace scaffoldingu napříč 5 EF-0x evaluátory; dvě větve vracející stejný výsledek v `DataOrchestrationFalsification` (matoucí, ne chybné); křehký force-unwrap v `DatabaseMigrationRecoveryFalsification.kt:150,152`; `catch (Throwable)` maskující i JVM `Error` (~189 míst s `runCatching`, 4 přímé `catch Throwable`); dvojčata C0.2/C0.3 conformance runnerů; kontroly s 15–25 `require()`, kde se reportuje jen první selhání; pětinásobně přepsaný "inventory exactness" vzor; extrémně křehké hardcoded PR/commit/job ID v `LanguageContractIntegrityLifecycle.kt` (PR 178, commit SHA, workflow run 34493567009, počty testů).

Architektonicky jde o kombinaci nadprůměrně promyšlené QA investice (EF-0x a adapter-conformance rodiny explicitně modelují sémantickou zachovalost, honestně rozlišují "nepodporováno" od "regrese") a bloated proces-byrokracie zakódované jako Kotlin (roadmap-lifecycle-authority soubory validující vlastní vývojový proces týmu, ne produkt).
