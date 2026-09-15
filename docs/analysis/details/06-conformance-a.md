# FlowAi `org.flowlang.conformance` — Analýza subsetu A (54 souborů)

Rozsah: `/home/user/FlowAi/src/main/kotlin/org/flowlang/conformance/`, 54 jmenovaných souborů, **12 318 řádků** (ověřeno `wc -l`), všechny přečteny celé.

---

## 1. Přehled — co tento subset dělá, jaký vzorec se opakuje

Balíček `conformance` je interní, ručně psaný "verifikační jazyk" nad vlastním kompilátorem FlowAi (Kotlin). Účel deklarovaný v komentářích: dokázat, že syntakticky odlišné frontendy (Flow Source / Intent YAML / Reviewed‑AI‑Proposal) produkují **stejný kanonický graf** (`CanonicalExecutionGraph`), že veřejné artefakty (ExecutionPlan, TargetManifest, schémata) jsou z něj odvozené a neměnné, a že jednotlivé domény (Backup, Certificate, Secret Rotation, Database Migration, Data Orchestration, Infrastructure…) jsou sémanticky "reprezentovatelné" oproti externímu korpusu reálného kódu (tzv. "EF‑0x Falsification").

V tomto subsetu (54/108 souborů balíčku) se opakují čtyři zřetelné vzory:

1. **Skutečné, chování testující "mutation‑style" konformance** (menšina, ale nejcennější část) — např. `CompilerAxisConformanceChecks`, `FlowSensitiveConformanceChecks`, `ExplicitMergeConformanceChecks`, `WorkflowFailureConformanceChecks`, `WorkflowOwnershipConformanceChecks`, `SemanticEquivalenceAuthority`. Tyto třídy skutečně kompilují fixture, mutují kanonický graf po jednotlivých sémantických osách (capability, effect, dependency, control, topology…) a ověřují, že se změní digest/validace. Poctivá, netriviální QA práce.
2. **EF‑0x "Falsification" framework** — `CertificateLifecycleFalsification`, `SecretRotationFalsification`, `BackupRestoreFalsification` (a odkazované, mimo rozsah: Database‑Migration, Data‑Orchestration, Infrastructure…). Každá třída definuje vlastní `Requirement` enum, `FactDeclaration`/`CaseAssessment`/`Finding`/`Report` datové třídy a `classify()` funkci, která pro každý "fakt" z externího korpusu vytvoří `IntentDocument`, prožene ho produkčním `IntentCapabilityValidator` a porovná efekty. Struktura je **téměř identická** napříč doménami.
3. **Baseline‑verifikátory** — `CertificateLifecycleBaseline`, `DatabaseMigrationRecoveryBaseline`, `DataOrchestrationBaseline`, `InfrastructureLifecycleBaseline`. Čtyři soubory, ~150–190 řádků každý, **strukturálně identické** (liší se jen řetězcem `EF‑0x` a názvy typů) — hlídají monotónnost nálezů (REPRESENTABLE nesmí regredovat) vůči snapshotu v YAML.
4. **"RoadmapLifecycleAuthority" — proces‑governance stavové automaty** — `SemanticEquivalenceRoadmapLifecycleAuthority`, `AbstractTopologyMatrixRoadmapLifecycleAuthority`, `OperationalDomainAdequacyRoadmapLifecycleAuthority`, `WorkflowSemanticsRecoveryLifecycle`, `CompilerModuleExtractionLifecycle`, `CompilerModuleAcceptance`. Tyto třídy **netestují kompilátor** — testují, že interní YAML soubory pod `.flow-agent/` (roadmapa, work‑packages, release‑state) jsou vzájemně konzistentní stran textových stavů (`"active"`, `"next"`, `"completed"`…), SHA a GitHub run‑ID. Je to bookkeeping AI‑agentího vývojového procesu vydávaný za "conformance".

Společný idiom: každá "checks()" metoda vrací `List<ConformanceCheck>` s `passed`/`message`; naprostá většina chyb se hlásí přes `runCatching{}` / vlastní `resultCheck()` wrapper, který **chytá `Throwable`**, ne jen `Exception` (viz §3).

---

## 2. Inventář souborů a tříd

### 2.1 Jádro kompilátor/graph konformance (chování testující)

**`CompilerAxisConformanceChecks.kt`** (863 ř., 2 top‑level typy)
- `class CompilerAxisConformanceChecks(rootDir: File)` — 14 checků (`AR-01`) nad konvergencí frontendů do `CanonicalExecutionGraph`: inventář entrypointů, zákaz přímé konstrukce pipeline (`forbidDirectPipeline`), shoda Intent‑YAML vs. Reviewed‑AI grafu, freeze veřejných verzí kontraktu, integrita grafu, **determinismus digestu vůči pořadí** (`digestDeterminismErrors`), **"mutation polarity"** (`mutationPolarityErrors` — mutuje capability/effect/dependency/control/topology a ověří změnu digestu), binding autorizace na digest, ekvivalence odvozených pohledů, autorizace cílové materializace, zákaz starého `SemanticActionGraph`.
- `data class CompilerEntrypointInventory(...)` — načítá a validuje `architecture-recovery/ar-01/compiler-entrypoint-inventory.yaml`.
- Klíčové veřejné funkce: `checks()`, `CompilerEntrypointInventory.load(rootDir)`.

**`FlowSensitiveConformanceChecks.kt`** (248 ř., 1 třída) — `AR-02A`, jediný vlastník data‑flow analýzy (`singleOwnerErrors`), mříž dostupnosti hodnot (`latticeErrors` — DefinitelyDefined/MaybeDefined/Merged/PARTIAL_PATHS/AMBIGUOUS_PRODUCERS) a odmítnutí nejistého čtení (`rejectionErrors`, očekává `UnsafeFlowAvailabilityException`).

**`ExplicitMergeConformanceChecks.kt`** (166 ř., 1 třída) — `AR-02B`, typovaný `merge()`: exhaustivita větví, invariance vůči permutaci argumentů (digest musí být stejný při `merge(left,right)` i `merge(right,left)`), veřejné kontrakty se nesmí tiše měnit.

**`WorkflowOwnershipConformanceChecks.kt`** (242 ř., 1 třída) — `AR-02C`, multi‑workflow vlastnictví uzlů, trigger routing, legacy jednoworkflow pohledy **musí selhat** (`MultipleWorkflowCompatibilityViewException`) místo tichého zploštění. `observeTargets(...)` pro `WorkflowSemanticsIntegrationChecks`.

**`WorkflowFailureConformanceChecks.kt`** (284 ř., 1 třída) — `AR-02D`, typovaný failure‑handler region: PROPAGATE disposition, zákaz překryvu handler/root, zákaz hrany napříč hranicí, `syntheticAuthorityErrors` — adaptér nesmí odvozovat error‑handler ze syntaktického tvaru (`lastOrNull() as? TryPlanNode`), jen z typované autority.

**`WorkflowSemanticsIntegrationChecks.kt`** (306 ř., 1 třída + top‑level fn) — `AR-02` integrace 4 výše uvedených tříd. `frontendMatrixErrors`, `mutationMatrixErrors` (10 pojmenovaných mutací), `targetMatrixErrors`, `publicCompatibilityErrors`, `findingClosureErrors` (váže na katalog nálezů F‑02/F‑08/F‑15).

**`SemanticBoundaryChecks.kt`** (158 ř., 1 třída) — `v0.4.x`: adversariální AI normalizace, zákaz SDK/runtime balíčku, architektonické guardrails, AI proposal review (musí odmítnout migraci bez backupu), zákaz tichého fallbacku netranslatovatelné podmínky.

**`StandardArchitectureNormalizationChecks.kt`** (551 ř., 1 třída) — 16 checků: verze snapshotů, pokrytí katalogu, kanonický význam intentu nezávislý na inventáři implementací, univerzální efekt/kontrolní model, abstraktní topologie (Jenkins MATCHED vs. GitHub Actions BLOCKED), diagnostická poctivost lowering, bezpečnost prostředí (schválení pro `"prod"`), zákaz Jacksonu v jádru, AI normalizace.

**`TrustAndReferenceChecks.kt`** (168 ř., 1 třída) — `v0.6.6–0.7.0`: AI trust boundary (musí blokovat lowering bez povinné clarifikace), standard example bundle, compatibility promise, reference corpus execution harness.

**`TargetSemanticsExportChecks.kt`** (116 ř., 1 třída) — target‑semantics matice musí být **odvozená**, ne ručně psaná; standard export bundle.

**`VectorIndexChecks.kt`** (54 ř., 1 třída) — vektorový index musí PASSnout a pokrývat gate release profilu.

**`StandardEvidenceChecks.kt`** (232 ř., 1 třída) — 12 checků: diagnostic coverage, artifact integrity, contract index (s negativním testem — vymyšlený artefakt musí selhat), dangling `derivedFrom` musí blokovat evidenci, compliance PASS/FAIL, freeze report, compatibility policy, reference/negativní korpus.

**`SourceDeclarationConformanceChecks.kt`** (123 ř., 1 třída) — testuje **parser** přímo: duplicitní deklarace odmítnuty s přesnou cestou/pozicí, zachování scope, odmítnutí **před** module‑lookupem.

**`TargetSelectionProvenanceIntegrityChecks.kt`** (149 ř., 1 třída) — jediná projection pipeline na classpath, uzavřený `TargetSelectionOrigin`, typované zdroje konfigurace, odvozený CLI exit‑kód.

**`ClosureBlockingIntegrityChecks.kt`** (409 ř., 1 třída) — autorovaná control evidence (ambiguózní text zůstává UNKNOWN), CI/CD bias inventář, CLI hranice, kanonická topologie, GitHub cancellation sémantika, exact‑head CI hranice, `checkReleaseLifecycle` stavový automat.

**`SemanticEquivalenceAuthority.kt`** (516 ř., 2 typy) — `C0.3`: schema/coverage/polarity/independence/concrete‑reference/boundary chyby; mutace evidence vždy → `NOT_EQUIVALENT`.

**`SemanticImplementationObservationAuthority.kt`** (340 ř., 2 typy) — převádí adapter‑owned projekci na C0.3 evidenci per požadavek (EFFECT/RESULT_IDENTITY/RESULT_VALUE/CONTINUITY).

**`ReferenceSnapshotHonesty.kt`** (300 ř., 7 typů) — poctivost committed snapshotů: zakázané "legacy executable‑looking" soubory, desítky invariantů.

**`ReferenceSnapshotBundleGenerator.kt`** (147 ř., 1 třída) — generuje referenční snapshoty přes produkční pipeline.

**`AdapterTopologyConformanceChecks.kt`** (267 ř., 2 typy) — `A0.2`: runtime topology profil, demontáž "profile‑only" cílů, executable topology proof.

**`AdapterArtifactRenderingConformanceChecks.kt`** (227 ř., 1 třída) — `A0.6`: review/executable separace identity, integrita receiptu (SHA‑256), obsahové kontroly renderu.

**`WorkflowFailureProjectionEvidence.kt`** (81 ř., 1 objekt) — porovnává metadata na renderovaných krocích s autorizovanou policy.

**`WorkflowPlanSetCompatibilityMatrix.kt`** (157 ř., 1 objekt) — vlastní mini JSON‑Schema evaluator, mutation testing schématu (5 negativních payloadů).

**`WorkflowSemanticEvidence.kt`** (150 ř., 2 typy) — `observeMutation()`, `commonApprovalMeaning()` — alfa‑ekvivalence napříč frontendy.

**`WorkflowTargetGatingMatrix.kt`** (90 ř., 2 typy) — matice očekávání outcome/blocked per target×scénář.

**`CanonicalExecutionPlanKindConformanceOracle.kt`** (26 ř., 1 objekt) — **záměrně** duplikuje malou produkční mapu jako nezávislou orákulní referenci (komentář to zdůvodňuje).

**`ConformanceManifest.kt`** (144 ř., 1 třída) — staví `ConformanceManifestReport`; ručně psaný seznam ~48 schémat a ~35 required artefaktů.

**`ConformanceRunner.kt`** (104 ř., 1 třída) — CLI‑facing orchestrátor, volá ~24 dílčích Runnerů (mnohé mimo tento subset).

**`SemanticClosureChecks.kt`** (17 ř., 1 třída) — finální brána; zahazuje `report.errors` (viz §3).

**`ToolchainModernizationConformanceRunner.kt`** (20 ř., 1 třída) — tenký wrapper.

**`StrictStandardBundleFixture.kt`** (49 ř., 1 objekt) — testovací fixture generátor.

### 2.2 EF‑0x "Falsification" rodina

**`ExternalCorpusLoader.kt`** (261 ř., 2 typy) — `EF-01`: přísný loader se SHA‑256, provenience (owner/repo, 40‑znakový hash), rozsahy řádků evidence.

**`CertificateLifecycleFalsification.kt`** (509 ř., 5 typů) — `EF-05`: 7 requirementů, `classify()` staví `IntentDocument`, validuje přes `IntentCapabilityValidator`.

**`SecretRotationFalsification.kt`** (390 ř., 5 typů) — `EF-06`: 6 requirementů — strukturálně identický vzor.

**`BackupRestoreFalsification.kt`** (383 ř., 5 typů) — `EF-03`: 4 requirementy, `MIN_CASES = 2` (nejnižší práh).

**`CertificateLifecycleBaseline.kt`** (183 ř.), **`DatabaseMigrationRecoveryBaseline.kt`** (181 ř.), **`DataOrchestrationBaseline.kt`** (175 ř.), **`InfrastructureLifecycleBaseline.kt`** (192 ř.) — po 5 typech, **strukturálně identické** Baseline‑verifikátory.

**`AdapterProfileEvidenceContracts.kt`** (177 ř., 8 typů) — kontrakty: claim status/dimension, source pin (SHA‑256), manifest, claim identity.

**`AdapterProfileEvidenceConformanceChecks.kt`** (118 ř., 3 typy) — `C0.4`: 7 pojmenovaných checků + inventář + runner.

### 2.3 Real‑world / operational‑domain korpus

**`RealWorldCorpusContracts.kt`** (291 ř., 25 top‑level typů) — čistě datový kontraktní soubor bez logiky.

**`RealWorldCorpusLoader.kt`** (471 ř., 1 třída) — `C0.1`: masivní strict loader, desítky `require()` invariantů, path‑traversal ochrana.

**`OperationalDomainAdequacyContracts.kt`** (81 ř., 5 typů) — `C1.0` analogie k výše uvedenému, zúžená.

**`OperationalDomainAdequacyConformanceChecks.kt`** (294 ř., 3 typy) — `C1.0`: lifecycle, corpus integrity, scope/domain/capability coverage, mutation polarity, hranice s C0.1.

**`OperationalDomainAdequacyRoadmapLifecycleAuthority.kt`** (228 ř., 3 typy) — stavový automat nad 6 YAML soubory; používá produkční `WorkflowBoundaryEvidence` přímo (korektně, na rozdíl od §2.4).

### 2.4 "RoadmapLifecycleAuthority" proces‑governance rodina

**`SemanticEquivalenceRoadmapLifecycleAuthority.kt`** (349 ř., 5 typů) — `C0.3` stavový automat; lokálně přebaluje `WorkflowBoundaryEvidence`.

**`AbstractTopologyMatrixRoadmapLifecycleAuthority.kt`** (276 ř., 4 typy) — `C0.2`, **téměř řádek po řádku identický** s předchozím souborem.

**`WorkflowSemanticsRecoveryLifecycle.kt`** (207 ř., 1 objekt) — fingerprintuje bajty evidence (SHA‑256), ověřuje odlišnost 4 hraničních eventů.

**`CompilerModuleExtractionLifecycle.kt`** (203 ř., 1 objekt) — `AR-03`: 4‑slice extrakční lifecycle, silně provázané `require` řetězce.

**`CompilerModuleAcceptance.kt`** (126 ř., 1 objekt) — nejhutnější evidence‑binding logika; jediný force‑unwrap v subsetu.

### 2.5 Ostatní

**`ArchitectureRecoveryConformanceChecks.kt`** (293 ř., 3 typy) — název souboru neodpovídá hlavní třídě (`AdapterTargetMaturityConformanceChecks`, AR‑00): publikace maturity per target, strukturální evidence, bounded executable evidence.

**`ScenarioPackQualityAnalyzer.kt`** (117 ř., 3 typy) — kvalita AI scénářových balíčků: metadata, "dekorativní" příklady (heuristika), povinné pokrytí blocked scénářů.

**`WorkflowSemanticsFindingEvidence.kt`** (109 ř., 7 typů) — váže historické nálezy (F‑02/F‑08/F‑15) na PR revize + produkční symboly + výsledky checků.

---

## 3. Zjištěné chyby a nedostatky

### 3.1 Vysoká závažnost

**[VYSOKÁ] Plošné `catch (Throwable)` maskuje JVM chyby jako běžné selhání konformance.**
- `ConformanceCheckSupport.runCheck()` (mimo scope, ale používá jej >15 z těchto 54 souborů) — `catch (t: Throwable)`.
- Stejný vzor uvnitř scope: `CompilerAxisConformanceChecks.kt:670-677`, `WorkflowFailureConformanceChecks.kt:251-263`, `FlowSensitiveConformanceChecks.kt:200-207`, `ExplicitMergeConformanceChecks.kt:156-159`, `AdapterTopologyConformanceChecks.kt:224-225`, `AdapterArtifactRenderingConformanceChecks.kt:210-217`, `OperationalDomainAdequacyConformanceChecks.kt:30,71`.
- **Scénář selhání:** `StackOverflowError`/`OutOfMemoryError` se v CI zaloguje jako "conformance check X failed", ne jako infrastrukturní selhání — vede k ladění špatným směrem.
- **Návrh opravy:** `catch (e: Exception)` místo `Throwable`, nechat `Error` probublat.

**[VYSOKÁ] `SemanticClosureChecks.kt:8-16` zahazuje diagnostiku finální brány.**
```kotlin
fun checks(completedChecks: List<ConformanceCheck>): List<ConformanceCheck> {
    val report = SemanticClosureAuthority(rootDir).evaluate(completedChecks)
    return listOf(ConformanceCheck(SemanticClosureAuthority.CHECK_ID, report.status == "PASS"))
}
```
`message` se nikdy nenastaví, `report.errors` se nikam nepropaguje — u **poslední, nejdůležitější brány** ("Final conformance group") uvidíte při selhání jen `passed=false` bez důvodu. Ostatních ~53 souborů tuto disciplínu dodržuje. **Návrh opravy:** `message = report.errors.takeIf { it.isNotEmpty() }?.joinToString(" | ")`.

### 3.2 Střední závažnost

**[STŘEDNÍ] Nekonzistentní odstraňování komentářů/řetězců při textovém skenování zdroje.**
`CompilerAxisConformanceChecks.kt` definuje `codeWithoutCommentsAndStrings()` a používá ji v `dependencyDirectionErrors`/`requireExactProductionCallers`, ale `flowSourceAxisErrors` (120‑127), `intentConvergenceErrors` (129‑145), `reviewedAiConvergenceErrors` (147‑156), `obligationRetirementErrors` (492‑520) a `noPseudoGraphErrors` (522‑538) skenují **syrový** text. Podobně `WorkflowFailureConformanceChecks.kt:217-233` a `ClosureBlockingIntegrityChecks.kt:353-367` (čistě textové `contains` nad YAML). **Scénář selhání:** komentář zmiňující zakázaný termín způsobí falešné selhání; řetězcový literál v chybové hlášce může uniknout detekci. **Návrh opravy:** sjednotit přes `codeWithoutCommentsAndStrings`/`KotlinSourceBoundaryScanner.structuralSource`.

**[STŘEDNÍ] `OperationalDomainAdequacyConformanceChecks.checks()` (18‑97) — předčasný `return produced` skryje identity 7 zbylých checků při selhání načtení korpusu.** Runner sice selže díky přesné shodě s inventářem, ale výstup neřekne, který konkrétní check chybí. **Návrh:** vyprodukovat "skeleton" `ConformanceCheck` i při selhání korpusu.

**[STŘEDNÍ] `BackupRestoreFalsification.MIN_CASES = 2`** (řádek 367) vs. `CertificateLifecycleFalsification.MIN_CASES = 5`, `SecretRotationFalsification.MIN_CASES = 6` — nekonzistentně nízký evidenční práh pro stejně kritickou doménu (4 requirementy pokryté jen 2 případy). Vypadá jako opomenutí při kopírování šablony napříč doménami — přímý důkaz rizika duplikace z §4.

**[STŘEDNÍ] Fragilní `!!` — `CompilerModuleAcceptance.kt:70`:** `count < positive(baseline)!!` — bezpečné jen díky pořadí `||` operandů; jakýkoli refaktoring může vnést NPE. **Návrh:** extrahovat do lokální proměnné před porovnáním.

**[STŘEDNÍ] Detekce "odstraněné třídy" přes `ClassLoader.getResource`** — `ClosureBlockingIntegrityChecks.kt:151-153`, `TargetSelectionProvenanceIntegrityChecks.kt:38-44`. Fragilní na shaded‑jar/classloader prostředí. **Návrh:** ověřovat existenci zdrojového souboru místo classpath.

**[STŘEDNÍ] Silná duplikace = riziko "opraveno na jednom místě, ne na ostatních"** — viz §4, potvrzeno konkrétním nálezem `MIN_CASES` výše.

### 3.3 Nízká závažnost / kosmetika

- `ArchitectureRecoveryConformanceChecks.kt` — název souboru neodpovídá hlavní třídě (`AdapterTargetMaturityConformanceChecks`), ztěžuje navigaci.
- `ConformanceManifest.kt:55-104` — ručně vypsaný seznam schémat/artefaktů bez validace existence na disku (na rozdíl od jiných loaderů v balíčku).
- `ScenarioPackQualityAnalyzer.hasUsefulExample()` (100‑105) — heuristika "token overlap" může falešně prohlásit example za užitečný při náhodné shodě obecného slova.
- `CanonicalExecutionPlanKindConformanceOracle.kt` — fallback větev `"task"` nikdy nedetekuje drift u nové capability, i když to komentář slibuje.
- `WorkflowSemanticsFindingEvidence.kt` — `expectedChecks` mapa hardcoded jen pro F‑02/F‑08/F‑15, inventář žije v Kotlinu místo v datech.
- Jediný textový výskyt `"TODO"` v celém subsetu je **testovací fixture hodnota** (`ClosureBlockingIntegrityChecks.kt:66`), ne skutečný nedodělek. Žádné `FIXME`/`HACK`/`XXX` nenalezeny.

---

## 4. Architektonická pozorování — přiměřená QA investice, nebo bloated proces‑governance kód?

**Obojí, v nerovnováze.**

1. **Legitimní, nadprůměrně důkladná QA (~40 % řádků):** mutation‑testing na kanonickém grafu, invariance vůči pořadí/permutaci, path‑traversal ochrana v loaderech, SHA‑256 vázaná evidence, explicitně negativní testy. `CanonicalExecutionPlanKindConformanceOracle` dokonce **vědomě** volí duplikaci nad závislostí se zdůvodněním v komentáři — vyzrálé rozhodnutí, ne nedopatření.

2. **Bloated proces‑governance kód (odhadem 25‑30 % řádků subsetu):** `*RoadmapLifecycleAuthority` (3 soubory, ~850 ř.), `WorkflowSemanticsRecoveryLifecycle`+`CompilerModuleExtractionLifecycle`+`CompilerModuleAcceptance` (3 soubory, ~530 ř.), Baseline rodina (4 soubory, ~730 ř.), EF‑Falsification rodina (3 soubory, ~1280 ř., z čehož podstatná část je opakovaný boilerplate). Tyto třídy netestují chování FlowAi — testují bezrozpornost ručně udržovaných YAML souborů popisujících interní vývojový proces (`.flow-agent/*`). Legitimní potřeba pro samo‑disciplinující AI‑agentní proces, ale není to "conformance" softwaru a jeho směšování se sémantickou ekvivalencí kompilátoru ve stejném CLI běhu je zavádějící.

3. **Copy‑paste bez abstrakce je dominantní neřest, ne špatná logika:**
   - `SemanticEquivalenceRoadmapLifecycleAuthority.kt:13-51` a `AbstractTopologyMatrixRoadmapLifecycleAuthority.kt:13-51` — identická datová třída `*WorkflowEvidence`, obě jen přebalují `WorkflowBoundaryEvidence`, který `structurallyValid` nabízí přímo (jak správně dělá sourozenec `OperationalDomainAdequacyRoadmapLifecycleAuthority`).
   - 4 Baseline soubory mají identickou kostru `verify()/loadBaseline()/loadLifecycleStatus()/failed()/key()`, liší se jen EF‑kódem a názvem typu — ideální kandidát na generický `ExternalFalsificationBaselineVerifier<TFact,TFinding,TReport>`.
   - 3 EF‑Falsification soubory sdílejí `semanticProjection()/classify()/validateValues()` kostru.
   
   Odhad úspory sjednocením: **600–900 řádků**, hlavně odstranění rizika driftu (`MIN_CASES` nekonzistence je přímý důkaz, že k drift už dochází).

4. **Doporučení:** (a) přesunout proces‑governance třídy mimo balíček `conformance`; (b) extrahovat generický Baseline/Falsification framework; (c) sjednotit `catch(Throwable)`→`catch(Exception)`.

---

## 5. Statistická tabulka

| Soubor | Řádky | Tříd/objektů | Veř. funkcí (odhad) |
|---|---:|---:|---:|
| CompilerAxisConformanceChecks.kt | 863 | 2 | 2 |
| StandardArchitectureNormalizationChecks.kt | 551 | 1 | 3 |
| SemanticEquivalenceAuthority.kt | 516 | 2 | 1 |
| CertificateLifecycleFalsification.kt | 509 | 5 | 1 |
| RealWorldCorpusLoader.kt | 471 | 1 | 1 |
| ClosureBlockingIntegrityChecks.kt | 409 | 1 | 1 |
| SecretRotationFalsification.kt | 390 | 5 | 1 |
| BackupRestoreFalsification.kt | 383 | 5 | 1 |
| SemanticEquivalenceRoadmapLifecycleAuthority.kt | 349 | 5 | 4 |
| SemanticImplementationObservationAuthority.kt | 340 | 2 | 2 |
| WorkflowSemanticsIntegrationChecks.kt | 306 | 1 | 2 |
| ReferenceSnapshotHonesty.kt | 300 | 7 | 4 |
| OperationalDomainAdequacyConformanceChecks.kt | 294 | 3 | 4 |
| ArchitectureRecoveryConformanceChecks.kt | 293 | 3 | 3 |
| RealWorldCorpusContracts.kt | 291 | 25 | 5 |
| WorkflowFailureConformanceChecks.kt | 284 | 1 | 1 |
| AbstractTopologyMatrixRoadmapLifecycleAuthority.kt | 276 | 4 | 4 |
| AdapterTopologyConformanceChecks.kt | 267 | 2 | 2 |
| ExternalCorpusLoader.kt | 261 | 2 | 2 |
| FlowSensitiveConformanceChecks.kt | 248 | 1 | 1 |
| WorkflowOwnershipConformanceChecks.kt | 242 | 1 | 2 |
| StandardEvidenceChecks.kt | 232 | 1 | 1 |
| OperationalDomainAdequacyRoadmapLifecycleAuthority.kt | 228 | 3 | 2 |
| AdapterArtifactRenderingConformanceChecks.kt | 227 | 1 | 1 |
| WorkflowSemanticsRecoveryLifecycle.kt | 207 | 2 | 5 |
| CompilerModuleExtractionLifecycle.kt | 203 | 1 | 1 |
| InfrastructureLifecycleBaseline.kt | 192 | 5 | 2 |
| CertificateLifecycleBaseline.kt | 183 | 5 | 2 |
| DatabaseMigrationRecoveryBaseline.kt | 181 | 5 | 2 |
| AdapterProfileEvidenceContracts.kt | 177 | 8 | 2 |
| DataOrchestrationBaseline.kt | 175 | 5 | 2 |
| TrustAndReferenceChecks.kt | 168 | 1 | 1 |
| ExplicitMergeConformanceChecks.kt | 166 | 1 | 1 |
| SemanticBoundaryChecks.kt | 158 | 1 | 2 |
| WorkflowPlanSetCompatibilityMatrix.kt | 157 | 1 | 5 |
| WorkflowSemanticEvidence.kt | 150 | 2 | 5 |
| TargetSelectionProvenanceIntegrityChecks.kt | 149 | 1 | 1 |
| ReferenceSnapshotBundleGenerator.kt | 147 | 1 | 1 |
| ConformanceManifest.kt | 144 | 1 | 1 |
| CompilerModuleAcceptance.kt | 126 | 1 | 1 |
| SourceDeclarationConformanceChecks.kt | 123 | 1 | 1 |
| AdapterProfileEvidenceConformanceChecks.kt | 118 | 3 | 3 |
| ScenarioPackQualityAnalyzer.kt | 117 | 3 | 1 |
| TargetSemanticsExportChecks.kt | 116 | 1 | 2 |
| WorkflowSemanticsFindingEvidence.kt | 109 | 7 | 2 |
| ConformanceRunner.kt | 104 | 1 | 1 |
| WorkflowTargetGatingMatrix.kt | 90 | 2 | 2 |
| OperationalDomainAdequacyContracts.kt | 81 | 5 | 2 |
| WorkflowFailureProjectionEvidence.kt | 81 | 1 | 3 |
| VectorIndexChecks.kt | 54 | 1 | 1 |
| StrictStandardBundleFixture.kt | 49 | 1 | 3 |
| CanonicalExecutionPlanKindConformanceOracle.kt | 26 | 1 | 1 |
| ToolchainModernizationConformanceRunner.kt | 20 | 1 | 1 |
| SemanticClosureChecks.kt | 17 | 1 | 1 |
| **Celkem (54 souborů)** | **12 318** | **~137** | **~108** |

---

## Shrnutí

Přečteno celý subset 54/108 souborů balíčku `org.flowlang.conformance` (12 318 řádků, 100 % pokrytí). Balíček je hybrid dvou zcela odlišných věcí: (1) skutečně kvalitní, mutation‑testing style QA nad kompilátorem FlowAi (~40 % řádků) — mutuje kanonický graf po sémantických osách a ověřuje citlivost digestu, kontroluje invarianci vůči pořadí/permutaci, path‑traversal a SHA‑256 integritu v loaderech; a (2) proces‑governance bookkeeping (~25‑30 % řádků) — stavové automaty (`*RoadmapLifecycleAuthority`, `*Lifecycle`, `CompilerModuleAcceptance`), které ověřují jen to, že interní YAML soubory pod `.flow-agent/` popisující stav AI‑agentního vývojového procesu jsou vzájemně bezrozporné, ne že software funguje správně.

Dominantní neřest je **copy‑paste bez abstrakce**, ne špatná logika: 4 Baseline‑verifikátory (Certificate/DatabaseMigration/DataOrchestration/Infrastructure) jsou strukturálně identické (~730 řádků), 3 EF‑Falsification třídy sdílejí téměř identickou kostru (~1280 řádků), a 2 RoadmapLifecycleAuthority soubory duplikují stejný wrapper nad produkční `WorkflowBoundaryEvidence`, kterou třetí sourozenec používá přímo. Přímý důkaz rizika driftu: `BackupRestoreFalsification.MIN_CASES = 2` je nekonzistentně nízké oproti `CertificateLifecycleFalsification` (5) a `SecretRotationFalsification` (6).

**Bugy podle závažnosti:** 2 vysoké (plošné `catch(Throwable)` napříč ~15+ míst maskuje JVM chyby jako conformance‑fail; `SemanticClosureChecks.kt:8-16` zahazuje diagnostiku finální brány), 5 střední (nekonzistentní stripping komentářů při textovém skenování zdrojů → falešné poplachy; předčasný return skrývající identity checků v `OperationalDomainAdequacyConformanceChecks`; nekonzistentní `MIN_CASES`; fragilní `!!` v `CompilerModuleAcceptance.kt:70`; fragilní `ClassLoader.getResource` detekce), 5 nízkých (kosmetika/pojmenování). Žádné skutečné TODO/FIXME/HACK.
