# FlowAi (Horistum) — Analýza č. 1: Front-end vrstva (parser, ast, intent, ai/normalization, scenarios)

Rozsah: `src/main/kotlin/org/flowlang/{parser,ast,intent,ai/normalization,scenarios}` — 37 souborů, 6 567 řádků (přesný součet `wc -l`). Všechny soubory byly přečteny celé.

---

## 1. Přehled vrstvy

Tato vrstva je „front-end" jazyka Flow ve smyslu README pipeline:

```
lidský/AI text → (scenarios) → IntentDocument + NormalizationReport
                                        │
                         (ai/normalization) IntentProposalReview  — nezávislá re-validace
                                        │
                      (intent) IntentCapabilityValidator → IntentValidationReport
                                        │
                      (intent) IntentToAstPlanner.plan() → FlowDocument (Flow AST)
                                        ▲
              (parser) FlowParser.parse(".flow" text) ────────────┘  (alternativní, přímá cesta)
```

Existují tedy **dva nezávislé vstupní body** do Flow AST:

1. **Nativní Flow DSL** (`parser/` → `ast/`): ruční `.flow` zdroj se tokenizuje (`Lexer`), parsuje (`FlowParser`, `ExpressionParser`) přímo do `FlowDocument`/`FlowNode` (žádná AI, žádný intent model).
2. **Standard Intent Model** (`intent/`, `ai/normalization/`, `scenarios/`): AI nebo deterministické scénářové balíčky produkují `IntentDocument` (buď z YAML/JSON přes `IntentYamlLoader`, nebo z přirozeného jazyka přes `ScenarioPackRegistry`), ten prochází `IntentCapabilityValidator` a je snížen (`IntentToAstPlanner`) do stejného `FlowDocument`/AST modelu jako cesta 1.

Obě cesty se sbíhají do jednoho kanonického, cílově-neutrálního AST — to je architektonicky správně a odpovídá principu README „Flow AST is platform-neutral… does not own execution".

Klíčová bezpečnostní vlastnost, kterou kód skutečně implementuje (ne jen deklaruje v README): **„AI navrhuje, standard rozhoduje"** — `IntentProposalReview` (ai/normalization) záměrně ignoruje sebehodnocení poskytovatele (risks/openQuestions/confidence) a re-odvozuje verdikt výhradně z `IntentCapabilityValidator.validate(intent)`. To je přesně ten bezpečnostní hraniční bod, který README vyžaduje („a system that trusts AI-generated code directly" je explicitně vyloučeno).

V rámci vrstvy nebyl nalezen žádný odkaz na Jenkins/GitHub Actions/Tekton-specifické konstrukty, žádný `TODO`/`FIXME`/`HACK`, žádný neošetřený catch-all (`catch (e: Exception)`), žádné volání externí sítě/IO mimo classpath resource (`StandardCapabilityCompatibility`). To je kladné zjištění — shoduje se s principy README.

Zjištěné problémy jsou převážně jemnější povahy: **duplicitní/nekonzistentní heuristiky** (zejména detekce „produkčního" prostředí a interpretace bezpečnostních politik) mezi několika nezávisle udržovanými místy v kódu, a několik mezer ve validaci duplicit (systémy, ID kroků po normalizaci). Nebyl nalezen žádný force-unwrap, který by byl prokazatelně dosažitelný s neplatným vstupem (viz 3.6), ani žádná nekontrolovaná výjimka v běžné cestě parsování.

---

## 2. Inventář souborů a tříd

### 2.1 `parser/` (6 souborů, 1 301 řádků)

**`Token.kt`** (36 ř.)
- `enum class TokenType` — 19 druhů tokenů Flow lexeru (IDENT, STRING, NUMBER, závorky, operátory, NEWLINE, EOF).
- `data class Token(type, text, line, column, rawValue?, isInteger)` — jeden token s provenience informací pro diagnostiku; `rawValue` nese dekódovaný obsah STRING literálu.
- `class LexException(message, line, column) : IllegalArgumentException` — chyba lexeru s pozicí.

**`Lexer.kt`** (160 ř.)
- `class Lexer(src: String)` — ruční, jednoprůchodový lexer. Klíčová veřejná funkce: `fun tokenize(): List<Token>` — tokenizuje celý zdroj, vrací `SourceTokenList` obohacený o `decodedStringLocations` (mapování offsetů uvnitř dekódovaného stringu zpět na zdrojové line/column, kvůli přesné diagnostice u template-string interpolací). Podporuje `#`/`//` komentáře, escapy (`\n`,`\t`,`\r`,`\"`,`\\`,`\$`), rozlišuje desetinnou tečku od member-access tečky (`123.foo` vs `1.5`).

**`TokenStream.kt`** (103 ř.)
- `open class ParseException(message, line, column) : IllegalArgumentException` — základní parse chyba s pozicí.
- `class TokenStream(tokens: List<Token>)` — kurzor nad tokeny sdílený expression- i statement-parserem. Veřejné funkce: `peek(offset)`, `next()`, `check/checkWord/checkOp`, `match/matchWord`, `expect/expectWord` (vyhodí `ParseException` s pozicí), `seek(i)` (bounded lookahead pro backtracking), `skipSeparators()/skipNewlines()`. `atPath(path, parse)` — dočasně přepne diagnostickou JSON-path pro chybové zprávy (thread-local-like scoping přes `finally`).
- `internal class SourceTokenList` — `AbstractList<Token>` nesoucí mapu string-provenience.
- `internal class DecodedStringLocations` — `AbstractList<SourceLocation>` s O(log n) binárním vyhledáváním pozice znaku v dekódovaném stringu (piecewise-linear runs).

**`DeclarationOccurrences.kt`** (53 ř.)
- `class DuplicateDeclarationException(path, firstOccurrence, secondOccurrence) : ParseException` — nese strukturovaný `code = "FLOW_DUPLICATE_DECLARATION"` a obě lokace kolize.
- `internal class DeclarationOccurrences` — `fun declare(key, token, path)`: registruje první výskyt klíče, při druhém vyhodí `DuplicateDeclarationException`. Používá se pro klíče v mapách/param-blicích/`transform.select`/`match.when` — **ale ne konzistentně pro všechny pojmenované deklarace**, viz 3.4.
- `internal fun declarationPath(parent, key)` — bezpečně escapuje klíč do JSON-path segmentu, pokud není platný identifikátor.

**`ExpressionParser.kt`** (365 ř.)
- `class ExpressionParser(ts, scope="auto", inheritedNestingDepth=0)` — Pratt-like rekurzivní parser výrazů s prioritou `or < and < not < comparison < postfix(empty/exists) < primary`. Veřejné/companion funkce:
  - `companion fun parseSource(source, scope, inheritedNestingDepth): ExpressionNode` — vstupní bod pro parsování samostatného výrazu (používá se i pro `${...}` interpolaci v šablonových řetězcích); validuje limit `MAX_EXPRESSION_NESTING_DEPTH = 128` už na vstupu.
  - `fun parse(): ExpressionNode` — hlavní vstupní bod (`parseOr()`).
  - Podporuje: logické operátory, porovnávací i slovní operátory (`in`,`contains`,`matches`,`startsWith`,`endsWith`), `not empty`/`not exists`/`not <op>` tvary, bezpečnou navigaci (`?.`), indexaci (`[...]`), volání funkcí, `secret(...)`/`secret:NAME`, kompaktní `ReferenceNode` pro homogenní `a.b.c`/`a?.b?.c` cesty (s degradací na `MemberExpressionNode` při smíšené `.`/`?.` navigaci), a template stringy s `${...}` interpolací včetně remapování pozic při `DuplicateDeclarationException` uvnitř interpolace.
  - Rekurzivní hloubka je hlídána na dvou nezávislých místech (`parsePrefixNot` pro `not`-řetězce a `parsePrimary` pro obecné vnořování) proti `MAX_EXPRESSION_NESTING_DEPTH`.

**`FlowParser.kt`** (584 ř.)
- `class FlowParser` — rekurzivní sestupný parser celého `.flow` dokumentu. Klíčové veřejné funkce:
  - `fun parse(file: File): FlowDocument` / `fun parse(source: String, sourceFile: String?): FlowDocument` — vstupní body; parsují `version`, `use module`, přesně jeden `flow { ... }` blok.
  - Privátní `parseFlow`, `parseInputBlock`, `parseVarsBlock`, `parseSystemsBlock`, `parseStatementBlock`, `parseStatement` (dispatch na `if/for/parallel/match/retry/try/fail/skip/set/approve/transform/validate/aggregate/action`), `parseIf`, `parseFor`, `parseParallel`, `parseMatch`, `parseRetry`, `parseTry`, `parseSet`, `parseApprove`, `parseTransform`, `parseValidate`, `parseAggregate`, `parseAction`, `parseParamBlock`, `parseSafety`, `parseResultHandler` — každá odpovídá jedné konstrukci Flow jazyka a mapuje ji 1:1 na `ast/AstNodes.kt` uzel. Hlídá `MAX_STATEMENT_NESTING_DEPTH = 128` na úrovni `parseStatement`.
  - Parser nikdy tiše nezahazuje neznámou konstrukci — vždy vyhodí `ParseException` s pozicí (dodržuje vlastní dokumentovaný slib v hlavičce souboru).

### 2.2 `ast/` (2 soubory, 389 řádků)

**`AstNodes.kt`** (275 ř.) — kanonický, cílově-neutrální Flow AST model (docs/04). 36 top-level typů, mj.:
- `data class FlowDocument`, `FlowNode`, `MetadataNode`, `ModuleImportNode`, `TriggerNode`, `ScheduleNode`, `InputNode`, `ValueTypeNode`, `VariableNode`, `SystemNode`
- `sealed interface StatementNode { val type: String }` a jeho implementace: `ActionNode` (nejbohatší uzel — nese `semanticCapability`, `semanticEffects`, `sourceId`/`sourceDescription`/`bindingMetadata`/`declaredOutputs`/`dependsOn` — evidence z intent-lowering, oddělená od syntaxe), `IfNode`, `ForNode`, `ParallelNode`(+`ParallelBranchNode`), `MatchNode`, `RetryNode`(+`RetryPolicyNode`), `FailNode`, `SkipNode`, `ApproveNode`, `SetNode`, `TryNode`, `TransformNode`, `ValidateNode`(+`ValidateRuleNode`), `AggregateNode`, `ErrorHandlerNode`, `ExpectNode`.
- `data class ResultBindingNode`, `ResultHandlerNode`, `sealed interface ResultHandlerRuleNode` (+ `WhenNode`), `SafetyNode`.
- Žádné veřejné funkce — čistě datový model (v souladu se souborovou dokumentací „musí být target-neutral").

**`ExpressionNodes.kt`** (114 ř.) — 18 typů výrazového AST:
- `sealed interface ExpressionNode { val type: String }` a implementace: `StringLiteralNode`, `NumberLiteralNode` (nese `isInteger` pro věrnou re-generaci), `BooleanLiteralNode`, `NullLiteralNode`, `IdentifierLiteralNode` (symbolické literály typu HTTP metod), `ReferenceNode` (kompaktní `a.b.c`/`a?.b?.c` cesta), `BinaryExpressionNode`, `UnaryExpressionNode`, `UnaryPostfixExpressionNode`, `LogicalExpressionNode`, `ListLiteralNode`, `MapLiteralNode`, `TemplateStringNode` (+ `sensitive` flag pro `secret()` uvnitř interpolace), `CallExpressionNode`, `MemberExpressionNode` (smíšená `.`/`?.` navigace), `IndexExpressionNode`, `SecretRefNode`.

### 2.3 `intent/` (18 souborů, 3 138 řádků)

**`IntentModel.kt`** (199 ř.) — Standard Intent Model, samostatný od Flow AST. 24 typů: `IntentDocument`, `IntentInput`, `sealed interface IntentValue` (+ `IntentString/Number/Boolean/Null/List/Object/SecretRef/Ref/Expression`), `IntentSystem`, `IntentTrigger`(+`IntentTriggerType` enum, `IntentSchedule`+`IntentScheduleKind`), `IntentWorkflow`(+`IntentWorkflowKind`, 14 hodnot), `IntentStep`, `enum class StandardCapability` (29 hodnot — uzavřený katalog kanonických schopností), `IntentPolicy`(+`IntentPolicyType`), `IntentFailurePolicy`. Netriviální veřejné extension funkce: `IntentValue?.asTextOrNull()`, `IntentValue?.asBooleanOrNull()` — bezpečné, totální (no-throw) konverze používané napříč celou vrstvou.

**`IntentYamlLoader.kt`** (277 ř.)
- `class IntentSourceException(code, path, sourceName, detail) : IllegalStateException` — stabilní hranice chyby (kód + JSON-path + zdroj).
- `object IntentYamlLoader` — striktní YAML/JSON→`IntentDocument` boundary. Veřejné: `fun load(file: File)`, `fun loadText(text, sourceName)`, `fun normalize(root: Map<String,Any?>, sourceName)`. Odmítá neznámá pole (`requireOnly`), neznámé enum hodnoty, nesprávné typy, duplicitní `config`/inline klíče u systémů (`DUPLICATE_SYSTEM_CONFIG_SOURCE`), a rozpoznává textové prefixy `secret:`/`ref:`/`expr:`/`${...}`/`$name` jako typované `IntentValue` varianty. **Fail-closed přístup je důsledný** — žádná neznámá hodnota nezůstává tiše ignorována.

**`CanonicalIntentMeaning.kt`** (309 ř.)
- `data class CanonicalIntentMeaning`, `CanonicalIntentWorkflow`, `CanonicalIntentStep` — „meaning" bez konkrétní vazby na systém/modul (inventory-independent).
- `enum class IntentBindingStatus { UNBOUND, RESOLVED, INVALID }`, `data class IntentBindingEvidence`, `IntentBindingIssue`, `CanonicalIntentResolution`.
- `class CanonicalIntentMeaningAuthority(registry: ModuleCatalog)` — jediná autorita, která páruje `uses: module.action` s katalogem modulů. `fun resolve(intent): CanonicalIntentResolution` — pro každý krok řeší binding (`resolveBinding`), validuje `module.action`, `params.system`, typovou shodu systému (`BINDING_SYSTEM_TYPE_MISMATCH`), a parametry (`validateParameters`). Companion: `fun canonicalize(intent): CanonicalIntentMeaning` (čistě strukturální projekce bez inspekce registru — dokumentovaná invariant), `fun semanticParameters/semanticParameterNames`.

**`IntentBindingContracts.kt`** (127 ř.)
- `enum class IntentBindingParameterSource { SEMANTIC, BINDING, DEFAULT }`, `enum class IntentBindingEffectPolicy { PRESERVE_CANONICAL }`.
- `data class IntentBindingContract`, `ResolvedIntentBindingParameters`.
- `object IntentBindingContractAuthority` — `fun derive(module, action, capability, contract): IntentBindingContract` (odvodí, které parametry akce pokrývají kanonické sémantické parametry dané capability), `fun resolveParameters(step, binding, action): ResolvedIntentBindingParameters` (sestaví finální hodnoty s evidencí zdroje — SEMANTIC/BINDING/DEFAULT), `fun canonicalSemanticParameters(capability): Set<String>`.

**`IntentCapabilityValidator.kt`** (278 ř.)
- `class IntentCapabilityValidator(registry: ModuleCatalog)` — hlavní design-time validátor. `fun validate(intent): IntentValidationReport` provádí ~20 tříd kontrol: prázdné jméno, duplicity workflow/trigger/step (přesná shoda řetězce — viz 3.5), cross-workflow závislosti, neznámé/duplicitní `requires`/`produces`, kontradikce zdrojového textu (`IntentSourceContradictionAuthority`), neznámé typy systémů + konfigurační schéma, kontrolní požadavky (`CanonicalControlRequirementAuthority`, mimo rozsah), binding issues, chybějící/neznámé step parametry, **detekce cyklů** (`detectCycles` — DFS se third-color/visited-state, `VisitState.VISITING/DONE`).
- `data class IntentValidationReport(valid, issues, meaning, bindings, controlAssessment)` s `fun assertValid()`.
- `data class IntentValidationIssue(level, code, message)`.

**`IntentToAstPlanner.kt`** (460 ř.) — snížení `IntentDocument` → `FlowDocument`.
- `internal data class ValidatedIntentEvaluation`, `internal class ValidatedIntent private constructor(document, report)` — **unforgeable evidence pattern**: privátní konstruktor zaručuje, že nikdo nemůže spárovat `IntentDocument` s cizí/zastaralou validační zprávou; jediná cesta k instanci je `companion fun evaluate(registry, document)`.
- `class IntentToAstPlanner(registry, expressions: IntentExpressionParser)`:
  - `fun plan(intent): FlowDocument` — veřejný vstupní bod, validuje a promítne (vyžaduje přesně jeden workflow po zploštění).
  - `internal fun planProgram(validated): LoweredIntentProgram` — nová multi-workflow-aware cesta; per-workflow filtruje `ControlAssessment` a `ExecutionTopologyRequirement` podle scope (`workflowControlAssessment`, `workflowTopology`).
  - `lowerOrderedSteps`/`orderedSteps` — topologické seřazení kroků podle `requires` (stabilní podle původního pořadí přes `minByOrNull { orderIndex[...] }`); při nesplnitelné/cyklické závislosti vyhazuje `error(...)` s poznámkou, že to je „interní invariant" already caught by validator (viz 3.3 pro riziko, kdy tento invariant neplatí).
  - `lowerStep`/`boundAction`/`semanticStatement`/`standardAction`/`approvalStatement` — mapují `IntentBindingStatus.RESOLVED` na konkrétní `ActionNode` (module.action) a `UNBOUND` na generický `standard.execute`/`standard.rollback`.
  - `buildErrorHandler(intent)` — syntetizuje `on error` handler z `failure.rollback`/`failure.notify`.
  - `result(id)`/`applyDependencies` — normalizují ID (`-`→`_`) pro `ResultBindingNode`/`dependsOn` (viz 3.3).

**`IntentDesignAnalyzer.kt`** (137 ř.)
- `data class IntentDesignReport`, `IntentCapabilityUse`, `IntentRequiredSystem`.
- `class IntentDesignAnalyzer(registry: ModuleCatalog)` — `fun analyze(intent): IntentDesignReport` — vysvětlující, „architektonická" zpráva (ne nízkoúrovňová validace): jaké capabilities se používají, jaké systémy jsou potřeba/chybí, jaké rozhodnutí ještě chybí, jaké konvence (`ConventionResolver`) byly potichu předpokládány, poznámky k portabilitě.

**`IntentDecisionAnalyzer.kt`** (302 ř.)
- `data class IntentDecisionReport`, `IntentDecision`, `IntentMissingDecision`, `IntentDecisionAssumption`, `IntentDecisionRisk`, `IntentSafetyGate`, `IntentLoweringDecision`.
- `class IntentDecisionAnalyzer(registry: ModuleCatalog)` — `fun analyze(intent): IntentDecisionReport`. Detekuje: cleanup bez retence (`detectCleanupDecisions`), backup schedule bez timezone (`detectBackupScheduleDecisions`), DB migraci bez backupu (`detectDatabaseMigrationDecisions`), produkční deploy bez schválení (`detectProductionDeployApproval` — **viz 3.1, vlastní neúplná `isProduction` heuristika**), a bezpečnostní brány z `IntentPolicyType.SAFETY` politik (`detectPolicySafetyGates` — **viz 3.2, duplicitní/neúplná re-implementace `SafetyRequirement` slovníku**). Report je informativní/„advisory" vrstva nad tím, co už dělá `IntentCapabilityValidator`+`CanonicalControlRequirementAuthority` (mimo rozsah) — proto je rozpor mezi oběma obzvlášť rizikový (viz 3.2).

**`IntentSourceDirectiveAuthority.kt`** (350 ř.) — nejkomplexnější lexikální komponenta ve vrstvě.
- `enum class IntentSourceDirectiveConcept` (BACKUP/REPOSITORY/NOTIFICATION/APPROVAL/ROLLBACK/RESTORE), `IntentSourceMentionPolarity`, `IntentSourceDirectiveStatus` (REQUESTED/DENIED/CONFLICTING/ABSENT).
- `data class IntentSourceMention`, `IntentSourceDirectiveEvidence`.
- `object IntentSourceDirectiveAuthority` — jediná lexikální autorita pro shodu frází a polaritu negace, sdílená mezi `scenarios/` a `intent/`. Veřejné: `fun analyze(source, concept): IntentSourceDirectiveEvidence`, `fun affirmedPhrases/containsAffirmedPhrase`, `fun phraseTokenCount`. Implementuje: klauzulové hranice (`. ! ? ; ,` + `but/however/except/instead`), tokenizaci s podporou `._/-` uvnitř tokenu, expanzi kontrakcí (`don't`→`do not`), přesnou frázovou shodu + dvě ohraničené gramatické formy (`migrate <entity> database`, `migration <verze> on database`), a negaci s look-back/look-ahead oknem (7/5 tokenů) a ošetřením `not only` jako ne-negace. Návrhově velmi pečlivé (viz kladné hodnocení v sekci 4), ale i s touto propracovaností se ukazuje jako **jedna ze tří nezávislých implementací "je toto produkce?"** jinde v kódu (viz 3.1) — samotná tato třída žádnou takovou logiku neobsahuje, ale její styl (word-boundary, tokenizovaná shoda) je přesně to, co chybí jinde.

**`IntentSourceContradictionAuthority.kt`** (51 ř.)
- `object IntentSourceContradictionAuthority` — `fun validationIssues(intent): List<IntentValidationIssue>` — detekuje konkrétní rozpor: zdrojový text explicitně popírá backup (`explicitlyDeniesBackup` přes `IntentSourceDirectiveAuthority`), ale normalizovaný intent přesto deklaruje pozitivní backup evidenci → `CONTRADICTORY_BACKUP_EVIDENCE`. Úzce zaměřená, dobře zdokumentovaná bezpečnostní pojistka proti tomu, aby scénářový normalizér „halucinoval" bezpečnostní krok jen kvůli přítomnosti slova.

**`IntentSystemTypeAuthority.kt`** (17 ř.) — `object IntentSystemTypeAuthority` — `fun bindingType(sourceType): String` — mapuje jen explicitní legacy aliasy (`dockerRegistry→docker`, `notification/email→notify`); záměrně **ne** obecné systémové typy (dokumentovaný non-goal: `containerRegistry` nesmí implikovat Docker).

**`StandardCapabilityCompatibility.kt`** (100 ř.) — `object StandardCapabilityCompatibility` — `fun resolveSourceName(normalizedName): StandardCapability?`, `val retiredSourceNames`, `fun isSupportedManifestText(text): Boolean`. Načítá deklarativní manifest retired-capability aliasů z classpath resource, **pinuje jeho SHA-256** (`SUPPORTED_MANIFEST_SHA256`) a při neshodě odmítá start (`require`). Ověřeno: hash v kódu (`c27278d5…ba4f116`, 64 hex znaků) skutečně odpovídá `sha256sum` reálného souboru `src/main/resources/standard/compatibility/capability-aliases.yaml` — **funguje správně**. Obsahuje `@Deprecated val StandardCapability.Companion.KUBERNETES_MAINTENANCE` — extension-property kompatibilní alias, používaný jen ve testech mimo rozsah.

**`PolicyCondition.kt`** (123 ř.)
- `sealed interface PolicyCondition` (+ `Requirement`, `RetentionRule`, `Custom`), `data class PolicyConditionParseResult`, `PolicyConditionParseIssue`.
- `enum class RetentionConstraintKind` (RETENTION/TTL/OLDER_THAN), `enum class SafetyRequirement` (9 uzavřených hodnot — REQUIRES_CLARIFICATION…EXTERNAL_SIDE_EFFECT).
- `companion object` na `PolicyCondition`: `fun parse(raw): PolicyCondition`, `fun analyze(raw): PolicyConditionParseResult` — parsuje bez substring-inference (žádné „obsahuje slovo"), s fail-closed chováním pro poškozenou retenční formu (→ `REQUIRES_CLARIFICATION` + diagnostika). Toto je **kanonický slovník**, který `IntentDecisionAnalyzer.detectPolicySafetyGates` **nepoužívá** a místo něj re-implementuje vlastní (neúplnou) verzi — viz 3.2.

**`ConventionResolver.kt`** (28 ř.) — `object ConventionResolver` — `fun assumptions(intent): List<String>` — vyjmenovává implicitní konvence (default branch `main`, default test/build command), které se **musí** reportovat, nikdy tiše skrýt v lowering kódu (dokumentovaný princip transparentnosti).

**`IntentExamples.kt`** (42 ř.) — `object IntentExamples` — `val buildTestDeploy: IntentDocument` — vestavěný ukázkový intent pro dokumentaci/testy.

**`LoweredIntentProgram.kt`** (55 ř.)
- `internal data class LoweredIntentWorkflow(name, document)` — `init { require(name.isNotBlank()) }`.
- `internal data class LoweredIntentProgram(name, inputs, triggers, sourceIntent, workflows)` — silné invarianty v `init{}`: neprázdné jméno, alespoň jeden workflow, unikátní jména workflow, a že každý trigger routuje jen na existující, neduplicitní workflow jména. `fun requireSingleDocument(): FlowDocument` — vyhodí `MultipleWorkflowCompatibilityViewException` (mimo rozsah balíček `planner`), pokud program obsahuje víc než jeden workflow a volající chce jednoduchý „compatibility view".

**`MandatorySafetyPolicy.kt`** (20 ř.) / **`SafetyPolicyValidator.kt`** (20 ř.) — oba `@Deprecated`, oba jen tenké fasády přes `CanonicalControlRequirementAuthority.assess()` (mimo rozsah). **Ověřeno grepem: `MandatorySafetyPolicy` a `RuleBasedIntentNormalizer` (viz 2.4) nemají v celém repozitáři (main ani test) jediné volací místo — jde o mrtvý kód** (viz 3.9). `SafetyPolicyValidator` je zmíněn jen jako řetězec cesty v `architecture/ArchitectureGovernance.kt` (governance seznam), nikoli instanciován/volán.

### 2.4 `ai/normalization/` (4 soubory, 297 řádků)

**`AiIntentNormalization.kt`** (168 ř.) — provider-neutrální kontrakt.
- `interface AiIntentProvider { fun normalize(request): AiIntentResponse }` + typealiasy `IntentNormalizationProvider/Request/Response`.
- `data class AiIntentRequest(userText, context: AiIntentContext, mode: NormalizationMode)`, `AiIntentContext`, `enum class NormalizationMode { DRAFT, STRICT, EXPLAIN, REPAIR }`.
- `data class AiIntentResponse(normalizedIntent, report)` — `fun assertUsableForLowering()`: kontroluje **jen** vlastní (sebehodnocené) `report.openQuestions`/`report.risks` poskytovatele — dokumentovaně nedostatečné pro nedůvěryhodného poskytovatele (proto existuje `IntentProposalReview` jako silnější brána).
- `object TargetPortabilityEvidence` — `fun deferred(requestedTarget): Map<String,String>` — standardizovaný „DEFERRED" status s odkazy na autoritativní artefakty (compatibility/readiness/selection/decision-trace reporty), aby normalizace nikdy sama netvrdila portabilitu na cíl.
- `data class NormalizationReport` (+ 8 podpůrných datových tříd/enumů: `ScenarioSelectionReport`, `IntentClassification`, `ClassificationAlternative`, `ConfidenceScore`, `NormalizationAssumption`, `ClarificationQuestion`+`ClarificationSeverity`, `IntentRisk`+`RiskSeverity`).

**`TargetPortabilityDisposition.kt`** (5 ř.) — `internal class TargetPortabilityDisposition(requestedTarget) : LinkedHashMap<String,String>(...)` — tenký typ zachovávající veřejný JSON tvar mapy.

**`IntentProposalReview.kt`** (97 ř.)
- `sealed interface IntentProposalDecision` (+ `Accepted`, `Rejected(violations)`).
- `data class IntentProposalReviewEvidence(decision, validation)` s `init{}` invarianty: `Accepted` musí nést platnou zprávu, `Rejected` musí nést přesně blokující issues z validace (`require(decision.violations == blocking)`) — **další příklad unforgeable-evidence patternu**, brání nekonzistenci mezi veřejným rozhodnutím a interní validací.
- `class IntentProposalReview(registry: ModuleCatalog)` — `fun review(response/intent): IntentProposalDecision`, `fun reviewWithEvidence(...)`. Toto je **hlavní trust boundary** popsaná v sekci 1.

**`RuleBasedIntentNormalizer.kt`** (27 ř.)
- `class ScenarioPackIntentNormalizer : AiIntentProvider` — deterministický referenční poskytovatel, deleguje na `ScenarioPackRegistry.normalize`.
- `@Deprecated class RuleBasedIntentNormalizer : AiIntentProvider` — zpětně-kompatibilní alias; **mrtvý kód** (viz 2.3, 3.9).

### 2.5 `scenarios/` (7 souborů, 1 685 řádků)

**`ScenarioPacks.kt`** (168 ř.)
- `data class ScenarioPackDefinition`, `ScenarioPackMatch`, `ScenarioNormalizationResult`.
- `interface ScenarioPack { val definition; fun match(...); fun normalize(...) }`.
- `object ScenarioPackRegistry` — registr přesně 12 balíčků (hardcoded `List`, žádný plugin/SPI mechanismus — v souladu s README „no plugin lifecycle framework"). Veřejné funkce: `fun bestMatch(text, context): ScenarioPackMatch` (nejvyšší skóre přes všechny balíčky; práh `<= 0.05` → fallback na „custom" se score 0.35), `fun normalize(request): AiIntentResponse` (hlavní vstupní bod — sestaví kompletní `NormalizationReport` včetně `confidence()` a `safetyGates()`), `fun markdown(): String` (dokumentační export pro CLI `scenarios --markdown`), `fun jsonReady(): List<ScenarioPackDefinition>`. Privátní `confidence()` počítá `ConfidenceScore` z počtu required/recommended otázek a rizik podle ručně vyladěného lineárního vzorce.

**`BaseScenarioPack.kt`** (307 ř.) — `abstract class BaseScenarioPack : ScenarioPack` — sdílená infrastruktura pro všech 11 konkrétních balíčků. Nejdůležitější veřejné (`protected`) funkce:
- `override fun match(text, context): ScenarioPackMatch` — výchozí skórovací algoritmus: `0.38 + hits.size*0.10 + specificity*0.04`, capped na 0.95.
- `applicationEntity`, `subjectEntity`, `databaseEntity`, `certificateEntity`, `maintenanceWindowEntity`, `kubernetesScopeEntity`, `extractFromTo` — regexové extraktory entit pro jednotlivé domény.
- `commonSystems(text, context, includeSource, notify): List<IntentSystem>` — sdílená syntéza `source`/`notifier` systémů, gatovaná `IntentSourceDirectiveAuthority` (nikdy nesyntetizuje systém, pokud je explicitně popřen).
- `environmentEntity(text, context): String?` — vrací kanonicky jen `"prod"|"dev"|"staging"|"qa"|"test"|null`, s word-boundary regexem (viz 3.1).
- `explicitApprovalRequested/approvalExplicitlyDenied/rollbackExplicitlyDenied/rollbackRequested/notificationRequested` — tenké obálky nad `IntentSourceDirectiveAuthority`, s explicitním komentářem (ř. 60-66): *„Approval must be explicit… must never synthesize its own APPROVE step or APPROVAL policy"* — **princip, který dva konkrétní balíčky porušují**, viz 3.7.
- `recurringSchedule(text): IntentSchedule?` — rozpozná `every N day/hour/week/minute` (→ ISO-8601 interval) i kvalitativní cadence (`nightly/daily/weekly/monthly`).
- `packResult(...): ScenarioNormalizationResult` — **centrální finalizační funkce volaná všemi balíčky**: doplní automaticky generované otázky pro konfliktní direktivy (`requireResolution` pro BACKUP/REPOSITORY/NOTIFICATION/APPROVAL/ROLLBACK), přidá blokující `IntentPolicy` při `REQUIRED` otázce nebo nemitigovaném vysokém riziku, sestaví finální `IntentDocument`.
- `cleanEntityCandidate`/`cleanEntityPhrase`/`isMeaningfulEntityCandidate` — čištění extrahovaných entit s rozsáhlým `nonEntityTokens` stop-list (~90 slov).

**`DeliveryScenarioPacks.kt`** (208 ř.) — 3 objekty:
- `object DeploymentScenarioPack` — build→test→build-image→[approve]→deploy→verify→[rollback]→[notify]. Detekuje ArgoCD vs. generický Kubernetes cíl. `val prod = environment == "prod" || environment == "production"` (ř. 28 — částečně mrtvý kód, viz 3.1/3.8).
- `object RollbackScenarioPack` — rollback→verify→[notify], záměrně nesyntetizuje nový deploy.
- `object BuildTestScenarioPack` — checkout→build→test; vlastní `override fun match` explicitně vrací skóre 0 pokud text obsahuje `deploy(ment)`, aby se nekřížil s Deployment balíčkem.

**`MaintenanceScenarioPacks.kt`** (198 ř.) — 5 objektů:
- `object KubernetesMaintenanceScenarioPack` — `val prod = lower.contains("prod") || lower.contains("production")` (ř. 34 — **naivní substring match bez word-boundary**, viz 3.1).
- `object ProvisionScenarioPack` — plan→**approve** (nepodmíněně, ř. 89)→apply→validate→[notify] (viz 3.7).
- `object CleanupScenarioPack` — cleanup blokovaný, dokud není explicitní retence/podmínka (`extractRetentionRule`).
- `object IncidentRunbookScenarioPack` — runbook→verify→[notify].
- `object CustomScenarioPack` — fallback balíček, `override fun match` vrací fixní `ScenarioPackMatch("custom", 0.35, emptyList())` nezávisle na textu.
- Top-level `fun normalizeText(text): String`, `internal fun mapOfNotNull(...)`.

**`OperationsScenarioPacks.kt`** (295 ř.) — 5 objektů:
- `object BackupRestoreScenarioPack` — komplexní `wantsBackup` ternární logika (requested/denied-conflicting/wantsRestore/default-true), rozpoznává explicitní cron (`extractExactCron`) i kvalitativní cadence.
- `object DataSyncScenarioPack` — sync→transform→validate→[notify].
- `object SecretRotationScenarioPack` — **approve** (nepodmíněně, ř. 150)→rotate→verify→[notify] (viz 3.7).
- `object DatabaseMigrationScenarioPack` — nejbohatší podmíněný graf závislostí ([backup]→[approve]→migrate→validate→[rollback]→[notify]) s explicitním `highRisk` pokud je backup textově **popřen**.
- `object CertificateRenewalScenarioPack` — renew→verify→[notify], s volitelným plánovačem.

**`ReferenceAdapterProjectionMatrix.kt`** (71 ř.)
- `object ReferenceAdapterProjectionMatrix` — `val supportedTargets = {"jenkins","github-actions","tekton"}`. `fun evaluate(scenario, target, coreBlocked, compatibility?, manifest?): ReferenceAdapterProjectionExpectation` — klasifikuje očekávaný výsledek projekce (EXECUTABLE/REVIEW_ONLY/FAIL_FAST) **výhradně na základě evidence** (compatibility report, manifest render mode) — dokumentovaně žádné ID scénáře/cíle nesmí predeklarovat úspěch. Pokud scénář není blokovaný a `manifest == null`, explicitně `error(...)` (fail-fast na chybějící evidenci, ne tichý default).
- `data class ReferenceAdapterProjectionExpectation`, `enum class ReferenceAdapterProjectionOutcome`.

**`ReferenceScenarioMatrix.kt`** (438 ř.) — referenční korpus pro konformitní testy.
- `object ReferenceScenarioMatrix` — `fun all()/positiveScenarios()/negativeScenarios(): List<ReferenceScenario>` — 7 pozitivních + 1 negativní scénář, každý jako **kompletní, ručně napsaný `.flow` zdrojový text** (build-test-deploy, api-sync, database-migration, rollback-workflow, cleanup-approved, secret-rotation, notification-workflow, a negativní `cleanup-without-approval-negative` s `expectedDiagnosticCodes = {"SAFETY_REQUIRED","APPROVAL_REQUIRED"}`).
- `data class ReferenceScenario`, `ReferenceSemanticExpectation`; `enum class ReferencePortabilityClass`, `ReferenceScenarioKind`, `ReferenceScenarioRisk`. Čistě datová/fixture vrstva — žádná zjištěná logická chyba.

---

## 3. Zjištěné chyby a nedostatky

### 3.1 [HIGH] Tři nezávislé, vzájemně nekonzistentní implementace „je toto produkční prostředí?"

Bezpečnostně klíčová otázka „je tento krok/deploy produkční?" je řešena **třikrát nezávisle**, s odlišným (a v jednom směru neúplným, v druhém přestřelujícím) chováním:

1. **`intent/IntentDecisionAnalyzer.kt:288-294`** (`isProduction`) — přesná shoda:
   ```kotlin
   normalized == "prod" || normalized == "production" || normalized.contains(" production")
   ```
   Toto je autoritativní zdroj pro `detectProductionDeployApproval` (ř. 220-254), tedy pro **bezpečnostní bránu** „produkční deploy bez schválení" v `IntentDecisionReport`/`intent-decision-report.json`. Hodnota typu `"prod-eu"`, `"production-east"`, `"PROD-EU"` (běžný tvar `environment`/`namespace` pojmenování) **neprojde** — `contains(" production")` vyžaduje mezeru před slovem, ne pomlčku/podtržítko, a přesná rovnost `"prod"`/`"production"` hyphenated variantu nezachytí. **Scénář selhání:** intent s `environment = "prod-eu"` a deploy krokem bez approve kroku projde `detectProductionDeployApproval` beze zjištění rizika, ačkoliv jde o produkci.
2. **`scenarios/BaseScenarioPack.kt:90-101`** (`environmentEntity`) — word-boundary regex `\b(prod|production)\b`, správně zachytí i `"production-east"` (pomlčka je word-boundary), ale vrací pouze kanonickou hodnotu `"prod"` (nikdy `"production"`).
3. **`scenarios/MaintenanceScenarioPacks.kt:34`** (`KubernetesMaintenanceScenarioPack.normalize`) — naivní substring: `lower.contains("prod") || lower.contains("production")`. **Scénář selhání (false positive):** text „restart the **prod**uct catalog pods" nebo „redeploy the **counterprod**uctive test job" nastaví `prod = true`, což spustí `missing-maintenance-window` povinnou otázku a `requiresDryRun` bezpečnostní politiku i pro netriviálně nesouvisející požadavek. (Druhá polovina `|| contains("production")` je navíc nedosažitelný kód, protože `"production"` už obsahuje `"prod"`.)

**Závažnost:** HIGH — bod 1 je přímé porušení bezpečnostního záměru README („production deployment without explicit approval" má být blokující případ) pro běžný a realistický tvar názvu prostředí.
**Návrh opravy:** Jedna sdílená `ProductionEnvironmentClassifier` (ideálně stejný word-boundary/tokenizační přístup jako `IntentSourceDirectiveAuthority`), použitá na všech třech místech; odstranit mrtvou větev v `DeliveryScenarioPacks.kt:28`; přidat regresní test pro `"prod-eu"`/`"production-east"` i pro `"product"`/`"reproducible"` (negativní test).

### 3.2 [HIGH] `IntentDecisionAnalyzer.detectPolicySafetyGates` je neúplná duplicitní re-implementace kanonického `SafetyRequirement` slovníku

`intent/IntentDecisionAnalyzer.kt:256-276`:
```kotlin
val normalized = condition.replace("-", "").replace("_", "").replace(" ", "").lowercase()
val blocked = when (normalized) {
    "requiresclarification", "unmitigatedhighrisk" -> true
    "requiresapproval" -> !hasApproval(intent, steps)
    "requiresdryrun" -> ...
    "requiresbackup" -> ...
    "requiresrollbackplan" -> ...
    else -> false
}
```
Toto přesně odpovídá normalizačnímu schématu a šesti z devíti hodnot `intent/PolicyCondition.kt`'s `enum class SafetyRequirement` (REQUIRES_CLARIFICATION, UNMITIGATED_HIGH_RISK, REQUIRES_APPROVAL, REQUIRES_DRY_RUN, REQUIRES_BACKUP, REQUIRES_ROLLBACK_PLAN), ale **soubor `IntentDecisionAnalyzer.kt` `PolicyCondition`/`SafetyRequirement` vůbec neimportuje ani nepoužívá** (ověřeno grepem — jediní uživatelé `PolicyCondition` v celém repozitáři jsou `intent/PolicyCondition.kt` samotný a `controls/CanonicalControlRequirementAuthority.kt`, mimo rozsah). Chybějící tři hodnoty — `REQUIRES_CHANGE_TICKET`, `DESTRUCTIVE_OPERATION`, `EXTERNAL_SIDE_EFFECT` — spadnou do `else -> false`, tedy `IntentSafetyGate.status = "satisfied"`, `blocksLowering = false`.

Ověřeno korespondenčně (mimo rozsah, jen jako doklad rozporu): `controls/CanonicalControlRequirementAuthority.kt:442-451` obsahuje **exhaustivní** `when (this: SafetyRequirement)` pokrývající všech 9 hodnot včetně těchto tří (`ControlRequirementKind.CHANGE_TICKET`, `SAFETY_GUARD`, `EXTERNAL_EFFECT_REVIEW`). To znamená, že autoritativní validace (`IntentCapabilityValidator` → `CanonicalControlRequirementAuthority.assess`) pravděpodobně politiku `requiresChangeTicket`/`destructiveOperation`/`externalSideEffect` správně vyhodnotí a zablokuje, zatímco **`IntentDecisionReport`/`intent-decision-report.json`** (samostatný veřejný artefakt dle README) pro stejný intent nahlásí „satisfied"/„not blocking" — tedy **rozporná, matoucí veřejná zpráva** pro stejný vstup.

**Scénář selhání:** Intent s `IntentPolicy(type=SAFETY, condition="requiresChangeTicket")` a bez odpovídajícího tiketu → `intent-decision-report.json` řekne, že lowering je povolen (`loweringDecision.allowed = true`, žádná blokující brána), zatímco skutečná pipeline (jiná, mimo-rozsahová vrstva) požadavek stejně zablokuje. Uživatel/AI čtoucí decision report dostane nesprávný signál.
**Návrh opravy:** `detectPolicySafetyGates` přepsat na `PolicyCondition.parse(policy.condition)` + `when (requirement: SafetyRequirement)` (exhaustivní, kompilátorem vynucené pokrytí), odstranit duplicitní stringovou normalizaci.

### 3.3 [MEDIUM] Kolize `ResultBindingNode`/`dependsOn` jmen po normalizaci `-`→`_` není zachycena validací duplicit

`intent/IntentToAstPlanner.kt:450` (`result(id) = ResultBindingNode(name = id.replace('-', '_'))`) a `:333` (`applyDependencies`, `dependencyIds.map { it.replace('-', '_') }`) normalizují ID kroku nahrazením pomlčky podtržítkem. `intent/IntentCapabilityValidator.kt:93-95` ale kontroluje duplicity **jen na přesné (nenormalizované) rovnosti** `stepIds.groupBy { it }`.

**Scénář selhání:** Intent se dvěma kroky `id = "build-image"` a `id = "build_image"` projde validací (jsou to různé stringy), ale po `IntentToAstPlanner.lowerStep` oba dostanou `ResultBindingNode(name = "build_image")` — stejné jméno výsledkové proměnné ve stejném `FlowNode.steps`. Cokoliv dál v pipeline, co čte `dependsOn`/result binding podle jména (downstream vrstvy `planner`/`validator`, mimo rozsah), může tiše spárovat závislost na špatný krok nebo přijmout kolizi beze slova.
**Návrh opravy:** Validovat unikátnost `stepIds` i na normalizovaném tvaru (`id.replace('-', '_')`) v `IntentCapabilityValidator`, případně zakázat současný výskyt `-` a `_` variant stejného základu jména.

### 3.4 [MEDIUM] Chybí kontrola duplicitních jmen `system`/`input`/`vars` — v obou nezávislých modelech

**Nativní Flow parser** (`parser/FlowParser.kt`): `parseInputBlock` (ř. 98-135), `parseVarsBlock` (ř. 137-150), `parseSystemsBlock` (ř. 152-192) nikdy nevolají `DeclarationOccurrences.declare()` pro jméno vstupu/proměnné/systému samotné — jen pro *vnořené* klíče (modifikátory `required`/`default`, per-systémové `config` klíče). To je nekonzistentní s tím, jak stejný soubor ošetřuje duplicity všude jinde: `parseParamBlock` (ř. 521-528), `parseMap` (ř. 260-274, `ExpressionParser.kt`), `parseTransform.select`, `parseAggregate.fields`, `match.when error`/`default` — všechny tyto **volají** `DeclarationOccurrences.declare()` a vyhodí strukturovanou `DuplicateDeclarationException`. Dva bloky `system "db" { type: postgres }` a `system "db" { type: mysql }` ve stejném `.flow` souboru projdou parserem beze slova a skončí jako dva prvky v `FlowNode.systems`.

**Standard Intent Model** (`intent/IntentYamlLoader.kt`, `toIntentSystem`, ř. 67-85, a `intent/IntentCapabilityValidator.kt:123-132`): stejná mezera — na rozdíl od `intent.workflows` (ř. 32-34), `intent.triggers` (ř. 49-50) a step `id` (ř. 93-95), které mají explicitní `groupBy{...}.filterValues{it.size>1}` kontrolu, **`intent.systems` duplicitní jméno nikdy nekontroluje**.

**Konkrétní pozorovaný dopad** (uvnitř rozsahu, ne jen teoretický): `intent/IntentToAstPlanner.kt:76`:
```kotlin
intent.systems.forEach { systems[it.name] = it.toSystemNode() }
```
`systems` je `LinkedHashMap<String, SystemNode>` — druhý systém se stejným jménem **tiše přepíše** první, bez jakékoli validační issue nebo poznámky v žádném reportu.

**Návrh opravy:** Přidat `DUPLICATE_INTENT_SYSTEM` kontrolu analogickou k workflow/trigger/step kontrolám v `IntentCapabilityValidator`; ve `FlowParser` použít `DeclarationOccurrences` i pro jména `input`/`vars`/`systems` bloků (per-flow scope, napříč opakovanými bloky stejného typu).

### 3.5 [MEDIUM] Duplicity `IntentWorkflow`/`IntentTrigger`/`IntentStep` jsou detekovány, ale ne odstraněny před dalším zpracováním

Navazuje na 3.4/3.3 — validátor `IntentCapabilityValidator.validate()` **hlásí** duplicity (jako `error`), takže `IntentValidationReport.valid = false` a `IntentToAstPlanner.plan()` (veřejná cesta) korektně selže na `assertValid()`. Toto **není bug** v hlavní veřejné cestě. Je to zmíněno zde jen jako upozornění na to, že `IntentToAstPlanner.planProgram(validated: ValidatedIntent)` (interní, ř. 66) je volatelná i s `ValidatedIntent`, jejíž report je nucen validní (`private constructor` invariant, viz sekce 2.3) — takže při skutečném použití veřejného API je toto riziko uzavřené. Ponecháno jako **informační** položka, ne jako samostatná chyba k opravě.

### 3.6 [LOW] Force-unwrap (`!!`) na `Token.rawValue` v šesti místech `FlowParser.kt`

Řádky **35, 58, 60, 68, 158, 291**, vždy ve tvaru `ts.expect(TokenType.STRING, "...").rawValue!!`. Bezpečné pouze díky neformální invariantě `Lexer.lexString()` (`parser/Lexer.kt:156`), že `rawValue` je pro STRING token *vždy* nastaven — nic v typovém systému to negarantuje a žádný komentář u `!!` to nevysvětluje. Kdyby v budoucnu vznikl STRING token jinou cestou (např. syntetický token při budoucí error-recovery/repair režimu), spadne na `NullPointerException` bez srozumitelné diagnostiky pozice.
**Návrh opravy:** `requireNotNull(...) { "internal: STRING token without rawValue at $line:$column" }` nebo modelovat STRING token samostatným ne-nullable typem.

### 3.7 [MEDIUM] Nekonzistentní dodržování vlastního principu „approve step se nikdy nesyntetizuje automaticky"

`scenarios/BaseScenarioPack.kt:60-66` (dokumentační komentář k `explicitApprovalRequested`):
> „Approval must be explicit. A production-like word may create a safety obligation, but it must never synthesize its own APPROVE step or APPROVAL policy. Otherwise the negative corpus case 'production deploy without approval' becomes unreachable."

`DeploymentScenarioPack`, `DatabaseMigrationScenarioPack`, `KubernetesMaintenanceScenarioPack` tento princip dodržují — `approve` krok přidávají jen když `explicitApprovalRequested(...)` vrátí true. Ale:
- `ProvisionScenarioPack.normalize` (`scenarios/MaintenanceScenarioPacks.kt:89`) přidává `IntentStep("approve-provision", StandardCapability.APPROVE, ...)` **nepodmíněně**, spolu s `IntentPolicy(..., IntentPolicyType.APPROVAL, "true", ...)`.
- `SecretRotationScenarioPack.normalize` (`scenarios/OperationsScenarioPacks.kt:150`) přidává `IntentStep("approve-rotation", StandardCapability.APPROVE)` **nepodmíněně**.

Může jít o záměrné produktové rozhodnutí („provisioning a rotace tajemství vždy vyžadují schválení"), ale (a) je to v přímém textovém rozporu s dokumentovaným principem v `BaseScenarioPack`, (b) není to nikde vysvětleno jako výjimka, (c) narušuje to konzistentnost negative-corpus testovatelnosti zmíněnou v komentáři — negativní scénář „provisioning bez schválení" nemůže nikdy nastat jako výstup normalizéru (protože se vždy dosyntetizuje), takže riziko chybějícího schválení tady nikdy neprojde stejnou cestou jako u Deployment/Migration/Maintenance.
**Návrh opravy:** Buď zdokumentovat výjimku explicitně (a proč), nebo — v souladu s principem „AI proposes, standard decides" — přesunout vynucení povinného schválení z normalizéru do validátoru/`CanonicalControlRequirementAuthority` (mimo rozsah), aby normalizace nikdy sama nefabrikovala bezpečnostní krok.

### 3.8 [LOW] Mrtvá/nedosažitelná větev — `environment == "production"`

`scenarios/DeliveryScenarioPacks.kt:28`:
```kotlin
val prod = environment == "prod" || environment == "production"
```
`environment` pochází z `environmentEntity()` (`BaseScenarioPack.kt:90-101`), která může vrátit pouze `"prod"`, `"dev"`, `"staging"`, `"qa"`, `"test"` nebo `null` — nikdy literál `"production"`. Druhá polovina podmínky je nedosažitelná (pravděpodobně pozůstatek z doby před zavedením kanonizační helper funkce).
**Návrh opravy:** Odstranit `|| environment == "production"`.

### 3.9 [LOW] Mrtvý kód — zpětně-kompatibilní fasády bez jediného volajícího

Ověřeno grepem přes celý `src/` (main i test):
- `intent/MandatorySafetyPolicy.kt` (`@Deprecated object MandatorySafetyPolicy`) — **0 volacích míst** mimo vlastní definici.
- `ai/normalization/RuleBasedIntentNormalizer.kt` (`@Deprecated class RuleBasedIntentNormalizer`) — **0 volacích míst** mimo vlastní definici.
- `intent/SafetyPolicyValidator.kt` — zmíněn jen jako řetězec cesty v `architecture/ArchitectureGovernance.kt:385` (governance seznam souborů), nikdy instanciován/volán.

Nejde o chybu v běhu, ale o udržovací zátěž — tři celé soubory existují jen jako `@Deprecated` obálky, které nikdo nepoužívá. Vhodné pro odklizení v rámci `.flow-agent/roadmap.yaml` deprecation cleanup položky (mimo rozsah k ověření zde).

### 3.10 [LOW] Vlastní duplicitní-klíč logika v `parseRetry` místo sdíleného `DeclarationOccurrences`

`parser/FlowParser.kt:346` (`parseRetry`) používá lokální `val seenKeys = mutableSetOf<String>()` a `if (!seenKeys.add(key)) throw ParseException("duplicate policy key '$path'", ...)` — funkčně ekvivalentní, ale **nezávisle re-implementované** vůči `DeclarationOccurrences`, které je použito pro identickou úlohu všude jinde v souboru. Důsledek: chyba duplicitního `retry` klíče je obyčejný `ParseException` bez strukturovaného `code` a bez druhé (`firstOccurrence`) lokace, zatímco jinde stejná třída chyby nese `FLOW_DUPLICATE_DECLARATION` + obě pozice. Nekonzistentní chybový kontrakt pro nástroje/IDE, které chyby parsují strojově.
**Návrh opravy:** Nahradit `seenKeys` sdíleným `DeclarationOccurrences`.

### 3.11 [LOW] Ztráta zdrojové pozice u nezavřeného `${ }` v šablonovém řetězci

`parser/ExpressionParser.kt:363` (`matchingBrace`): `throw ParseException("unterminated \${ } in template string", 0, 0)` — jediné místo v celém parseru, kde `ParseException` nenese skutečnou pozici tokenu (`line=0, column=0`). Ostatní chyby v obou parserech vždy nesou reálnou pozici.
**Návrh opravy:** Předat `startLine`/`startCol` volajícího STRING tokenu (`parseStringContent`, ř. 283) do `matchingBrace` a použít je v chybové zprávě.

### 3.12 [INFO] Drobná ztráta věrnosti u `Double`→`IntentNumber(isInteger=...)` v YAML loaderu

`intent/IntentYamlLoader.kt:166`: `is Double -> IntentNumber(this, isInteger = this % 1.0 == 0.0)`. Pokud YAML parser (mimo rozsah) předá `1.0` jako Kotlin `Double`, výsledek bude označen `isInteger = true`, ačkoliv zdrojový zápis byl explicitně desetinný. `NumberLiteralNode.isInteger` je dokumentovaně „preserves whether the source token was an integer, for faithful generation" (`ast/ExpressionNodes.kt:13`) — u YAML-odvozených hodnot tato věrnost není zaručena stejně jako u přímo parsovaného Flow zdroje (`Lexer.lexNumber`, které `isInt` odvozuje ze skutečné přítomnosti tečky ve zdroji). Nízké riziko, ovlivňuje jen kosmetiku znovu-vygenerovaného zdroje, ne sémantiku.

---

## 4. Architektonická pozorování

**Shoda s principy README — v zásadě velmi dobrá.** V celé vrstvě nebyl nalezen jediný odkaz na Jenkins/GitHub Actions/Tekton-specifickou syntaxi, žádný runtime executor, žádný plugin/SPI mechanismus (scénářové balíčky jsou uzavřený, hardcoded seznam — `ScenarioPackRegistry.packs`), a žádné tiché „fallback" chování bez evidence (`TargetPortabilityEvidence.deferred`, `ReferenceAdapterProjectionMatrix.evaluate` explicitně `error()`-ují, pokud chybí evidence místo aby tiše předstíraly úspěch).

**„AI proposes, standard decides" je skutečně implementováno, ne jen deklarováno.** `IntentProposalReview` je nezávislý na `AiIntentResponse.assertUsableForLowering()` (které důvěřuje sebehodnocení poskytovatele) a re-odvozuje verdikt čistě z `IntentCapabilityValidator`. To je přesně ten bezpečnostní hraniční bod, který README vyžaduje, a je dobře zdokumentován i v kódu (`IntentProposalReview.kt:64-73`).

**Vzor „unforgeable evidence" je použit konzistentně a smysluplně** na třech místech: `ValidatedIntent` (private constructor, `intent/IntentToAstPlanner.kt`), `IntentProposalReviewEvidence` (`init{}` invarianty vážící `Accepted`/`Rejected` na skutečný obsah validace), `LoweredIntentProgram`/`LoweredIntentWorkflow` (`init{}` požadavky na neprázdnost a konzistenci trigger-routingu). To je solidní obranné programování a jasně signalizuje zralý architektonický záměr — snížit riziko, že se „rozhodnutí" a „důkaz rozhodnutí" v kódu rozejdou.

**Hlavní systémový nedostatek: duplikovaná/rozcházející se heuristika napříč nezávisle vyvíjenými soubory.** Nálezy 3.1 a 3.2 nejsou izolované chyby — jsou to instance jednoho vzoru: stejná sémantická otázka („je to produkce?", „co znamená tato bezpečnostní politika?") se řeší na více místech nezávisle, s různou mírou pečlivosti. `IntentSourceDirectiveAuthority` (`intent/IntentSourceDirectiveAuthority.kt`) je přitom navržena přesně jako **jediná lexikální autorita** pro tento typ úlohy (BACKUP/REPOSITORY/NOTIFICATION/APPROVAL/ROLLBACK/RESTORE koncepty) a je použita důsledně — ale „produkce" mezi její koncepty nepatří, a tak si každý spotřebitel napsal vlastní, různě kvalitní verzi. Doporučení: rozšířit `IntentSourceDirectiveConcept` o `PRODUCTION_ENVIRONMENT` (nebo vytvořit analogickou sdílenou utilitu), a `PolicyCondition`/`SafetyRequirement` důsledně používat i v `IntentDecisionAnalyzer`, ne jen v `controls/` (mimo rozsah).

**Dvojí paralelní datový model (Flow-nativní vs. Intent-model) je záměrný a odpovídá README vrstvení**, ale zvyšuje plochu pro přesně ten typ mezery, který je popsán v 3.4: kontrola implementovaná pro jeden model (`intent.workflows`/`triggers`/`steps` duplicity) není zrcadlena pro analogickou konstrukci ve druhém modelu (`intent.systems`) ani v nativním parseru (`system`/`input`/`vars` bloky). Doporučuji jednotný checklist validací (duplicitní jméno, prázdné jméno, neznámé pole) aplikovaný na *všechny* pojmenované deklarace v obou modelech, ne odvozovat ad hoc od případu k případu.

**Scénářové balíčky mají nekonzistentní politiku ohledně automatické syntézy `approve` kroku** (3.7) — to není jen kosmetická nekonzistence, ale přímo se to dotýká bezpečnostního invariantu, který si projekt sám stanovil textem v kódu. Vzhledem k tomu, že README explicitně zmiňuje „production deployment without explicit approval" jako blokující případ jako jeden z hlavních příkladů validace, je žádoucí, aby analogické chování (kdy se schválení syntetizuje vs. kdy se jen vyžaduje) bylo jednotné a zdokumentované napříč všemi 11 balíčky.

**Kvalita `IntentYamlLoader` a `PolicyCondition` je nadprůměrná** — striktní, fail-closed, bez substring-inference, s dobře navrženými chybovými kódy a JSON-path lokalizací. Tyto dva soubory by měly sloužit jako referenční vzor pro zbytek vrstvy (viz 3.2, kde tento vzor nebyl znovupoužit).

**Testovatelnost/konformita:** `scenarios/ReferenceScenarioMatrix.kt` a `ReferenceAdapterProjectionMatrix.kt` tvoří promyšlený, evidence-řízený referenční korpus (pozitivní i negativní pokrytí, jak README vyžaduje: „negative coverage for risky capabilities"). Nebyla nalezena žádná snaha „ohýbat kód, aby prošly testy" (README zakázané chování) — naopak, `unsafeCleanupWithoutApproval()` scénář je přesně ten typ negativního testu, který README žádá.

**Mrtvý kód (3.9) je izolovaný a nízkorizikový**, ale ve třech souborech navíc — vhodný kandidát na úklid v rámci existujícího deprecation procesu (soubory už nesou `@Deprecated`, jen chybí následné odstranění).

---

## 5. Statistická tabulka

Metodika: „počet tříd" = všechny top-level `class`/`data class`/`object`/`interface`/`sealed interface`/`enum class` (včetně `internal`/`private`, aby odpovídalo požadavku sekce 2 „VŠECHNY"). „Počet veřejných funkcí" = deklarace `fun` s viditelností **ne** `private` ani `internal` (tj. výchozí `public`, `protected` i explicitní `public`) — u `protected` funkcí v `abstract class BaseScenarioPack` jde o rozšířené API pro podděděné balíčky, proto jsou zahrnuty.

| Soubor | Řádky | Počet tříd/objektů/rozhraní/enumů | Počet veřejných funkcí |
|---|---:|---:|---:|
| `parser/Token.kt` | 36 | 3 | 2 |
| `parser/Lexer.kt` | 160 | 1 | 2 |
| `parser/TokenStream.kt` | 103 | 5 | 15 |
| `parser/DeclarationOccurrences.kt` | 53 | 3 | 1 |
| `parser/ExpressionParser.kt` | 365 | 2 | 4 |
| `parser/FlowParser.kt` | 584 | 2 | 2 |
| **parser/ celkem** | **1 301** | **16** | **26** |
| `ast/AstNodes.kt` | 275 | 36 | 0 |
| `ast/ExpressionNodes.kt` | 114 | 18 | 0 |
| **ast/ celkem** | **389** | **54** | **0** |
| `intent/IntentModel.kt` | 199 | 24 | 2 |
| `intent/IntentYamlLoader.kt` | 277 | 2 | 3 |
| `intent/CanonicalIntentMeaning.kt` | 309 | 9 | 4 |
| `intent/IntentBindingContracts.kt` | 127 | 5 | 3 |
| `intent/IntentCapabilityValidator.kt` | 278 | 5 | 3 |
| `intent/IntentToAstPlanner.kt` | 460 | 4 | 3 |
| `intent/IntentDesignAnalyzer.kt` | 137 | 4 | 2 |
| `intent/IntentDecisionAnalyzer.kt` | 302 | 8 | 1 |
| `intent/IntentSourceDirectiveAuthority.kt` | 350 | 7 | 4 |
| `intent/IntentSourceContradictionAuthority.kt` | 51 | 1 | 1 |
| `intent/IntentSystemTypeAuthority.kt` | 17 | 1 | 1 |
| `intent/StandardCapabilityCompatibility.kt` | 100 | 3 | 2 |
| `intent/PolicyCondition.kt` | 123 | 11 | 4 |
| `intent/ConventionResolver.kt` | 28 | 1 | 1 |
| `intent/IntentExamples.kt` | 42 | 1 | 0 |
| `intent/LoweredIntentProgram.kt` | 55 | 2 | 1 |
| `intent/MandatorySafetyPolicy.kt` | 20 | 1 | 1 |
| `intent/SafetyPolicyValidator.kt` | 20 | 1 | 1 |
| **intent/ celkem** | **2 895** | **90** | **37** |
| `ai/normalization/AiIntentNormalization.kt` | 168 | 16 | 3 |
| `ai/normalization/TargetPortabilityDisposition.kt` | 5 | 1 | 0 |
| `ai/normalization/IntentProposalReview.kt` | 97 | 5 | 4 |
| `ai/normalization/RuleBasedIntentNormalizer.kt` | 27 | 3 | 2 |
| **ai/normalization/ celkem** | **297** | **25** | **9** |
| `scenarios/BaseScenarioPack.kt` | 307 | 1 | 34 |
| `scenarios/ScenarioPacks.kt` | 168 | 5 | 6 |
| `scenarios/DeliveryScenarioPacks.kt` | 208 | 3 | 4 |
| `scenarios/MaintenanceScenarioPacks.kt` | 198 | 5 | 7 |
| `scenarios/OperationsScenarioPacks.kt` | 295 | 5 | 5 |
| `scenarios/ReferenceAdapterProjectionMatrix.kt` | 71 | 3 | 1 |
| `scenarios/ReferenceScenarioMatrix.kt` | 438 | 6 | 3 |
| **scenarios/ celkem** | **1 685** | **28** | **60** |
| **CELKEM (37 souborů)** | **6 567** | **213** | **132** |

Poznámka k „počtu tříd": vysoké číslo u `ast/` a `intent/IntentModel.kt`/`CanonicalIntentMeaning.kt` odráží záměrně datově-modelový charakter těchto souborů (mnoho malých `data class`/`enum class` bez chování) — to je zdravý návrh pro cílově-neutrální model, ne code smell.

---

## Shrnutí

Analyzováno bylo všech 37 souborů front-end vrstvy FlowAi (6 567 řádků): `parser/` (Flow DSL lexer+parser), `ast/` (kanonický AST model), `intent/` (Standard Intent Model, validace, binding, lowering), `ai/normalization/` (AI trust-boundary kontrakt) a `scenarios/` (12 deterministických scénářových balíčků + referenční korpus). Vrstva je architektonicky velmi dobře sladěná s principy README: žádná target-specifická syntaxe, žádný runtime executor, žádný plugin framework, žádný TODO/FIXME, žádný neošetřený catch-all. Trust boundary „AI proposes, standard decides" (`IntentProposalReview`) je skutečně implementována, nejen deklarována, a napříč kódem se opakovaně objevuje solidní vzor „unforgeable evidence" (private-constructor invarianty).

Nalezeno bylo 12 konkrétních zjištění: **2 HIGH**, **4 MEDIUM**, **5 LOW**, **1 INFO**.

Nejzávažnější: (1) **tři nezávislé, nekonzistentní implementace detekce „produkčního prostředí"** (`IntentDecisionAnalyzer.isProduction` přesnou shodou minimalizuje pokrytí a nechytí `"prod-eu"`/`"production-east"`, zatímco `KubernetesMaintenanceScenarioPack` naopak naivním substringem `contains("prod")` generuje false-positivy jako „product"/„reproducible") — přímo oslabuje bezpečnostní bránu „produkční deploy bez schválení" zmíněnou v README. (2) **`IntentDecisionAnalyzer.detectPolicySafetyGates`** je neúplná ruční duplikace kanonického `PolicyCondition`/`SafetyRequirement` slovníku — chybí 3 z 9 hodnot (`REQUIRES_CHANGE_TICKET`, `DESTRUCTIVE_OPERATION`, `EXTERNAL_SIDE_EFFECT`), takže veřejný `intent-decision-report.json` může tvrdit „not blocking" v rozporu se skutečnou autoritativní validací.

Medium nálezy: kolize `ResultBindingNode` jmen po `-`→`_` normalizaci nezachycená validací; chybějící kontrola duplicitních jmen `system`/`input`/`vars` v obou paralelních modelech (Flow parser i Intent loader/validator) — s konkrétním prokázaným tichým „last-wins" přepsáním v `IntentToAstPlanner`; nekonzistentní politika automatické syntézy `approve` kroku (Provision/SecretRotation balíčky porušují vlastní zdokumentovaný princip „approval must be explicit").

Low nálezy: 6× force-unwrap `!!` na `Token.rawValue` (fragilní, ale aktuálně bezpečné); mrtvý kód (`MandatorySafetyPolicy`, `RuleBasedIntentNormalizer` — 0 volání v celém repu); nedosažitelná větev; nekonzistentní duplicate-key mechanismus v `parseRetry`; ztráta pozice u nezavřeného `${}`.
