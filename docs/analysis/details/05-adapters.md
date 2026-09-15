# Analýza vrstvy `org.flowlang.adapters` (FlowAi / Horistum)

Rozsah: `/home/user/FlowAi/src/main/kotlin/org/flowlang/adapters/**` — 40 souborů, 8 332 řádků.
Všech 40 souborů přečteno celých. Křížově ověřeno proti `/home/user/FlowAi/adapters/**/*.yaml` (18 evidence
souborů, 2 816 řádků), proti `targets/builtin-targets.yaml`, proti `generators/manifest/*` a `targets/builtin/*`,
a proti `.flow-agent/roadmap*.yaml`.

---

## 1. Přehled vrstvy

`adapters/*` **není** duplicitní vůči `targets/builtin/*` ani `generators/manifest/*` — je to doplňková,
třetí rovina nad nimi, ale s významným vedlejším efektem (viz sekce 4).

Tři vrstvy mají jasně odlišné role:

| Vrstva | Odpovědnost | Analogie |
|---|---|---|
| `generators/manifest/*` | Target-neutrální **kontrakt**: `TargetManifest`, `TargetProjectionProvider` interface, `TargetRenderPolicy`, `TargetCompatibilityReadinessAnalyzer`. Definuje „co to znamená" vygenerovat manifest a renderovat artefakt. | mechanismus/rozhraní |
| `targets/builtin/*` | Konkrétní **implementace** kontraktu pro Jenkins/GitHub Actions/Tekton (`JenkinsManifestRenderer`, `GitHubActionsManifestGenerator`, …). Skutečně vyrábí Jenkinsfile/YAML. | implementace/vendor renderery |
| `adapters/*` | **Certifikační/auditní hranice**. Neobsahuje žádný renderer. Načítá ručně psané YAML „evidence" dokumenty (`adapters/continuity/…`, `adapters/controls/…`, `adapters/triggers/…`, …), ověřuje že každé tvrzení o podpoře (SUPPORTED/UNSUPPORTED/UNKNOWN) je kryto (a) composed providerem, (b) `src/main` implementací, (c) nezávislým `src/test` chováním, a teprve pak dovolí `TargetManifest` přejít do `EXECUTABLE` režimu; jinak jej degraduje na `REVIEW_ONLY` nebo zablokuje (`AdapterDiagnosticReconciliation.blocked`). | audit/gate |

Adaptery tedy **nepřekrývají** vykreslování (to zůstává v `targets/builtin`), ale zavádí druhý, nezávisle
autorovaný zdroj pravdy vedle `targets/builtin-targets.yaml`: každá topologie/kontrola/kontinuita/trigger
vlastnost musí být deklarována **dvakrát** — jednou v target registry (`targets/builtin-targets.yaml`),
podruhé v `adapters/<domain>/builtin-*.yaml` — a `Adapter*EvidenceAuthority` třídy kontrolují, že se obě
shodují (`ADAPTER_TOPOLOGY_REGISTRY_STATUS_MISMATCH` apod.). To je záměrný „dva nezávislé zdroje pravdy"
governance vzor odpovídající cíli projektu („conformance-tested public standard surface" z README), nikoli
nedopatření — ale je to reálná údržbová daň, viz sekce 4.

Kromě toho `adapters/*` obsahuje **druhou, zcela nesouvisející** podvrstvu: 8 tříd `*RoadmapLifecycleAuthority`
(1 476 řádků, ~17.7 % balíčku), které nemají nic společného s Intent→AST→Plan→Manifest pipeline — ověřují,
že stav `.flow-agent/roadmap-adapters.yaml` / `.flow-agent/roadmap.yaml` / `.flow-agent/release-state.yaml`
odpovídá dokončení konkrétní roadmap položky (A0.1…A0.7, A1.0). Toto je projektové/procesní governance
zapečené jako produkční Kotlin kód — viz sekce 4.

`adapters/yaml/*` (`IntentYamlLoader`, `TargetRegistryYamlLoader`) jsou tenké delegující fasády nad
`org.flowlang.intent.*` / `org.flowlang.targets.*` — ověřeno, že jsou aktivně používány (~20, resp. ~40
volajících souborů), takže nejde o mrtvý kód, ale o záměrnou izolaci I/O od core modelu (dle komentáře v
souboru).

---

## 2. Inventář souborů a tříd

### `adapters/` (1 soubor, 44 řádků)
- **`AdapterDiagnosticReconciliation.kt`** (44) — `object AdapterDiagnosticReconciliation`: jediná sdílená
  funkce `blocked(manifest, metadata, issues)`, která mechanicky promítne blokující `CompatibilityIssue`
  listy do `TargetManifest` (status `UNSUPPORTED`, `executable=false`). Sdíleno všemi doménovými autoritami
  (continuity/control/trigger).

### `adapters/binding/` (2 soubory, 486 řádků)
- **`AdapterCapabilityBindingAuthority.kt`** (384) — `AdapterCapabilityBindingClass`, `AdapterCapabilityBindingRecord`,
  `AdapterCapabilityBindingDocument`, `BindingKey`, `AdapterCapabilityBindingFinding/Report`,
  `object AdapterCapabilityBindingLoader` (načítá `adapters/bindings/builtin-capability-bindings-v1.1.yaml`
  i zmrazenou `…-v1.0.yaml`), `class AdapterCapabilityBindingAuthority.analyze()` (cross-checkuje binding
  evidenci proti skutečným `ModuleRegistry` implementačním nárokům), `class AdapterCapabilityBindingMigrationAuthority`
  — ověřuje, že v1.1 se od zmrazené v1.0 liší **pouze** v jedné povolené migraci (`dockerfile` z sémantického
  parametru na binding parametr u `docker.build`), včetně hardcoded SHA-256 (`C04_BINDING_SHA256`).
- **`AdapterBindingRoadmapLifecycleAuthority.kt`** (102) — roadmap-lifecycle pro A0.3 (viz vzor v sekci 4).

### `adapters/yaml/` (2 soubory, 33 řádků)
- **`IntentYamlLoader.kt`** (19) — fasáda nad `org.flowlang.intent.IntentYamlLoader`.
- **`TargetRegistryYamlLoader.kt`** (14) — fasáda nad `org.flowlang.targets.TargetRegistryYamlLoader`.

### `adapters/continuity/` (10 souborů, 1 419 řádků)
- **`AdapterContinuityContracts.kt`** (328) — `AdapterContinuityFamily/ClaimStatus`, `AdapterContinuitySemanticPartition/Claim`,
  `AdapterContinuityTargetRecord/EvidenceDocument`, `AdapterContinuityRequirement(Completeness)`,
  `AdapterContinuityEvidence(Status)`, `AdapterContinuityDecision`, `AdapterContinuityAssessment`,
  `UnresolvedAdapterContinuitySatisfactionException`, `object AdapterContinuitySemanticContract` (uzavřený
  katalog `data.value` / `artifact.shared-workspace` / `state.mutable.workflow` / `state.durable.workflow`),
  `object AdapterContinuityRequirementAuthority.derive(plan)` (odvozuje požadavky z `PlanDependencyRelation`),
  `object AdapterContinuityEvidenceLoader` (parser `adapters/continuity/builtin-continuity-satisfaction.yaml`).
- **`AdapterContinuityEvidenceIntegrityAuthority.kt`** (217) — `class …analyze()`: cross-check proti target
  registry, portfoliu, uzavřené sémantice, self-referential evidence apod.
- **`AdapterContinuityProjectionExecutionGate.kt`** (44) — `class` implementuje
  `TargetProjectionExecutionGate` + `TargetProjectionCapabilityResolver`; jediný produkční vstupní bod
  volaný z compileru před renderem.
- **`AdapterContinuityRoadmapLifecycleAuthority.kt`** (125) — roadmap-lifecycle pro A0.5.
- **`AdapterContinuitySatisfactionAuthority.kt`** (168) — `class …assess/requireMatched/reconcileDiagnostic`:
  hlavní runtime matching engine (plán vs. evidence vs. scoped support).
- **`AdapterContinuityScopedCapabilityResolver.kt`** (87) — `class` — úzce ohraničené „promotion" pravidlo:
  jen GitHub Actions `git.checkout`→`docker.build` workspace continuity se smí povýšit na SUPPORTED.
- **`AdapterContinuityScopedSupport.kt`** (67) — `data class AdapterContinuityScopedSupport` (silně validovaný
  init blok) + `object AdapterContinuityScopedSupportAuthority.matchingSupport()`.
- **`AdapterContinuityScopedSupportContracts.kt`** (111) — `AdapterContinuityScopedSupportFinding/Report`,
  `class AdapterContinuityScopedSupportIntegrityAuthority.analyze()`.
- **`AdapterExecutableContinuityRoadmapLifecycleAuthority.kt`** (243) — samostatná lifecycle autorita pro A1.0
  (mimo zamrzlou A0.x sekvenci, fáze IMPLEMENTING/PROMOTING/COMPLETED).
- **`BuiltInAdapterContinuityScopedSupport.kt`** (29) — `object` s jedinou deklarací
  `githubActionsCheckoutBuildWorkspace`.

### `adapters/contract/` (2 soubory, 216 řádků)
- **`AdapterCatalog.kt`** (43) — `interface AdapterCatalog<T>` + `class StaticAdapterCatalog<T>`: generický,
  target-neutrální katalog (žádná znalost Jenkins/GH/Tekton).
- **`TargetAdapterContract.kt`** (173) — `AdapterArtifactRole/InvariantSeverity/DiagnosticSeverity`,
  `TargetAdapterArtifact/Invariant`, `AdapterDiagnosticIssue/Report`, `TargetAdapterContractReport`,
  `class TargetAdapterContractAnalyzer.analyze()` — popisuje veřejný kontrakt (povolené vstupy/výstupy,
  MUST/SHOULD invarianty typu `ADAPTER_MUST_NOT_READ_INTENT`).

### `adapters/trigger/` (6 souborů, 1 541 řádků)
- **`AdapterTriggerContracts.kt`** (309) — `AdapterTriggerFamily/ClaimStatus/TimezoneMode/ExpressionMode`,
  `AdapterTriggerClaimConstraints/SemanticPartition/Claim`, `object AdapterTriggerSemanticContract`,
  `object AdapterTriggerRequirementAuthority.derive(plan)` + `isPortablePosixCron`/`isIanaTimezone`.
- **`AdapterTriggerEvidenceIntegrityAuthority.kt`** (407, největší integrity třída) — validuje family
  kompletnost, sémantické partition, `AdapterTriggerClaimConstraints` per-family pravidla (MANUAL/CRON/EVENT…).
- **`AdapterTriggerEvidenceLoader.kt`** (284) — **jediný loader v celém balíčku s odlišnou architekturou**:
  index (`builtin-trigger-materialization.yaml`) + adresář `adapters/triggers/targets/*.yaml`, s vlastní
  YAML `<<` merge-key expanzí šablon (`expandClaimTemplate`). Viz finding č. 1.
- **`AdapterTriggerMaterializationAuthority.kt`** (233) — runtime matching (`assess/requireMatched/reconcileDiagnostic`),
  včetně CRON/timezone/event constraint violation logiky.
- **`AdapterTriggerAuthorizedRenderingAuthority.kt`** (59) — dekorátor nad `AdapterArtifactRenderingAuthority`,
  vynucuje že BLOCKED trigger nikdy neprojde jako EXECUTABLE artefakt.
- **`AdapterTriggerRoadmapLifecycleAuthority.kt`** (249) — roadmap-lifecycle pro A0.7 (terminál A0 sekvence).

### `adapters/portfolio/` (4 soubory, 858 řádků)
- **`AdapterPortfolioAuthority.kt`** (318) — `AdapterPortfolioRole/SupportClass`, `AdapterPortfolioLimitation/Record/Document/Finding/Assessment/Report`,
  `object AdapterPortfolioLoader`, `class AdapterPortfolioAuthority.analyze/evaluate()` — hlavní „kdo je
  co" registr (SEMANTIC_REFERENCE vs TARGET_ADAPTER × EXECUTABLE_REFERENCE/NATIVE_LEAF_ONLY/PROFILE_ONLY).
- **`AdapterRoadmapSequence.kt`** (81) — `object AdapterRoadmapSequence`: čistě aritmetická pomůcka pro
  A0.x/A1.0 ordinály, sdílená všemi 8 lifecycle autoritami.
- **`AdapterExecutableReferencePromotion.kt`** (210) — `data class AdapterExecutableReferencePromotion(Document)`,
  `object AdapterExecutableReferencePromotionLoader`, `class …Authority.analyze/evaluate()` — post-A0
  „scoped promotion" bez modifikace historických portfolio záznamů.
- **`AdapterRoadmapLifecycleAuthority.kt`** (249) — `data class AdapterWorkflowEvidence` (sdíleno napříč
  VŠEMI 8 lifecycle autoritami) + lifecycle pro A0.1.

### `adapters/control/` (5 souborů, 1 339 řádků)
- **`AdapterControlMaterializationContracts.kt`** (334) — `AdapterControlFamily/ClaimStatus/Ownership/Scope`,
  `AdapterControlSemanticPartition/Claim/TargetRecord/Document/Finding/Report`, `AdapterControlRequirement(Completeness)`,
  `AdapterControlEvidence(Status)/Decision/Assessment`, `UnresolvedAdapterControlMaterializationException`,
  `object AdapterControlSemanticContract` (uzavřený katalog 21 sémantik × scope), `object AdapterControlMaterializationLoader`.
- **`AdapterControlEvidenceIntegrityAuthority.kt`** (363, `internal`) — nejnáročnější integrity validace
  v balíčku: scope-contract shoda, ownership, anchor-in-source ověření (`CONTROL_EVIDENCE_ANCHOR_UNRESOLVED`).
- **`AdapterControlMaterializationAuthority.kt`** (194) — hlavní runtime vstupní bod
  (`assess(CompilationAuthorization|ExecutionPlan, target)`, `requireMatched`, `reconcileDiagnostic`).
- **`AdapterControlRequirementAuthority.kt`** (326, `internal object`) — odvozuje requirements z
  `ApprovalNode`/`RetryGroupNode`/`TryPlanNode`/`WorkflowFailurePolicy`/triggers/`sourceIntent.policies`.
- **`AdapterControlRoadmapLifecycleAuthority.kt`** (122) — roadmap-lifecycle pro A0.4.

### `adapters/maturity/` (1 soubor, 730 řádků — největší soubor v balíčku)
- **`AdapterTargetMaturity.kt`** (730) — `TargetMaturityStage` (DECLARED→ANALYZABLE→RENDERABLE→EXECUTABLE→BEHAVIORALLY_CERTIFIED),
  `TargetMaturityEvidenceSource`, `AdapterTargetMaturityEvidenceScope/Document`, `object AdapterTargetMaturityEvidenceLoader`,
  `TargetMaturityFinding/ScopeAssessment/Assessment/Report`, `class AdapterTargetMaturityPublisher` —
  největší a nejsložitější třída: slučuje portfolio + promotions + topology + snapshot soubory do jednoho
  kumulativního maturity žebříčku per target/scope. Veřejné funkce: `analyze()`, `evaluate(document)`, plus
  ~15 privátních validačních metod (`validateHistoricalSource`, `validatePromotionSource`, `validateSnapshot`, …).

### `adapters/topology/` (3 soubory, 577 řádků)
- **`AdapterTopologyEvidenceAuthority.kt`** (394) — `AdapterTopologyFacet/ClaimStatus` (+ `toCoreStatus()`),
  `AdapterTopologyClaim/Record/EvidenceDocument/Finding/Assessment/Report`, `object AdapterTopologyEvidenceLoader`,
  `object AdapterTopologyClaimContract` (mapuje 11 core `ExecutionTopologyKind` + `interaction`/`concurrency`
  na fasety), `class AdapterTopologyEvidenceAuthority.analyze/evaluate()` — cross-checkuje evidenci proti
  runtime `target.topologyProfile`.
- **`AdapterTopologyProfileFactory.kt`** (48) — `object AdapterTopologyProfileFactory.profiles/profile()`:
  převádí evidenci na `ExecutionTopologyProfile` konzumovaný Core matchingem.
- **`AdapterTopologyRoadmapLifecycleAuthority.kt`** (135) — roadmap-lifecycle pro A0.2.

### `adapters/rendering/` (4 soubory, 1 089 řádků)
- **`AdapterArtifactRenderingAuthority.kt`** (421) — `class …render(manifest)` (hlavní render vstupní bod,
  volí EXECUTABLE vs REVIEW_ONLY), `internal object AdapterArtifactRenderingIntegrityAuthority.requireValid()`
  (post-hoc self-check vyrenderovaného bundle), `internal object AdapterArtifactRenderingFingerprint` (SHA-256),
  `internal object AdapterArtifactEvidenceInventory.from()` (staví kompletní evidence inventář z manifestu).
- **`AdapterArtifactRenderingContracts.kt`** (166) — `AdapterArtifactRenderingClaimStatus/RenderedArtifactKind`,
  `AdapterArtifactFormat/Record/Document/Finding/Report`, `AdapterArtifactSemanticEvidence/EvidenceReceipt/RenderedArtifact/Bundle`,
  `AdapterArtifactRenderingBlockedException`, `object AdapterArtifactRenderingEvidenceLoader`.
- **`AdapterArtifactRenderingEvidenceIntegrityAuthority.kt`** (251) — validace formátů, evidence, SUPPORTED/REVIEW_ONLY/UNKNOWN
  pravidel, cesta „uvnitř repozitáře" kontrola.
- **`AdapterArtifactRenderingRoadmapLifecycleAuthority.kt`** (251) — roadmap-lifecycle pro A0.6.

---

## 3. Zjištěné chyby a nedostatky

Žádný nalezený `!!` force-unwrap, žádné `TODO/FIXME/HACK` komentáře (ověřeno grepem přes celý balíček) —
kódová základna je v tomto ohledu čistá a defenzivně psaná (`require`/`check`/`runCatching` prakticky
všude). Nálezy níže jsou proto převážně architektonicko-údržbové, nikoli runtime crash bugy.

### Střední závažnost

**F1 — Nekonzistentní YAML loading architektura pro trigger evidenci.**
`trigger/AdapterTriggerEvidenceLoader.kt` (celý soubor, zejména řádky 14–99, 189–202) je jediný ze 7
evidence loaderů, který místo jednoho vloženého YAML souboru (vzor použitý v `continuity`, `control`,
`topology`, `rendering`, `binding`, `portfolio`) používá index + adresář (`adapters/triggers/targets/*.yaml`)
s vlastní implementací YAML `<<` merge-key šablonování (`expandClaimTemplate`, řádky 189–202) a kontrolou
úplnosti adresáře (`requireCompleteDirectoryIndex`, řádky 101–121). Funkčně to funguje správně, ale je to
o ~130 řádků složitější než ekvivalentní `AdapterContinuityEvidenceLoader`/`AdapterControlMaterializationLoader`
bez zjevného funkčního důvodu v kódu (žádný komentář nevysvětluje, proč triggery potřebují jinou architekturu
než control/continuity/topology, které mají srovnatelný počet targetů a claimů). Riziko: budoucí domény
budou kopírovat „ten správný" vzor nekonzistentně podle toho, který soubor autor viděl jako poslední.
*Návrh opravy:* buď sjednotit control/continuity/topology na stejný directory+index vzor (pokud má výhody
škálování), nebo trigger evidenci sloučit zpět do jednoho YAML jako ostatní domény.

**F2 — 1 476 řádků (17,7 % balíčku) tvoří duplicitní roadmap-lifecycle bookkeeping bez runtime vztahu k
adapterům.** 8 tříd (`AdapterBindingRoadmapLifecycleAuthority`, `AdapterContinuityRoadmapLifecycleAuthority`,
`AdapterExecutableContinuityRoadmapLifecycleAuthority`, `AdapterControlRoadmapLifecycleAuthority`,
`AdapterRoadmapLifecycleAuthority`, `AdapterTopologyRoadmapLifecycleAuthority`,
`AdapterArtifactRenderingRoadmapLifecycleAuthority`, `AdapterTriggerRoadmapLifecycleAuthority`) mají
téměř identickou strukturu (fáze IMPLEMENTING/COMPLETED/INVALID, `requiredYaml`, stejné privátní
extension funkce `string/map/mapList/itemStatus/check`) a čtou pouze `.flow-agent/*.yaml`. Toto není
funkční bug, ale zásadní „scope creep" a redundance zdrojového stromu.
*Návrh opravy:* přesunout mimo `src/main/kotlin` (např. do samostatného governance/tooling zdrojového
setu nebo `buildSrc`), protože nejsou součástí Intent→AST→Plan→Manifest runtime cesty.

**F3 — Duplikované privátní YAML-přístupové pomocné funkce v 8+ souborech.**
Identické (řádek po řádku téměř totožné) privátní extension funkce
`Map<String,Any?>.string(vararg path)`, `.map(key)`, `.mapList(key)`, `requireExactKeys`, `check(id,…)`
jsou zkopírovány v každé z 8 `*RoadmapLifecycleAuthority` tříd, např.:
- `binding/AdapterBindingRoadmapLifecycleAuthority.kt:87-94`
- `continuity/AdapterContinuityRoadmapLifecycleAuthority.kt:93-100`
- `control/AdapterControlRoadmapLifecycleAuthority.kt:89-96`
- `topology/AdapterTopologyRoadmapLifecycleAuthority.kt:114-127`
- `trigger/AdapterTriggerRoadmapLifecycleAuthority.kt:189-216`
- `rendering/AdapterArtifactRenderingRoadmapLifecycleAuthority.kt:196-223`
- `portfolio/AdapterRoadmapLifecycleAuthority.kt:214-241`

Odhad ~30 duplikovaných řádků × 8 = ~240 řádků čisté kopie. Typický „nadměrná/redundantní abstrakce" nález
požadovaný zadáním. *Návrh opravy:* extrahovat do jednoho `internal object AdapterRoadmapYamlSupport` v
`portfolio` balíčku (kde už žije sdílený `AdapterRoadmapSequence`) a nechat všech 8 tříd na něm delegovat.

**F4 — Dvojice téměř identických binding YAML souborů je křehká past pro budoucí přispěvatele.**
`adapters/bindings/builtin-capability-bindings.yaml` (190 řádků, v1.0, „zamrzlá") a
`builtin-capability-bindings-v1.1.yaml` (190 řádků, v1.1, „živá") se liší jen ve 3 řádcích (verze +
`dockerfile` reklasifikace, ověřeno diffem). Integrita je hlídána hardcoded SHA-256 konstantou
`C04_BINDING_SHA256` v `AdapterCapabilityBindingAuthority.kt:380`. Pokud někdo v dobré víře „opraví
překlep" v zamrzlém v1.0 souboru, dostane jen kryptickou zprávu „Frozen C0.4 binding digest changed:
observed=… expected=…" hluboko v `AdapterCapabilityBindingMigrationAuthority`, kterou nikdo pravidelně
nespouští (není součástí hlavního `analyze()` cesty renderovací pipeline). *Návrh opravy:* přidat komentář
přímo do YAML hlavičky obou souborů („DO NOT EDIT — frozen C0.4 snapshot") a/nebo zařadit migration-proof
test do standardní `./gradlew test` cesty (pokud tam ještě není).

### Nízká závažnost

**F5 — Systematicky nevyužívané importy `requireProvider` (a v menší míře `providerFor`).**
Vzor `import org.flowlang.generators.manifest.providerFor` + `import …requireProvider` se objevuje v
19 souborech, ale `requireProvider(...)` se v těle souboru nikdy nevolá (a v `AdapterControlMaterializationAuthority.kt`
ani `providerFor` využit není). Příklady: `continuity/AdapterContinuityEvidenceIntegrityAuthority.kt:12`,
`continuity/AdapterContinuitySatisfactionAuthority.kt:12`, `control/AdapterControlMaterializationAuthority.kt:12-13`,
`control/AdapterControlEvidenceIntegrityAuthority.kt:11`, `maturity/AdapterTargetMaturity.kt:16`,
`portfolio/AdapterPortfolioAuthority.kt:9`, `topology/AdapterTopologyEvidenceAuthority.kt:12`,
`trigger/AdapterTriggerAuthorizedRenderingAuthority.kt:11`, `trigger/AdapterTriggerMaterializationAuthority.kt:12`,
`trigger/AdapterTriggerEvidenceIntegrityAuthority.kt:12`, `portfolio/AdapterExecutableReferencePromotion.kt:9`
a další. Jasná stopa copy-paste vývoje napříč doménovými autoritami. Bez funkčního dopadu, ale je to
zbytečné rozšíření veřejného importovaného API surface a případné riziko, pokud build zapíná `-Werror`
na unused imports. *Návrh opravy:* odstranit nepoužité importy (IDE „optimize imports"/`detekt`).

**F6 — Generická diagnostika pro strukturálně vždy-blokující control sémantiky.**
`AdapterControlRequirementAuthority` (control) odvozuje sémantiky `compensation.detached-error-handler`
(řádek 145) a `approval.mode.<mode>` (řádek 98) pro netriviální/nestandardní Flow konstrukce. Tyto
sémantiky nejsou a nemohou být v `AdapterControlSemanticContract.scopesBySemantic`
(`AdapterControlMaterializationContracts.kt:141-165`) — `scopesFor()` proto vždy vrátí `null` a
`AdapterControlMaterializationAuthority.assess()` (řádek 79-83) vždy vydá `UNKNOWN` s hláškou „Control
semantic '…' is outside the closed adapter control contract.". Testy (`WorkflowFailureSemanticsTests.kt:118`,
`AdapterControlMaterializationAuthorityTests.kt:136,158`) potvrzují, že jde o **záměrné** fail-closed
chování pro nepodporované konstrukce — nejde tedy o bug, ale diagnostická zpráva nerozlišuje „tahle
sémantika nikdy nebude podporována" od „evidence chybí, doplňte YAML", což ztěžuje operátorovi debugging
zablokovaného manifestu. *Návrh opravy:* přidat dedikovaný nález/kód (např. `CONTROL_SEMANTIC_STRUCTURALLY_UNSUPPORTED`)
pro sémantiky mimo uzavřený kontrakt s jasnějším textem.

**F7 — `single { it.target == target }` volání spoléhají na dřívější invariantu bez lokální ochrany.**
`continuity/AdapterContinuitySatisfactionAuthority.kt:61`, `control/AdapterControlMaterializationAuthority.kt:68`,
`trigger/AdapterTriggerMaterializationAuthority.kt:53` — všechny volají Kotlin `single { … }` nad
`certifiedDocument.targets`, což při nesplnění invarianty vyhodí nečitelnou `NoSuchElementException`/
`IllegalArgumentException` ze standardní knihovny místo doménové výjimky. V současném kódu je invarianta
garantována `certifiedDocument`/`check(report.status == "PASS")` o pár řádků výš ve stejném souboru, takže
za normálních okolností nedojde k selhání — ale je to křehké vůči budoucí refaktorizaci (např. kdyby
`documentOverride` a `targets` mapa dostaly nekonzistentní vstupy ze dvou různých volajících). Severity
nízká-střední (robustnost/kvalita chybové hlášky, ne aktuální funkční chyba).
*Návrh opravy:* nahradit `single { … }` za `singleOrNull { … } ?: error("…")` s doménovým popisem.

**F8 — Tichá nejednoznačnost `executable: {}` vs. chybějící klíč v rendering evidenci.**
`rendering/AdapterArtifactRenderingContracts.kt:134-139` (`optionalFormat`) — pokud YAML obsahuje
`executable: {}` (prázdná mapa) namísto úplného vynechání klíče, `map(key)` vrátí prázdnou mapu přes
`.orEmpty()` a funkce ji tiše převede na `null` bez jakékoli validační chyby. Není to dnes využito v
žádném z reálných YAML souborů (ověřeno), ale je to latentní past pro budoucí editaci — autor YAML by
očekával validační chybu za nekompletní `executable` blok, ne tiché „jako by tam nebyl".
*Návrh opravy:* rozlišit `key !in this` (skutečně chybí → null) od `key in this && map je prázdná`
(přítomno, ale neplatné → chyba).

### Pozitivní zjištění (bez nálezu)
- Žádný `!!` operátor v celém balíčku.
- Žádné `TODO`/`FIXME`/`HACK` značky.
- Validace proti `/home/user/FlowAi/adapters/**/*.yaml` je důsledná: každý loader má `requireExactKeys`
  (žádná neznámá pole, žádná chybějící pole), takže „tiché" schéma drift není možné — YAML soubory jsou
  validovány přísněji než typický Jackson/SnakeYAML mapping.

---

## 4. Architektonická pozorování

**Hranice vůči `targets/generators` je jasná, ale zaplacená vysokou cenou dvojí autorizace.**
`adapters/*` nikdy nerenderuje ani neplánuje — jen certifikuje a blokuje/degraduje. To je čistý,
jednosměrný závislostní tok (`adapters` → `generators.manifest` interface + `targets.builtin` přes
`AdapterCatalog<TargetProjectionProvider>`, nikdy obráceně — ověřeno, žádný `targets/builtin/*` soubor
neimportuje `org.flowlang.adapters`). Problém je, že každá nová target capability (topology declaration,
control semantic, continuity claim, trigger family, rendering format) musí být zapsána **ručně na dvou
místech** — v `targets/builtin-targets.yaml` (runtime profil) a znovu v odpovídajícím
`adapters/<domain>/builtin-*.yaml` (evidence) — a `Adapter*EvidenceAuthority` třídy pak hlídají, že se
neliší (`ADAPTER_TOPOLOGY_REGISTRY_STATUS_MISMATCH`, `ADAPTER_TOPOLOGY_REGISTRY_EVIDENCE_MISMATCH` atd.).
To je smysluplný „independent double-entry" bezpečnostní vzor pro projekt, jehož README explicitně
požaduje „conformance-tested public standard surface" a „no silent fallback" — ale znamená to, že přidání
jednoho nového targetu vyžaduje současnou editaci až 6 samostatných YAML souborů (`bindings`, `continuity`,
`controls`, `topology`, `rendering`, `triggers/targets/<target>.yaml`) + portfolio + maturity evidence,
jinak selže integrity check. Toto je vědomý kompromis, ne chyba, ale zvyšuje lineárně náklady na přidání
targetu s počtem domén.

**Riziko redundantní vrstvy z postupných work packages je potvrzeno, a to konkrétně.**
Struktura balíčku doslova kopíruje roadmap ID: `A0.1` (portfolio), `A0.2` (topology), `A0.3` (binding),
`A0.4` (control), `A0.5` (continuity), `A0.6` (rendering), `A0.7` (trigger), `A1.0` (executable continuity)
— viz `.flow-agent/roadmap-adapters.yaml` (status: `completed`, `roadmapVersion: 10`). Každá položka
přinesla vlastní, strukturálně identickou `*RoadmapLifecycleAuthority` třídu (8×, 1 476 řádků/17,7 %
balíčku — F2/F3 výše), která ověřuje **stav dokončení roadmapy**, nikoli chování adaptérů za běhu. Protože
`roadmap-adapters.yaml.status == "completed"`, jde nyní o kód, který se udržuje „navěky" jako artefakt
procesu, kterým vznikl, ne jako trvalá součást runtime hranice Intent→Plan→Manifest. Toto je přesně ten
typ akumulace, na který se ptá zadání: postupné work-packages zanechaly trvalou vrstvu procesního
bookkeepingu propletenou s runtime certifikační logikou ve stejném balíčku, se stejnou konvencí
pojmenování (`Adapter*Authority`), takže je při čtení kódu netriviální odlišit „toto ověřuje běhový
target manifest" od „toto ověřuje, že jsme ve správné fázi interního roadmap procesu".
*Doporučení:* při další major verzi zvážit vyjmutí všech 8 `*RoadmapLifecycleAuthority` tříd (+ sdílený
`AdapterWorkflowEvidence`/`AdapterRoadmapSequence`) do odděleného modulu/zdrojového setu mimo `adapters`,
protože svazují hlavní produkční balíček s rychle se měnícím `.flow-agent/` procesním formátem, aniž by
přispívaly k samotné certifikaci target adaptérů.

**Sekundární „evidence-of-evidence" vzor (maturity) je nejsložitější a nejvíc propojený.**
`adapters/maturity/AdapterTargetMaturity.kt` (730 řádků, jediný soubor v balíčku) syntetizuje portfolio +
promotions + topology + spustitelné snapshoty do kumulativního žebříčku (`DECLARED→…→BEHAVIORALLY_CERTIFIED`).
Je to logicky čistý (`require(stages == TargetMaturityStage.entries.take(stages.size))` vynucuje
kumulativnost), ale je to čtvrtá nezávislá agregace nad již třikrát zopakovanými daty (portfolio → topology
→ control/continuity/trigger/rendering → maturity), což zvyšuje riziko, že budoucí přidání pátého evidence
typu bude vyžadovat úpravu na 5 místech místo jednoho centrálního modelu.

---

## 5. Statistická tabulka

| Soubor | Řádky | Tříd/objektů/enum (top-level) | Veř. funkcí (odhad) |
|---|---:|---:|---:|
| `AdapterDiagnosticReconciliation.kt` | 44 | 1 | 1 |
| `binding/AdapterBindingRoadmapLifecycleAuthority.kt` | 102 | 5 | 2 |
| `binding/AdapterCapabilityBindingAuthority.kt` | 384 | 10 | 4 |
| `yaml/IntentYamlLoader.kt` | 19 | 1 | 3 |
| `yaml/TargetRegistryYamlLoader.kt` | 14 | 1 | 2 |
| `continuity/AdapterContinuityContracts.kt` | 328 | 18 | 3 |
| `continuity/AdapterContinuityEvidenceIntegrityAuthority.kt` | 217 | 1 | 1 |
| `continuity/AdapterContinuityProjectionExecutionGate.kt` | 44 | 1 | 2 |
| `continuity/AdapterContinuityRoadmapLifecycleAuthority.kt` | 125 | 5 | 2 |
| `continuity/AdapterContinuitySatisfactionAuthority.kt` | 168 | 1 | 5 |
| `continuity/AdapterContinuityScopedCapabilityResolver.kt` | 87 | 1 | 1 |
| `continuity/AdapterContinuityScopedSupport.kt` | 67 | 3 | 1 |
| `continuity/AdapterContinuityScopedSupportContracts.kt` | 111 | 2 | 1 |
| `continuity/AdapterExecutableContinuityRoadmapLifecycleAuthority.kt` | 243 | 5 | 2 |
| `continuity/BuiltInAdapterContinuityScopedSupport.kt` | 29 | 1 | 0 |
| `contract/AdapterCatalog.kt` | 43 | 2 | 3 |
| `contract/TargetAdapterContract.kt` | 173 | 9 | 1 |
| `control/AdapterControlEvidenceIntegrityAuthority.kt` | 363 | 1 | 1 |
| `control/AdapterControlMaterializationAuthority.kt` | 194 | 1 | 8 |
| `control/AdapterControlMaterializationContracts.kt` | 334 | 19 | 3 |
| `control/AdapterControlRequirementAuthority.kt` | 326 | 1 | 2 |
| `control/AdapterControlRoadmapLifecycleAuthority.kt` | 122 | 5 | 2 |
| `maturity/AdapterTargetMaturity.kt` | 730 | 10 | 3 |
| `portfolio/AdapterExecutableReferencePromotion.kt` | 210 | 6 | 3 |
| `portfolio/AdapterPortfolioAuthority.kt` | 318 | 10 | 3 |
| `portfolio/AdapterRoadmapLifecycleAuthority.kt` | 249 | 6 | 3 |
| `portfolio/AdapterRoadmapSequence.kt` | 81 | 1 | 7 |
| `rendering/AdapterArtifactRenderingAuthority.kt` | 421 | 4 | 6 |
| `rendering/AdapterArtifactRenderingContracts.kt` | 166 | 13 | 1 |
| `rendering/AdapterArtifactRenderingEvidenceIntegrityAuthority.kt` | 251 | 1 | 1 |
| `rendering/AdapterArtifactRenderingRoadmapLifecycleAuthority.kt` | 251 | 5 | 2 |
| `topology/AdapterTopologyEvidenceAuthority.kt` | 394 | 11 | 6 |
| `topology/AdapterTopologyProfileFactory.kt` | 48 | 1 | 2 |
| `topology/AdapterTopologyRoadmapLifecycleAuthority.kt` | 135 | 5 | 2 |
| `trigger/AdapterTriggerAuthorizedRenderingAuthority.kt` | 59 | 1 | 2 |
| `trigger/AdapterTriggerContracts.kt` | 309 | 20 | 4 |
| `trigger/AdapterTriggerEvidenceIntegrityAuthority.kt` | 407 | 1 | 1 |
| `trigger/AdapterTriggerEvidenceLoader.kt` | 284 | 1 | 1 |
| `trigger/AdapterTriggerMaterializationAuthority.kt` | 233 | 1 | 5 |
| `trigger/AdapterTriggerRoadmapLifecycleAuthority.kt` | 249 | 5 | 2 |
| **Celkem (40 souborů)** | **8 332** | **~166** | **~95** |

*Poznámka:* sloupec „tříd" počítá top-level `class`/`object`/`interface`/`enum class`/`data class`
deklarace (ne vnořené `companion object`); sloupec „veřejných funkcí" je konzervativní odhad netriviálních
veřejných metod (bez generovaných `data class` accessorů/`copy()`).

---

## Shrnutí

`org.flowlang.adapters` (40 souborů, 8 332 řádků) je certifikační/auditní vrstva nad `targets/builtin`
(konkrétní renderery) a `generators/manifest` (target-neutrální kontrakt) — nikoli jejich duplicita.
Zajišťuje, že žádné tvrzení o podpoře cílové platformy (continuity/control/trigger/topology/rendering)
nesmí vést k EXECUTABLE manifestu bez composed provideru, `src/main` implementace a nezávislého `src/test`
důkazu; jinak manifest degraduje na REVIEW_ONLY nebo je zablokován. Validace proti 18 YAML evidence
souborům v `/home/user/FlowAi/adapters/**` je přísná (`requireExactKeys` všude), kód je bez `!!` a bez
TODO/FIXME dluhu.

Klíčová zjištění (0 kritických/vysokých, 4 střední, 4 nízké):
- **Střední:** (F1) trigger evidence používá jinou (index+adresář+YAML merge-key) loading architekturu
  než všech 6 ostatních domén bez zdůvodněného důvodu; (F2) 1 476 řádků (17,7 % balíčku) tvoří 8 téměř
  identických `*RoadmapLifecycleAuthority` tříd validujících `.flow-agent/roadmap*.yaml` proces-stav —
  runtime nesouvisející s Intent→Plan→Manifest; (F3) ~240 duplikovaných řádků privátních YAML helper funkcí
  napříč těmito 8 třídami; (F4) dvojice téměř identických binding YAML (v1.0/v1.1) hlídaná hardcoded SHA-256
  — křehká past pro budoucí editory.
- **Nízké:** (F5) nevyužívané `requireProvider`/`providerFor` importy v ~19 souborech (copy-paste stopa);
  (F6) generická diagnostická zpráva pro strukturálně vždy-neúspěšné control sémantiky
  (`compensation.detached-error-handler`, `approval.mode.*`); (F7) `single { … }` volání ve 3 authority
  třídách bez lokální ochrany proti budoucí invariant-violaci; (F8) tichá nejednoznačnost `executable: {}`
  vs. chybějící klíč v rendering evidenci.

Architektonicky je hranice vůči `targets/generators` čistá a jednosměrná, ale balíček nese dvě odlišné
odpovědnosti pod jedním jménem: (1) runtime certifikaci target adaptérů a (2) trvale udržovanou historii
dokončeného roadmap procesu (A0.1–A1.0). Doporučuji oddělit (2) do samostatného modulu/zdrojového setu.
