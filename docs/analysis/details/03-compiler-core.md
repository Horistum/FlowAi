# FlowAi (Horistum) — Analýza jádra kompilátoru
## compiler / lowering / core / topology / projection / modules

Rozsah: 32 souborů, 8 548 řádků, 6 balíčků. Každý soubor přečten celý.

---

## 1. Přehled vrstvy

Tato vrstva je skutečné jádro Horistum kompilátoru — implementuje řetězec
`Flow AST / Intent → path-sensitive dataflow analýza → Execution Plan →
CanonicalExecutionGraph (typovaný, cílově-neutrální) → validace → digest →
zpětná projekce do kompatibilních pohledů (ExecutionPlan/CanonicalExecutionPlan)`.
Modul `modules/` navíc definuje kontraktní model pro Flow moduly (systémové typy,
akce, efekty, kontinuitu, bezpečnost), `topology/` odvozuje požadavky na
prováděcí topologii (izolace/životnost/perzistence/propagace) a `projection/`
definuje vazby pro cílovou projekci (target manifest).

Klíčové architektonické rysy pozorované napříč vrstvou:

- **Cílová neutralita jako invariant, ne konvence.** `CanonicalExecutionGraph`
  (compiler/CanonicalExecutionGraph.kt) explicitně vylučuje frontend
  provenienci, diagnostiku, implementační vazby (`module/action/target`) a
  cílovou metadatu — ty žijí odděleně v `CanonicalExecutionBindingSet`.
- **Fail-closed / žádný tichý fallback.** V celém rozsahu (8 548 řádků) nebyl
  nalezen jediný force-unwrap (`!!`), žádné prázdné `catch` bloky, žádné
  `TODO/FIXME/HACK`. Chybové stavy jsou vždy explicitní `require()`/`error()`
  nebo typované `Rejected`/`Issue` výsledky.
- **Dvojitá verifikace / "nevěř vlastnímu výstupu".** `CompilationAuthorization`
  po sestavení grafu znovu validuje, počítá digest a **zpětně projektuje** graf
  do `ExecutionPlan`, který musí být strukturálně roven původnímu plánovačem
  vytvořenému plánu (`compiler/CompilationAuthorization.kt:340-343`). Podobně
  `topology/ExecutionPlanCanonicalTopologyAuthority` nezávisle přepočítává
  topologické požadavky z retained provenience místo důvěry uloženým hodnotám.
- **Path-sensitive dataflow jako sdílený zdroj pravdy.** `FlowAvailabilityAnalyzer`
  počítá jedinou immutable dataflow analýzu použitou jak validátorem, tak
  plánovačem (AR-02A), včetně explicitního `merge()` (phi-uzel) mechanismu se
  striktní kontrolou pokrytí cest.
- **Žádná souběžnost.** Celá vrstva je bez sdíleného mutable stavu, bez
  `Thread`/`synchronized`/`Atomic*`/`lateinit var`. Jediné `var` jsou lokální
  akumulátory uvnitř jedné funkce (viz `core/FlowAvailabilityAnalyzer.kt`).
  Race condition v tomto rozsahu je tedy strukturálně vyloučena — kompilátor je
  čistě synchronní, jednoprůchodový pipeline nad immutable daty.
- **Modulární kontrakty se striktní YAML branou.** `CanonicalModuleLoader` je
  jediná strukturální validační autorita pro `.flow` moduly; `ModuleYamlLoader`
  je až následný dekodér, který smí běžet jen po úspěšné validaci.

---

## 2. Inventář souborů a tříd

### 2.1 `compiler/` (11 souborů, 4 517 řádků)

#### `CanonicalExecutionGraph.kt` (589 ř.)
Definice typovaného, cílově-neutrálního grafu.
- `CanonicalExecutionGraph` (data class) — kořenový typ: workflows, inputs,
  triggers, outputs, capabilities, control/topology requirements, value
  merges, uzly, hrany závislostí. `init{}` vynucuje verzi grafu a neprázdný
  název flow.
- `CanonicalWorkflowId/NodeId/CapabilityId/ValueTypeId/MergeId` (`@JvmInline value class`) —
  typované, jednořádkové, neprázdné identifikátory (`requireGraphText`).
- `CanonicalValueMergeInput`, `CanonicalValueMerge` — typovaný phi-merge
  kontrakt s invarianty: min. 2 vstupy, žádné duplicitní cesty, přesné pokrytí
  cest (`coverage.toSet() == paths.toSet() && coverage.size == paths.size`).
- `CanonicalWorkflowFailureDisposition` (enum PROPAGATE/RECOVER),
  `CanonicalWorkflowFailureHandlerEntry/Region/Policy` — typovaná politika
  selhání workflow s vynuceným handlerem při RECOVER.
- `CanonicalWorkflow` — workflow s kořenovými uzly a politikou selhání.
- `CanonicalGraphInput/Trigger/Output`, `CanonicalGraphSchedule` — typované
  I/O a triggery s `fromWire()` validací enumů (`CanonicalTriggerKind`,
  `CanonicalScheduleKind`) a kontrolou konzistence schedule/event dle druhu triggeru.
- `CanonicalNodeSemantics`, `CanonicalSafetyFacet` — sémantika uzlu
  (capability, effects, parametry, výstupy, required capabilities, safety).
- `CanonicalExecutionNodeKind` (enum, 15 druhů) a `sealed interface
  CanonicalExecutionNode` s `structuralChildren(): List<CanonicalNodeId>`.
- 13 konkrétních uzlů: `CanonicalTaskNode`, `CanonicalApprovalNode`,
  `CanonicalConditionNode`, `CanonicalLoopNode`, `CanonicalParallelBranch/Node`,
  `CanonicalMatchCase/Node`, `CanonicalRetryNode`, `CanonicalTryNode`,
  `CanonicalDataOperationNode` (+`CanonicalDataOperationKind`),
  `CanonicalControlOperationNode` (+`CanonicalControlOperationKind`).
- `CanonicalDependencyKind/Evidence/Resolution` (enumy) a
  `CanonicalDependencyEdge` — hrana závislosti se sémantikou ORDERING/VALUE/
  WORKSPACE/STATE, evidencí a stavem RESOLVED/UNRESOLVED/AMBIGUOUS; vynucuje
  konzistenci `stateLifetime` jen pro STATE hrany a `sourceNodeId`/`candidates`
  podle rezoluce.

#### `CanonicalExecutionGraphBindings.kt` (147 ř.)
- `TaskParameterProjectionMode` (enum) — jak se mapují task parametry
  zpět na `inputs`/`params` kompatibilní pohled.
- `CanonicalTaskBinding` — implementační vazba uzlu (module/action/target),
  úmyslně **mimo** sémantický graf.
- `CanonicalNodeProjectionMetadata` — přesná kompatibilní identita uzlu
  (plán-node-id, druh, zdrojová provenience).
- `ExecutionPlanProjectionMetadata`, `WorkflowFailureCompatibilityProjectionMetadata`,
  `WorkflowExecutionPlanProjectionMetadata`, `ExecutionProgramProjectionMetadata` —
  metadata potřebná k bezztrátové zpětné projekci na `ExecutionPlan`.
- `CanonicalExecutionBindingSet` — kolekce všech binding záznamů +
  `requireWorkflowPlan()`, `planMetadata` (getter vynucující přesně 1 workflow —
  legacy kompatibilita).
- `CanonicalExecutionGraphBuild` — pár (graph, bindings).

#### `CanonicalExecutionGraphBuilder.kt` (603 ř.)
`object CanonicalExecutionGraphBuilder` — jediný stavební mechanismus grafu.
- `build(plan: ExecutionPlan): CanonicalExecutionGraphBuild` — veřejný vstup
  pro jednoduchý plán bez merge kontraktů; vynucuje, že plán neobsahuje
  explicitní merge evidenci bez typovaných kontraktů.
- `internal build(plan, mergeContracts, producerNodeIds, failurePolicy)` —
  plná varianta jednoho workflow.
- `internal build(program: ExecutionProgramPlanningResult)` — multi-workflow
  varianta; sjednocuje control requirements/evidence/topology napříč workflow
  přes `mergeIdentical()` (vyžaduje identické duplicity, jinak `error()`).
- `private fun buildWorkflow(...)` — hlavní rekurzivní `visit()` procházející
  `PlanNode` strom, přiřazuje deterministické `CanonicalNodeId` (buď z autorské
  `sourceId`, nebo ze strukturální cesty), staví task bindings, node metadata,
  merge kontrakty a `CanonicalWorkflowFailurePolicy`.
- `private fun requireFailureProjection(...)` — extrahuje poslední `TryPlanNode`
  jako kompatibilní hranici failure-handleru, pokud politika existuje.
- Několik `private fun *.toCanonical()` mapovacích rozšíření
  (PlanInput/Trigger/Schedule/Output/DependencyRelation → Canonical*).
- `private fun identity(kind, value) = "$kind:${value.length}:$value"` —
  deterministické, délkově-prefixované ID kódování (bez kolizí).

#### `CanonicalExecutionGraphDigest.kt` (301 ř.)
- `CanonicalExecutionGraphDigest` (`value class`, privátní konstruktor) —
  SHA-256 otisk, `fromCanonicalBytes()`.
- `object CanonicalExecutionGraphDigestComputer` — `digest(graph)`,
  `requireMatches(graph, expected)`. Vlastní ruční kanonikalizace
  (`record`/`atom`/`ordered`/`unordered`) s délkovým prefixováním proti
  kolizím serializace.
- Deprecated alias `CanonicalExecutionGraphDigestAuthority`.
  *(Viz nález B1 — digest vynechává pole `evidence`, `path`, `evidenceReference`
  hrany závislosti.)*

#### `CanonicalExecutionGraphPlanMappings.kt` (43 ř.)
Čisté 1:1 mapovací funkce mezi `PlanDependencyKind/Evidence/Resolution` a
jejich `Canonical*` protějšky (obousměrně, `toCanonical()`/`toPlan()`).

#### `CanonicalExecutionGraphProjection.kt` (554 ř.)
`object CanonicalExecutionGraphProjection` — zpětná projekce grafu na
kompatibilní pohledy.
- `toWorkflowExecutionPlanSet(build/graph+bindings): WorkflowExecutionPlanSet`
- `toExecutionPlan(...)`, `toCanonicalExecutionPlan(...)` — pohodlné
  jednoworkflow zkratky (vyžadují přesně 1 workflow).
- `private fun projectExecutionPlan(...)` a `projectCanonicalExecutionPlan(...)` —
  téměř identická rekurzivní `project(id)` funkce, která z `CanonicalExecutionNode`
  + `CanonicalNodeProjectionMetadata` + `CanonicalTaskBinding` rekonstruuje
  `PlanNode`/`CanonicalPlanNode` strom, včetně `dependenciesFor(id)` (filtruje
  ORDERING+RESOLVED hrany cílené na daný uzel).
- `private fun projectFailurePolicy(...)`.

#### `CanonicalExecutionGraphValidator.kt` (707 ř.)
`object CanonicalExecutionGraphValidator` — hlavní validační autorita.
- `requireValid(build)` / `validate(build): CanonicalExecutionGraphValidationReport`.
- `validateStructuralAcyclic` — DFS detekce cyklů (white/gray/black) nad
  `structuralChildren()` z kořenů workflow + failure-handler regionů.
- `validateWorkflowFailureRegions` — zajišťuje, že normální a failure-handler
  regiony se nepřekrývají a hrany/kandidáti/outputy mezi nimi nekříží hranici.
- `validateEdges` — dangling zdroj/cíl/kandidát/cesta, workflow-crossing,
  self-loop, duplicitní hrany.
- `validateMerges` — cílový uzel musí být `SET` s přesně odvozeným `detail`
  textem, typová kompatibilita vstupů, přítomnost odpovídajících VALUE i
  ORDERING hran pro každý merge vstup, absence "neowned" VALUE hran.
- `validateOrderingAcyclic` — DFS cyklů **jen** nad `ORDERING`+`RESOLVED`
  hranami. *(Viz nález B2.)*
- `validateControls` — duplicity/dangling evidence, kontradiktorní scope, a
  nezávislé přepočítání `ControlDecision` (`deriveControlDecision`) porovnané s
  uloženým — **druhý, nezávislý orákl** (komentář to explicitně říká).
- `validateBindings` — 1:1 shoda task uzlů a bindings, kontrola
  `parameterProjectionMode` konzistence, projection-metadata duplicit/orphans,
  kolize plán-node-id napříč workflow.
- `deriveControlDecision(graph)` — nezávislá reimplementace rozhodovací logiky.

#### `CompilationAuthorization.kt` (426 ř.)
- `CompilationAuthorizationOrigin` (enum COMPILATION_UNIT/COMPATIBILITY_PLAN).
- `CompilationValidationBinding` — vázaný digest + zdrojový SHA + platnost
  validací; `init{}` vynucuje kombinace polí podle origin/proposal review.
- `CompilationAuthorization` (interní konstruktor) — nezfalšovatelná
  autorizace jednoho grafu; `requireIntegrity()`, `inspectionView()`,
  `requireMatchingGraph(candidate, digest)` — **znovu validuje kandidátní
  graf, ověřuje digest, přeprojektuje a porovná s uloženým
  `workflowPlanSet`** (obranná kopie proti mutaci po autorizaci).
- `AuthorizedCanonicalTask`, `requireAuthorizedTask(task)` — rozšiřující
  funkce resolvující kompatibilní `TaskNode` zpět na autorizovaný kanonický
  uzel + binding, s ověřením shody `module/action/target`.
- `object CanonicalExecutionGraphGate` — veřejná brána:
  `authorizeCompilation(...)` (3 přetížení pro plán/program/legacy), `internal
  authorize(...)`, `authorizeProgram(...)`, `authorizeCompatibilityPlan(...)`.
  `authorizeProgram` obsahuje podrobnou diagnostiku rozdílů polí (`differences`)
  při neshodě projekce — velmi užitečné pro ladění.

#### `CompilationContracts.kt` (422 ř.)
- `CompilationFrontend` (enum FLOW_SOURCE/INTENT_YAML/REVIEWED_AI_PROPOSAL).
- `CompilationSource` — immutable provenience zdrojových bajtů (SHA-256,
  velikost), `fromBytes()` factory.
- `CompilationInput` (sealed) — `FlowSourceCompilationInput`,
  `IntentCompilationInput`, `ReviewedAiProposalCompilationInput`.
- `FrontendCompilationEvidence` (sealed) + `IntentCompilationEvidence` (sealed) —
  `FlowSourceCompilationEvidence`, `IntentFrontendCompilationEvidence`,
  `ReviewedAiProposalCompilationEvidence` (vynucuje `review.intent == intent`,
  `review.validation == validation`).
- `CompilationStage` (enum, 6 fází), `CompilationDiagnosticSeverity`,
  `CompilationDiagnostic`, `CompilationRejection` (vynucuje min. 1 ERROR
  diagnostiku a konzistentní AI-proposal evidenci).
- `CompiledWorkflow` — název+AST+validace jednoho workflow.
- `CompilationUnit` (privátní konstruktor) — plně autorizovaná přijatá
  kompilace; `companion.from(...)`/`fromProgram(...)` volají
  `CanonicalExecutionGraphGate`. `requireIntentEvidence()`,
  `requireReviewedAiProposalEvidence()`.
- `CompilationResult` (sealed: Accepted/Rejected), `requireAccepted()`.

#### `FlowCompilationService.kt` (462 ř.)
`class FlowCompilationService` — orchestrace celé kompilace.
- `compile(input: CompilationInput): CompilationResult` — dispatch podle typu.
- `private fun compileFlowSource/compileIntent/compileReviewedAiProposal(...)`.
- `private fun compileIntentDocument(...)` — validace Intent → lowering
  (`intentPlanner.planProgram`) → `compileProgram(...)`.
- `private fun compileProgram(source, evidence, program: LoweredIntentProgram)` —
  pro každý workflow: availability analýza → Flow validace → plánování
  (`flowPlanner.planWithProvenance`) → sestaví `ExecutionProgramPlanningResult`
  → `CompilationUnit.fromProgram`. Vynucuje shodný `inputs` kontrakt napříč
  nezávisle naplánovanými workflow (`require(drafts.all { it.planning.plan.inputs == inputs })`).
- `private fun compileAst(...)` — jednoduchá cesta pro čistý Flow Source.
- Pomocné `ensureErrorDiagnostic()`, `rejection()`.

#### `WorkflowFailureAuthorization.kt` (67 ř.)
- `AuthorizedWorkflowFailureProjection` (interní konstruktor).
- `requireSingleWorkflowFailureProjection()` (rozšíření na
  `CompilationAuthorization`) — rekonstruuje normální/handler uzly a ověřuje,
  že kompatibilní `TryPlanNode` hranice odpovídá typované politice
  (prázdné tělo, shodné error-handler ID).

---

### 2.2 `lowering/` (4 soubory, 968 řádků)

#### `IntentExpressionParser.kt` (8 ř.)
- `fun interface IntentExpressionParser { fun parse(source: String): ExpressionNode }` —
  syntaxový port dodávaný frontendem (žádná parser závislost v jádru).

#### `IntentLoweringContracts.kt` (797 ř.)
`object IntentLoweringAuthority` — artefaktová evidence loweringu
Intent→ExecutionPlan.
- `canonicalSystemType(type)`.
- `sourceMetadata(intent, expressions): IntentSourceMetadata` — staví
  seznam `IntentSourceField` (očekávaných zdroj→cíl hodnot) se SHA-256 digesty;
  vynucuje unikátnost identit v obou směrech.
- `report(plan): IntentLoweringReport` a `internal report(flowName, inputs,
  triggers, sourceIntent, workflowPlans)` — **jediné veřejné API produkující
  report**; pro každé pole znovu resolvuje cílovou hodnotu z konkrétního
  `ExecutionPlan` a **porovnává digest** s očekávaným (`require(targetDigest ==
  field.expectedTargetDigest)`) — reprodukovatelná, nefalšovatelná evidence.
- `private fun sourceFields(...)` — generuje pole pro name/description/inputs/
  systems/triggers/workflows/steps/policies/failure.
- `private fun MutableList<IntentSourceField>.addStepFields(...)` — pole na
  úrovni jednoho kroku (id, capability, description, uses→binding, requires,
  produces, params).
- `private fun validateAuthoredOrderingGraph(workflowPlans, sourceIntent)` —
  **dvousměrné** ověření, že `requires` hrany z Intentu odpovídají přesně
  množině `DECLARED_ORDERING` relací v plánu (žádné chybějící, žádné navíc).
- `private fun resolveTarget/resolveInput/resolveTrigger/resolveSourceIntent/
  resolveNode(...)` — resolvery cílové identity → hodnota.
  *(Viz nález B4 — `sourceNode()`/`resolveNode()` dělají lineární prohledávání
  přes celý strom uzlů pro každé pole.)*
- `private fun projectedInputType(...)`, `preserved()/transformed()/field()`,
  `canonicalRecord()/canonicalList()/digest()/segment()/unsegment()`,
  `missing()/unique()`.

#### `IntentLoweringMetadata.kt` (82 ř.)
Čisté datové typy: `IntentLoweringDisposition` (PRESERVED/TRANSFORMED),
`IntentSourceField`, `IntentLoweringEvidence`, `IntentLoweringReport`
(s `CONTRACT_VERSION`/`ARTIFACT_KIND`), `IntentSourceMetadata`,
`IntentWorkflowMetadata`, `IntentPolicyMetadata`, `IntentSystemMetadata`,
`IntentFailureMetadata`.

#### `IntentValueExpressionLowering.kt` (61 ř.)
`object IntentValueExpressionLowering` — **jediná** konverze
`IntentValue`→`ExpressionNode`, sdílená evidencí loweringu i skutečným AST
plánovačem (takže evidence nemůže validovat jinou reprezentaci, než jaká je
skutečně produkována).
- `lower(value, expressions): ExpressionNode` — exhaustivní `when` nad
  `IntentString/Number/Boolean/Null/SecretRef/Ref/Expression/List/Object`.
- `private fun lowerString(...)` — parsuje `${...}` interpolace regexem
  `\$\{([^}]+)\}` do `TemplateStringNode`.

---

### 2.3 `core/` (4 soubory, 1 337 řádků)

#### `FlowAvailabilityAnalyzer.kt` (693 ř.)
`class FlowAvailabilityAnalyzer` — jediný veřejný vstup pro path-sensitive
dataflow analýzu.
- `analyze(document): FlowAvailabilityAnalysis`, `internal analyze(document, workflow)`.
- `private class Builder` — veškerá logika:
  - `analyzeStatements/analyzeStatement` — exhaustivní `when` nad
    `StatementNode` (Action/If/For/Parallel/Match/Retry/Try/Approve/Transform/
    Aggregate/Validate/Set/Fail/Skip/Expect/ErrorHandler).
  - `analyzeAction/analyzeApprove` — inspekce cílů/parametrů, `dependsOn`
    (s `allowUniquePartialProducer = true`), definice výstupů, zpracování
    `handler.rules` (Expect/When).
  - `analyzeIf/analyzeFor/analyzeParallel/analyzeMatch/analyzeTry` —
    větvení stavu (`enterAlternative`) a sloučení (`joinAlternatives`/
    `joinConcurrent`). *(Viz nález B3 — kombinatorický růst `paths`.)*
  - `analyzeCondition(...)` — rozklad `exists`/`not`/`and`/`or` do
    `ConditionStates(whenTrue, whenFalse)` pro path refinement.
  - `inspectExpression/inspectBinding` — rekurzivní návštěva výrazů; detekuje
    nevalidní `merge(...)` mimo kontext `set` (`MERGE_CONTEXT_INVALID`).
  - `analyzeMerge(...)` — plná validace explicitního `merge()`: arita,
    jednoduchá vazba na referenci, duplicity, nedefinované/externí/ambiguous
    zdroje, nemergeovatelné (concurrent) cesty, úplné a nepřekrývající se
    pokrytí cest, typová kompatibilita — poté staví `FlowMergeContract`.
  - `inferValueType`, `define`, `refinePresent/refineAbsent`,
    `withImplicitResult`.

#### `FlowAvailabilityJoins.kt` (118 ř.)
- `ProducerKey`, `ConditionStates` (internal data classes).
- `bindingAliases(name)` — `{name, name s '-'→'_'}` (kebab/snake kompatibilita).
- `issueForState(...)` — mapuje stav vazby na `FlowAvailabilityIssue`
  (UNRESOLVED_REFERENCE / VALUE_PRODUCER_AMBIGUOUS / VALUE_MAY_BE_UNDEFINED).
- `joinAlternatives(states)` — sloučí alternativní (vzájemně vylučující se)
  stavy; pokud žádný není dosažitelný, vrátí `unreachable()` prvního.
- `joinAlternativeBinding(states, paths)` — sloučí `producerPaths` napříč
  alternativami do `FlowBindingState.fromCoverage(...)`.
- `joinConcurrent(input, branches)` — join paralelních větví; nedosažitelná
  libovolná větev ⇒ celé sloučení nedosažitelné.
- `IMPLICIT_RESULT_NAMES` — 21 vestavěných jmen implicitního výsledku
  (ok/status/code/data/text/…).

#### `FlowAvailabilityModel.kt` (496 ř.)
- `FlowValueAvailability` (UNDEFINED/MAYBE_DEFINED/DEFINITELY_DEFINED/MERGED),
  `FlowAvailabilityReason` (NONE/PARTIAL_PATHS/AMBIGUOUS_PRODUCERS/EXPLICIT_MERGE).
- `FlowValueType` (`value class`) s `fromWire()` normalizací typových aliasů.
- `FlowWorkflowIdentity`, `FlowStatementPath` (`child(region, index)`),
  `FlowPathIdentity` (`child(joinPoint, arm, mergeable)` — **konkatenace cesty
  a mergeable AND**), `FlowProducerIdentity`.
- `FlowMergeIdentity`, `FlowMergeInput`, `FlowMergeContract` — typovaný
  phi-merge s přísnými `init{}` invarianty (pokrytí cest, min. 2 vstupy,
  žádné concurrent cesty).
- `FlowBindingState` — stav jedné vazby na programovém bodě; `producers`,
  `external`, `coveredPaths`, `safeToRead`, `uniqueProducer`; `init{}`
  vynucuje konzistenci `availability`↔`producers`↔`external`.
  - `remap(mapping)`, `restrictTo(paths)`.
  - `companion`: `Undefined`, `external()`, `produced()`, `merged()`,
    `fromCoverage(...)` — centrální rozhodovací logika (plné/částečné
    pokrytí, jediný/více producentů, explicitní merge).
- `FlowAvailabilityState` — stav na programovém bodě (`reachable`, `paths`,
  `bindings`); `binding(name)`, `withBinding/withExternal/restore/
  enterAlternative/restrictTo/unreachable`.
- `FlowAvailabilityIssueKind`, `FlowAvailabilityUse`, `FlowAvailabilityIssue`
  (`blocksDirectPlanning` — jen PARTIAL_PATH/AMBIGUOUS_PRODUCER/INVALID_MERGE).
- `UnsafeFlowAvailabilityException`.
- `FlowAvailabilityAnalysis` (interní konstruktor) — `stateBefore/After`,
  `bindingBefore`, `mergeAt`, **`producerBefore()`** (běžné čtení — vyžaduje
  `safeToRead`), **`orderingProducerBefore()`** (deklarovaná závislost —
  stačí `uniqueProducer`, i když hodnota není na všech cestách bezpečná ke
  čtení — záměrně odlišná sémantika), `producerAt()`, `requireDirectPlanningSafe()`.

#### `SemanticCorePackageBoundary.kt` (30 ř.)
- `object SemanticCorePackageBoundary { val packages: List<String> }` —
  architektonický seznam 18 balíčků tvořících serializaci-prostou sémantickou
  hranici (používáno testy/conformance, ne runtime skenem classpath).

---

### 2.4 `topology/` (5 souborů, 545 řádků)

#### `CanonicalTopologyRequirementAuthority.kt` (56 ř.)
`object CanonicalTopologyRequirementAuthority` — `requirementsFor(intent):
List<ExecutionTopologyRequirement>` — odvozuje WORKFLOW_SCOPE/LIFETIME pro
každý neprázdný workflow, SUSPEND_RESUME pro `APPROVE` kroky,
FAILURE_PROPAGATION pokud `failure.notify || failure.rollback`.

#### `ExecutionPlanCanonicalTopologyAuthority.kt` (72 ř.)
`object ExecutionPlanCanonicalTopologyAuthority` — **nezávisle přepočítává**
tatáž kanonická topologická pravidla, ale výhradně ze `sourceIntent`
provenience uložené v `ExecutionPlan` (ne z jeho vlastní `topologyRequirements`
kolekce!) — slouží jako nezávislý orákl proti driftu.
`requirementsFor(plan): List<ExecutionTopologyRequirement>`.

#### `ExecutionTopology.kt` (295 ř.)
- `ExecutionTopologyDimension` (ISOLATION/LIFETIME/PERSISTENCE/PROPAGATION).
- `ExecutionTopologyKind` (enum, 11 hodnot s `registryKey`, `fromRegistryKey()`).
- `ExecutionTopologyRequirementSource` (5 hodnot).
- `ExecutionTopologyRequirement`.
- `ExecutionTopologySupportStatus` (SUPPORTED/PARTIAL/UNSUPPORTED/UNKNOWN),
  `ExecutionTopologySupportDeclaration`, `ExecutionTopologyProfile`
  (`companion.fullySupported()`).
- `ExecutionTopologyProfileIssue`, `object ExecutionTopologyProfileAuthority` —
  `validate(profile, requireComplete)` (kontrola duplicit/kontradikcí a
  úplnosti), `requireValid(...)`.
- `ExecutionTopologyEvidenceStatus/Source`, `ExecutionTopologyEvidence`.
- `ExecutionTopologyDecisionStatus` (MATCHED/DEGRADED/BLOCKED),
  `ExecutionTopologyDecision`, `ExecutionTopologyAssessment`.
- `object ExecutionTopologyMatchingAuthority` — `assess(requirements, profile):
  ExecutionTopologyAssessment` — páruje požadavky s profilem cíle, řeší
  chybějící/duplicitní/kontradiktorní deklarace, agreguje na
  MATCHED/DEGRADED/BLOCKED rozhodnutí.

#### `PlanningTopologyAuthority.kt` (86 ř.)
`object PlanningTopologyAuthority` — `requirementsFor(flowName,
canonicalRequirements, nodes, dependencyRelations)` — rozšiřuje kanonické
požadavky o plán-specifické: BRANCH_ISOLATION (Parallel), ATTEMPT_ISOLATION
(Retry), SUSPEND_RESUME (Approval), FAILURE_PROPAGATION (neprázdný Try error
handler), a pro každou RESOLVED `PlanDependencyRelation` odpovídající
VALUE_PROPAGATION/EPHEMERAL_WORKSPACE+WORKSPACE_PROPAGATION/
DURABLE_STATE+STATE_PROPAGATION.

#### `TopologyRequirementIdentityAuthority.kt` (36 ř.)
`object TopologyRequirementIdentityAuthority` — `assign(requirements)`
(deleguje na `CollisionSafeIdentityAuthority` s `KEEP_FIRST` politikou pro
sémanticky duplicitní požadavky), `baseId(kind, subject)`, `slug(value)`.

---

### 2.5 `projection/` (2 soubory, 530 řádků)

#### `ProjectionBindings.kt` (230 ř.)
- `ProjectionBindingKind` (9 hodnot: LITERAL/TASK_PARAMETER/TASK_INPUT/
  TASK_METADATA/FLOW_INPUT/SECRET/ARTIFACT/TASK_OUTPUT/TARGET_EXPRESSION).
- `ProjectionBindingResolutionStatus` (RESOLVED/SYMBOLIC/UNRESOLVED),
  `TaskMetadataField` (ID/TARGET).
- `ProjectionBinding` — diskriminovaný kontrakt s 8 factory funkcemi
  (`literal/taskParameter/taskInput/taskMetadata/flowInput/secret/artifact/
  taskOutput/targetExpression`).
- `object ProjectionBindingContract` — `requireTemplate(binding, context)`,
  `requireManifest(binding, payloadTarget, context)`,
  `validationReason(binding, manifest, payloadTarget): String?` — velmi
  podrobná validace: která pole smí/musí být vyplněna pro danou kombinaci
  (kind × template-vs-manifest × resolutionStatus), plus
  `manifestStatusReason(...)` — např. `TASK_PARAMETER` v manifestu musí být
  RESOLVED (s hodnotou) nebo UNRESOLVED (s důvodem), nikdy SYMBOLIC.

#### `TargetProjectionPlan.kt` (300 ř.)
- `TargetProjectionArtifactKind` (5 hodnot: TARGET_NATIVE/NOTES_BACKED/
  ADAPTER_BOUNDARY/REVIEW_RECORD/CONFORMANCE_RECORD).
- `TargetProjectionArtifact`, `TargetProjectionPlan` (`artifactsByNode()`).
- `TargetProjectionPlanStatus`, `TargetProjectionPlanIssue`,
  `TargetProjectionPlanReport`.
- `class TargetProjectionPlanValidator(notesPackages)` — `validate(plan):
  TargetProjectionPlanReport`:
  - `validateArtifactCoverage` — každé materialization rozhodnutí musí mít
    artefakt a naopak.
  - `validateArtifacts` — validní/unikátní ID, `validateStatusAlignment`
    (artefakt kind musí odpovídat materialization statusu — MATERIALIZABLE
    smí být jen target-native/notes-backed/conformance, ADAPTER_REQUIRED jen
    adapter-boundary/review-record, UNSUPPORTED/BLOCKED/DEFERRED nesmí být
    target-native/notes-backed), `validateArtifactShape`,
    `validateProjectionMechanism` — **explicitně zakazuje** raw runtime
    mechanismy (`command/script/shell/run` pole, `shell/bash/powershell/
    cmd.exe` reprezentace, `shell.*/command.*/script.*` notes reference) —
    přímý strážce invariantu "žádný runtime executor" z README.
- `object StandardTargetProjectionPlans` — `baseline(negotiation)` +
  `baselineArtifact(...)` — výchozí projekce z materialization rozhodnutí.

---

### 2.6 `modules/` (6 souborů, 877 řádků)

#### `CanonicalModuleLoader.kt` (273 ř.)
`object CanonicalModuleLoader` — **jediná** strukturální validační autorita
pro modul YAML.
- `class ContractException`.
- `loadDirectory(dir)`, `loadTexts(texts)`, `loadText(yaml, source)` —
  hlavní vstupní body; `loadText` provádí rozsáhlou validaci (neznámá pole,
  ID regex, systemTypes/actions neprázdné, `targetTypes` musí odkazovat na
  známé systémy, `targetImplications` explicitně zakázáno — "adapter evidence
  belongs to target registries", validace schémat/efektů/kontinuity/
  bezpečnosti/retry/timeout/idempotence/errors) — teprve po úspěchu volá
  `ModuleYamlLoader.decodeText(yaml, source)`. *(Viz nález B5 — dvojí parsing.)*
- `validateSchema/Effects/Continuity/Safety/SupportBlock/Errors/Idempotent/MemberId`,
  `rejectUnknownFields`, `rejectDuplicateModuleIds`, typové helpery
  `text/map/objectList/list/bool`.
- Bezpečnostně relevantní pravidlo: `if (destructive && "safety" !in
  requirements) throw ContractException(...)` — destruktivní akce musí
  vyžadovat safety gate (viz i `ModuleContractAnalyzer`).

#### `ModuleCatalog.kt` (12 ř.)
`interface ModuleCatalog` — `findModule`, `allModules`, `requireModule`
(default `error()`), `findAction`, `findSystemType`.

#### `ModuleContractAnalyzer.kt` (159 ř.)
- `CapabilityModuleContractReport` (`valid` = žádné "error" issues),
  `CapabilityModuleTotals`, `ModuleSummary`, `ModuleActionSummary`,
  `CapabilityModuleIssue`.
- `object ModuleContractAnalyzer` — `analyze(registry): CapabilityModuleContractReport` —
  audituje chybějící popis, prázdné moduly (error), chybějící targetTypes/
  input/output/effects (warning), **destruktivní akci bez `requiresSafety`
  (error)**, sensitive input, který není typu `secret` (warning), prázdné
  jméno secretu (warning).

#### `ModuleContracts.kt` (222 ř.)
- `FlowModule`, `SystemTypeContract`, `ModuleActionContract`, `ModuleErrorRule`.
- `SchemaType` (enum, 10 hodnot s `wireName`, `fromWireName()`).
- `SchemaValueKind` (7 hodnot).
- `object SchemaTypeCompatibility` — `accepts(type, kind)` (kompatibilní
  matice typ↔hodnota), `defaultValidationError(type, value)`,
  `defaultValueKind(value)`, `isRepresentableDefault(value)` (rekurzivní pro
  List/Map).
- `SchemaField` (`init{}` validuje default proti typu).
- `Effects`, `ContinuityKind` (VALUE/WORKSPACE/STATE, `capability` getter),
  `ContinuityChannel` (`effectiveStateLifetime`, `satisfies(required)` —
  **DURABLE splňuje WORKFLOW požadavek, nikdy naopak**), `ContinuityContract`,
  `SafetyContract`.

#### `ModuleRegistry.kt` (43 ř.)
`class ModuleRegistry(modules) : ModuleCatalog` — implementace nad mapou;
`companion`: `fromDirectory(dir)`, `fromDescriptors(texts)`,
`loadCanonical(rootDir)`, plus 3 `@Deprecated` kompatibilní přetížení
ignorující parametr `includeDefaults` (`@Suppress("UNUSED_PARAMETER")`).

#### `ModuleYamlLoader.kt` (158 ř.)
`object ModuleYamlLoader` — **interní dekodér**, smí běžet jen po
`CanonicalModuleLoader` validaci.
- `class LoadException`.
- `loadText/loadFile/loadDirectory(...)` — obalují `CanonicalModuleLoader` a
  překládají `ContractException` na `LoadException`.
- `internal fun decodeText(yaml, sourceName): FlowModule` — **reparsuje**
  YAML text (viz nález B5) a mapuje na `FlowModule` bez opětovné validace.
- `parseAction/parseSchema/parseContinuity/continuityChannels/parseEffects`,
  helpery `asMap/asList/strList/boolOf`.

---

## 3. Zjištěné chyby a nedostatky

### B1 — Digest kanonického grafu vynechává pole hrany závislosti (evidence/path/evidenceReference)
**Soubor:** `compiler/CanonicalExecutionGraphDigest.kt:232-241` (`canonicalEdge`)
**Závažnost:** VYSOKÁ

`CanonicalExecutionGraphDigestComputer` je deklarován jako "Deterministic
semantic identity" a používá se jako integritní kotva v
`CompilationValidationBinding.graphDigest` a `CompilationAuthorization.
requireMatchingGraph()`/`requireBoundGraph()` — tj. jako záruka, že graf,
se kterým downstream konzument pracuje, je *přesně* ten, který byl
autorizován. `canonicalEdge()` ale do otisku zahrnuje jen `source`, `target`,
`kind`, `channel`, `stateLifetime`, `resolution`, `candidates` — **vynechává
`edge.evidence` (DECLARED_ORDERING/DATA_REFERENCE/MODULE_CONTRACT), `edge.path`
a `edge.evidenceReference`**, přestože jde o pole přímo na `CanonicalDependencyEdge`
(součást sémantického grafu, ne binding metadata).

**Scénář selhání:** Dva `CanonicalExecutionGraph` instance, které se liší
*pouze* v tom, zda je konkrétní závislost klasifikována jako
`DECLARED_ORDERING` vs. `DATA_REFERENCE` (nebo mají odlišný evidence-path
řetězec), vyprodukují **identický digest**. `requireMatches()` by takovou
záměnu nezachytila — integrita/tamper-evidence garance je tak slabší, než
dokumentace třídy tvrdí.

**Návrh opravy:** Buď (a) přidat `evidence`, `path` a `evidenceReference` do
`canonicalEdge()` (pokud jsou sémanticky relevantní), nebo (b) pokud jsou
tato pole čistě diagnostická/provenience, přesunout je z `CanonicalDependencyEdge`
do binding/metadata vrstvy analogicky k tomu, jak jsou `sourceId`/
`sourceDescription` drženy v `CanonicalNodeProjectionMetadata`, ne na
sémantickém uzlu.

---

### B2 — Detekce cyklů v `validateOrderingAcyclic` pokrývá jen ORDERING hrany, ne VALUE/WORKSPACE/STATE
**Soubor:** `compiler/CanonicalExecutionGraphValidator.kt:509-542`
**Závažnost:** STŘEDNÍ–VYSOKÁ (podmíněno invarianty plánovače mimo rozsah)

```kotlin
val adjacency = graph.dependencyEdges
    .filter {
        it.kind == CanonicalDependencyKind.ORDERING &&
            it.resolution == CanonicalDependencyResolution.RESOLVED &&
            it.sourceNodeId != null
    }
    ...
```

DFS detekce cyklů (jinak korektně implementovaná — white/gray/black) staví
adjacency výhradně z hran typu `ORDERING`. Hrany `VALUE`, `WORKSPACE` a
`STATE` (které rovněž reprezentují skutečnou datovou/perzistentní závislost
mezi uzly — např. uzel B čte výstup uzlu A) jsou z cyklické analýzy zcela
vyloučeny. Jediné místo, kde je párování VALUE↔ORDERING hran kontrolováno, je
`validateMerges()` — a to jen pro merge (`SET`) cílové uzly, ne pro obecné
task-to-task datové závislosti.

**Scénář selhání:** Pokud by (mimo tento rozsah, v plánovači) vznikla dvojice
uzlů A→B (VALUE, B čte výstup A) a B→A (VALUE, A čte výstup B) *bez*
odpovídajících ORDERING hran, `validateOrderingAcyclic` by takový graf
prohlásil za bezcyklický, přestože representuje neschopnost sestavit pořadí
provedení (execution deadlock). Vzhledem k tomu, že tato vrstva je
deklarovaná jako autoritativní brána ("no runtime executor... standard must
be checkable"), jde o mezeru v obraně do hloubky přesně tam, kde by měla být
nejsilnější.

**Návrh opravy:** Buď rozšířit `validateOrderingAcyclic` o `VALUE`/`WORKSPACE`/
`STATE` hrany (sloučit adjacency ze všech typů se sémantikou "musí
předcházet"), nebo přidat obecné pravidlo (analogicky k `validateMerges`),
že každá RESOLVED `VALUE`/`STATE` hrana musí mít odpovídající RESOLVED
`ORDERING` hranu mezi stejnou dvojicí uzlů — a tuto invariantu samostatně
testovat.

---

### B3 — Kombinatorický růst počtu sledovaných cest v path-sensitive availability analýze
**Soubory:** `core/FlowAvailabilityJoins.kt:52-68` (`joinAlternatives`),
`core/FlowAvailabilityModel.kt:353-363` (`enterAlternative`),
`core/FlowAvailabilityAnalyzer.kt:259-343` (analyzeIf/analyzeFor/
analyzeParallel/analyzeMatch/analyzeTry)
**Závažnost:** STŘEDNÍ (výkon/škálovatelnost)

Každá větvicí konstrukce (`if`, `for` — zero/body větev, `match` — N case +
error + default, `try` — success/error) volá `enterAlternative()` pro každou
větev (což **znásobí** počet existujících `paths` v aktuálním stavu — každá
existující cesta dostane nový, odlišný sufix) a následně `joinAlternatives()`,
která sjednotí `paths` všech větví **bez deduplikace/kolapsu**
(`paths = reachable.flatMap(FlowAvailabilityState::paths).toSet()` —
`toSet()` deduplikuje jen shodné hodnoty, ale nové cesty jsou vždy unikátní
řetězce). Pro N sekvenčně vnořených/řazených dvojcestných konstrukcí tak
`paths` množina roste až ~2^N (přesná násobnost závisí na počtu větví dané
konstrukce — `match`/`try` mají obvykle >2 větve).

**Scénář selhání:** Pro automatizační flow s desítkami sekvenčních podmínek
(realistické u AI-generovaných run-booků s mnoha validačními/rozhodovacími
kroky) může množina `FlowPathIdentity` v jednom `FlowBindingState` růst
exponenciálně, což zpomaluje/nafukuje paměť u `FlowAvailabilityAnalyzer`
(a tedy i u validace a plánování, které na něm staví) — v krajním případě až
do prakticky neúnosných časů/paměti pro velké generované flow.

**Návrh opravy:** Zvážit "path summarization" (např. omezit rozlišování na
K posledních úrovní větvení, nebo přejít na klasickou powerset-lattice
abstrakci místo explicitní množiny cest) nebo dokumentovat/vynutit horní mez
počtu vnořených větvicích konstrukcí per flow jako conformance limit.

---

### B4 — Kvadratické vyhledávání v ověřování lowering evidence
**Soubor:** `lowering/IntentLoweringContracts.kt:598-657`
(`resolveNode`, `sourceNode`)
**Závažnost:** STŘEDNÍ (výkon)

`report()` (řádek 111) iteruje přes všechna `IntentSourceField` (řádově
úměrné počtu kroků × počet polí na krok) a pro každé volá `resolveTarget()`
→ (pro pole typu `node`) `resolveNode()` → `sourceNode()`:

```kotlin
private fun sourceNode(workflowPlans, sourceId, identity): PlanNode = unique(
    workflowPlans.flatMap { plan ->
        PlanDependencyRelations.flatten(plan.nodes).filter { node -> sourceIdOf(node) == sourceId }
    },
    identity
)
```

Toto **znovu zplošťuje celý strom uzlů plánu** (`PlanDependencyRelations.
flatten`) a lineárně prohledává pro **každé jednotlivé pole** každého kroku.
Výsledná složitost je O(P × N), kde P je počet polí (~5-10 na krok) a N je
celkový počet uzlů (úměrný počtu kroků S) — tedy O(S²) v počtu kroků flow.
Pro kontrast: `validateAuthoredOrderingGraph()` (o pár desítek řádků výše, na
řádku 461) staví `nodesById` mapu **jednou** přes `groupBy` — správný vzor,
který zde není použit.

**Návrh opravy:** Předpočítat `sourceId → PlanNode` mapu jednou na začátku
`report()` (analogicky k `nodesById` ve `validateAuthoredOrderingGraph`) a
sdílet ji mezi voláními `resolveNode`.

---

### B5 — Dvojí parsing YAML modulu a duplikovaná znalost schématu
**Soubory:** `modules/CanonicalModuleLoader.kt:29-93` (zejména řádek 92),
`modules/ModuleYamlLoader.kt:20-49`
**Závažnost:** NÍZKÁ–STŘEDNÍ (výkon + údržba)

`CanonicalModuleLoader.loadText(yaml, source)` naparsuje YAML
(`FlowYaml.readMap`), provede rozsáhlou strukturální validaci, a na konci:

```kotlin
return ModuleYamlLoader.decodeText(yaml, source)
```

…zavolá `ModuleYamlLoader.decodeText`, který **znovu parsuje ten samý YAML
text od nuly** (vlastní `FlowYaml.readMap` volání na řádku 33-34
`ModuleYamlLoader.kt`) a extrahuje hodnoty do `FlowModule` bez opětovné
validace. Navíc seznam povolených/požadovaných polí je udržován na dvou
místech nezávisle (`MODULE_KEYS`/`ACTION_KEYS`/… v `CanonicalModuleLoader` vs.
extrakční logika v `ModuleYamlLoader.parseAction/parseSchema/...`) — dvě
zdroje pravdy pro tentýž kontrakt, jejichž rozejití by validace/dekódování
mlčky nezachytila (validace by nový klíč odmítla jako "unknown field" dřív,
než by se k dekodéru vůbec dostal, takže riziko je spíš v opomenutí přidat
podporu do dekodéru po přidání pole do validátoru — než by se to projevilo,
resp. spadlo by na `error("Canonical module validation admitted unsupported
schema type...")` v `ModuleYamlLoader.kt:118`).

**Návrh opravy:** Nechat `CanonicalModuleLoader.loadText` vrátit již
naparsovanou mapu (`Map<String, Any?>`) společně s validačním výsledkem a
předat ji do `ModuleYamlLoader.decodeText` namísto surového YAML textu, aby
se parsing provedl jen jednou.

---

### B6 — Příliš široké `catch (failure: Exception)` v `FlowCompilationService`
**Soubor:** `compiler/FlowCompilationService.kt:177, 249, 329, 391, 420`
**Závažnost:** NÍZKÁ–STŘEDNÍ (diagnostikovatelnost)

Pět míst zachytává generické `Exception` kolem `intentPlanner.planProgram(...)`,
`flowPlanner.planWithProvenance(...)` a `CompilationUnit.from(...)/fromProgram(...)`,
a převádí je na `CompilationDiagnostic` obsahující pouze
`failure.message ?: failure.javaClass.simpleName` — **stack trace se
zahazuje** a jakákoli neočekávaná programová chyba (např. `NullPointerException`,
`ClassCastException` způsobená interní nekonzistencí, ne uživatelským
vstupem) je nerozlišitelně namapována na stejný obecný diagnostický kód jako
legitimní doménová chyba. To je v souladu s "no silent fallback" (chyba
*je* hlášena), ale ztěžuje ladění produkčních incidentů, protože se ztrácí
informace, zda šlo o očekávanou doménovou validaci, nebo o skutečný bug v
kompilátoru.

**Návrh opravy:** Rozlišit očekávané doménové výjimky (pokud existuje
specifický typ, např. `IntentLoweringException`) od obecných `Exception` a u
těch druhých alespoň logovat/přikládat stack trace do interní diagnostiky
(mimo uživatelsky viditelnou zprávu), případně nechat neočekávané výjimky
propagovat a zachytávat je až na vyšší (aplikační) úrovni.

---

### B7 — Kolize jmen workflow při stavbě `workflowIdsByName` (`associate`) bez lokální obrany
**Soubor:** `compiler/CanonicalExecutionGraphBuilder.kt:84`
**Závažnost:** NÍZKÁ (defense-in-depth mezera, prakticky kryto validátorem)

```kotlin
val workflowIdsByName = workflows.associate { it.name to it.id }
```

Pokud by (výše v pipeline, mimo tento soubor) vznikly dva workflow se stejným
`name`, `associate {}` mlčky ponechá **poslední** záznam a přepíše dřívější
mapování — trigger routing (`triggers = program.triggers... map {
it.toCanonical(workflowIdsByName) }`, řádek 106) by pak tiše navázal trigger
na "špatné" workflow ID, aniž by v tomto místě padla chyba. Skutečná
duplicita jmen je sice následně odhalena samostatně v
`CanonicalExecutionGraphValidator.validate()` (`graph.workflow.name.duplicate`),
takže výsledná kompilace nakonec selže — ale až o krok později a s méně
přímou diagnostikou k příčině (trigger routing), než kdyby `build()` selhal
okamžitě na místě vzniku kolize.

**Návrh opravy:** Nahradit `associate` explicitní kontrolou
(`groupBy{it.name}.mapValues{require(it.value.size==1){...}}`) přímo v
builderu, aby chyba byla hlášena s přesnější lokalizací.

---

### B8 — Detekce cyklů hlásí jen první nalezený cyklus
**Soubor:** `compiler/CanonicalExecutionGraphValidator.kt:255-284`
(`validateStructuralAcyclic`), `509-542` (`validateOrderingAcyclic`)
**Závažnost:** NÍZKÁ (úplnost diagnostiky, ne korektnost)

Obě DFS implementace `return` ihned po nalezení prvního cyklu
(`if (!visit(root)) { issues += issue(...); return }`), takže pokud graf
obsahuje více nezávislých cyklů, validační report obsahuje jen jeden z nich.
Pro opravu chyby uživatelem to znamená víc iterací "oprav → zkompiluj znovu
→ najdi další cyklus" místo jednoho průchodu se všemi nálezy.

**Návrh opravy:** Pokračovat v procházení zbylých nenavštívených uzlů a
sbírat všechny nezávislé cykly do `issues`, ne se vracet po prvním nálezu.

---

### Dead code / technický dluh (bez konkrétní chyby, ale k úklidu)

- `compiler/CanonicalExecutionGraphDigest.kt:300-301` a
  `compiler/CompilationAuthorization.kt:425-426` — `@Deprecated` aliasy
  (`CanonicalExecutionGraphDigestAuthority`, `CanonicalExecutionGraphAuthority`)
  ponechané kvůli zpětné kompatibilitě zdrojového kódu; kandidát na
  odstranění po dokončení migrace volajících.
- `modules/ModuleRegistry.kt:19-24, 29-35, 40-41` — tři `@Deprecated`
  přetížení s `@Suppress("UNUSED_PARAMETER")` ignorující parametr
  `includeDefaults` — legitimní kompatibilní shim, ale stojí za sledování,
  aby nezůstal navždy.

### Nenalezeno (pozitivní zjištění)
- **Force-unwrap (`!!`)**: nula výskytů v celém rozsahu (32 souborů).
- **TODO/FIXME/HACK/XXX**: nula výskytů.
- **Race conditions / sdílený mutovatelný stav**: žádné nalezeny — vrstva je
  zcela bez vláken/synchronizace/atomických proměnných; jediná `var` jsou
  lokální akumulátory uvnitř jedné funkce.
- **Prázdné/tiché catch bloky**: žádné — všech 8 `catch` bloků v rozsahu
  produkuje explicitní diagnostiku nebo `LoadException`/`ContractException`.

---

## 4. Architektonická pozorování

1. **Vzor "postav, ověř, znovu-projektuj, porovnej" jako centrální
   bezpečnostní mechanismus.** `CanonicalExecutionGraphGate.authorize()`
   (compiler/CompilationAuthorization.kt:324-351) po sestavení grafu
   **zpětně projektuje** `ExecutionPlan` a vyžaduje bitovou shodu s
   originálem plánovače (`require(projectedSet.requireSingleExecutionPlan()
   == plannerPlan)`). To je nákladné (dvojnásobná práce na každou kompilaci),
   ale poskytuje silnou záruku, že kanonický graf skutečně nese stejný
   význam jako plán, ze kterého vznikl — vhodný kompromis pro
   compile-time (ne hot-path) validaci.

2. **Nezávislé přepočítávání jako obrana proti driftu.**
   `ExecutionPlanCanonicalTopologyAuthority` (topology/) i
   `deriveControlDecision()` (CanonicalExecutionGraphValidator.kt:585-609)
   demonstrativně **nepoužívají** uložené hodnoty jako referenci pro
   validaci vlastní produkce — přepočítají je nezávisle ze zdrojové
   provenience a teprve pak porovnají. Toto je opakující se, důsledně
   aplikovaný vzor napříč vrstvou (merge kontrakty ověřovány jak v
   `FlowAvailabilityAnalyzer`, tak znovu v `CanonicalExecutionGraphValidator.
   validateMerges`).

3. **Sémantická hranice grafu vs. binding metadata je čistě dodržena s
   jednou výjimkou (B1).** Rozdělení `CanonicalExecutionGraph`
   (sémantika) / `CanonicalExecutionBindingSet` (implementace/provenience)
   je konzistentně dodržováno — kromě `evidence`/`path`/`evidenceReference`
   na `CanonicalDependencyEdge`, které zůstávají na sémantickém typu, ale
   nejsou součástí jeho digestu (viz B1). Stojí za zvážení, zda tato pole
   patří na `CanonicalDependencyEdge`, nebo by měla být přesunuta analogicky
   k `CanonicalNodeProjectionMetadata`.

4. **`TARGET_EXPRESSION`/projection binding model je jediné místo v tomto
   rozsahu, kde se explicitně a strojově vynucuje "žádný runtime executor".**
   `TargetProjectionPlanValidator.validateProjectionMechanism()`
   (projection/TargetProjectionPlan.kt:188-223) tvrdě zakazuje pole
   `command/script/shell/run` a hodnoty jako `shell/bash/powershell/cmd.exe`
   v materializovatelných artefaktech — přímý, testovatelný strážce
   README invariantu "Horistum is not a runtime executor", ne jen
   dokumentační proklamace.

5. **Duální reprezentace stejné domény (`inputs`/`params` u `TaskNode`,
   `PlanNode` vs. `CanonicalPlanNode`, `ExecutionPlan` vs.
   `CanonicalExecutionPlan`) zvyšuje plochu údržby.**
   `CanonicalExecutionGraphProjection.kt` obsahuje dvě téměř identické ~150řádkové
   `project()` funkce (pro `PlanNode` a pro `CanonicalPlanNode`) lišící se
   jen cílovým typem. To je zjevně záměrná kompatibilní vrstva (legacy
   `ExecutionPlan` vs. nový `CanonicalExecutionPlan`), ale představuje
   zdvojenou údržbovou zátěž — každá budoucí změna uzlu se musí promítnout
   na dvou místech synchronně (validátor exaktní shody `authorizeProgram`
   toto naštěstí hlídá strojově).

6. **Modelování `merge()` (phi-uzlů) je neobvykle rigorózní pro DSL tohoto
   rozsahu** — explicitní SSA-like invariance (úplné, nepřekrývající se
   pokrytí cest, zákaz mergování concurrent/paralelních cest) je ověřena
   na třech nezávislých místech (analýza, `FlowMergeContract.init{}`,
   `CanonicalExecutionGraphValidator.validateMerges`). Toto je silně
   pozitivní zjištění, ale zároveň nejkomplexnější a nejkřehčí část celé
   vrstvy — případné budoucí rozšíření (např. merge napříč workflow) by
   vyžadovalo změny na všech třech místech konzistentně.

7. **`ModuleContractAnalyzer` a `CanonicalModuleLoader` oba vynucují
   "destructive ⇒ requires safety" nezávisle** (CanonicalModuleLoader.kt:174-176
   a ModuleContractAnalyzer.kt:112-113) — redundantní, ale konzistentní
   bezpečnostní kontrola na dvou různých úrovních (load-time strukturální
   gate vs. post-hoc audit report).

---

## 5. Statistická tabulka

| Soubor | Řádky | Tříd/objektů/interfaces/enumů (top-level) | Veřejných funkcí (odhad, bez `private`) |
|---|---:|---:|---:|
| compiler/CanonicalExecutionGraph.kt | 589 | 41 | 18 |
| compiler/CanonicalExecutionGraphBindings.kt | 147 | 9 | 1 |
| compiler/CanonicalExecutionGraphBuilder.kt | 603 | 1 | 7 |
| compiler/CanonicalExecutionGraphDigest.kt | 301 | 2 | 4 |
| compiler/CanonicalExecutionGraphPlanMappings.kt | 43 | 0 (top-level ext. fun) | 6 |
| compiler/CanonicalExecutionGraphProjection.kt | 554 | 1 | 15 |
| compiler/CanonicalExecutionGraphValidator.kt | 707 | 4 | 6 |
| compiler/CompilationAuthorization.kt | 426 | 5 | 10 |
| compiler/CompilationContracts.kt | 422 | 18 | 8 |
| compiler/FlowCompilationService.kt | 462 | 1 | 1 |
| compiler/WorkflowFailureAuthorization.kt | 67 | 1 | 1 |
| lowering/IntentExpressionParser.kt | 8 | 0 (fun interface) | 2 |
| lowering/IntentLoweringContracts.kt | 797 | 1 | 5 |
| lowering/IntentLoweringMetadata.kt | 82 | 9 | 0 |
| lowering/IntentValueExpressionLowering.kt | 61 | 1 | 1 |
| core/FlowAvailabilityAnalyzer.kt | 693 | 1 | 3 |
| core/FlowAvailabilityJoins.kt | 118 | 0 (top-level fun/klas) | 5 |
| core/FlowAvailabilityModel.kt | 496 | 17 | 28 |
| core/SemanticCorePackageBoundary.kt | 30 | 1 | 0 |
| topology/CanonicalTopologyRequirementAuthority.kt | 56 | 1 | 1 |
| topology/ExecutionPlanCanonicalTopologyAuthority.kt | 72 | 1 | 1 |
| topology/ExecutionTopology.kt | 295 | 16 | 5 |
| topology/PlanningTopologyAuthority.kt | 86 | 1 | 1 |
| topology/TopologyRequirementIdentityAuthority.kt | 36 | 1 | 2 |
| projection/ProjectionBindings.kt | 230 | 5 | 13 |
| projection/TargetProjectionPlan.kt | 300 | 8 | 3 |
| modules/CanonicalModuleLoader.kt | 273 | 1 | 3 |
| modules/ModuleCatalog.kt | 12 | 1 | 5 |
| modules/ModuleContractAnalyzer.kt | 159 | 6 | 1 |
| modules/ModuleContracts.kt | 222 | 13 | 7 |
| modules/ModuleRegistry.kt | 43 | 1 | 11 |
| modules/ModuleYamlLoader.kt | 158 | 1 | 4 |
| **Celkem** | **8 548** | **~168** | **~178** |

*(Sloupec "tříd" počítá top-level `class/data class/object/interface/enum
class/value class/sealed class/sealed interface` deklarace; vnořené
`private class` builder helpery — např. `FlowAvailabilityAnalyzer.Builder` —
nejsou v tomto součtu zahrnuty samostatně. Sloupec funkcí počítá `fun`
deklarace bez modifikátoru `private` na libovolné úrovni vnoření, tedy
včetně metod uvnitř `object`/`class` bloků.)*

---

## Shrnutí

Prošel jsem všech 32 souborů (8 548 řádků) v `compiler/`, `lowering/`,
`core/`, `topology/`, `projection/` a `modules/`. Kód je nadprůměrně
disciplinovaný: nula force-unwrapů (`!!`), nula TODO/FIXME/HACK, žádné tiché
catch bloky, žádný sdílený mutovatelný stav ani souběžnost (race conditions
strukturálně vyloučeny). Dominuje vzor "postav → validuj → digest → zpětně
projektuj → porovnej", aplikovaný redundantně na několika úrovních
(canonical graph authorization, topology re-derivace, merge kontrakty
ověřené třikrát nezávisle).

Přesto jsem našel 8 konkrétních nedostatků. Podle závažnosti: **1 vysoká**
(B1 — SHA-256 digest `CanonicalExecutionGraphDigest.kt:232-241` vynechává
pole `evidence`/`path`/`evidenceReference` hrany závislosti, takže dva
sémanticky odlišné grafy mohou mít identický otisk, což oslabuje integritní
záruku `CompilationAuthorization`); **1 středně-vysoká** (B2 — cyklická
detekce `CanonicalExecutionGraphValidator.kt:509-542` pokrývá jen ORDERING
hrany, ne VALUE/WORKSPACE/STATE, takže datový cyklus bez doprovodné ORDERING
hrany by neprošel detekcí); **4 střední** (B3 — exponenciální růst
sledovaných cest v path-sensitive dataflow analýze `core/
FlowAvailabilityJoins.kt`/`FlowAvailabilityAnalyzer.kt`; B4 — O(S²) lineární
vyhledávání v `lowering/IntentLoweringContracts.kt:598-657`; B6 — příliš
široký `catch(Exception)` v `compiler/FlowCompilationService.kt` na 5
místech; B7 lehce nižší); **2 nízké** (B5 — dvojí YAML parsing v
`modules/CanonicalModuleLoader.kt`/`ModuleYamlLoader.kt`; B8 — cyklová
detekce hlásí jen první nález). Plus drobný technický dluh: 2 `@Deprecated`
aliasy a 3 ignorující kompatibilní přetížení v `modules/ModuleRegistry.kt`.

Žádný z nálezů není katastrofický, ale B1 a B2 se přímo dotýkají
deklarovaného účelu vrstvy (nefalšovatelná integrita grafu, bezpečná DAG
detekce cyklů) a zaslouží prioritní pozornost.
