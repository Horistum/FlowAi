# FlowAi (Horistum) — Analýza testovací sady, dávka B (94 souborů)

Rozsah: `/home/user/FlowAi/src/test/kotlin/`, abecedně `AbstractExecutionTopologyModelTests.kt` … `WorkflowSemanticsRecoveryReceiptTests.kt`.
Souhrn: **94 souborů, 14 413 řádků, 553 metod `@Test`** (průměr ~154 řádků/soubor, ~5,9 testů/soubor). Dva soubory (`ProjectionAuthorizationTestSupport.kt`, `TopologyTestFixtures.kt`) jsou čisté fixture/support knihovny bez `@Test`.

---

## 1. Přehled — jaké typy testů převažují

Sada se nedá popsat jako klasická jednotková/integrační testovací sada. Jde o kombinaci pěti odlišných žánrů, které se v poměru cca takto podílejí na 94 souborech dávky B:

| Kategorie | Přibližný počet souborů | Charakter |
|---|---|---|
| **Governance/roadmap lifecycle testy** ("Authority" nad `.yaml` stavem roadmapy: A0.x, AR0.x, C0.x/C1.0) | ~16 | Testují, že interní bookkeeping projektu (stav dokončení pracovních balíčků, návaznost commit SHA/CI run ID) je vnitřně konzistentní. Netestují jazyk/kompilátor. |
| **"Falsification"/externí korpus testy (EF-02…EF-09)** | ~9 | Ověřují, zda kanonický sémantický model FlowAi dokáže reprezentovat reálné vzorce z cizích repozitářů (Velero, Restic, Airflow, Dagster, GitHub, Atlantis, StackStorm, cert-manager…), poctivě rozlišují `REPRESENTABLE` vs `MODEL_GAP`. |
| **Adapter/Target "Authority" testy** (Control, Trigger, Continuity, Topology, Artifact Rendering) | ~20 | End-to-end: Intent → AST → ExecutionPlan → TargetManifest → render, s "mutačním" stylem (vezmi platný dokument, poškoď jedno pole, ověř fail-closed kód chyby). |
| **Jádro jazyka/kompilátoru** (parser, planner, flow-sensitive availability, explicit merge, dependency/continuity graf) | ~15 | Klasické, kvalitní sémantické testy nad skutečným kompilátorem. Nejsilnější část sady. |
| **Architektonické "fitness function" testy** (package layering, naming, CI/CD bias, serialization boundary, source-grep testy) | ~10 | Skenují vlastní zdrojový kód (`.kt` soubory) regexy/importy, ne runtime chování. |
| **Kontraktní/verzovací testy** (schema, version consistency, target manifest/renderer contract) | ~10 | Ověřují JSON schémata a řetězcovou konzistenci verzí napříč desítkami MD/YAML souborů. |
| **"Mega-testy" (JUnit bridge na vlastní harness H)** | 2 | `FlowSpecJUnitTest`, `FlowConformanceBridgeJUnitTest` — jedna `@Test` metoda spouští stovky scénářů přes externí harness `H` (mimo rozsah této dávky). |

Klíčové pozorování: **výrazná menšina souborů testuje skutečnou funkčnost kompilátoru/generátorů** v klasickém slova smyslu. Většinová plocha kódu (governance-lifecycle + falsification + naming/layering fitness testy) testuje **procesní a dokumentační integritu repozitáře samotného**, nikoli chování produkčního API. Podrobněji v sekci 5.

---

## 2. Inventář souborů

(cesta relativní k `src/test/kotlin/`, řádky, framework, počet `@Test`, co skutečně testuje)

1. **AbstractExecutionTopologyModelTests.kt** | 188 | kotlin.test | 7 | Kanonická topologie exekučního plánu nezávislá na inventáři modulů; matching profilu targetu vs. požadavky; Jenkins vs. GitHub Actions workspace topology block.
2. **AdapterArtifactRenderingAuthorityTests.kt** | 127 | kotlin.test | 4 | Renderování Jenkinsfile z manifestu, review-only fallback, detekce manipulace s obsahem artefaktu (digest mismatch), fail-fast při BLOCKED materializaci.
3. **AdapterArtifactRenderingCliTests.kt** | 103 | kotlin.test | 2 | CLI end-to-end: `--render` produkuje Jenkinsfile + evidence JSON, nebo review-only YAML při nedostatečné podpoře.
4. **AdapterArtifactRenderingEvidenceIntegrityTests.kt** | 89 | kotlin.test | 4 | Integrita evidence-dokumentu renderování (žádné review nemůže předstírat executable, žádný "supported" bez nezávislé evidence).
5. **AdapterBindingRoadmapLifecycleAuthorityTests.kt** | 138 | kotlin.test | 7 | Lifecycle stavový automat pro roadmap položku A0.3 (binding) — governance test, ne funkční.
6. **AdapterCapabilityBindingMigrationAuthorityTests.kt** | 60 | kotlin.test | 3 | Migrace `dockerfile` z semantic do binding parametrů; digest zmrazeného C0.4 kontraktu.
7. **AdapterContinuityProviderBehaviorTests.kt** | 284 | kotlin.test | 6 | Skutečný přenos workspace mezi joby (Jenkins stash vs. GitHub Actions upload/download-artifact), včetně bajtové reprodukce souborů (`replayUpload`/`replayDownload`).
8. **AdapterControlCliMaterializationTests.kt** | 69 | kotlin.test | 1 | Nepodporovaný retry na Jenkins → review-only evidence, žádný Jenkinsfile.
9. **AdapterControlMaterializationAuthorityTests.kt** | 442 | kotlin.test | 21 | Největší a nejhutnější control-adapter test: approval scope, retry blokace, cron/timezone, scope mismatch, duplicitní evidence, verze manifestu — velmi vysoká hustota mutačních negativních scénářů.
10. **AdapterControlProviderBehaviorTests.kt** | 236 | kotlin.test | 4 | Skutečné renderování try/catch (Jenkins Groovy) a cron trigger napříč Jenkins/GitHub Actions přes produkční kompilátor.
11. **AdapterControlTypedEvidenceIntegrityTests.kt** | 87 | kotlin.test | 4 | Typed evidence nesmí chybět, uniknout z repo rootu, duplikovat referenci, nebo obsahovat neznámou sémantiku.
12. **AdapterExecutableContinuityRoadmapLifecycleAuthorityTests.kt** | 146 | kotlin.test | 6 | Governance lifecycle A1.0 (executable continuity) — completion boundary musí být odlišná od implementation.
13. **AdapterProfileEvidenceAuthorityTests.kt** | 111 | kotlin.test | 7 | Konzistence agregovaného profilu (topology/control/continuity/rendering) napříč targety, honesty-validace unsupported/unknown indexů.
14. **AdapterRoadmapLifecycleAuthorityTests.kt** | 157 | kotlin.test | 9 | Governance lifecycle A0.1.
15. **AdapterTopologyEvidenceAuthorityTests.kt** | 191 | kotlin.test | 9 | Topology claims per target (SUPPORTED/UNKNOWN), self-referential evidence detekce, loader odmítá neznámé klíče.
16. **AdapterTopologyRoadmapLifecycleAuthorityTests.kt** | 161 | kotlin.test | 9 | Governance lifecycle A0.2.
17. **AdapterTriggerMaterializationAuthorityTests.kt** | 216 | kotlin.test | 7 | Trigger rodiny (manual/cron/interval/calendar/event/webhook), Jenkins vs. GitHub Actions blokace na timezone/interval, duplicitní sémanticky stejné triggery odmítnuty.
18. **AdapterTriggerProviderBehaviorTests.kt** | 169 | kotlin.test | 6 | Reálné renderování `workflow_dispatch`, `cron()`, timezone do GitHub Actions/Jenkins YAML/Groovy.
19. **AdapterTriggerRoadmapLifecycleAuthorityTests.kt** | 141 | kotlin.test | 7 | Governance lifecycle A0.7.
20. **ArchitectureReadinessBaselineAnalyzerTests.kt** | 46 | kotlin.test | 2 | Baseline architektonických metrik (počet typů, autorit, public artifact contract verzí) nad živým repozitářem.
21. **ArtifactDerivedIntentLoweringEvidenceTests.kt** | 184 | kotlin.test | 7 | Lowering-report (Intent→Plan) musí být reprodukovatelný, detekce falšovaného digestu, chybějící/duplicitní evidence.
22. **AuthoredDependencyGraphPreservationTests.kt** | 192 | kotlin.test | 6 | Diamond-graph (independent siblings se neserializují), detekce přidané/chybějící DECLARED_ORDERING hrany.
23. **AuthorityConsolidationCompletionTests.kt** | 241 | kotlin.test | 5 | Governance: uzavření AR0.1 (Authority Responsibility Consolidation) — fixture-based state machine.
24. **AuthorityConsolidationEvidenceTests.kt** | 231 | kotlin.test | 4 | Governance: AR0.1 aktivní fáze, návaznost evidence boundaries.
25. **AuthorityResponsibilityCatalogCritiqueRegressionTests.kt** | 152 | kotlin.test | 5 | Statická analýza "co je produkční použití autority" (self-factory nepočítá, sourozenec ve stejném souboru ano) — testuje vlastní meta-analyzér `AuthorityResponsibilityCatalog`.
26. **BackupRestoreBaselineTests.kt** | 120 | kotlin.test | 6 | EF-03 baseline verifier — nelze přepsat MODEL_GAP na REPRESENTABLE bez schválení, nelze zahodit fakt.
27. **BackupRestoreFalsificationTests.kt** | 117 | kotlin.test | 9 | EF-03 falsifikace nad Restic/Velero: konkrétní outcome pro 5 faktů + 3 integrity testy proti manipulaci s assessment souborem.
28. **CanonicalControlScopeIntegrityTests.kt** | 338 | kotlin.test | 14 | Velmi kvalitní: canonical control requirement (backup chrání migraci jen přes graf-dosažitelnost, ne "je ve stejném workflow"/pořadí), dry-run scope, change-ticket scope.
29. **CanonicalImageTechnologyNeutralityTests.kt** | 118 | kotlin.test | 4 | `dockerfile` je čistě binding parametr, nemění kanonický význam intentu bez ohledu na přítomnost Docker modulu.
30. **CanonicalModuleTargetOwnershipTests.kt** | 89 | kotlin.test | 2 | Modul nesmí deklarovat target-specific pole (runtime/generators/template/entrypoint).
31. **CanonicalNotesDependencyParityTests.kt** | 27 | kotlin.test | 1 | Jediný test: řetězová závislost 4 "canonical notes" balíčků — velmi tenké pokrytí.
32. **CertificateLifecycleBaselineTests.kt** | 128 | kotlin.test | 6 | EF-05 baseline verifier (analog #26 pro cert-manager/certbot).
33. **CiCdBiasBoundaryPrecisionTests.kt** | 186 | kotlin.test | 6 | Lexikální analyzátor CI/CD-vendor bias (Jenkins/Kubernetes/Maven) rozlišuje katalogové výčty od kontrolních literálů; testuje hranice přesnosti přes syntetické fixture zdrojáky.
34. **CiCdBiasBuildConfigurationTests.kt** | 50 | kotlin.test | 2 | Maven v `settings.gradle.kts` je neutrální, Maven v core-sémantickém zdroji vyžaduje review.
35. **ClosureBlockingSafetyIntegrityTests.kt** | 179 | kotlin.test | 8 | Textová evidence (`AuthoredControlEvidenceTextAuthority`) rozlišuje ambiguous/denied/confirmed hodnoty pro backup/rollback/retention — 16+7+3 parametrizovaných případů.
36. **ClosureBlockingTopologyIntegrityTests.kt** | 124 | kotlin.test | 5 | Kanonická topologie nemůže být vynechána/zfalšována bez detekce (provenance chain).
37. **CodeNamingTests.kt** | 114 | kotlin.test | 6 | Fitness function: žádný soubor/deklarace nesmí nést "milestone" jméno (Ar02, EF-09…), vyjma verzovaných technických termínů; test na temp-fixture i live repo.
38. **CompatibilityReconciliationMonotonicityTests.kt** | 130 | kotlin.test | 3 | Native payload nemůže "vygumovat" existující compatibility blocker (monotónnost).
39. **CompilerModuleCompositionTests.kt** | 41 | kotlin.test | 3 | Classloader-level ověření, že distribuce nerecompiluje moduly (žádné duplicitní class definice).
40. **ControlEvidenceReferenceMigrationTests.kt** | 70 | kotlin.test | 2 | SHA-256 pinning přesunu zdrojových referencí v control evidence — byte-přesná migrace.
41. **CoreContractCheckTests.kt** | 15 | kotlin.test | 1 | Nejmenší soubor v dávce — jediný smoke test nad `CoreContractCheck.report()`.
42. **CritiqueFailClosedRegressionTests.kt** | 158 | kotlin.test | 8 | YAML duplicate-key rejection, lexer/parser stack-overflow fail-closed limity, drift-score bez negative-signal katalogu = FAIL, konstruktorové invarianty pro equivalence pár.
43. **DataOrchestrationBaselineTests.kt** | 108 | kotlin.test | 6 | EF-07 baseline (Airflow/Dagster).
44. **DataOrchestrationFalsificationTests.kt** | 141 | kotlin.test | 11 | EF-07: 8 případů, přesná REPRESENTABLE/MODEL_GAP klasifikace (task dependency ano, conditional branching/partition backfill ne) + anti-tamper testy.
45. **DatabaseMigrationRecoveryFalsificationTests.kt** | 149 | kotlin.test | 8 | EF-02: schema apply/reverse, PITR vs. named recovery point.
46. **DerivedModelGovernanceIntegrityTests.kt** | 263 | kotlin.test | 9 | Integrita odvozených modelů (negotiation/selection) napříč více fázemi pipeline, drift-score konfigurace, ADR exception scoping.
47. **ExecutionPlanControlScopeContractTests.kt** | 107 | kotlin.test | 4 | JSON serializace `ControlRequirementScope` — přesně jen vlastní pole (žádné field-leakage), schema AST 2.2/Plan 2.4 vynucuje strict scope.
48. **ExplicitMergeMaterializationRegressionTests.kt** | 130 | kotlin.test | 6 | Reálně parsovaný `merge()` prochází planning evidence; poškozené ordering evidence vždy detekováno (5 mutantů).
49. **ExplicitMergeSemanticsTests.kt** | 522 | kotlin.test | 11 | Nejdelší soubor dávky. Explicitní merge: cesty/edge, canonical graph digest neměnný na pořadí argumentů, merge module-name kolize, continuity z merge uzlu (ne z náhodné větve), digest-level mutace odhalena.
50. **FlowConformanceBridgeJUnitTest.kt** | 28 | kotlin.test | 4 | "Bridge" — každá `@Test` volá `H.reset()/report()` nad skupinou scénářů (intent/target/beta/rc4). Skutečný počet ověřovaných tvrzení je mnohonásobně vyšší, ale JUnit report ukáže jen 4 testy.
51. **FlowSensitiveAvailabilityTests.kt** | 379 | kotlin.test | 12 | Velmi kvalitní datový-flow analyzátor: MAYBE_DEFINED/AMBIGUOUS_PRODUCERS napříč if/parallel/for/match/try, exists-guard refinement, error handler neruší normální output.
52. **FlowSpecJUnitTest.kt** | 37 | kotlin.test | 1 | Jediná `@Test` metoda spouští ~17 interních skupin scénářů (lexer/parser/planner/e2e/…) přes vlastní harness `H` — mimo rozsah dávky B.
53. **GitHubJobConditionAuthorityTests.kt** | 82 | kotlin.test | 4 | GitHub Actions job-condition výrazy (`!cancelled()`, approval-skip-path, error handler `failure()`).
54. **HumanApprovalChangeControlFalsificationTests.kt** | 247 | kotlin.test | 12 | EF-09: 9 faktů (GitHub review policy, Atlantis apply gate…) s přesnými REPRESENTABLE/MODEL_GAP výroky + 3 anti-tamper testy.
55. **IncidentRemediationFalsificationTests.kt** | 174 | kotlin.test | 9 | EF-04: StackStorm/Braintree — převážně REPRESENTABLE, 1 MODEL_GAP (failure-conditioned human escalation).
56. **IntegratedModuleExtractionLifecycleTests.kt** | 84 | kotlin.test | 8 | Governance lifecycle nad `Map<Any?,Any?>` (mimikuje syrový YAML) — hustě zabalené negativní mutace (predecessor merge, acceptance self-cert, distribution profile confusion).
57. **IntentLoweringDiagnosticHonestyTests.kt** | 235 | kotlin.test | 5 | Ekvivalence YAML/JSON/block syntaxe intentu, binding-metadata odděleně od semantic params, přesné error kódy + JSON path pro malformed shapes.
58. **IntentLoweringNoShellProjectionTests.kt** | 61 | kotlin.test | 2 | Build/test/package/verify se lowerují na `standard.execute`, nikdy na shell/kubernetes — cílová neutralita.
59. **IntentSourceContradictionAuthorityTests.kt** | 54 | kotlin.test | 3 | "no backup"/"backup unavailable" text se nesmí stát positive evidence.
60. **ModuleCapabilityAndConfigPropagationTests.kt** | 56 | kotlin.test | 1 | Jediný test — ArgoCD capability + secret propagace do tasku (víceúčelový, ale jen 1 metoda).
61. **ModuleSchemaTypeCompatibilityTests.kt** | 101 | kotlin.test | 2 | Flow i Intent stejně odmítají textovou hodnotu pro number-schema, přijímají native number.
62. **NativeImageBuildProjectionTests.kt** | 293 | kotlin.test | 10 | Docker build → Jenkins/GitHub Actions/Tekton native syntax; path traversal, boolean literal, neznámá interpolace vždy fail-closed napříč všemi 3 targety.
63. **OperationalAdequacyCompletionTests.kt** | 310 | kotlin.test | 6 | Governance C1.0 completion — architecture backlog drift, release state drift.
64. **OperationalDomainAdequacyRoadmapLifecycleAuthorityTests.kt** | 135 | kotlin.test | 5 | Governance C1.0 lifecycle (activation/implementation/completion boundary musí být tři odlišné evidence).
65. **OperationalRecoveryEffectSemanticsTests.kt** | 297 | kotlin.test | 10 | Backup/restore efekty (DP01/DP02) — recovery endpoint typy, retention pouze na capture, materializace odmítá zfalšovaný recovery target.
66. **PackageLayeringIntegrityTests.kt** | 125 | kotlin.test | 6 | `standard`/`artifacts` balíčky nesmí importovat `scenarios`/`conformance`; produkční `SemanticCorePackageBoundary` inventář musí odpovídat zdrojovému textu (regex nad vlastním souborem).
67. **PlannerCapabilityConstraintGateTests.kt** | 96 | kotlin.test | 5 | Gate blokuje projekci před renderováním (Tekton manual approval), strict-mode mění DEGRADED na BLOCKED.
68. **PlanningActionContractFailClosedTests.kt** | 53 | kotlin.test | 2 | Neznámý modul.akce vždy odmítnut (execution i diagnostic autorizace).
69. **ProjectionAuthorizationTestSupport.kt** | 221 | — | 0 | Sdílený test-fixture helper (rekonstrukce `WorkflowFailurePolicy`, `testMaterializationRequest` atd.) — žádné `@Test`.
70. **SafetyBoundaryHardeningTests.kt** | 220 | kotlin.test | 8 | Bezpečnostní boundary validator: neznámý action-contract, sensitive env mutation vyžaduje approval, destructive-only-if stále vyžaduje approval, rollback mimo error handler blokován.
71. **SecretRotationBaselineTests.kt** | 96 | kotlin.test | 6 | EF-06 baseline (analog #26/#32).
72. **SemanticCoreSerializationBoundaryTests.kt** | 29 | kotlin.test | 1 | Sémantické core balíčky nesmí obsahovat Jackson/YAML importy — 1 test, celoplošný sken.
73. **SemanticEquivalenceAuthorityTests.kt** | 274 | kotlin.test | 11 | Silný test: konkrétní implementation profily proti commitnutým snapshotům, state lifetime jako součást observable meaning, "positive polarity"×"negative polarity" pro každý observation kind.
74. **SilentAuthoredValueCoercionTests.kt** | 146 | kotlin.test | 6 | Parser `failFast` boolean nesmí tiše koercovat "yes/no/1/0" — přesná pozice chyby (line/column); strict YAML odmítá cross-type scalar coercion.
75. **SourceDeclarationConformanceTests.kt** | 19 | kotlin.test | 1 | Tenký wrapper nad `SourceDeclarationConformanceChecks().checks()` — jen ověřuje, že produkční self-report "passed".
76. **StandardModelRecentPackageChecksTests.kt** | 43 | kotlin.test | 2 | Roadmap-governance checks jsou v inventáři, staré pseudo-check ID nejsou modelovány — silně vázáno na konkrétní verzovací ID (0.9.7.x).
77. **StateLifetimeRelationIdentityTests.kt** | 30 | kotlin.test | 1 | Jediný test — konfliktní state lifetime na stejném kanálu musí selhat v konstruktoru.
78. **TargetCapabilityMatrixTests.kt** | 47 | kotlin.test | 3 | Capability matrix nad reálným target registry + 2 negativní testy (chybějící target, prázdný popis).
79. **TargetManifestContractValidatorTests.kt** | 129 | kotlin.test | 3 | Kontrakt manifestu: 5 chybových kódů v jednom "malformed manifest" testu (blank target, chybějící metadata, duplicate step id…).
80. **TargetMaturityCompositionTests.kt** | 37 | kotlin.test | 2 | **Slabé**: test 1 pouze `source.contains("AdapterTargetMaturityPublisher")` — grep nad zdrojovým textem místo spuštění CLI.
81. **TargetNegotiationReportAnalyzerTests.kt** | 116 | kotlin.test | 4 | Negotiation report status (DEGRADED/BLOCKED) pro nedostatečná capability/approval/runtime-required scénáře.
82. **TargetProjectionTestBoundaryTests.kt** | 72 | kotlin.test | 2 | Architektonický fitness test: testy nesmí importovat konkrétní generátory mimo `targets.builtin`; žádné statické volání `TargetManifestGenerationPipeline.generate`.
83. **TargetRendererContractValidatorTests.kt** | 148 | kotlin.test | 5 | Renderer kontrakt: target mismatch, neznámá job-dependency, chybějící materialization reason, nested children v action stepu.
84. **TargetSelectionAuthorityTests.kt** | 173 | kotlin.test | 9 | CLI target selection provenance, uzavřená sada originů, reflection-check na absenci "raw target string" parametru v API, sealed-class arity=1.
85. **TargetStructuralProjectionHonestyTests.kt** | 325 | kotlin.test | 8 | Structural projection (condition/error-boundary/parallel/loop/match/retry) — Jenkins má jen 2 implementované struktury, forged structural claim bez behavior evidence odmítnut.
86. **TopologyEvidenceReferenceMigrationTests.kt** | 64 | kotlin.test | 2 | SHA-256 pinning dokumentačního přejmenování (analog #40).
87. **TopologyMatrixCompletionTests.kt** | 178 | kotlin.test | 3 | Governance C0.2→C0.3 přechod, reused implementation boundary odmítnut.
88. **TopologyTestFixtures.kt** | 41 | — | 0 | `testTargetCapability(...)` factory — sdílený fixture, žádné `@Test`.
89. **UniversalDependencyContinuityContractTests.kt** | 454 | kotlin.test | 15 | Velmi silný soubor: ordering≠continuity, value/workspace/state continuity evidence, durable vs. workflow-local state lifetime, ambiguous provider zůstává BLOCKED (ne "vybrán podle pořadí").
90. **UniversalModelCompletionTests.kt** | 347 | kotlin.test | 12 | Manifest generation reconciliace, neutral deploy nevymýšlí Kubernetes, interval trigger end-to-end YAML→AST→Plan, verze kontraktů, **CLI boundary testována přes `assertContains` na přesný zdrojový text vč. odsazení** (viz nález č. 3).
91. **VersionConsistencyTests.kt** | 115 | kotlin.test | 3 | Masivně hardcoded řetězcová shoda desítek verzí napříč `build.gradle.kts`, `REPORT.md`, `CHANGELOG.md`, `.flow-agent/*.yaml`, `schemas/*.json`, `docs/*.md` — "god test" (viz nález č. 1).
92. **WorkflowFailureProjectionEvidenceTests.kt** | 166 | kotlin.test | 8 | Workflow failure policy metadata nesmí být zfalšováno na úrovni manifestu (dispozice, error binding, handler membership, handler-job guarding).
93. **WorkflowSemanticsIntegrationTests.kt** | 285 | kotlin.test | 28 | Nejvyšší hustota testů/soubor v dávce. Integrovaná uzávěrka (frontend/mutation/target/public-compatibility matrix + finding closure) přes 5 dílčích kontrol; JSON-schema "bounded evaluator" odmítá nepodporovaný klíč.
94. **WorkflowSemanticsRecoveryReceiptTests.kt** | 206 | kotlin.test | 10 | Lifecycle "receipt" validace (exactHead/mergeCandidate/jobId nesmí být zaměněny), YAML int-width neutralita vs. string-typed rozdílnost.

---

## 3. Kvalitativní hodnocení

### Silné stránky
- **Mutační/adversariální styl je systematický a rozšířený.** Desítky souborů (Adapter*Authority*, Semantic*, Universal*, WorkflowSemantics*) berou platný dokument/graf/manifest, poškodí přesně jedno pole a ověří konkrétní chybový kód. Toto je kvalitativně nad úrovní běžné jednotkové sady — funguje jako ruční "mutation testing" nad vlastní validační logikou.
- **Explicitní "fail-closed" filozofie** je testována důsledně (neznámý action contract, neznámá interpolace, ambiguous provider, self-referential evidence) — nikde jsem nenašel test, který by akceptoval "tichý" fallback tam, kde by měl selhat.
- **Falsification rodina (EF-02…EF-09)** je neobvykle poctivá: aktivně vyhledává mezery modelu vůči reálným repozitářům (Airflow, Dagster, Velero, Restic, GitHub, Atlantis, StackStorm) a explicitně je hlásí jako `MODEL_GAP` namísto skrývání. To je vzácný a hodnotný testovací vzor.
- **End-to-end pokrytí Intent→AST→Plan→Manifest→render** je reálné, ne mockované — testy typu `AdapterContinuityProviderBehaviorTests` fyzicky replayují upload/download artefaktů a porovnávají bajty.

### Slabiny a rizika
- **"Source-grep" testování chování.** Několik testů neověřuje chování spuštěním kódu, ale hledáním podřetězců ve zdrojovém `.kt` textu:
  - `TargetMaturityCompositionTests.targetsCommandPublishesCurrentMaturityReport` — `source.contains("AdapterTargetMaturityPublisher")` místo spuštění CLI a kontroly výstupu.
  - `UniversalModelCompletionTests.cliUsesTheCanonicalManifestGenerationBoundary` (řádky 271–306) — ~15 `assertContains`/`assertFalse` na **přesné** fragmenty zdrojového kódu vč. odsazení (`"fun evaluate(\n        compilation: CompilationUnit"`). Přeformátování (ktlint, změna odsazení) test rozbije bez jakékoli změny chování.
  - `FlowSensitiveAvailabilityTests.compilerSharesOneAvailabilityResultAndPlannerHasNoGlobalLastWriterMap` — totéž nad `FlowCompilationService.kt`/`FlowPlanner.kt`.
  - Částečně omluvitelné u `PackageLayeringIntegrityTests`/`CodeNamingTests`/`CiCdBias*` — tam je grep **záměrnou** architektonickou fitness-funkcí (kontrola importů/identifikátorů), ne náhražkou funkčního testu.
- **"God testy" s desítkami nesouvisejících asercí v jedné metodě.** `VersionConsistencyTests.publicContractVersionsMatchTheirDocumentedMigrations` (řádky 30–88) dělá ~30 nezávislých `assertFileContains` napříč 15+ soubory v jedné `@Test` metodě. JUnit se zastaví na první selhavší asserci — zbylých ~29 kontrol se nespustí, takže po jedné opravě vývojář uvidí až další selhání při dalším běhu. Snižuje to diagnostickou hodnotu a prodlužuje iterační cyklus.
- **Tenké pokrytí u několika "jednorázových" souborů**: `CanonicalNotesDependencyParityTests` (1 test, jen accept-cesta, žádný cyklus/neplatný balíček), `SourceDeclarationConformanceTests` (1 test, jen "checks().all{passed}" — deleguje veškerou verifikaci na produkční kód, který sám sebe hodnotí), `ModuleCapabilityAndConfigPropagationTests` (1 test na 56 řádků, žádné negativní scénáře), `StateLifetimeRelationIdentityTests` (1 test).
- **Silná vazba na verzovaná/časová milestone ID uvnitř testů, které samy kritizují milestone-jména v produkčním kódu** (`CodeNamingTests` zakazuje "AR-02" apod. v produkčním kódu, ale governance testy typu `AdapterRoadmapLifecycleAuthorityTests`, `OperationalAdequacyCompletionTests` jsou samy plné A0.x/AR0.x/C1.0 řetězců a magických run-ID čísel jako `30342473373`) — nejde o rozpor (produkční vs. testovací kód mají jiná pravidla), ale zvyšuje to křehkost a kognitivní zátěž governance testů.
- **Governance testy nejsou hermetické vůči obsahu repozitáře.** Desítky testů (`*BaselineTests`, `*RoadmapLifecycleAuthorityTests.analyze()`, `VersionConsistencyTests`, `ArchitectureReadinessBaselineAnalyzerTests`) čtou `File(".")` — tedy živý stav repozitáře — a porovnávají jej s přesnými literály. Každá nesouvisející úprava dokumentace/YAML může rozbít testy mimo funkční doménu editace.
- **Duplicitní struktura napříč "RoadmapLifecycleAuthority" rodinou.** `AdapterRoadmapLifecycleAuthorityTests`, `AdapterTopologyRoadmapLifecycleAuthorityTests`, `AdapterTriggerRoadmapLifecycleAuthorityTests`, `AdapterExecutableContinuityRoadmapLifecycleAuthorityTests`, `AdapterBindingRoadmapLifecycleAuthorityTests`, `OperationalDomainAdequacyRoadmapLifecycleAuthorityTests` sdílejí téměř identickou kostru (`implementingInput()`/`completedInput()`/`passingEvidence()` s SHA-like fixture řetězci `"1111…"`/`"2222…"`). Jde o velmi vysokou strukturální duplicitu (6+ souborů, stovky řádků), která by šla sjednotit přes parametrizovaný test nebo sdílenou fixture-builder abstrakci — dnes to dělá každý soubor znovu.
- **Tautologické testy nejsou časté**, ale nalezl jsem hraniční případ: `SourceDeclarationConformanceTests` a částečně `TargetMaturityCompositionTests` v podstatě jen "zopakují", co produkční kód sám tvrdí, bez nezávislého ověření.
- **"Mega-testy" H-harness** (`FlowSpecJUnitTest`, `FlowConformanceBridgeJUnitTest`) skrývají skutečný počet testovaných tvrzení za jednu JUnit metodu — CI report ukáže "1 test failed", ne které z desítek/stovek vnitřních scénářů selhalo (harness `H` sám vypisuje `H.fails` do assertion message, což selhání do jisté míry zviditelní, ale strukturálně to obchází standardní JUnit test-reporting/granularitu, izolaci a paralelizaci).

### Duplicity
- Vzorec "mutuj jedno pole → ověř konkrétní error kód" se opakuje doslovně ve stovkách testů (to je záměrné a žádoucí), ale **strukturální kostra governance-lifecycle testů** (bod výše) je zbytečně kopírovaná, ne parametrizovaná.
- `ControlEvidenceReferenceMigrationTests` a `TopologyEvidenceReferenceMigrationTests` jsou prakticky identické šablony (SHA-256 pinning přesunu referencí) lišící se jen konstantami — kandidát na sdílenou abstrakci.

### Chybějící edge-case pokrytí
- `CanonicalNotesDependencyParityTests`: netestuje cyklickou závislost ani neexistující `packageId`.
- `ModuleSchemaTypeCompatibilityTests`: pouze `NUMBER` schema typ; žádné testy pro `BOOLEAN`/`LIST`/`OBJECT` type-mismatch, přestože produkční kód zjevně tyto typy podporuje (viz `SchemaType` import).
- `TargetCapabilityMatrixTests`: chybí test na duplicitní kapabilitu nebo neplatný `SupportLevel`.

---

## 4. Zjištěné chyby v testovacím kódu

Nejde o chyby v produkčním chování (ty jsem nehledal — úkolem byla analýza testů), ale o defekty/rizika **v samotném testovacím kódu**:

1. **`UniversalModelCompletionTests.kt:290`** — Závažnost: **střední**. `assertContains(authority, "fun evaluate(\n        compilation: CompilationUnit")` — test je vázán na přesné odsazení (8 mezer) a zalomení řádku zdrojového souboru `CliTargetEvidenceAuthority.kt`. Jakákoliv reformatace kódu (ktlint/IDE auto-format) test rozbije bez změny chování. Doporučení: nahradit AST/regex kontrolou signatury bez závislosti na whitespace, nebo přesunout do skutečného kompilačního/reflexního testu.

2. **`UniversalModelCompletionTests.kt:271-306`** — Závažnost: **nízká/střední**. Jedna testovací metoda `cliUsesTheCanonicalManifestGenerationBoundary` dělá ~20 nesouvisejících asercí nad 4 různými soubory (classloader-resource kontroly i source-text kontroly) — směšuje architektonické a textové ověření v jednom testu; při prvním selhání se zbytek nespustí.

3. **`VersionConsistencyTests.kt:30-88`** — Závažnost: **nízká** (návrhová vada, ne funkční chyba). Metoda `publicContractVersionsMatchTheirDocumentedMigrations` obsahuje ~30 nezávislých `assertFileContains` volání napříč patnácti soubory v jediném testu — "god test" bez izolace selhání. Doporučení: rozdělit na samostatné parametrizované testy (per-artefact-kontrakt) pro rychlejší diagnostiku.

4. **`TargetMaturityCompositionTests.kt:14-17`** — Závažnost: **nízká**. Test `targetsCommandPublishesCurrentMaturityReport` ověřuje chování CLI příkazu čistě přes `source.contains(...)` nad zdrojovým textem, aniž by CLI skutečně spustil. Nedokáže odhalit regresi, kdy je string přítomen, ale logika, která jej používá, je nedosažitelná/rozbitá.

5. **Rodina `*RoadmapLifecycleAuthorityTests`** (min. 6 souborů) — Závažnost: **nízká** (údržbová zátěž, ne bug). Identická strukturální kostra (`implementingInput()`, `completedInput()`, `passingEvidence()`) je kopírována soubor od souboru s mírně odlišnými poli. Riziko: oprava společné logiky (např. formát `exactHead`) vyžaduje synchronní úpravu 6+ souborů; chybějící parametrizace zvyšuje pravděpodobnost, že při budoucí úpravě zůstane jeden soubor pozadu a bude falešně-pozitivně/negativně procházet.

6. **`CanonicalNotesDependencyParityTests.kt`** — Závažnost: **nízká**. Jediný test v souboru neověřuje žádný negativní scénář (cyklus, chybějící balíček) — pokud produkční loader cyklus nedetekuje, test to neodhalí.

7. **Governance/baseline testy obecně (`*BaselineTests`, `AuthorityConsolidation*`, `OperationalAdequacyCompletionTests` aj.)** — Závažnost: **informativní**. Tyto testy nejsou vůči repozitáři hermetické — čtou `File(".")`. Není to "chyba" v klasickém smyslu, ale znamená to, že CI bude po jakékoliv nesouvisející úpravě `docs/`, `.flow-agent/*.yaml` nebo `CHANGELOG.md` potenciálně padat na těchto testech, což může svádět vývojáře k tomu texty "opravovat" mechanicky bez pochopení governance-invariantu.

Nenašel jsem žádnou zjevně **logicky nesprávnou** asserci (např. očekávanou hodnotu neodpovídající deklarovanému chování) ani skutečně tautologický test (`assertTrue(true)` apod.) v přečtených 94 souborech — což samo o sobě svědčí o vysoké redakční disciplíně autorů této sady, byť s výše uvedenými strukturálními výhradami.

---

## 5. Architektonické pozorování — poměr skutečné funkčnosti vs. procesní governance

Rozdělení 94 souborů podle toho, **co skutečně ověřují**:

| Skupina | Soubory (počet) | Podíl |
|---|---:|---:|
| A. Skutečná funkčnost kompilátoru/plannerů/adapterů/renderů (end-to-end chování) | ~46 | 49 % |
| B. Procesní/roadmap governance (lifecycle stavové automaty nad `.yaml`) | ~16 | 17 % |
| C. Falsification vůči externímu korpusu (doménová validita modelu) | ~9 | 10 % |
| D. Architektonické fitness funkce (naming, layering, CI/CD bias, serialization boundary, source-grep) | ~10 | 11 % |
| E. Verzovací/schema/dokumentační konzistence | ~10 | 11 % |
| F. Čisté fixture/support (bez `@Test`) | 2 | 2 % |
| G. Mega-test bridge (H-harness) | 2 | 2 % |

**Klíčové architektonické zjištění:** přibližně **41 % souborů (skupiny B+D+E)** v této dávce netestuje běhové chování produktu (kompilátor, planner, adaptery, renderery), ale **integritu vlastního repozitáře** — jeho roadmapu, dokumentaci, pojmenování a verzovací řetězce. To je u FlowAi záměrný a explicitně komunikovaný designový princip ("evidence-driven", "fail-closed", žádné neověřené tvrzení o stavu projektu) a koresponduje s celkovou filozofií kódu (viz i produkční `Authority`-vzory). Z pohledu testovací strategie to ale znamená:

- **Pozitivum:** projekt má neobvykle silnou obranu proti "manuálnímu" nebo nepravdivému tvrzení o stavu dokončení práce (typicky časovaný problém u AI-asistovaného vývoje) — roadmap nemůže "lhát" o tom, co je hotovo, bez rozbití testu.
- **Riziko:** poměr signálu k šumu pro nového vývojáře nebo CI-diagnostiku je nižší, než počet "94 testovacích souborů" naznačuje — necelá polovina skutečně brání regresi *produktové* funkčnosti. Zbytek brání regresi *procesní poctivosti projektu*, což je jiná (byť legitimní) kategorie rizika.
- Governance-testy navíc silně korelují s objemem kódu (`OperationalAdequacyCompletionTests` 310 řádků, `AuthorityConsolidationCompletionTests` 241 řádků) především kvůli syntetickým YAML fixture builderům, ne kvůli hustotě ověřovaných tvrzení — z 94 souborů dávky B čistě governance skupina B váží ~2100 řádků (cca 14–15 % celkového objemu), ale přináší jen zlomek pokrytí produktové logiky.
- **Skutečné jádro jazyka** (skupina A) je kvalitativně nejsilnější a nejlépe navržená část sady — husté mutační testování, žádné mocky, end-to-end přes reálný kompilátor. Kdyby FlowAi měl redukovat testovací dluh, governance/fitness vrstva (B+D) je nejlogičtějším kandidátem na konsolidaci (sdílené parametrizované fixture), zatímco vrstva A by měla zůstat referenčním vzorem.

---

## 6. Statistická tabulka

| Soubor | Řádky | Počet `@Test` |
|---|---:|---:|
| AbstractExecutionTopologyModelTests.kt | 188 | 7 |
| AdapterArtifactRenderingAuthorityTests.kt | 127 | 4 |
| AdapterArtifactRenderingCliTests.kt | 103 | 2 |
| AdapterArtifactRenderingEvidenceIntegrityTests.kt | 89 | 4 |
| AdapterBindingRoadmapLifecycleAuthorityTests.kt | 138 | 7 |
| AdapterCapabilityBindingMigrationAuthorityTests.kt | 60 | 3 |
| AdapterContinuityProviderBehaviorTests.kt | 284 | 6 |
| AdapterControlCliMaterializationTests.kt | 69 | 1 |
| AdapterControlMaterializationAuthorityTests.kt | 442 | 21 |
| AdapterControlProviderBehaviorTests.kt | 236 | 4 |
| AdapterControlTypedEvidenceIntegrityTests.kt | 87 | 4 |
| AdapterExecutableContinuityRoadmapLifecycleAuthorityTests.kt | 146 | 6 |
| AdapterProfileEvidenceAuthorityTests.kt | 111 | 7 |
| AdapterRoadmapLifecycleAuthorityTests.kt | 157 | 9 |
| AdapterTopologyEvidenceAuthorityTests.kt | 191 | 9 |
| AdapterTopologyRoadmapLifecycleAuthorityTests.kt | 161 | 9 |
| AdapterTriggerMaterializationAuthorityTests.kt | 216 | 7 |
| AdapterTriggerProviderBehaviorTests.kt | 169 | 6 |
| AdapterTriggerRoadmapLifecycleAuthorityTests.kt | 141 | 7 |
| ArchitectureReadinessBaselineAnalyzerTests.kt | 46 | 2 |
| ArtifactDerivedIntentLoweringEvidenceTests.kt | 184 | 7 |
| AuthoredDependencyGraphPreservationTests.kt | 192 | 6 |
| AuthorityConsolidationCompletionTests.kt | 241 | 5 |
| AuthorityConsolidationEvidenceTests.kt | 231 | 4 |
| AuthorityResponsibilityCatalogCritiqueRegressionTests.kt | 152 | 5 |
| BackupRestoreBaselineTests.kt | 120 | 6 |
| BackupRestoreFalsificationTests.kt | 117 | 9 |
| CanonicalControlScopeIntegrityTests.kt | 338 | 14 |
| CanonicalImageTechnologyNeutralityTests.kt | 118 | 4 |
| CanonicalModuleTargetOwnershipTests.kt | 89 | 2 |
| CanonicalNotesDependencyParityTests.kt | 27 | 1 |
| CertificateLifecycleBaselineTests.kt | 128 | 6 |
| CiCdBiasBoundaryPrecisionTests.kt | 186 | 6 |
| CiCdBiasBuildConfigurationTests.kt | 50 | 2 |
| ClosureBlockingSafetyIntegrityTests.kt | 179 | 8 |
| ClosureBlockingTopologyIntegrityTests.kt | 124 | 5 |
| CodeNamingTests.kt | 114 | 6 |
| CompatibilityReconciliationMonotonicityTests.kt | 130 | 3 |
| CompilerModuleCompositionTests.kt | 41 | 3 |
| ControlEvidenceReferenceMigrationTests.kt | 70 | 2 |
| CoreContractCheckTests.kt | 15 | 1 |
| CritiqueFailClosedRegressionTests.kt | 158 | 8 |
| DataOrchestrationBaselineTests.kt | 108 | 6 |
| DataOrchestrationFalsificationTests.kt | 141 | 11 |
| DatabaseMigrationRecoveryFalsificationTests.kt | 149 | 8 |
| DerivedModelGovernanceIntegrityTests.kt | 263 | 9 |
| ExecutionPlanControlScopeContractTests.kt | 107 | 4 |
| ExplicitMergeMaterializationRegressionTests.kt | 130 | 6 |
| ExplicitMergeSemanticsTests.kt | 522 | 11 |
| FlowConformanceBridgeJUnitTest.kt | 28 | 4 |
| FlowSensitiveAvailabilityTests.kt | 379 | 12 |
| FlowSpecJUnitTest.kt | 37 | 1 |
| GitHubJobConditionAuthorityTests.kt | 82 | 4 |
| HumanApprovalChangeControlFalsificationTests.kt | 247 | 12 |
| IncidentRemediationFalsificationTests.kt | 174 | 9 |
| IntegratedModuleExtractionLifecycleTests.kt | 84 | 8 |
| IntentLoweringDiagnosticHonestyTests.kt | 235 | 5 |
| IntentLoweringNoShellProjectionTests.kt | 61 | 2 |
| IntentSourceContradictionAuthorityTests.kt | 54 | 3 |
| ModuleCapabilityAndConfigPropagationTests.kt | 56 | 1 |
| ModuleSchemaTypeCompatibilityTests.kt | 101 | 2 |
| NativeImageBuildProjectionTests.kt | 293 | 10 |
| OperationalAdequacyCompletionTests.kt | 310 | 6 |
| OperationalDomainAdequacyRoadmapLifecycleAuthorityTests.kt | 135 | 5 |
| OperationalRecoveryEffectSemanticsTests.kt | 297 | 10 |
| PackageLayeringIntegrityTests.kt | 125 | 6 |
| PlannerCapabilityConstraintGateTests.kt | 96 | 5 |
| PlanningActionContractFailClosedTests.kt | 53 | 2 |
| ProjectionAuthorizationTestSupport.kt | 221 | 0 |
| SafetyBoundaryHardeningTests.kt | 220 | 8 |
| SecretRotationBaselineTests.kt | 96 | 6 |
| SemanticCoreSerializationBoundaryTests.kt | 29 | 1 |
| SemanticEquivalenceAuthorityTests.kt | 274 | 11 |
| SilentAuthoredValueCoercionTests.kt | 146 | 6 |
| SourceDeclarationConformanceTests.kt | 19 | 1 |
| StandardModelRecentPackageChecksTests.kt | 43 | 2 |
| StateLifetimeRelationIdentityTests.kt | 30 | 1 |
| TargetCapabilityMatrixTests.kt | 47 | 3 |
| TargetManifestContractValidatorTests.kt | 129 | 3 |
| TargetMaturityCompositionTests.kt | 37 | 2 |
| TargetNegotiationReportAnalyzerTests.kt | 116 | 4 |
| TargetProjectionTestBoundaryTests.kt | 72 | 2 |
| TargetRendererContractValidatorTests.kt | 148 | 5 |
| TargetSelectionAuthorityTests.kt | 173 | 9 |
| TargetStructuralProjectionHonestyTests.kt | 325 | 8 |
| TopologyEvidenceReferenceMigrationTests.kt | 64 | 2 |
| TopologyMatrixCompletionTests.kt | 178 | 3 |
| TopologyTestFixtures.kt | 41 | 0 |
| UniversalDependencyContinuityContractTests.kt | 454 | 15 |
| UniversalModelCompletionTests.kt | 347 | 12 |
| VersionConsistencyTests.kt | 115 | 3 |
| WorkflowFailureProjectionEvidenceTests.kt | 166 | 8 |
| WorkflowSemanticsIntegrationTests.kt | 285 | 28 |
| WorkflowSemanticsRecoveryReceiptTests.kt | 206 | 10 |
| **CELKEM (94 souborů)** | **14 413** | **553** |

---

## Shrnutí

Analyzoval jsem všech 94 přidělených testovacích souborů FlowAi (dávka B), celkem 14 413 řádků a 553 metod `@Test`.

**Klíčová zjištění:**

1. **Kvalita jádra je vysoká.** Testy kompilátoru/plannerů (`FlowSensitiveAvailabilityTests`, `ExplicitMergeSemanticsTests`, `UniversalDependencyContinuityContractTests`, `CanonicalControlScopeIntegrityTests`) používají systematický adversariální/mutační styl — berou platný artefakt, poškodí jedno pole, ověří přesný error kód. Nenašel jsem tautologické testy ani logicky chybné asserce.

2. **~41 % souborů testuje procesní governance repozitáře, ne produkt.** Rodina "RoadmapLifecycleAuthority" (16 souborů) ověřuje konzistenci `.yaml` stavu roadmapy (A0.x/AR0.x/C1.0), ne chování kompilátoru. Je to záměrný "evidence-driven" princip projektu, ale snižuje efektivní pokrytí produktové logiky vzhledem k počtu souborů.

3. **Neobvykle hodnotná "falsification" rodina (EF-02…EF-09, 9 souborů)** poctivě testuje sémantický model FlowAi proti reálným repozitářům (Airflow, Velero, GitHub, Atlantis) a explicitně přiznává `MODEL_GAP`.

4. **Nalezené defekty v testovacím kódu** (žádný kritický): (a) `UniversalModelCompletionTests.kt:290` — test vázaný na přesné odsazení zdrojového kódu, rozbije se refaktoringem; (b) `VersionConsistencyTests.kt:30-88` — "god test" s ~30 nezávislými asercemi bez izolace selhání; (c) `TargetMaturityCompositionTests.kt:14-17` — chování CLI ověřováno jen grepem zdroje; (d) 6+ souborů rodiny RoadmapLifecycleAuthority má téměř identickou nezparametrizovanou kostru — údržbové riziko.
