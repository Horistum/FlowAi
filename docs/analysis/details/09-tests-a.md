# Analýza testovací sady FlowAi — dávka A (94 souborů)

Rozsah: 94 explicitně vyjmenovaných souborů z `/home/user/FlowAi/src/test/kotlin/` (z celkových 188 v adresáři — zbytek pokrývá navazující dávka „B"). Analyzováno CELÝ obsah každého souboru, žádný sampling.

**Technická poznámka k buildu:** kořenový `build.gradle.kts` explicitně vyprazdňuje vlastní `sourceSets.test` (`kotlin.setSrcDirs(emptyList())`) — na první pohled to vypadá, že `src/test/kotlin` je „mrtvý" adresář mimo build. Ve skutečnosti `flow-conformance-kit/build.gradle.kts` tento adresář připojuje jako svůj vlastní test source set (`kotlin.sourceSets.test { kotlin.srcDirs(rootProject.file("src/test/kotlin"), rootProject.file("tests")) }`) a běží přes `useJUnitPlatform()`. Všech 94 souborů tedy **je** aktivně kompilováno a spouštěno jako testovací sada modulu `:flow-conformance-kit`, ne jako sirotčí kód. Framework je jednotně **Kotlin Test API (`kotlin.test.Test`) na JUnit 5 Platform**.

Celkem: **14 414 řádků**, **572 metod `@Test`** v 94 souborech (průměr ~6 testů/soubor, medián nižší kvůli několika extrémně velkým souborům).

---

## 1. Přehled — jaké typy testů převažují

Sada je hluboce bimodální a to je nejdůležitější strukturální zjištění celé analýzy:

**A) Skutečné testy standardizační vrstvy (Intent → Flow AST → Execution Plan → target manifest)** — cca 55–60 % souborů. Sem patří vysoce kvalitní, sémanticky bohaté testy: `CanonicalExecutionGraphAuthorityCutoverTests`, `WorkflowOwnershipTests`, `WorkflowFailureSemanticsTests`, `UniversalControlPolicyRequirementsTests`, `UniversalEffectStateTransitionModelTests`, `CrossTargetCheckoutProjectionTests`, `TargetRegistrySchemaAlignmentTests`, `ConditionalOrderingDependencyTests`, `EnvironmentSafetyProductionIntegrationTests`, `ScenarioNegationTokenBoundaryHonestyTests` aj. Tyto testy skutečně cvičí kompilátor, plánovač, projekční vrstvu a bezpečnostní hranice a mají smysluplné pozitivní i negativní scénáře.

**B) „RoadmapLifecycleAuthority" / procesní meta-testy (governance vlastního vývojového procesu)** — cca 15–16 souborů (`*RoadmapLifecycleAuthorityTests`, `*LifecycleTests` typu `CompilerModuleExtractionLifecycleTests`, `LanguageContractIntegrityLifecycleTests`, `RoadmapStreamTransitionAuthorityTests`, `ToolchainModernizationLifecycleTests`, `SemanticIntegrityRoadmapTests`...). Netestují kompilátor ani standard — testují **stavový automat nad YAML soubory popisujícími vlastní roadmapu/„work packages"/CI evidenci projektu** (fiktivní `runId`, `runNumber`, `exactHead`/`mergeCandidate` SHA hashe generované jako `'1'.repeat(40)`). Jde o self-hosting governance vrstvu implementovanou přímo v `src/main/kotlin` a otestovanou v `src/test/kotlin` — architektonicky neobvyklé, ale zjevně záměrné.

**C) „Falsification" / „Baseline" — externí evidenční meta-testy** — cca 10–12 souborů (`CertificateLifecycleFalsificationTests`, `SecretRotationFalsificationTests`, `InfrastructureLifecycleFalsificationTests`, `DatabaseMigrationRecoveryBaselineTests`, `HumanApprovalChangeControlBaselineTests`, `IncidentRemediationBaselineTests`...). Testují, že interní katalog „co je/není reprezentovatelné v modelu Flow" vůči korpusu reálných nástrojů (cert-manager, AWS Secrets Manager, OpenTofu...) odpovídá **napevno zakódovaným počtům** (`assertEquals(5, report.caseCount)`, `assertEquals(3, report.modelGapCount)`).

**D) Architektonické „fitness" testy přes grep zdrojového kódu** — technika používaná napříč cca 9 soubory (`CoreTargetProjectionBoundaryTests`, `DerivedViewAuthorityRetirementTests`, `MandatoryMaterializationAuthorityTests`, `ProjectionBindingContractTests`, `TargetNeutralProjectionPayloadTests`, `YamlParsingUnificationTests`, `CompilerAxisConformanceTests`, `AuthorityResponsibilityCatalogTests`, `CanonicalExecutionGraphAuthorityCutoverTests`) — testy čtou `.kt` soubory jako text a hledají/vylučují substringy („core balíček nesmí obsahovat řetězec 'jenkins'" apod.).

Poměr B+C (čistě procesní governance) k A+D (funkčnost/architektura) je zhruba **1:3 až 1:2.5** podle počtu souborů, ale vzhledem k tomu, že B skupina obsahuje několik extrémně dlouhých souborů (536, 417, 289, 289 řádků), je poměr **řádků kódu** blíž **1:1.8** — governance vrstva je nepřiměřeně těžká vzhledem ke svému skutečnému testovacímu přínosu.

---

## 2. Inventář souborů

Cesta ke všem souborům: `/home/user/FlowAi/src/test/kotlin/<název>`. Framework: Kotlin Test (`kotlin.test.Test`) přes JUnit 5 Platform, modul `:flow-conformance-kit`.

| Soubor | Řádky | @Test | Co skutečně testuje |
|---|---:|---:|---|
| AbstractTopologyMatrixAuthorityTests.kt | 99 | 4 | C0.2 topologická matice: pokrytí kind/dimension, polaritní flip matching authority, odmítnutí neznámých YAML polí |
| AbstractTopologyMatrixRoadmapLifecycleAuthorityTests.kt | 154 | 8 | Roadmap stavový automat pro C0.2 (implementing/validating/completed/invalid), reuse evidence odmítnut |
| AdapterArtifactRenderingProviderBehaviorTests.kt | 87 | 2 | Reálný end-to-end render Jenkinsfile z intentu; odmítnutí renderu při downgradované kompatibilitě |
| AdapterArtifactRenderingRoadmapLifecycleAuthorityTests.kt | 137 | 6 | Roadmap automat pro A0.6 artifact rendering milestone |
| AdapterCapabilityBindingAuthorityTests.kt | 258 | 9 | Binding authority: počet záznamů, missing/duplicitní evidence, nepodporovaný kanonický parametr, default hodnoty, end-to-end binding |
| AdapterContinuityCliEvidenceTests.kt | 79 | 2 | CLI evidence pro Jenkins workspace continuity (matched vs. blocked + review fallback) |
| AdapterContinuityEvidenceIntegrityTests.kt | 128 | 5 | Mutace continuity evidence dokumentu — duplicitní rodina, chybějící behavior evidence, forged promotion |
| AdapterContinuityRoadmapLifecycleAuthorityTests.kt | 120 | 5 | Roadmap automat pro A0.5 |
| AdapterContinuitySatisfactionAuthorityTests.kt | 166 | 8 | Plan-dependency → continuity requirement (workspace/value/state/durable-state), ambiguous/unresolved planning |
| AdapterControlEvidenceAnchorTests.kt | 50 | 1 | Jediný test: control evidence s neplatnou kotvou zdrojového kódu selže integrity |
| AdapterControlRoadmapLifecycleAuthorityTests.kt | 145 | 8 | Roadmap automat pro A0.4 |
| AdapterExecutableReferencePromotionTests.kt | 41 | 2 | Repository-backed promotion scope; neznámý scope odmítnut |
| AdapterPortfolioReassessmentTests.kt | 114 | 6 | Support-class reassessment (EXECUTABLE/NATIVE_LEAF/PROFILE_ONLY) vůči skutečnému target registry, ordering adapter cert po core closure |
| AdapterProfileEvidenceRoadmapLifecycleAuthorityTests.kt | 134 | 5 | Roadmap automat pro C0.3/C0.4 adapter profile evidence |
| AdapterRoadmapSequenceTransitionTests.kt | 40 | 4 | Čistá unit logika ordinal/alignment utility pro roadmap verze |
| AdapterTriggerCliEvidenceTests.kt | 145 | 3 | Cron/interval/timezone trigger přes CLI evidence (Jenkins OK, GH interval/timezone blocked) |
| AuthorityResponsibilityCatalogTests.kt | 86 | 4 | Grep `src/main` na třídy `*Authority`, musí být v ownership katalogu; duplicitní jméno/drift callerů |
| BoundedDomainCorpusRoadmapLifecycleAuthorityTests.kt | 120 | 5 | Roadmap automat pro C0.1 lokální korpus |
| CanonicalExecutionGraphAuthorityCutoverTests.kt | 299 | 7 | Digest stabilní vůči reorderingu, citlivý na sémantickou mutaci, validátor odhalí duplicity/cykly/dangling, digest-bound authorization |
| CanonicalExecutionPlanSemanticsTests.kt | 170 | 6 | Schema/enum parita node kinds, capability→kind klasifikace odolná vůči spoofing implementačních labelů |
| CanonicalIntentMeaningTests.kt | 235 | 6 | Kanonický význam stabilní napříč různými module bindings (argo vs. kube), unbound lowering |
| CanonicalModuleAuthorityTests.kt | 241 | 17 | Nejrozsáhlejší „čistý" unit test: schema shape, type coercion, unknown fields, default hodnoty, retry defaults, destructive safety |
| CertificateLifecycleFalsificationTests.kt | 187 | 9 | EF-05 falsification: hardcoded počty (5 cases/2 repos/4 representable/3 gap) + integrity negativy |
| CliDiagnosticReleaseHonestyTests.kt | 333 | 8 | Mix: reálné CLI testy (target-neutral, render gating) + čtení živých roadmap/release YAML s duplikovanou if/else logikou pro odvození fáze |
| CollisionSafeSemanticIdentityTests.kt | 148 | 6 | Hash-based identity, ale s napevno vypsanými hash suffixy jako očekávaným výstupem |
| CompilerAxisConformanceTests.kt | 275 | 11 | Architecture fitness: mutace kopie zdrojových souborů (string replace) prokazuje detekci bypassů konvergence |
| CompilerModuleExtractionLifecycleTests.kt | 289 | 20 | AR-03 extraction slices — 20 téměř identických `rejected()` volání nad generickou Map snapshotu |
| ConditionalOrderingDependencyTests.kt | 193 | 4 | Podmíněný producer smí zpětně vázat ordering bez value-read; ambiguous producer odmítnut |
| ConformanceQualityGateTests.kt | 28 | 2 | Tenký smoke: přesný seznam 2 gate jmen + all passed |
| CoreTargetProjectionBoundaryTests.kt | 181 | 7 | Budoucí target composable bez core změn; provider validace mismatch; grep „core nesmí znát built-in targets" |
| CritiqueGovernancePrecisionTests.kt | 57 | 2 | Lexical-context detekce CI-bias termínů (quoted „jenkins") — úzké pokrytí |
| CrossTargetCheckoutProjectionTests.kt | 218 | 7 | Git checkout sémantika zachována a vyrenderována správně napříč Jenkins/GH Actions/Tekton, konkrétní string asserty |
| DatabaseMigrationRecoveryBaselineTests.kt | 115 | 6 | EF-02 baseline meta-test: nelze přepsat snapshot, regrese detekována, lifecycle-status gating |
| DerivedViewAuthorityRetirementTests.kt | 148 | 5 | Compatibility view projektován přímo z autorizovaného grafu, ne přes legacy fasádu; grep na retirement SemanticActionGraph |
| EnvironmentSafetyProductionIntegrationTests.kt | 233 | 13 | Klasifikace prod/dev literálů, approval gating, unknown fail-closed, conditional approval scoping, CLI blokuje unsafe flow |
| ExecutionPlanCanonicalControlMaterializationTests.kt | 161 | 5 | Control requirement vázán na authored operation scope; widened scope odmítnut; ambiguous ownership fail-closed |
| ExternalCorpusLoaderTests.kt | 192 | 9 | Loader kontrakt: lifecycle cardinality, sha256 provenance, path traversal prevention, immutable git revision |
| FlowRetryParserIntegrityTests.kt | 58 | 3 | Retry policy parsing: validní round-trip, malformed hodnoty s přesnou cestou, duplicate/unknown klíč |
| GitHubActionsWorkspaceContinuityTests.kt | 116 | 2 | Scoped capability promotion přesně ohraničená, nepřepisuje obecnou registry claim |
| HistoricalConformanceLifecycleMonotonicityTests.kt | 139 | 4 | Historické položky zůstávají validní i po dokončení pozdějších — meta-governance |
| HumanApprovalChangeControlBaselineTests.kt | 147 | 6 | EF-09 baseline meta-test (stejný vzor jako DB migration) |
| IncidentRemediationBaselineTests.kt | 128 | 6 | EF-04 baseline meta-test |
| InfrastructureLifecycleBaselineTests.kt | 132 | 6 | EF-08 baseline meta-test |
| InfrastructureLifecycleFalsificationTests.kt | 211 | 12 | EF-08 detail: 8 case, hardcoded outcome mapa + integrity negativy |
| IntentFrontendConvergenceTests.kt | 126 | 5 | Intent pipeline odpovídá manuálnímu kroku-za-krokem; formátování nemění compiled meaning; invalid intent stopne před flow planning |
| IntentLoweringWorkflowMembershipIntegrityTests.kt | 101 | 3 | Lowering evidence: step-membership digest odpovídá schema kontraktu, tampering odmítnut |
| JsonSchemaSmokeValidatorTests.kt | 88 | 4 | Vlastní JSON-schema „smoke" validátor: unknown assertions, additionalProperties, if/then/else, uniqueItems+$ref |
| LanguageContractIntegrityLifecycleTests.kt | 289 | 32 | **Nejvyšší počet testů v dávce.** AR-04 language integrity slice — masivně opakovaný `rejected()` vzor |
| MainMissingReviewFixesTests.kt | 130 | 4 | Různorodý regression bucket: GH JSON literal, Jenkins error-handler wrapping, schema $id normalizace |
| MandatoryMaterializationAuthorityTests.kt | 190 | 5 | Invalid evidence blokuje generator ještě před voláním; future target funguje přes mandatory authority; grep na bypass |
| ManifestHonestyTests.kt | 102 | 5 | DB/notify vyžadují adapter materialization (ne tichý native); rollback jen s propojenou evidencí |
| ModuleExtractionLifecycleFixtures.kt | 46 | 0 | **Není test** — sdílená fixture (`moduleExtractionCandidateSnapshot()`) pro lifecycle testy |
| NormalizationPortabilityHonestyTests.kt | 115 | 4 | AI normalizace neuvádí fabrikované „likely-full" portability claims; generic containerRegistry zachován |
| OperationalDomainAdequacyConformanceChecksTests.kt | 32 | 1 | Jediný agregátní test: C1.0 inventář odpovídá passing checks |
| OperationalDomainCorpusLoaderTests.kt | 51 | 2 | DP corpus loader + baseline/mutation polarity flip |
| PlannerCapabilityConstraintTests.kt | 123 | 6 | Tekton blokuje approval, Jenkins povoluje, GH partial topology blocked (strict i non-strict) |
| PlannerSafetyDefenseInDepthTests.kt | 42 | 1 | Jediný test: planner nezávisle odmítá neznámý module/action i bez předchozí validace |
| ProjectionBindingContractTests.kt | 399 | 9 | Binding template kontrakt, resolver, edge renderery, serializace; obsahuje privátní reimplementaci binding→status mapy v testu |
| ProjectionBindingSerializationBoundaryTests.kt | 17 | 1 | **Extrémně úzké** — testuje jen LITERAL kind serializaci |
| ProjectionStabilitySmokeTests.kt | 90 | 3 | Deterministický render, honest REVIEW_ONLY fallback místo fake pipeline syntaxe |
| ProviderApprovalMetadataHonestyTests.kt | 41 | 1 | Jediný test: semantic approval job nikdy nefabrikuje provider approval payload |
| ProviderBackedApprovalTopologyIdentityTests.kt | 294 | 9 | Native-only Jenkins approval payload, forged native bez payloadu neexekutovatelný, slug-collision identity uniqueness |
| PublishedSchemaContractTests.kt | 27 | 2 | Každé publikované schema má právě jednoho vlastníka; žádné netvrdí plnou validity authority |
| ReferenceScenarioMatrixTests.kt | 178 | 6 | Pokrytí kind/dimension scénářů, flagship scénář bez raw shell, realistic build-test-deploy REVIEW_ONLY vs FAIL_FAST |
| RetainedDerivedProjectionIntegrityTests.kt | 60 | 2 | Forged task/approval dependency projection odmítnuta |
| ReviewRegressionTests.kt | 93 | 3 | Deploy-with-approval zůstává ADAPTER_REQUIRED; Groovy regex escaping; interpolation paths |
| ReviewedAiProposalCliConvergenceTests.kt | 34 | 1 | Jediný CLI smoke test pro `normalize --lower` |
| ReviewedAiProposalFrontendConvergenceTests.kt | 324 | 8 | AI proposal a Intent YAML konvergují na identický graf; defensive-copy test caller-owned kolekcí |
| RoadmapStreamTransitionAuthorityTests.kt | 536 | 13 | **Nejdelší soubor v dávce.** 5 řetězených fixture builderů (A1.0→C0.2→C0.3→C0.4→AR0.1) přes string-replace na YAML |
| RuntimeCommandSemanticBoundaryTests.kt | 56 | 2 | Free-form runtime command text odmítnut napříč BUILD/TEST/PACKAGE/RUN_COMMAND |
| SafetyBoundaryHandlerTests.kt | 78 | 2 | Regression guard (v0.8.5 gap): destruktivní akce v result-handleru stále vyžaduje approval |
| ScenarioNegationTokenBoundaryHonestyTests.kt | 227 | 16 | Rozsáhlá NLP hranice: token boundary, negace, entity-slot bounding, contradiction detection |
| ScenarioPackQualityAnalyzerTests.kt | 28 | 2 | Aktuální packy projdou quality gate; duplicate pack ID detekován |
| SchemaContractIntegrityLifecycleTests.kt | 65 | 3 | AR-04B/C schema-integrity slice — stejný governance vzor jako výše, kratší |
| SecretRotationFalsificationTests.kt | 114 | 8 | EF-06 falsification, hardcoded outcome mapa + 3 integrity negativy |
| SemanticEquivalenceHardeningTests.kt | 153 | 5 | Semantic observation authority: contradictory mutation, duplicate evidence, polarity independence napříč fixtures |
| SemanticEquivalenceRoadmapLifecycleAuthorityTests.kt | 206 | 6 | Roadmap automat C0.3 semantic equivalence |
| SemanticIntegrityRoadmapTests.kt | 170 | 7 | Terminal SI-08 stav: cross-check roadmap YAML + **doslovný prose text v REPORT.md** — extrémně křehké |
| SemanticKernelCompositionTests.kt | 17 | 1 | Reflektivní test: dvě třídy z jednoho jaru, kompilátor nerekompiluje kernel — křehké (`codeSource.location`) |
| SourceDeclarationLifecycleTests.kt | 108 | 8 | AR-04A language activation slice — stejný Map-mutation `rejected()` vzor |
| SourceInputDiagnosticTests.kt | 48 | 3 | Duplicitní safety declaration → žádné artefakty; parser/lex chyby jako INVALID_INPUT ne INTERNAL_FAILURE |
| StandardCapabilityNeutralityTests.kt | 106 | 6 | BUILD_IMAGE/PUSH_IMAGE bez Docker terminologie; retired KUBERNETES_MAINTENANCE alias confined |
| TargetCapabilityDegradationAnalyzerTests.kt | 162 | 6 | SUPPORTED/DEGRADED/BLOCKED tiers; **1 test má zavádějící název** (viz sekce 4) |
| TargetMaturityPublicationTests.kt | 180 | 6 | Maturity stage mapa per target; missing evidence nemůže zůstat BEHAVIORALLY_CERTIFIED |
| TargetNativeProjectionArchitectureTests.kt | 265 | 9 | Native projection catalog kontrakt: duplicity, missing required binding, unresolved vs. empty string |
| TargetNeutralProjectionPayloadTests.kt | 126 | 5 | Neznámý payload kind akceptován bez core změn; core sources neobsahují built-in payload kinds (grep) |
| TargetRegistrySchemaAlignmentTests.kt | 176 | 8 | Schema a produkční loader souhlasí na 5 různých malformed registry případech |
| ToolchainModernizationLifecycleTests.kt | 417 | 16 | Kotlin/Gradle/JDK migrace sequencing — stejná string-replace YAML fragility jako RoadmapStreamTransition |
| UniversalControlPolicyRequirementsTests.kt | 370 | 15 | **Jeden z nejsilnějších souborů** — canonical control requirement napříč inventáři, PENDING vs BLOCKED, forged assessment |
| UniversalEffectStateTransitionModelTests.kt | 225 | 7 | 3 doménové oblasti sdílí jeden typed effect model; resource-state transitions; forged effect evidence odmítnuta |
| WorkflowBoundaryEvidenceTests.kt | 70 | 5 | Unit testy value typu WorkflowBoundaryEvidence: structural validity, follows()/distinctFrom() |
| WorkflowFailureSemanticsTests.kt | 275 | 8 | `on error` handler jako first-class v canonical graph; JSON kontrakt; digest mutace + root-overlap validace |
| WorkflowOwnershipTests.kt | 443 | 9 | **Nejsilnější soubor v dávce** — multi-workflow membership/roots/triggers, storage-permutation invariance, CLI round-trip |
| YamlParsingUnificationTests.kt | 64 | 3 | Sdílená YAML hranice; jeden Jackson-YAML vlastník; grep na absenci legacy MiniYaml |

---

## 3. Kvalitativní hodnocení

### Tautologické / cirkulární testy
- **`ProjectionBindingContractTests.kt` (řádky 377–397)** — privátní extension `ProjectionBinding.asManifestBinding()` v testu ručně reimplementuje logiku přiřazení `resolutionStatus` podle druhu bindingu. Test tak částečně ověřuje vlastní kopii pravidla, ne produkční kód, který skutečné přiřazení dělá jinde (v resolveru). Pokud se produkční pravidlo změní, test může zůstat zeleně, protože porovnává hlavně proti vlastní kopii.
- **`CliDiagnosticReleaseHonestyTests.releaseMetadataAxesCorrectionAndClosureLifecyclesAreConsistent`** (řádky 182–221) — test si sám odvozuje očekávanou fázi (`expectedPhase`, `expectedClosureStatus`, `expectedTrackStatus`...) pomocí `when` bloku, který kopíruje pravděpodobnou produkční logiku, a pak to porovnává s `report.*`. Je to blíž modelovému duplikátu produkčního rozhodování než nezávislému ověření.
- Desítky „RoadmapLifecycleAuthority" testů (bod B v sekci 1) v podstatě znovu implementují část stavového automatu jako fixture a pak ověřují, že produkční automat vrátí stejný výsledek — legitimní pro *negativní* větve (mutace → FAIL), ale pro pozitivní větev jde o duplicitu logiky mezi testem a produkcí.

### Slabé/hardcoded asserty
- **Golden-count testy** — `CertificateLifecycleFalsificationTests` (`assertEquals(5, report.caseCount)`, `assertEquals(2, report.distinctRepositoryCount)`...), stejně `SecretRotationFalsificationTests`, `InfrastructureLifecycleFalsificationTests`. Přidání jednoho externího case do korpusu rozbije test bez ohledu na to, zda je logika správná — test kontroluje počet, ne invariantu.
- **`CollisionSafeSemanticIdentityTests`** — pevně zakódované hash suffixy (`"control.approval.production--76f694a1ea83"`). Komentář v kódu vysvětluje proč (ochrana proti collision), ale jde o „captured output" test — při jakékoli legitimní změně hashovacího algoritmu (i bezpečné) se rozbije bez vysvětlení příčiny v assert message.
- **`SemanticIntegrityRoadmapTests.repositoryReportMatchesCompletedSemanticStreamAndArtifactContracts`** (řádky 24–52) — assertuje **doslovný text prózy** v `REPORT.md` (`"Completed semantic-integrity item: ..."`). Jde o nejkřehčí test v celé dávce — jakákoli kosmetická úprava markdown reportu (typo fix, reformattace) test rozbije.
- **`SemanticKernelCompositionTests`** — spoléhá na `Class::class.java.protectionDomain.codeSource.location`, což je fragilní vůči jakékoli změně packagingu (shadow jar, native-image, jiný classloader) a nemusí odrážet skutečnou architektonickou regresi.
- **`ConformanceQualityGateTests`** — assertuje přesné pořadí 2 položek v seznamu (`listOf(CORE_CONTRACT_CHECK, SCENARIO_PACK_QUALITY)`), citlivé na reordering bez sémantického významu.

### Duplicity
- **Fixture-building boilerplate** (`evidence(runNumber, runId, exactDigit, mergeDigit)` generující `exactDigit.repeat(40)` jako fake SHA) se opakuje téměř identicky nejméně v **10 souborech**: `AbstractTopologyMatrixRoadmapLifecycleAuthorityTests`, `AdapterArtifactRenderingRoadmapLifecycleAuthorityTests`, `AdapterContinuityRoadmapLifecycleAuthorityTests`, `AdapterControlRoadmapLifecycleAuthorityTests`, `AdapterProfileEvidenceRoadmapLifecycleAuthorityTests`, `BoundedDomainCorpusRoadmapLifecycleAuthorityTests`, `HistoricalConformanceLifecycleMonotonicityTests`, `SemanticEquivalenceRoadmapLifecycleAuthorityTests`, `RoadmapStreamTransitionAuthorityTests`, `ToolchainModernizationLifecycleTests`. Přitom `ModuleExtractionLifecycleFixtures.kt` dokazuje, že autoři umí sdílet fixture přes samostatný soubor — u této rodiny to ale udělané není.
- **Generické `section()/records()/rejected()` helpery** operující nad `Map<Any?, Any?>` jsou téměř identicky přepsané v `CompilerModuleExtractionLifecycleTests`, `LanguageContractIntegrityLifecycleTests`, `SchemaContractIntegrityLifecycleTests`, `SourceDeclarationLifecycleTests` — čtyři nezávislé kopie stejného pattern-matchingu nad neotypovanými mapami.
- Vzor „grep zdrojáků na zakázaný token" (sekce 1D) je fakticky reimplementovaný v 7+ souborech bez sdílené utility.

### Chybějící edge-case pokrytí
- **`ProjectionBindingSerializationBoundaryTests.kt`** — 17 řádků, 1 test, pokrývá jen `ProjectionBindingKind.LITERAL`. Sourcecode (viz `ProjectionBindingContractTests`) má nejméně 9 druhů bindingů (SECRET, TASK_OUTPUT, TARGET_EXPRESSION...) — serializační hranice pro ně není samostatně ověřena.
- **`AdapterControlEvidenceAnchorTests.kt`** — jediný test v celém souboru (50 řádků), pouze negativní scénář (chybějící kotva). Pozitivní/happy-path evidence anchor test chybí v tomto souboru (i když může existovat jinde).
- **`PlannerSafetyDefenseInDepthTests.kt`**, **`ProviderApprovalMetadataHonestyTests.kt`** — po jednom testu na soubor; „defense in depth" princip z názvu naznačuje víc vrstev, ale testována je jen jedna cesta.
- **`CritiqueGovernancePrecisionTests.kt`** — název slibuje „precision" analýzu, ale jen 2 testy pokrývají 2 konkrétní lexikální kontexty; false-positive prevence (např. text v komentáři, v jiném přirozeném jazyce) netestována.

### Hardcoded data
- Systematické používání literálů typu `"1111111111111111111111111111111111111111"` (40× stejný znak) jako fingovaný git SHA napříč desítkami testů — funkčně v pořádku pro testovací účely, ale znesnadňuje grep/debug (nelze odlišit jeden fake commit od druhého kromě posledního znaku, což autoři řeší parametrizací znaku — funguje, ale je to křehké ad-hoc řešení místo sdíleného generátoru testovacích SHA).
- `RoadmapStreamTransitionAuthorityTests` a `ToolchainModernizationLifecycleTests` vkládají do fixture YAML doslovné číselné `runId` v řádu 3×10^10 — magic numbers bez vysvětlujícího komentáře, jejich vzájemná konzistence (musí být „later" než předchozí) je ověřována jen implicitně přes produkční kód.

---

## 4. Zjištěné chyby v testovacím kódu

| Soubor:řádek | Závažnost | Popis |
|---|---|---|
| `TargetCapabilityDegradationAnalyzerTests.kt:117` | Nízká (dokumentační) | Test `generatedPartialTektonManifestIsDegradedAndReviewable` — název slibuje výsledek „degraded and reviewable", ale assert (řádek 121–123) ověřuje `TargetCapabilityDegradationStatus.BLOCKED` a `assertFalse(report.valid)`. Název testu neodpovídá skutečně testovanému chování — matoucí při čtení reportu z CI. |
| `ProjectionBindingContractTests.kt:377-397` | Střední | Privátní `ProjectionBinding.asManifestBinding()` duplikuje produkční mapovací logiku uvnitř testu místo volání skutečné produkční implementace — riziko, že test zůstane zelený i po regresi v produkčním mapování, pokud se produkční a testovací kopie rozejdou nezávisle. |
| `SemanticIntegrityRoadmapTests.kt:35,39,51` | Střední (křehkost) | Assert na doslovný text v `REPORT.md` prose (`"Completed semantic-integrity item: ..."`). Jakákoli editorská úprava dokumentace (ne kódu) rozbije test bez skutečné regrese. |
| `SemanticKernelCompositionTests.kt:11-15` | Nízká–střední (křehkost) | Test spoléhá na `protectionDomain.codeSource.location` k ověření, že dvě třídy pochází ze stejného JARu a compiler modul nerekompiluje kernel. Pod jiným packaging modelem (fat JAR, GraalVM native-image, testovací classloader s in-memory třídami) může throw/false-negative i bez skutečné architektonické regrese. |
| `RoadmapStreamTransitionAuthorityTests.kt` (celý soubor, 536 řádků) | Střední (udržovatelnost) | Pět řetězených `createXBoundary()` funkcí, kde každá volá tu předchozí a dál mutuje YAML string-replacem (`require(oldValue in original)`). Selhání jednoho `require` uprostřed řetězu způsobí nesrozumitelnou chybovou hlášku bez souvislosti s testovaným chováním — a jakákoli neškodná změna formátu fixture YAML (mezera, pořadí klíčů) shodí celý řetězec testů. |
| `ConformanceQualityGateTests.kt:12-18` | Nízká | `assertEquals(listOf(CORE_CONTRACT_CHECK, SCENARIO_PACK_QUALITY), names)` — test na přesné pořadí prvků v seznamu, kde pořadí pravděpodobně nemá sémantický význam; robustnější by bylo `assertEquals(setOf(...), names.toSet())` pokud pořadí není kontrakt. |
| `CertificateLifecycleFalsificationTests.kt`, `SecretRotationFalsificationTests.kt`, `InfrastructureLifecycleFalsificationTests.kt` | Nízká (návrh) | Testy kombinují ověření *obsahu* (konkrétní outcome per case) s ověřením *přesného počtu* (`caseCount`, `representableCount`). Přidání nového externího case vyžaduje upravit tyto testy současně s produkčními daty — vysoká vazba mezi testem a objemem korpusu bez jasného důvodu, proč počet musí být přesně fixován. |

Žádné nálezy nenaznačují, že by testy testovaly nesprávnou věc kvůli logické chybě v assertu (např. obrácená polarita, špatně pojmenovaná proměnná) — kvalita samotné testovací logiky je v drtivé většině souborů vysoká. Nalezené problémy jsou převážně **architektonické/údržbové** (křehkost, duplicita, matoucí pojmenování), ne funkční bugy v testech.

---

## 5. Architektonické pozorování

1. **Self-hosting governance je neobvykle rozsáhlá.** Produkční kód v `src/main/kotlin/org/flowlang/{roadmap,conformance,architecture}` implementuje desítky tříd `*Authority`/`*Lifecycle`, které parsují YAML popisující vlastní vývojový proces (verze milníků jako `C0.2`, `A0.5`, `AR-03`, `SI-08`, CI run evidenci) a validují jejich vzájemnou konzistenci. Tato dávka (94/188 souborů) obsahuje minimálně 16 testovacích souborů (a přes 2 900 řádků) věnovaných výhradně této vrstvě — to je zhruba **20 % objemu kódu této dávky**, které netestuje nic z Intent→AST→Plan→Manifest řetězce, ale ověřuje, že projektová bookkeeping metadata jsou interně konzistentní. Je to důsledný a promyšlený systém (fail-closed, žádné reuse evidence, žádné „přeskočení" kroku), ale znamená to, že velká část testovací suity je vázaná na aktuální stav roadmapy repozitáře — každý postup projektu vyžaduje synchronizovanou úpravu těchto testů.

2. **Duplicitní vzor bez sdílené abstrakce.** Přestože `ModuleExtractionLifecycleFixtures.kt` ukazuje, že tým umí extrahovat sdílené fixtures, rodina „RoadmapLifecycleAuthority" testů (10+ souborů) tento vzor nevyužívá a místo toho kopíruje téměř identický `evidence()`/`implementingInput()`/`completedInput()` boilerplate. Vytvoření sdíleného `RoadmapLifecycleTestKit` by snížilo objem o odhadem 30–40 % v této skupině.

3. **Testy jako spouštěč architektonických invariant přes textové vyhledávání.** Vzor „načti zdrojový soubor jako text, ověř absenci/přítomnost substringu" (`CoreTargetProjectionBoundaryTests`, `DerivedViewAuthorityRetirementTests`, `YamlParsingUnificationTests`, `MandatoryMaterializationAuthorityTests`, `TargetNeutralProjectionPayloadTests`, `CompilerAxisConformanceTests`) je funkční, ale je to nejslabší dostupná forma architektonického testu — sémanticky ekvivalentní obchvat s jiným zápisem (např. String template, jiný identifikátor) projde nepovšimnutý, zatímco neškodný refaktoring (rename komentáře) může falešně selhat. Vhodnější by byla static-analysis/AST-based kontrola (např. Detekt custom rule), ale dá se chápat jako pragmatický kompromis bez zavádění další závislosti.

4. **Poměr skutečné funkčnosti vs. procesní governance.** Když se z 94 souborů odečtou (B) roadmap-lifecycle (16), (C) externí falsification/baseline (10) a započítá se `ModuleExtractionLifecycleFixtures.kt` jako ne-test, zbývá **67 souborů (71 %)**, které testují skutečnou funkčnost kompilátoru, plánovače, adaptérů a bezpečnostních hranic. To je zdravý poměr — governance vrstva je menšinová co do počtu souborů, ale kvůli několika extrémně dlouhým souborům (536, 417, 289+289 řádků) zabírá neúměrně velký podíl **řádků** (odhadem 35–40 % z 14 414 řádků této dávky).

5. **Kvalita funkčních testů je nadprůměrná.** Soubory jako `WorkflowOwnershipTests`, `UniversalControlPolicyRequirementsTests`, `WorkflowFailureSemanticsTests`, `CrossTargetCheckoutProjectionTests`, `EnvironmentSafetyProductionIntegrationTests`, `TargetRegistrySchemaAlignmentTests`, `ScenarioNegationTokenBoundaryHonestyTests` prokazují disciplinovaný přístup: pozitivní i negativní scénáře, „storage-permutation invariance" testy (pořadí v YAML nesmí měnit sémantiku), fail-closed testování na hranicích bezpečnosti/schválení, testování digest/hash invariant při syntaktických mutacích. To je vzor, který by měl být standardem i pro governance vrstvu, ale tam převažuje spíš „ověř přesný výstup dnešního stavu" než „ověř invariantu."

---

## 6. Statistická tabulka

Řazeno sestupně podle počtu řádků.

| Soubor | Řádky | @Test |
|---|---:|---:|
| RoadmapStreamTransitionAuthorityTests.kt | 536 | 13 |
| WorkflowOwnershipTests.kt | 443 | 9 |
| ToolchainModernizationLifecycleTests.kt | 417 | 16 |
| ProjectionBindingContractTests.kt | 399 | 9 |
| UniversalControlPolicyRequirementsTests.kt | 370 | 15 |
| CliDiagnosticReleaseHonestyTests.kt | 333 | 8 |
| ReviewedAiProposalFrontendConvergenceTests.kt | 324 | 8 |
| CanonicalExecutionGraphAuthorityCutoverTests.kt | 299 | 7 |
| ProviderBackedApprovalTopologyIdentityTests.kt | 294 | 9 |
| CompilerModuleExtractionLifecycleTests.kt | 289 | 20 |
| LanguageContractIntegrityLifecycleTests.kt | 289 | 32 |
| CompilerAxisConformanceTests.kt | 275 | 11 |
| WorkflowFailureSemanticsTests.kt | 275 | 8 |
| TargetNativeProjectionArchitectureTests.kt | 265 | 9 |
| AdapterCapabilityBindingAuthorityTests.kt | 258 | 9 |
| CanonicalModuleAuthorityTests.kt | 241 | 17 |
| CanonicalIntentMeaningTests.kt | 235 | 6 |
| EnvironmentSafetyProductionIntegrationTests.kt | 233 | 13 |
| ScenarioNegationTokenBoundaryHonestyTests.kt | 227 | 16 |
| UniversalEffectStateTransitionModelTests.kt | 225 | 7 |
| CrossTargetCheckoutProjectionTests.kt | 218 | 7 |
| InfrastructureLifecycleFalsificationTests.kt | 211 | 12 |
| SemanticEquivalenceRoadmapLifecycleAuthorityTests.kt | 206 | 6 |
| ExternalCorpusLoaderTests.kt | 192 | 9 |
| ConditionalOrderingDependencyTests.kt | 193 | 4 |
| MandatoryMaterializationAuthorityTests.kt | 190 | 5 |
| CertificateLifecycleFalsificationTests.kt | 187 | 9 |
| CoreTargetProjectionBoundaryTests.kt | 181 | 7 |
| TargetMaturityPublicationTests.kt | 180 | 6 |
| ReferenceScenarioMatrixTests.kt | 178 | 6 |
| TargetRegistrySchemaAlignmentTests.kt | 176 | 8 |
| SemanticIntegrityRoadmapTests.kt | 170 | 7 |
| CanonicalExecutionPlanSemanticsTests.kt | 170 | 6 |
| AdapterContinuitySatisfactionAuthorityTests.kt | 166 | 8 |
| TargetCapabilityDegradationAnalyzerTests.kt | 162 | 6 |
| ExecutionPlanCanonicalControlMaterializationTests.kt | 161 | 5 |
| SemanticEquivalenceHardeningTests.kt | 153 | 5 |
| CollisionSafeSemanticIdentityTests.kt | 148 | 6 |
| DerivedViewAuthorityRetirementTests.kt | 148 | 5 |
| HumanApprovalChangeControlBaselineTests.kt | 147 | 6 |
| AdapterTriggerCliEvidenceTests.kt | 145 | 3 |
| AdapterControlRoadmapLifecycleAuthorityTests.kt | 145 | 8 |
| AdapterArtifactRenderingRoadmapLifecycleAuthorityTests.kt | 137 | 6 |
| AdapterProfileEvidenceRoadmapLifecycleAuthorityTests.kt | 134 | 5 |
| InfrastructureLifecycleBaselineTests.kt | 132 | 6 |
| MainMissingReviewFixesTests.kt | 130 | 4 |
| AdapterContinuityEvidenceIntegrityTests.kt | 128 | 5 |
| IncidentRemediationBaselineTests.kt | 128 | 6 |
| IntentFrontendConvergenceTests.kt | 126 | 5 |
| TargetNeutralProjectionPayloadTests.kt | 126 | 5 |
| PlannerCapabilityConstraintTests.kt | 123 | 6 |
| AdapterPortfolioReassessmentTests.kt | 114 | 6 |
| SecretRotationFalsificationTests.kt | 114 | 8 |
| GitHubActionsWorkspaceContinuityTests.kt | 116 | 2 |
| NormalizationPortabilityHonestyTests.kt | 115 | 4 |
| DatabaseMigrationRecoveryBaselineTests.kt | 115 | 6 |
| BoundedDomainCorpusRoadmapLifecycleAuthorityTests.kt | 120 | 5 |
| AdapterContinuityRoadmapLifecycleAuthorityTests.kt | 120 | 5 |
| StandardCapabilityNeutralityTests.kt | 106 | 6 |
| ManifestHonestyTests.kt | 102 | 5 |
| IntentLoweringWorkflowMembershipIntegrityTests.kt | 101 | 3 |
| SourceDeclarationLifecycleTests.kt | 108 | 8 |
| AbstractTopologyMatrixAuthorityTests.kt | 99 | 4 |
| ProjectionStabilitySmokeTests.kt | 90 | 3 |
| ReviewRegressionTests.kt | 93 | 3 |
| JsonSchemaSmokeValidatorTests.kt | 88 | 4 |
| AdapterArtifactRenderingProviderBehaviorTests.kt | 87 | 2 |
| AuthorityResponsibilityCatalogTests.kt | 86 | 4 |
| AdapterContinuityCliEvidenceTests.kt | 79 | 2 |
| SafetyBoundaryHandlerTests.kt | 78 | 2 |
| WorkflowBoundaryEvidenceTests.kt | 70 | 5 |
| SchemaContractIntegrityLifecycleTests.kt | 65 | 3 |
| YamlParsingUnificationTests.kt | 64 | 3 |
| FlowRetryParserIntegrityTests.kt | 58 | 3 |
| RetainedDerivedProjectionIntegrityTests.kt | 60 | 2 |
| CritiqueGovernancePrecisionTests.kt | 57 | 2 |
| RuntimeCommandSemanticBoundaryTests.kt | 56 | 2 |
| OperationalDomainCorpusLoaderTests.kt | 51 | 2 |
| AdapterControlEvidenceAnchorTests.kt | 50 | 1 |
| SourceInputDiagnosticTests.kt | 48 | 3 |
| ModuleExtractionLifecycleFixtures.kt | 46 | 0 |
| PlannerSafetyDefenseInDepthTests.kt | 42 | 1 |
| ProviderApprovalMetadataHonestyTests.kt | 41 | 1 |
| AdapterExecutableReferencePromotionTests.kt | 41 | 2 |
| AdapterRoadmapSequenceTransitionTests.kt | 40 | 4 |
| ReviewedAiProposalCliConvergenceTests.kt | 34 | 1 |
| OperationalDomainAdequacyConformanceChecksTests.kt | 32 | 1 |
| ConformanceQualityGateTests.kt | 28 | 2 |
| ScenarioPackQualityAnalyzerTests.kt | 28 | 2 |
| PublishedSchemaContractTests.kt | 27 | 2 |
| HistoricalConformanceLifecycleMonotonicityTests.kt | 139 | 4 |
| SemanticKernelCompositionTests.kt | 17 | 1 |
| ProjectionBindingSerializationBoundaryTests.kt | 17 | 1 |

**Součet (94 souborů):** 14 414 řádků, 572 metod `@Test`.

---

## Shrnutí

Přečteno bylo všech 94 přidělených souborů (14 414 řádků, 572 `@Test` metod), kompilovaných a spouštěných jako testovací sada modulu `:flow-conformance-kit` (Kotlin Test na JUnit 5 Platform); adresář `src/test/kotlin` na první pohled vypadá jako vyřazený z root buildu, ve skutečnosti je ale explicitně připojen jinam — není to mrtvý kód.

Sada je bimodální: **cca 71 % souborů (67/94)** testuje skutečnou standardizační vrstvu Intent→AST→Plan→Manifest a je většinou vysoce kvalitní — dobré pozitivní/negativní páry, invariance vůči storage-permutaci, fail-closed testování bezpečnostních a approval hranic (`WorkflowOwnershipTests`, `UniversalControlPolicyRequirementsTests`, `EnvironmentSafetyProductionIntegrationTests`, `CrossTargetCheckoutProjectionTests`). Zbylých ~27 souborů je procesní meta-governance: (a) `*RoadmapLifecycleAuthority`/`*Lifecycle` testy validují vlastní YAML bookkeeping vývojového procesu (verze milníků, fingované CI run ID/SHA), (b) `*Falsification`/`*Baseline` testy ověřují napevno zakódované počty (case/representable/gap) vůči korpusu externích nástrojů. Tato skupina zabírá kvůli několika extrémně dlouhým souborům (536, 417, 289+289, 370, 333 řádků) neúměrně velký podíl objemu — odhadem 35–40 % řádků dávky.

Hlavní systémové nálezy: (1) masivní duplikace fixture-building kódu (`evidence()`, `section()/records()/rejected()`) napříč ~10–15 souborů bez sdílené abstrakce, ač vzor sdílení existuje (`ModuleExtractionLifecycleFixtures.kt`); (2) křehké testy vázané na doslovný text (REPORT.md prose v `SemanticIntegrityRoadmapTests`), na classloader detaily (`SemanticKernelCompositionTests`) nebo na string-replace YAML řetězce (`RoadmapStreamTransitionAuthorityTests`, `ToolchainModernizationLifecycleTests`); (3) architektonické fitness testy implementované jako grep zdrojového textu (7+ souborů) místo AST analýzy; (4) jeden zavádějící název testu (`TargetCapabilityDegradationAnalyzerTests`); (5) test v `ProjectionBindingContractTests` duplikující produkční logiku uvnitř testovacího souboru. Žádné logické bugy v samotných assertech nalezeny nebyly.
