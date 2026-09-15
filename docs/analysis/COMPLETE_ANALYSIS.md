# Kompletní analýza repozitáře FlowAi (Horistum)

**Datum analýzy:** 2026-09-15
**Rozsah:** Celý repozitář — 683 Kotlin souborů (~117 000 řádků: ~76 000 produkční kód, ~28 800 testy), 1571 souborů celkem (411 YAML, 263 Markdown, zbytek konfigurace/schémata/zdroje).
**Metoda:** Deset paralelních specializovaných analytických průchodů pokrývajících každý soubor produkčního zdrojového kódu a testovací sady beze zbytku, plus přímé prostudování architektury buildu, governance dokumentace a menších modulů. Podrobné nálezy jsou v `docs/analysis/details/01–10`.

---

## 1. Executive summary

FlowAi (produktově "Horistum") je **AI-first standardizační vrstva pro DevOps/IT automatizaci**. Základní myšlenka: lidský nebo AI-generovaný popis automatizačního záměru ("nasaď billing-api do produkce se schválením") se nesmí přímo přeložit na spustitelný Jenkinsfile/GitHub Actions workflow — musí projít přes stabilní, cílově-neutrální model (Standard Intent → Flow AST → Execution Plan → Target Manifest), který je validovaný a auditovatelný v každém kroku, než se vůbec zváží konkrétní vendor výstup. Projekt se explicitně vymezuje proti tomu, aby se stal runtime executorem, SDK-first platformou nebo pluginovým frameworkem — jde čistě o standardizační a validační vrstvu.

Toto architektonické jádro je **realizováno důsledně a na vysoké technické úrovni**. Napříč statisíci řádky kódu se opakuje řada vyspělých vzorů: "fail-closed" jako defaultní chování (neznámá hodnota nikdy neznamená "bezpečné"), "unforgeable evidence" (rozhodnutí je typově svázáno s důkazem, který k němu vedl — nelze si jen tak vytvořit `Accepted` bez proběhlé validace), a opakované nezávislé přepočítávání kritických invariantů (topologie, kontrolní požadavky, digest grafu) jako obrana proti driftu. V celém produkčním kódu (~76 000 řádků) se prakticky nevyskytují nebezpečné `!!` force-unwrapy, prázdné catch bloky ani TODO/FIXME/HACK komentáře — to je u projektu tohoto rozsahu neobvyklé a svědčí o disciplinovaném vývoji.

Zároveň analýza odhalila **jednu kritickou bezpečnostní chybu** (Groovy code injection do generovaného Jenkinsfile), několik dalších vysoce závažných logických chyb v bezpečnostních hranicích (nekonzistentní detekce "produkčního prostředí", díra v pokrytí schvalovacích kontrol, chybějící validace operátorů) a výrazný **systémový architektonický problém**: repozitář v sobě nese enormní, opakovaně duplikovanou vrstvu procesní "governance" (sledování vlastního vývojového postupu formou roadmap/work-package YAML souborů ověřovaných desítkami tříd a stovkami testů), která v několika vrstvách zabírá 15–40 % objemu kódu, aniž by testovala nebo chránila chování produktu.

**Celkový verdikt:** Jádro produktu (parser → AST → validátor → planner → compiler → generators/targets) je kvalitní, bezpečnostně uvážlivý a architektonicky čistý standard. Kolem něj ale narostla neúměrně těžká sebekontrolní/procesní vrstva, která zvyšuje náklady na údržbu, ztěžuje orientaci a v part relativizuje samotný princip "fail closed / no self-referential governance", který si projekt sám stanovil jako architektonický zákon (`architecture-constitution.md`, princip č. 10: *"Flow must avoid self-referential governance that does not measure behavior, quality or drift"*) — a přesto právě to je nejvýraznější zjištěný vzorec v celém repozitáři.

---

## 2. Architektura a myšlenka projektu

### 2.1 Pipeline

```
Human / AI Intent
  -> Standard Intent Model       (org.flowlang.intent, scenarios, ai.normalization)
  -> Capability & safety validace (org.flowlang.controls, safety, effects)
  -> Flow AST                     (org.flowlang.parser, ast)
  -> Flow validace                (org.flowlang.validator)
  -> CanonicalExecutionGraph      (org.flowlang.compiler) — typovaný, cílově-neutrální
  -> Execution Plan               (org.flowlang.planner)
  -> Target compatibility         (org.flowlang.capabilities)
  -> Target Manifest              (org.flowlang.generators, targets, adapters)
  -> Volitelný vendor renderer    (Jenkinsfile / GitHub Actions YAML / Tekton YAML)
```

Dva nezávislé vstupy se sbíhají do jednoho AST: nativní `.flow` DSL (parser) a Standard Intent Model (YAML/JSON nebo AI normalizace přes 12 deterministických "scenario packs"). Klíčová bezpečnostní hranice — **"AI navrhuje, standard rozhoduje"** — je reálně implementována: `IntentProposalReview` ignoruje sebehodnocení AI poskytovatele a nezávisle přehodnocuje návrh přes `IntentCapabilityValidator`.

### 2.2 Fyzická architektura repozitáře

Ačkoliv `settings.gradle.kts` deklaruje 14 samostatných Gradle modulů (`flow-semantic-kernel`, `flow-compiler`, `flow-cli`, `flow-conformance-kit` atd.), **fyzicky existuje jediný sdílený zdrojový strom** `src/main/kotlin/org/flowlang/**` (374 produkčních souborů). Jednotlivé moduly si přes textové manifesty (`gradle/*-sources.txt`) "nárokují" disjunktní podmnožiny souborů a build-time úlohy (`VerifyProductionSourceOwnership`, `VerifyProductionModuleClasspath`) ověřují, že rozdělení je úplné, disjunktní a že modul nesmí mít na classpath nic mimo povolený seznam. `flow-conformance-kit` samo vlastní 138 z 374 produkčních souborů (celý `conformance`/governance aparát) a navíc agreguje celou testovací sadu (188 souborů `src/test/kotlin`). Produkt má **dvě oddělené distribuce**: `flow-product` (10 CLI příkazů, `HonestFlowCli`) a `flow-core`/verifikační host (15 příkazů včetně `conformance`, `standard-export` atd.) — to je zdravé oddělení uživatelského povrchu od vývojářského tooling.

Tento mechanismus je sofistikovaný a funkčně smysluplný (vynucuje modulární hranice bez nutnosti fyzicky přesouvat soubory), ale je také neobvyklý a netriviální na pochopení pro nového přispěvatele — "moduly" v obvyklém smyslu (samostatný `src/main` adresář s vlastním kódem) v podstatě neexistují, existuje jen jedna sdílená sada souborů rozřezaná manifestama.

### 2.3 Governance a vývojový proces

Repozitář obsahuje `.flow-agent/` — kompletní formální proces pro AI-agentní vývoj (`agent-contract.md`, `architecture-constitution.md`, `roadmap*.yaml`, 72 "work packages", 71 release reportů). Vývoj probíhá v extrémně jemnozrnných inkrementech (verze jako `v0.9.7.9.11`, `v0.9.7.10.2`) a každý krok musí projít work-package → architecture check → implementace → testy → conformance vektory → release report → aktualizace `release-state.yaml`. Tento proces samotný je z části zakódován jako produkční Kotlin (viz sekce 4.2) — což je architektonicky nejvýraznější a nejpozoruhodnější rys celého projektu.

---

## 3. Statistický přehled podle vrstev

| Vrstva (balíček) | Soubory | Řádky | Charakter |
|---|---:|---:|---|
| `parser` + `ast` + `intent` + `ai.normalization` + `scenarios` | 37 | 6 567 | Front-end: Flow DSL, Standard Intent Model, AI trust boundary |
| `validator` + `capabilities` + `planner` + `controls` + `effects` + `safety` + `obligations` | 35 | 6 944 | Validace AST, capability negotiation, execution plan, control evidence |
| `compiler` + `lowering` + `core` + `topology` + `projection` + `modules` | 32 | 8 548 | Jádro kompilátoru: CanonicalExecutionGraph, path-sensitive dataflow |
| `generators` + `targets` | 56 | 7 729 | Projekce Execution Plan → Target Manifest → vendor renderer |
| `adapters` | 40 | 8 332 | Certifikační/auditní vrstva nad targets/generators |
| `conformance` | 108 | 24 629 | Interní verifikační framework (**největší balíček v repozitáři**) |
| `architecture` + `standard` + `artifacts` + `roadmap` + `release` + `distribution` + `notes` + `materialization` + `verification` + `identity` + `serialization` + `preview` + `continuity` + `frontend` + `cli` | 66 | 13 241 | Meta vrstva: CLI, governance, veřejný standard model, roadmap tracking |
| `src/test/kotlin` | 188 | 28 827 | Testovací sada (572+553=1125 `@Test` metod) |
| **Celkem produkční Kotlin** | **374** | **~76 000** | |
| **Celkem se testy** | **562** | **~105 000** | (+ ~12 000 v malých `flow-*` modulech a `tools/`) |

Nejdůležitější strukturální zjištění: **balíček `conformance` (24 629 řádků) je sám o sobě větší než celá vrstva parser+intent+validator+planner+compiler dohromady** (22 059 řádků). Podobně masivní je testovací sada (28 827 řádků, srovnatelná s celým jádrem kompilátoru).

---

## 4. Konsolidovaný seznam nálezů podle závažnosti

Očíslování nálezů odpovídá originálnímu značení v podrobných reportech (viz `docs/analysis/details/`). Zde jsou vybrány a seřazeny nálezy s reálným dopadem na chování/bezpečnost; kompletní seznamy včetně nízké závažnosti jsou v jednotlivých detailních dokumentech.

### 4.1 KRITICKÁ

| # | Nález | Umístění | Dopad |
|---|---|---|---|
| **K1** | **Groovy code injection přes neescapovaný `$` v `matches` regex vzoru pro Jenkins.** `JenkinsGroovyExpr.slashRegex()` escapuje jen `/`, ne `$`. Jenkins Groovy "slashy string" (`/…/`) je GString podporující `${...}` interpolaci — sesterská funkce `gstr()` ve stejném souboru `$` správně escapuje, `slashRegex` na to zapomíná. | `src/main/kotlin/org/flowlang/generators/JenkinsGroovyExpr.kt:61,76` | Flow podmínka `x matches "${@@nějaký.groovy.Expr}"` se přeloží do `(x ==~ /${@@nějaký.groovy.Expr}/)` — Jenkins vykoná Jenkinsfile jako Groovy skript, GString se vyhodnotí za běhu → **spuštění libovolného Groovy kódu** při vyhodnocení `if` podmínky vygenerovaného pipeline. Přesná injection cesta z uživatelského/AI vstupu do exekučního kódu. |

**Doporučená okamžitá akce:** opravit `slashRegex()` tak, aby escapovala i `$` (`.replace("$", "\\$")`), nebo se zcela vyhnout Groovy slashy-string interpolaci ve prospěch bezpečně quotovaného `Pattern.compile(...)` volání. Toto je jediný nález v celé analýze, který doporučuji opravit bez ohledu na zbytek prioritizace.

### 4.2 VYSOKÁ

| # | Nález | Umístění | Dopad |
|---|---|---|---|
| **V1** | `groovyEscape()` neescapuje zpětné lomítko (na rozdíl od sesterské `groovyString()`). Používá se na `input.name`, což je plain `String` bez znakové validace kdekoliv ve vrstvě. | `src/main/kotlin/org/flowlang/targets/builtin/JenkinsProjectionRenderingSupport.kt:43-45` | Flow input pojmenovaný se zpětným lomítkem před koncovou uvozovkou může vylomit z Groovy single-quote literálu — cesta k injektáži do generovaného Jenkinsfile. |
| **V2** | `FlowValidator.checkExpr` nevaliduje pole `operator` u `UnaryExpressionNode`/`UnaryPostfixExpressionNode` (na rozdíl od `BinaryExpressionNode`/`LogicalExpressionNode` o pár řádků výš). Prokázaný downstream dopad: Jenkins i GitHub Actions renderery u neznámého postfixového operátoru **tiše zahodí operátor** a vyrenderují jen operand. | `src/main/kotlin/org/flowlang/validator/FlowValidator.kt:352-353` (+ `generators/JenkinsGroovyExpr.kt:32-36`, `targets/builtin/GitHubActionsTargetExpressionTranslator.kt:53-56`) | Poškozený/zfalšovaný bezpečnostně-relevantní test typu `exists`/`empty` může **beze stopy zmizet** z generovaného CI/CD workflow bez jediné chyby na validaci, plánování nebo generování. Přímo koliduje s deklarovaným cílem "safety boundary mezi natural language a executable automation". |
| **V3** | Nekonzistentní `.any` vs `.all` pokrytí operací mezi `intentControlSteps` (ANY) a `controlStepsProtecting` (ALL) — dvě strukturálně téměř identické metody vyhodnocující, zda schválení "chrání" požadavek. `intentControlSteps` (ANY) se používá pro intent-wide APPROVAL policy. | `src/main/kotlin/org/flowlang/controls/CanonicalControlRequirementAuthority.kt:492-502` vs `:504-526` | Intent se 3 operacemi, kde schválení chrání jen 1 z nich, projde jako `SATISFIED` pro celointentový "vyžaduje schválení" požadavek — zbylé 2 rizikové operace projdou bez schválení dál k target generaci. |
| **V4** | Tři nezávislé, vzájemně nekonzistentní implementace detekce "produkčního prostředí": (a) `IntentDecisionAnalyzer.isProduction` — přesná shoda nezachytí `"prod-eu"`/`"production-east"`; (b) `BaseScenarioPack.environmentEntity` — word-boundary regex, korektní ale vrací jen `"prod"`; (c) `KubernetesMaintenanceScenarioPack` — naivní `contains("prod")` — false-positive na "product"/"reproducible". | `intent/IntentDecisionAnalyzer.kt:288-294`, `scenarios/BaseScenarioPack.kt:90-101`, `scenarios/MaintenanceScenarioPacks.kt:34` | Bezpečnostní brána "produkční deploy bez schválení" (jeden z hlavních příkladů z README) je nespolehlivá pro běžné tvary názvů prostředí (`prod-eu`) a zároveň generuje falešné poplachy jinde. |
| **V5** | `IntentDecisionAnalyzer.detectPolicySafetyGates` je neúplná ruční duplikace kanonického `PolicyCondition`/`SafetyRequirement` slovníku (9 hodnot) — chybí 3 z 9 (`REQUIRES_CHANGE_TICKET`, `DESTRUCTIVE_OPERATION`, `EXTERNAL_SIDE_EFFECT`), spadají do `else -> false`. Autoritativní `controls/CanonicalControlRequirementAuthority` pokrývá všech 9 hodnot exhaustivně. | `intent/IntentDecisionAnalyzer.kt:256-276` | Veřejný artefakt `intent-decision-report.json` může tvrdit "not blocking"/"satisfied" pro politiku, kterou skutečná autoritativní validace zablokuje — rozporná, matoucí veřejná zpráva pro stejný vstup. |
| **V6** | SHA-256 digest `CanonicalExecutionGraph` (integrity kotva `CompilationAuthorization`) vynechává pole `evidence`/`path`/`evidenceReference` hrany závislosti. | `compiler/CanonicalExecutionGraphDigest.kt:232-241` | Dva sémanticky odlišné grafy (lišící se jen klasifikací závislosti jako `DECLARED_ORDERING` vs. `DATA_REFERENCE`) mohou mít identický digest — integritní záruka je slabší, než dokumentace tvrdí. |
| **V7** | Diagnostický kód skutečně emitovaný governance skenerem (`ARCHITECTURE_FORBIDDEN_SYMBOL_IN_SOURCE`) neodpovídá kódu deklarovanému ve stabilním veřejném katalogu (`ARCHITECTURE_FORBIDDEN_TERM_IN_SOURCE`) ani kódu citovanému v negativním conformance fixture. | `architecture/ArchitectureGovernance.kt:469` vs `standard/StandardDiagnosticCatalog.kt:120` vs `artifacts/PublicStandardDraft.kt:169` | Konkrétní "diagnostic honesty" drift v projektu, který si na diagnostic honesty explicitně zakládá — negativní conformance případ testuje kód, který produkční kód nikdy neemituje. |

### 4.3 STŘEDNÍ (výběr nejvýznamnějších — kompletní seznamy v detailních reportech)

- Cyklická detekce v `CanonicalExecutionGraphValidator.validateOrderingAcyclic` pokrývá jen `ORDERING` hrany, ne `VALUE`/`WORKSPACE`/`STATE` — datový cyklus bez doprovodné ORDERING hrany by neprošel detekcí (`compiler/CanonicalExecutionGraphValidator.kt:509-542`).
- Tekton `in` operátor tiše zahazuje nepřeložitelné položky seznamu místo fail-closed bloku — porušuje vlastní princip "no silent fallback" (`targets/builtin/TektonTargetExpressionTranslator.kt:30-38`).
- `AuthoredControlEvidenceTextAuthority` akceptuje libovolnou URI/cestu jako "konkrétní backup evidenci" — parametr `backup: "https://cokoliv.example.com"` splní `BACKUP` požadavek pro DB migraci bez skutečné zálohy (`controls/AuthoredControlEvidenceTextAuthority.kt:65-100,145-147`).
- Kolize `ResultBindingNode` jmen po normalizaci `-`→`_` (`build-image` vs `build_image`) není zachycena validací duplicit (`intent/IntentToAstPlanner.kt:450` + `IntentCapabilityValidator.kt:93-95`).
- Chybí kontrola duplicitních jmen `system`/`input`/`vars` v obou paralelních modelech (Flow parser i Intent YAML) — prokázané tiché "last-wins" přepsání v `IntentToAstPlanner.kt:76`.
- Nekonzistentní dodržování vlastního principu "approve krok se nikdy nesyntetizuje automaticky" — `ProvisionScenarioPack` a `SecretRotationScenarioPack` jej porušují bez zdůvodnění.
- `.first {}` bez `firstOrNull` fallbacku v `StandardReleaseProfile.kt:97` — budoucí přejmenování artefaktu shodí `standard-export`/`release-profile` s neinformativní `NoSuchElementException`.
- `CallExpressionNode.function` (volání funkcí ve Flow výrazech) není nikde validováno — typo v názvu funkce projde beze zmínky až do vendor výstupu.
- Přibližně 189 míst s `catch (Throwable)`/širokým `catch (Exception)` napříč `conformance`, `compiler` a dalšími vrstvami maskuje skutečné programátorské chyby (NPE, StackOverflow) jako běžné doménové selhání.

### 4.4 Souhrnná tabulka podle vrstev

| Vrstva | Kritická | Vysoká | Střední | Nízká |
|---|---:|---:|---:|---:|
| Frontend/intent | 0 | 2 | 3 | 5 |
| Validation/planning | 0 | 2 | 1 | 5 |
| Compiler core | 0 | 1 | 1 | 4 |
| Generators/targets | 1 | 1 | 3 | 4 |
| Adapters | 0 | 0 | 4 | 4 |
| Conformance A+B | 0 | 3 | 8 | ~15 |
| Meta/governance | 0 | 1 | 1 | 6 |
| **Celkem (přibližně)** | **1** | **10** | **21** | **~43** |

---

## 5. Systémové architektonické vzorce (napříč vrstvami)

Toto je nejdůležitější část analýzy nad rámec jednotlivých bugů — vzorce, které se opakují nezávisle v šesti a více různých vrstvách a signalizují spíše proces vývoje než lokální nedopatření.

### 5.1 "Governance-as-code" bloat — nejvýraznější systémový nález

Napříč **conformance** (24 629 ř.), **adapters** (8 332 ř.), **roadmap** (2 078 ř.) a testovací sadou (28 827 ř.) se opakuje stejný vzorec: desítky tříd pojmenovaných `*RoadmapLifecycleAuthority`/`*Lifecycle`, které nevalidují chování kompilátoru ani generovaný výstup, ale **ověřují, že interní `.flow-agent/*.yaml` soubory popisující stav vlastního vývojového procesu** (které milníky jsou dokončené, jaké git SHA/PR číslo/CI run ID je patří) jsou vzájemně konzistentní.

Konkrétní míra:
- V balíčku `adapters` tvoří 8 takových tříd **17,7 % objemu balíčku** (1 476 z 8 332 řádků), z toho ~240 řádků je doslovně zkopírovaný boilerplate mezi nimi.
- V balíčku `conformance` odhadem **25–40 % objemu** (6–10 tisíc řádků) patří do této kategorie namísto skutečné sémantické konformity.
- V testovací sadě je to **minimálně 16 souborů / ~2 900+ řádků** jen v jedné ze dvou analyzovaných dávek, další desítky souborů v druhé dávce.
- Samostatný balíček `roadmap/` obsahuje soubor `RoadmapStreamTransitionAuthority.kt` o **1 530 řádcích** — nejhutnější jednotlivý soubor v celém repozitáři — který validuje 13 fází vývojového procesu, z nichž **všechny jsou už trvale uzavřené** (ověřeno přímo v `.flow-agent/roadmap.yaml`: `closureItemStatus: "completed"`, žádný `nextItem`). Tato třída běží při každém `conformance` běhu navěky, přestože ověřuje neměnná historická data.

Tyto třídy obsahují **natvrdo zapsané historické git SHA, PR čísla a CI run ID jako Kotlin konstanty** (`private const val SI011_HEAD = "e94beb34dd6..."`) — data, která už existují paralelně jako YAML v `.flow-agent/roadmap-semantic-integrity.yaml`. Vzniká tak dvojí, ručně synchronizovaná kopie téže pravdy s reálným rizikem překlepu v 40znakovém hexadecimálním SHA.

**Hodnocení:** Toto je přímo v rozporu s vlastním architektonickým zákonem projektu (`architecture-constitution.md`, princip #10: *"Flow must avoid self-referential governance that does not measure behavior, quality or drift"*). Je to pochopitelný důsledek extrémně jemnozrnného AI-agentního vývojového procesu (stovky mikro-verzí, každá vyžadující work-package a release report), ale bez redesignu poroste tato vrstva lineárně s každým dalším milníkem a nikdy se nezmenší, protože staré fáze zůstávají navěky aktivně ověřované.

### 5.2 Duplikovaná/rozcházející se heuristika pro stejnou sémantickou otázku

Vzorec zjištěný nejméně třikrát nezávisle:
- **"Je toto produkční prostředí?"** — 3 nezávislé implementace s různou (a vzájemně nekonzistentní) přesností (V4 výše).
- **"Co znamená tato bezpečnostní politika?"** — kanonický `PolicyCondition`/`SafetyRequirement` slovník existuje, ale `IntentDecisionAnalyzer` si vytvořil vlastní neúplnou kopii (V5 výše).
- **Dva nezávislé Kotlin lexery** (`KotlinLexicalScanner` vs `KotlinSourceBoundaryScanner`) dělající prakticky totéž (odlišení kódu od řetězců/komentářů) s jemně odlišným chováním u vnořených interpolací — riziko, že dva governance nástroje dají pro stejný vstup různý výsledek.
- **Čtyři nezávislé grafové "ancestor/DFS" implementace** (`IntentControlGraph`, `PlanningControlAuthority.ControlGraph`, `FlowPlanner.Ctx.findProviders`, `ArchitectureObligationGraph`) — architektonicky obhajitelné oddělení vrstev, ale zvyšuje pravděpodobnost přesně typu nekonzistence popsané ve V3.

`IntentSourceDirectiveAuthority` přitom v projektu **existuje** přesně jako vzor "jediná autorita pro lexikální otázku" (BACKUP/APPROVAL/ROLLBACK koncepty) a je použita důsledně — jde jen o to, že "produkce" a "safety policy" mezi ošetřené koncepty nepatří, takže si je každý spotřebitel implementoval znovu s různou pečlivostí.

### 5.3 Physical-vs-virtual modularizace

Viz sekce 2.2 — sofistikovaný, ale netriviální mechanismus, který znamená, že klasické očekávání "modul = adresář s vlastním kódem" v tomto repozitáři neplatí. To samo o sobě není chyba, ale zvyšuje bariéru vstupu pro nové přispěvatele a je zdrojem duplicitní údržby (build-time verifikační třídy `VerifyProductionSourceOwnership`/`VerifyKernelSourceOwnership`/`VerifyProductionModuleClasspath` jsou samy vzájemně téměř identické kopie).

### 5.4 Konzistentně pozitivní vzorce (stojí za zachování)

- **Fail-closed jako univerzální výchozí chování.** Neznámá capability → `UNSUPPORTED`. Neznámé prostředí → `UNKNOWN`, nikdy tiše `NON_SENSITIVE`. Nerozpoznaná struktura → `BLOCKED`, nikdy aproximace. Toto je konzistentní přes desítky nezávisle psaných tříd napříč všemi vrstvami.
- **"Unforgeable evidence" pattern.** `ValidatedIntent`, `IntentProposalReviewEvidence`, `CompilationAuthorization`, `LoweredIntentProgram` — všechny používají privátní konstruktory/`init{}` invarianty, které znemožňují sestavit "rozhodnutí" bez odpovídajícího "důkazu rozhodnutí". Opakovaně a smysluplně aplikováno.
- **Nezávislé přepočítávání jako obrana proti driftu.** `ExecutionPlanCanonicalTopologyAuthority`, `deriveControlDecision()` v `CanonicalExecutionGraphValidator` — namísto důvěry uloženým hodnotám se kritické invarianty přepočítávají znovu ze zdrojové provenience a teprve pak porovnávají s uloženým výsledkem.
- **EF-0x "Falsification" rodina** (`conformance`) aktivně vyhledává mezery vlastního sémantického modelu vůči reálným repozitářům (Airflow, Velero, GitHub, Atlantis, cert-manager…) a čestně je označuje jako `MODEL_GAP` namísto skrývání — vzácný a hodnotný vzor v projektech tohoto typu.
- **"Renderery neplánují" je vynuceno typově, ne jen dokumentačně** — žádný renderer nemůže sestavit `TargetRendererPayload` sám; jediné místo vzniku je `TargetNativeProjectionCatalog`, odděleně validované proti kontraktu.
- **Registry honesty vrstva** (`targets/TargetRegistryHonesty.kt`) systémově vynucuje, že tvrzení o úrovni podpory cíle (`PRODUCTION_SUPPORTED` apod.) musí mít odpovídající typ evidence — explicitně zakazuje fráze jako "fallback shell"/"assume supported".

---

## 6. Testovací sada — souhrnné hodnocení

Celá sada (188 souborů, 28 827 řádků, 1 125 metod `@Test`) je bimodální:

- **~71 % souborů testuje skutečnou funkčnost** (kompilátor, planner, adaptéry, bezpečnostní hranice) — kvalita je nadprůměrná: systematický mutační/adversariální styl (vezmi platný artefakt, poškoď jedno pole, ověř přesný chybový kód), žádné mocky u end-to-end testů, storage-permutation invariance testy (pořadí v YAML nesmí měnit sémantiku). V žádném z 1 125 testů nebyl nalezen tautologický assert (`assertTrue(true)`) ani logicky obrácená polarita.
- **~29 % souborů (a kvůli několika extrémně dlouhým souborům 35–40 % *řádků*)** patří do kategorie procesní governance (viz 5.1) nebo "falsification/baseline" meta-testů s napevno zakódovanými počty (`assertEquals(5, report.caseCount)` — nová externí evidence rozbije test bez ohledu na správnost logiky).

Konkrétní slabiny testovacího kódu (ne produkčního): testy vázané na doslovný text v `REPORT.md` prose (rozbije se opravou překlepu v dokumentaci), testy vázané na přesné odsazení zdrojového kódu (`assertContains` na fragment se specifickým whitespace), "god testy" s ~30 nesouvisejícími asercemi v jedné metodě (`VersionConsistencyTests`), a nejméně 6 souborů rodiny `RoadmapLifecycleAuthority` s téměř identickou nezparametrizovanou kostrou fixture-building kódu.

Detaily: `docs/analysis/details/09-tests-a.md`, `10-tests-b.md`.

---

## 7. Doporučení pro budoucí řešení

### 7.1 Okamžité (bezpečnostní)

1. **Opravit K1** (Groovy injection) — je to jediný nález, který má charakter skutečné zneužitelné zranitelnosti a měl by být opraven bez ohledu na cokoliv jiného v tomto dokumentu.
2. Opravit V1 (`groovyEscape` backslash) ve stejném pull requestu — stejná třída problému, stejný soubor.
3. Doplnit chybějící validaci `operator` u unárních výrazů (V2) a sjednotit `.any`/`.all` sémantiku v `CanonicalControlRequirementAuthority` (V3) — obě přímo oslabují deklarovanou bezpečnostní hranici projektu.
4. Sjednotit detekci "produkčního prostředí" do jedné sdílené utility (V4) a nahradit ruční duplikát `SafetyRequirement` slovníku voláním kanonického `PolicyCondition.parse()` (V5).

### 7.2 Architektonické (střednědobé)

5. **Redukovat governance-as-code vrstvu.** Jakmile je roadmap fáze trvale uzavřená (status `completed`, žádný `nextItem`), nahradit její "živou" strukturální validační třídu (často stovky řádků) jednoduchým immutable snapshot/hash testem. To by mohlo z `roadmap/` odstranit řádově 1 500+ řádků bez ztráty skutečné ochrany (historická data se z definice už nemění).
6. **Extrahovat sdílený `RoadmapLifecycleTestKit`** pro fixture-building boilerplate opakovaný v 10+ testovacích souborech a 8 adapter-lifecycle třídách — odhad úspory 30–40 % objemu této skupiny.
7. **Sjednotit dva Kotlin lexery** (`KotlinLexicalScanner`/`KotlinSourceBoundaryScanner`) na jednu implementaci — odstraní riziko rozdílného chování dvou governance nástrojů nad stejným vstupem.
8. **Přesunout hardcoded historická SHA/PR/run-ID** z `RoadmapStreamTransitionAuthority.kt` do `.flow-agent/*.yaml`, kde analogická data už paralelně existují — Kotlin kód by měl číst a ověřovat, ne duplikovat.
9. Zvážit, zda 8 nezávislých `*RoadmapLifecycleAuthority` tříd v `adapters/` (17,7 % balíčku) patří vůbec do produkčního zdrojového stromu `src/main/kotlin`, nebo by měly žít v odděleném tooling/governance modulu mimo cestu Intent→Plan→Manifest.

### 7.3 Dlouhodobé / procesní

10. **Vyhodnotit poměr investice do governance vs. produktu.** Aktuálně je `conformance` (24 629 ř.) největším balíčkem v repozitáři a testovací sada governance-vrstvy roste s každým milníkem bez horní meze. Doporučuji stanovit explicitní rozpočet (např. "governance kód nesmí přesáhnout X % objemu produkčního kódu") a při jeho překročení konsolidovat/archivovat starší, trvale uzavřené kontroly namísto přidávání dalších.
11. Zvážit zjednodušení fyzické/virtuální modularizace (sekce 2.2, 5.3) — buď skutečně fyzicky rozdělit zdrojový strom podle modulů (klasický Gradle vzor), nebo pokud je současný manifest-based mechanismus záměrně zachováván, důkladněji jej zdokumentovat pro nové přispěvatele, protože jde o výrazně nestandardní řešení.
12. Pokračovat v konzistentním dodržování vlastních principů (fail-closed, unforgeable evidence, no silent fallback) při rozšiřování o nové targety/domény — sekce 5.4 ukazuje, že tam, kde jsou dodrženy, je kvalita kódu nadprůměrná; nalezené vysoce závažné chyby (V2–V5) vznikly téměř výhradně tam, kde byl tento princip z nějakého důvodu opuštěn nebo duplikován nekonzistentně.

---

## 8. Podrobné reporty

Kompletní inventář tříd, funkcí a nálezů (file:line) pro každou vrstvu je v samostatných dokumentech:

| Dokument | Vrstva | Rozsah |
|---|---|---:|
| [`details/01-frontend-intent.md`](details/01-frontend-intent.md) | parser, ast, intent, ai.normalization, scenarios | 37 souborů, 6 567 ř. |
| [`details/02-validation-planning.md`](details/02-validation-planning.md) | validator, capabilities, planner, controls, effects, safety, obligations | 35 souborů, 6 944 ř. |
| [`details/03-compiler-core.md`](details/03-compiler-core.md) | compiler, lowering, core, topology, projection, modules | 32 souborů, 8 548 ř. |
| [`details/04-generators-targets.md`](details/04-generators-targets.md) | generators, targets | 56 souborů, 7 729 ř. |
| [`details/05-adapters.md`](details/05-adapters.md) | adapters | 40 souborů, 8 332 ř. |
| [`details/06-conformance-a.md`](details/06-conformance-a.md) | conformance (skupina A) | 54 souborů, 12 318 ř. |
| [`details/07-conformance-b.md`](details/07-conformance-b.md) | conformance (skupina B) | 54 souborů, 12 311 ř. |
| [`details/08-meta-governance.md`](details/08-meta-governance.md) | architecture, standard, artifacts, roadmap, release, distribution, notes, materialization, verification, identity, serialization, preview, continuity, frontend, cli | 66 souborů, 13 241 ř. |
| [`details/09-tests-a.md`](details/09-tests-a.md) | src/test/kotlin (skupina A) | 94 souborů, 14 414 ř. |
| [`details/10-tests-b.md`](details/10-tests-b.md) | src/test/kotlin (skupina B) | 94 souborů, 14 413 ř. |

Každý dokument obsahuje: přehled vrstvy, úplný inventář souborů/tříd/funkcí, nálezy s file:line odkazy a návrhem opravy, architektonická pozorování specifická pro danou vrstvu a statistickou tabulku.
