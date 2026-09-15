# FlowAi (Horistum) — analýza validační a plánovací vrstvy

Rozsah: `validator/` (3), `capabilities/` (13), `planner/` (8), `controls/` (5), `effects/` (3), `safety/` (2), `obligations/` (1) — 35 souborů, 6 944 řádků. Všechny soubory přečteny celé.

---

## 1. Přehled vrstvy

Tato část repozitáře realizuje prostřední tři kroky standardizačního řetězce popsaného v README (`Flow AST -> validace -> Execution Plan -> target compatibility`):

- **`validator/`** — lexikální a bezpečnostní validátor Flow AST. `FlowValidator` kontroluje strukturální korektnost (importy, scope, typy, operátory, vazby výsledků) a spolupracuje se sdílenou path-sensitivní analýzou dostupnosti hodnot (`FlowAvailabilityAnalyzer`, mimo rozsah). `SafetyBoundaryValidator` je samostatná bezpečnostní brána nad stejným AST, která vynucuje schválení (approval) u destruktivních/citlivých akcí.
- **`safety/`** — deklarativní politika citlivosti prostředí (`EnvironmentSafetyPolicy`), na kterou se `SafetyBoundaryValidator` odkazuje. Vše je „fail closed": nerozpoznaná hodnota parametru prostředí je `UNKNOWN`, nikdy tiše `NON_SENSITIVE`.
- **`effects/`** — cílově neutrální model vedlejších efektů (`SemanticEffect`), který nese informaci o čem action/capability mění, bez vazby na konkrétní modul či cíl.
- **`controls/`** — samostatná vrstva „control requirements / evidence / decision" oddělující *co musí být kontrolováno* od *co bylo skutečně prokázáno*. Používá se jak na úrovni Intentu (`CanonicalControlRequirementAuthority`), tak na úrovni Execution Plánu (`PlanningControlAuthority`, task-scoped, reachability-based).
- **`planner/`** — `FlowPlanner` převádí validovaný Flow AST na `ExecutionPlan` (strom `PlanNode`), včetně dependency/continuity hran, capability requirements a workflow failure policy. `CanonicalExecutionPlan` je stabilní veřejný „wire" pohled na stejná data.
- **`capabilities/`** — matice schopností cílových platforem (`TargetCapability`), vyjednávání kompatibility (`CompatibilityAnalyzer`), readiness/selection reporty a rozsáhlá sada „derived model integrity" validátorů, které kontrolují, že odvozená veřejná pole reportů (souhrny, doporučení) jsou skutečně reprodukovatelná z detailní evidence — jde o interní self-audit vrstvu.
- **`obligations/`** — graf architektonických závazků vázaný na „notes" balíčky; hlídá, že se do sémantiky nedostanou syrové runtime mechanismy (`shell.`, `command.`, `script.` apod.) jako „univerzální" reprezentace akce.

Architektonický styl je velmi konzistentní: prakticky každá „derived"/agregovaná datová struktura má vlastní `*IntegrityAuthority`, která ji zpětně ověřuje proti zdrojové evidenci a vyhazuje `InvalidDerivedModelException`, pokud report neodpovídá invariantům. To je silná obrana proti „lhaní" reportů (např. doporučení cíle bez skutečné evidence spustitelnosti).

---

## 2. Inventář souborů a tříd

### validator/

**`FlowValidator.kt`** (479 ř.)
- `class FlowValidator(registry, environmentPolicy)` — hlavní AST validátor. `fun validate(document): ValidationReport` (veřejná) — entry point; interně volá `validate(document, availability)`, prochází importy, systémy, kroky, error handler, sladí diagnostiky s `FlowAvailabilityAnalysis` a připojí `SafetyBoundaryValidator`.
  - `private fun validateStatement(...)` — rekurzivní dispatch přes všechny typy `StatementNode` (If/For/Parallel/Match/Retry/Try/Transform/Aggregate/Validate/Set/Fail/Skip/Approve/Expect/ErrorHandler).
  - `private fun validateAction(...)` — validuje modul/akci/target/parametry/handler pravidla jedné `ActionNode`.
  - `private fun checkExpr(...)` — rekurzivní validace výrazů (viz zjištění #1).
  - `private fun validateValueType/isCompatible/schemaValueKind` — typová kompatibilita hodnoty vůči schématu modulu, včetně `SECRET_PLAINTEXT` kontroly.
  - `private fun reconcileAvailabilityIssues(...)` — slaďuje starší lexikální `UNRESOLVED_REFERENCE` diagnostiky s autoritativním path-sensitivním product.
  - `private class Scope` — jednoduchý lexikální scope s rodičovským řetězcem.
- Žádná jiná top-level třída.

**`SafetyBoundaryValidator.kt`** (397 ř.)
- `class SafetyBoundaryValidator(registry, environmentPolicy)` — bezpečnostní brána nad AST. `fun validate(document): List<ValidationIssue>` (veřejná).
  - `private data class ApprovalState(unconditional, sensitiveEnvironmentGuard)` — nesený stav přes statementy.
  - `private fun validateStatements(...)` — rekurzivní procházení s hloubkovým limitem `MAX_STATEMENT_NESTING_DEPTH = 128` (`SAFETY_NESTING_DEPTH_EXCEEDED`).
  - `private fun validateAction(...)` — jádro pravidel: `ENVIRONMENT_CLASSIFICATION_UNKNOWN`, `ENVIRONMENT_APPROVAL_REQUIRED`, `ROLLBACK_APPROVAL_REQUIRED`/`APPROVAL_REQUIRED`, `SAFETY_REQUIRED`.
  - `private fun conditionSelectsSensitiveEnvironment/sensitiveEnvironmentEquality` — rozpoznává `if environment == "prod": approve()` vzor.
  - `private fun conditionProvesFiniteNonSensitiveEnvironment/evaluateForParameter/evaluateBinaryForParameter` — dokazuje, že `onlyIf` podmínka nad konečnou doménou vstupu vybírá jen ne-citlivé hodnoty prostředí.
  - `private fun isRollbackSensitive(action)` — rozpozná `standard.rollback` a `standard.execute(operation="rollback")`.
  - companion `MAX_STATEMENT_NESTING_DEPTH` (internal const).

**`Validation.kt`** (15 ř.) — `data class ValidationIssue(level, code, message, location)`, `data class ValidationReport(valid, issues)`. Čisté DTO, bez logiky.

### capabilities/

**`CompatibilityAnalyzer.kt`** (319 ř.) — `class CompatibilityAnalyzer(targets)`.
- `fun negotiate(plan, strict): TargetCapabilityNegotiationReport` (veřejná) — vyhodnotí všechny cíle, spočítá portabilitu, blokující problémy a workaroundy; volá `DerivedModelIntegrityAuthority.requireNegotiation`.
- `fun analyze(plan, targetName, strict): CompatibilityReport` (veřejná) — kompatibilita jednoho cíle; rekurzivně `inspect()` přes `PlanNode` strom.
- `private fun supportForCapability(target, capability): SupportLevel` — centrální rozhodovací tabulka „capability → SupportLevel" s fail-closed defaultem `UNSUPPORTED`.
- `private fun scoreCapabilities/averageScore/roundScore/addIfLimited/requiredCapabilities/collectRequiredCapabilities` — pomocné.

**`CompatibilityReadiness.kt`** (76 ř.) — enumy `MaterializationReadinessStatus`, `ProjectionReadinessStatus`; `data class CompatibilityReadinessFinding`; `data class CompatibilityReadinessReport` s computed `recommendationEligible` a factory `notEvaluated()` (veřejná).

**`DerivedModelIntegrity.kt`** (468 ř.) — jádro self-auditu.
- `data class DerivedModelIntegrityIssue`, `class InvalidDerivedModelException`.
- `object DerivedModelIntegrityAuthority`: `requireNegotiation`, `requireSelection`, `requireTraceInputs` (veřejné, throw-on-invalid varianty) + `negotiationIssues`, `selectionIssues` (veřejné, čisté listy issues). Desítky invariantů: unikátnost capability, pokrytí partition setů, `portabilityScore` v `0.0..1.0`, `blockedTargets`/`recommendedTargets` odvozenost, `executable`/`productionReady` shoda s „executable shape" apod.

**`ExecutionReadiness.kt`** (158 ř.) — enumy `ExecutionReadinessStatus`, `ReadinessSeverity`; `data class ReadinessFinding`, `data class ExecutionReadinessReport`; `class ExecutionReadinessAnalyzer(targets)` s `fun analyze(plan, target, strict): ExecutionReadinessReport` (veřejná) — převádí `CompatibilityReport`+`negotiate()` na blockery/warningy a preliminary readiness; volá `ExecutionReadinessIntegrityAuthority.requireValid`.

**`ExecutionReadinessIntegrity.kt`** (126 ř.) — `object ExecutionReadinessIntegrityAuthority`: `requireValid`, `issues` (veřejné) — ověřuje `generationAllowed`/`readiness`/`executable`/`productionReady` konzistenci a odvozuje očekávaný `readiness` stav pro křížovou kontrolu.

**`PlannerCapabilityConstraints.kt`** (59 ř.) — enum `PlannerCapabilityConstraintStatus`; `data class PlannerCapabilityConstraintReport`; `class PlannerCapabilityConstraintViolation`; `class PlannerCapabilityConstraintGate(targets)` s `fun check(...)` a `fun requireProjectionAllowed(...)` (obě veřejné) — pre-projekční brána, prokazatelně zapojená (`MandatoryMaterializationAuthority`).

**`TargetCapabilities.kt`** (69 ř.) — `data class TargetCapability` (všechny podpory defaultně `UNSUPPORTED` — fail closed by design), `fun feature(name, fallback)` (veřejná); enum `SupportLevel`, enum `TargetProjectionMode`; `typealias TargetRendererPayloadKind`; `data class TargetRendererPayloadTemplate`; `data class TargetProjectionRule` s `fun matches(module, action)` (veřejná).

**`TargetCapabilityMatrix.kt`** (128 ř.) — `data class TargetCapabilityMatrixReport/Entry`; `class TargetCapabilityMatrixAnalyzer(targets, requiredTargets)` s `fun analyze(): TargetCapabilityMatrixReport` (veřejná) — generuje maticový report a hlídá, že `conditions != UNSUPPORTED` vždy doprovází `expressionSupport` evidence.

**`TargetCompatibilityModels.kt`** (79 ř.) — `data class CompatibilityIssue`; enum `CompatibilityLevel`; `data class CompatibilityReport` s `fun assertAllowed(strict)` (veřejná, throw-style brána); `data class TargetCapabilityNegotiationReport/TargetNegotiationEntry/PortabilityIssue/TargetWorkaround`.

**`TargetDecisionTrace.kt`** (181 ř.) — enumy `DecisionTraceStatus`, `TargetDecisionKind`; DTO kroky/vysvětlení; `class TargetDecisionTraceAnalyzer(targets)` s přetíženou `fun analyze(...)` (veřejná, 2 varianty) — auditní vrstva nad již existujícími `negotiate()`/`TargetSelectionAnalyzer` výstupy; explicitně *nesmí* zavádět druhý výběrový algoritmus (viz komentář v kódu).

**`TargetExpressionSupport.kt`** (249 ř.) — nejbohatší soubor v adresáři z hlediska API.
- `enum TargetExpressionEvidenceKind`; `data class TargetExpressionSupportDeclaration/Decision`.
- `object TargetExpressionFeatures` — `call()`, `unary()`, `postfix()`, `logical()`, `binary()`, `isKnown()` (6 veřejných) — generuje/ověřuje identifikátory funkcí Flow expression jazyka.
- `object TargetExpressionSupport` — `evaluate()` (3 přetížení), `unsupportedReason()` (3 přetížení), `requiredFeatures(expression): Set<String>` (exhaustivní `when` přes **všechny** podtypy `ExpressionNode`, bez `else` větve — viz architektonické pozorování), `declarationValidationReason()`.

**`TargetNegotiationReportAnalyzer.kt`** (147 ř.) — DTO reporty + `object TargetNegotiationReportAnalyzer` s `fun explain(report): TargetNegotiationExplanationReport` (veřejná) — lidsky čitelné odůvodnění nad `TargetCapabilityNegotiationReport`.

**`TargetSelection.kt`** (94 ř.) — `data class TargetSelectionCandidate/Report`; `class TargetSelectionAnalyzer(targets)` s `fun analyze(plan, strict): TargetSelectionReport` (veřejná) — řadí kandidáty podle readiness a portability score; `recommendedTarget` v této preliminary fázi vždy `""` (doporučení vzniká až po evidenci materializace/projekce).

### planner/

**`CanonicalExecutionPlan.kt`** (234 ř.) — `data class CanonicalExecutionPlan/CanonicalPlanNode/CanonicalPlanBranch/CanonicalMatchCase`; `@Deprecated object ExecutionPlanCanonicalizer` s `fun canonicalize(plan): CanonicalExecutionPlan` (veřejná) — legacy compatibility facade, explicitně označená jako *ne*-autoritativní pro produkční kompilaci.

**`CanonicalExecutionPlanSemanticsAuthority.kt`** (57 ř.) — `enum CanonicalPlanNodeKind` (closed wire vocabulary); `object CanonicalExecutionPlanSemanticsAuthority` s `kindFor(node)`, `kindForSemanticCapability(name)` (obě veřejné) — jediné místo, které smí mapovat `semanticCapability` na `CanonicalPlanNodeKind`.

**`DependencyContinuity.kt`** (112 ř.) — enumy `PlanDependencyKind` (s computed `capability`), `PlanDependencyEvidence`, `PlanDependencyResolution`; `data class PlanDependencyRelation` s `init` invariantem „jen STATE relace smí nést `stateLifetime`"; `object PlanDependencyRelations` s `inferOrdering`, `relationKey`, `flatten`, `dependencies` (4 veřejné).

**`ExecutionPlan.kt`** (230 ř.) — jádro veřejného kontraktu. `data class ExecutionPlan` s `init` invariantem zakazujícím duplicitní `MODULE_CONTRACT` continuity relace pro stejnou (target, kind, channel) identitu; computed `val tasks` (depth-first flatten). Dále `PlanTrigger`, `PlanSchedule`, `PlanInput`, `PlanOutput`, `PlanAssumption`, sealed `PlanNode` a jeho implementace `TaskNode`, `ConditionNode`, `LoopNode`, `ParallelGroupNode`, `PlanBranch`, `MatchPlanNode`, `MatchCase`, `RetryGroupNode`, `TryPlanNode`, `ApprovalNode`, `DataOpNode`, `ControlNode` (celkem 19 top-level typů). Bez veřejných metod nad rámec datových tříd.

**`ExpressionRenderer.kt`** (70 ř.) — `object ExpressionRenderer` s `fun render(e): String` (veřejná) — exhaustivní `when` přes `ExpressionNode` (bez `else`), renderuje kompaktní Flow-like text pro diagnostiku/plán; obsahuje záměrně obfuskovaný `specialName()` (skládá řetězec z `charArrayOf` aritmetiky) pro rozlišení `secret(...)` volání od běžné funkce — viz zjištění v sekci 3. `object RuntimeParamRenderer` s `fun render(e, inputNames): String` (veřejná) — runtime-parametrová varianta, která vstupní reference obaluje do `"${...}"` interpolace.

**`FlowPlanner.kt`** (754 ř.) — největší soubor v rozsahu. `class MissingPlanningActionContractException`. `class FlowPlanner(registry)`:
- `fun plan(document): ExecutionPlan` (veřejná) — hlavní vstup.
- `internal fun plan(document, availability)`, `internal fun planWithProvenance(...)` — jádro s multi-workflow podporou; `availability.requireDirectPlanningSafe()` na začátku coby explicitní pre-condition.
- `private fun planStatement/planAction/planApproval/planMerge` — převod jednotlivých AST uzlů na `PlanNode`, včetně řešení `dependsOn`, `continuity` požadavků modulu a capability inference (`inferRequiredCapabilities`).
- `private class Ctx` — mutable plánovací kontext: generování ID uzlů (`id(prefix)`), registrace producentů hodnot (`registerProducer` s `check()` invariantem 1:1 mapování), `resolveContinuityRequirement`/`findProviders` — DFS hledání poskytovatele continuity kanálu napříč `merge` uzly a task dependency grafem (s ochranou proti cyklům přes `visited`).

**`WorkflowExecutionPlanSet.kt`** (200 ř.) — `class MultipleWorkflowCompatibilityViewException`; `data class WorkflowExecutionPlanView` (invarianty: neprázdný workflowId/Name, shoda `planVersion`, shoda množiny node id mezi `executionPlan`/`canonicalPlan`); `data class WorkflowExecutionPlanSet` s bohatým `init` blokem (unikátnost workflow id/name, trigger routing, žádné sdílení node id napříč workflows, single-workflow vs multi-workflow invarianty pro `sourceIntent`/`loweringReport`) a veřejnými `workflow(name)`, `requireSingleExecutionPlan()`, `requireSingleCanonicalPlan()`. Interní `WorkflowPlanningResult`, `ExecutionProgramPlanningResult`.

**`WorkflowFailurePolicy.kt`** (85 ř.) — `enum WorkflowFailureDisposition`; `data class WorkflowFailureHandlerEntry/Region` (s `init` invarianty); `data class WorkflowFailurePolicy` (RECOVER vyžaduje handler); `data class PlannedWorkflowFailurePolicy` s `fun compatibilityMirror(): List<PlanNode>` (veřejná) a companion `fun none()` (veřejná) — viz zjištění o `!!` v sekci 3.

### controls/

**`AuthoredControlEvidenceTextAuthority.kt`** (162 ř.) — `enum AuthoredControlEvidenceTextStatus`; `data class AuthoredControlEvidenceTextAssessment`; `object AuthoredControlEvidenceTextAuthority` s `fun assess(parameterName, value): AuthoredControlEvidenceTextAssessment` (veřejná) — regexový klasifikátor autorského textu (backup/rollback/change-ticket/retention/safety evidence). Viz zjištění #3 v sekci 3.

**`CanonicalControlRequirementAuthority.kt`** (573 ř.) — `object CanonicalControlRequirementAuthority`:
- `fun requirementsFor(intent): List<ControlRequirement>` (veřejná) — generuje požadavky z workflow kroků (`DATABASE_MIGRATE`→BACKUP, `DEPROVISION`→APPROVAL, `CLEANUP`→RETENTION_GUARD) a z `intent.policies`.
- `fun requirementsForCapabilities(capabilities)` (veřejná) — capability-only projekce pro inventář (explicitně *ne* pro autorizaci evidence).
- `fun assess(intent): ControlAssessment` (veřejná) — plné vyhodnocení requirement+evidence+decision.
- `fun validationIssues(assessment, requirementIds?)` (veřejná) — převod na `IntentValidationIssue` (SAFETY_REQUIRES_* kódy).
- `private fun approvalEvidence/booleanParameterEvidence/backupEvidence/rollbackEvidence/changeTicketEvidence/retentionEvidence/genericSafetyEvidence/externalReviewEvidence/policyEvaluationEvidence/confirmedParameterEvidence` — evidence resolvery podle druhu požadavku.
- `private data class IntentControlGraph` — pomocný graf ancestor vztahů uvnitř jednoho workflow (`ancestorsOf`, `controlStepsProtecting`, `intentControlSteps`, `parameterSteps`). Viz zjištění #2 v sekci 3 (nekonzistence `.any` vs `.all`).

**`ControlContracts.kt`** (202 ř.) — centrální DTO/enum sada: `ControlRequirementKind` (11 hodnot), `ControlRequirementSource` (5), `ControlRequirementScopeKind` (3) + `data class ControlRequirementScope` (s `init` invarianty per-kind a companion factory `operation()`/`planNode()`), `ControlRequirement`, `ControlEvidenceStatus` (4), `ControlEvidenceSource` (8), `ControlEvidence` (s invariantem „DYNAMIC evidence musí nést enforcement capabilities"), `ControlDecisionStatus` (3), `ControlDecision`, `ControlAssessment`. `object ControlDecisionAuthority` s `fun evaluate(requirements, evidence): ControlDecision` (veřejná) — jádro rozhodovací logiky: `SATISFIED`→OK, `UNSATISFIED`/`UNKNOWN`→blocking, `DYNAMIC`→pending; a `fun assessment(...)` (veřejná).

**`ControlRequirementIdentityAuthority.kt`** (36 ř.) — `object ControlRequirementIdentityAuthority` s `fun assign(requirements): List<ControlRequirement>` (veřejná) — přiděluje kolizně-bezpečná ID přes `CollisionSafeIdentityAuthority` (mimo rozsah), s `SemanticDuplicatePolicy.REJECT` pro přesné duplicity.

**`PlanningControlAuthority.kt`** (262 ř.) — `object PlanningControlAuthority`:
- `fun assess(canonicalRequirements, canonicalEvidence, nodes, modules, workflowFailureHandlerNodes): ControlAssessment` (veřejná) — doplňuje task-scoped požadavky z modulových kontraktů (`requiresApproval`/`destructive`/`requiresSafety`) k již existujícím intent-owned požadavkům; **dynamické (`DYNAMIC`) intent evidence je na této hranici tvrdě převedeno na `UNKNOWN`** (`failClosedForExecutionPlanning`) — záměrný fail-closed přechod.
- `fun requiredEnforcementCapabilities(assessment)` (veřejná).
- `fun rederivedModuleRequirements(...)` (veřejná, pohodlný wrapper).
- `private data class ControlGraph` — `ancestorsOf(nodeId)` DFS s ochranou proti cyklům; rozlišuje `unconditionalApprovalIds` vs `dynamicApprovalIds` podle toho, zda je `ApprovalNode` zanořen v podmíněné konstrukci (`If`/`Loop`/`Parallel`/`Match`/`Retry`) — `TryPlanNode.body` dědí okolní `dynamicContext`, `errorHandler` je vždy `dynamic=true`.

### effects/

**`CanonicalIntentEffectAuthority.kt`** (161 ř.) — `object CanonicalIntentEffectAuthority` s `fun effectsFor(capability, params)` a `fun effectsForRendered(capability, params)` (obě veřejné) — kompletní mapovací tabulka `StandardCapability → List<SemanticEffect>` pro všech 26 standardních capabilities, včetně `RecoverySemantics` pro `BACKUP`/`RESTORE`.

**`ModuleEffectCanonicalizer.kt`** (30 ř.) — `object ModuleEffectCanonicalizer` s `fun canonicalize(effects: Effects): List<SemanticEffect>` (veřejná) — adaptér z legacy `Effects` bucketů (reads/writes/creates/updates/deletes/executes/network/filesystem) na `SemanticEffect`; záměrně nehádá `EffectDomain` z názvu zdroje (zůstává `UNKNOWN`).

**`SemanticEffects.kt`** (170 ř.) — enumy `EffectDomain` (6), `EffectOperation` (8), `ResourceState` (3), `RecoveryEffectKind` (2), `RecoveryEndpointKind` (3); `data class ResourceStateTransition/RecoveryEndpoint/RecoverySemantics` (s bohatými `init` invarianty vázajícími `kind`↔`endpoint kind`↔`operation`); `data class SemanticEffect` s `init` invariantem „`transition` musí odpovídat `defaultTransition(operation)`" a companion `fun defaultTransition(operation)` (veřejná). Top-level `fun defaultTransition(...)` a `fun SemanticEffect.canonicalObservationValue(): String` (obě veřejné, extension/wrapper).

### safety/

**`EnvironmentSafetyPolicy.kt`** (204 ř.) — enumy `EnvironmentSensitivity` (3), `EnvironmentValueKind` (3), `UnmatchedEnvironmentValueDisposition` (2); `data class EnvironmentParameterEvidence` (s `init` invariantem podle `valueKind`), `EnvironmentPolicyRule`, `EnvironmentSafetyPolicyNotes`, `EnvironmentClassificationEvidence` (computed `evidenceAvailable`/`classificationResolved`). `class EnvironmentSafetyPolicy(notes)`:
- `fun recognizesParameter(name): Boolean`, `fun classify(parameters: Map<String,String>)`, `fun classify(parameters: List<EnvironmentParameterEvidence>)` (3 veřejné) — jádro klasifikace; při více odpovídajících pravidlech vybírá podle priority `SENSITIVE(0) < UNKNOWN(1) < NON_SENSITIVE(2)` — tedy při konfliktu vyhrává bezpečnější (přísnější) klasifikace.
- `init` blok validuje konzistenci celé politiky (unikátní rule id, `approvalEnvironment` jen u `SENSITIVE`, žádné „unmatched disposition" pro neregistrovaný parametr).

**`StandardEnvironmentSafetyPolicyNotes.kt`** (47 ř.) — `object StandardEnvironmentSafetyPolicyNotes` s `fun baseline(): EnvironmentSafetyPolicyNotes` a `fun policy(): EnvironmentSafetyPolicy` (obě veřejné) — konkrétní výchozí politika: `prod/production/live` → SENSITIVE (s `approvalEnvironment="production"`), `dev/development/test/testing/qa/sandbox` → NON_SENSITIVE, `namespace`/`cluster` mají `NOT_ENVIRONMENT_EVIDENCE` dispozici (tj. nerozpoznaná hodnota u těchto polí se nepovažuje ani za `UNKNOWN` prostředí).

### obligations/

**`ArchitectureObligationGraph.kt`** (311 ř.) — enumy `ArchitectureObligationKind` (7), `ArchitectureObligationEdgeKind` (4); `data class ArchitectureObligationNode/Edge/Graph` (`nodeIds()` veřejná), `ArchitectureObligationGraphStatus`, `ArchitectureObligationGraphIssue`, `ArchitectureObligationGraphReport` (computed `valid`). `class ArchitectureObligationGraphValidator(notesPackages)` s `fun validate(graph): ArchitectureObligationGraphReport` (veřejná) — validuje ID formát (regex), duplicitní node id, vazbu na `notesPackage`/`declaration`, **explicitní zákaz „univerzálních" runtime mechanismů** (`shell.`, `command.`, `script.`, `bash.`, `powershell.` prefixy a odpovídající raw payload klíče `command/script/shell/run`), acykličnost hran (DFS s `visiting`/`visited`). `object StandardArchitectureObligationGraphs` s `fun baseline()` (veřejná) — referenční graf `approval.require → approval.required → human.approval → notes.contract.valid`.

---

## 3. Zjištěné chyby a nedostatky

### #1 — Chybí validace `operator` u `UnaryExpressionNode`/`UnaryPostfixExpressionNode` (POTVRZENO, s prokázaným downstream dopadem)

**Soubor/řádky:** `src/main/kotlin/org/flowlang/validator/FlowValidator.kt:352-353`

```kotlin
is UnaryExpressionNode -> checkExpr(expr.operand, scope, defaultScope, resultFields, issues, strictResultFields)
is UnaryPostfixExpressionNode -> checkExpr(expr.operand, scope, defaultScope, resultFields, issues, strictResultFields)
```

oproti sousedním větvím, které pole `operator: String` explicitně validují proti povolené množině:

```
FlowValidator.kt:344   is BinaryExpressionNode -> if (expr.operator !in validOperators) issues += err("UNKNOWN_OPERATOR", ...)
FlowValidator.kt:349   is LogicalExpressionNode -> if (expr.operator !in setOf("and","or")) issues += err("UNKNOWN_OPERATOR", ...)
```

Obě dotčené AST třídy (`ast/ExpressionNodes.kt:49-59`) nesou `val operator: String` bez jakéhokoli enum omezení na úrovni typu, takže cokoli, co dosadí AST přímo (např. AI-first intent lowering nebo ručně sestavený strom, ne nutně přes `ExpressionParser`), projde validátorem bez jediné diagnostiky bez ohledu na hodnotu `operator`. Parser sám generuje pouze `"not"` (prefix) a `"empty"`/`"exists"` (postfix) — `parser/ExpressionParser.kt:82,103-104,122-123` — takže validátor by měl minimálně tuto množinu vynucovat stejně jako u binárních/logických operátorů.

**Prokázaný dopad po proudu (mimo přímý rozsah zadání, ověřeno pro posouzení závažnosti):**
- `generators/JenkinsGroovyExpr.kt:32-36` — `UnaryPostfixExpressionNode` s neznámým `operator` spadne do `else -> render(e.operand)`: **operátor se tiše zahodí** a vyrenderuje se jen samotný operand.
- `targets/builtin/GitHubActionsTargetExpressionTranslator.kt:53-56` — identický vzor (`else -> renderGitHub(e.operand, inputs)`).
- `generators/JenkinsGroovyExpr.kt:31` a `GitHubActionsTargetExpressionTranslator.kt:48-52` — `UnaryExpressionNode` s `operator != "not"` se vyrenderuje jako syntaxe volání funkce `"${e.operator}(${operand})"`, což u cílové platformy buď spadne za běhu (Jenkins/Groovy), nebo v horším případě vytvoří validní, ale sémanticky nesmyslný výraz.

Protože všechny tři vrstvy (validátor → renderer Jenkins → renderer GitHub Actions) sdílejí stejnou mezeru, poškozený/nesprávný `operator` u postfixového uzlu (typicky bezpečnostně relevantní `exists`/`empty` test) může **tiše zmizet z generovaného Jenkinsfile/GitHub Actions YAML bez jakékoli chyby na validaci, plánování nebo generování**. To přímo koliduje s deklarovaným cílem projektu být „safety boundary mezi natural language a executable automation" (README).

**Závažnost:** Vysoká — jde o mezeru přesně tam, kde bezpečnostní hranice deklaruje, že AI-generovaný AST musí být deterministicky ověřen dřív, než se stane spustitelným artefaktem.

**Návrh opravy:**
```kotlin
private val validUnaryOperators = setOf("not")
private val validPostfixOperators = setOf("empty", "exists")
...
is UnaryExpressionNode -> {
    if (expr.operator !in validUnaryOperators) issues += err("UNKNOWN_OPERATOR", "Unknown unary operator '${expr.operator}'")
    checkExpr(expr.operand, ...)
}
is UnaryPostfixExpressionNode -> {
    if (expr.operator !in validPostfixOperators) issues += err("UNKNOWN_OPERATOR", "Unknown postfix operator '${expr.operator}'")
    checkExpr(expr.operand, ...)
}
```
Zároveň stojí za úvahu doplnit `location` na `UnaryExpressionNode`/`UnaryPostfixExpressionNode` (aktuálně na rozdíl od `BinaryExpressionNode`/`LogicalExpressionNode`/`ReferenceNode` nemají pole `location: SourceLocation?` vůbec — `ast/ExpressionNodes.kt:49-59`), jinak nová diagnostika nebude mít přesnou pozici ve zdroji.

---

### #2 — Nekonzistentní `ANY` vs `ALL` pokrytí operací mezi `intentControlSteps` a `controlStepsProtecting`

**Soubor/řádky:** `src/main/kotlin/org/flowlang/controls/CanonicalControlRequirementAuthority.kt:492-502` vs `:504-526`

Dvě strukturálně téměř identické metody uvnitř `IntentControlGraph` počítají, zda control step (např. `APPROVE`) „chrání" intent-scoped požadavek:

```kotlin
fun intentControlSteps(capability: StandardCapability): List<ScopedIntentStep> {   // řádek 492
    val controls = controlSteps(capability)
    val operations = nonControlOperations()
    if (operations.isEmpty()) return controls
    return controls.filter { control ->
        operations.any { operation -> ... control.step.id in ancestorsOf(...) }    // řádek 497 — ANY
    }
}

fun controlStepsProtecting(requirement, capability): List<ScopedIntentStep> {      // řádek 504
    ...
    return controls.filter { control ->
        operations.all { operation -> ... control.step.id in ancestorsOf(...) }    // řádek 521 — ALL
    }
}
```

`intentControlSteps` (ANY) se používá výhradně v `approvalEvidence()` (řádek 187) v cestě `allowIntentWideApproval && scope.kind == INTENT && source == INTENT_POLICY` — tj. přesně pro autorský intent-wide policy požadavek typu *„tento intent vyžaduje schválení"*. Výsledek: pokud má intent 3 operace a schválení chrání (je ancestorem) jen jednu z nich, `approvals.isEmpty()` je `false` → požadavek je vyhodnocen jako `SATISFIED` (případně `DYNAMIC`, pokud má podmínku), přestože zbylé dvě operace nemají žádné schválení nad sebou.

Naproti tomu strukturálně stejný požadavek pro jiné druhy kontrol (`BACKUP` apod., cesta `controlStepsProtecting` s `INTENT` scope a `!allowIntentWideApproval`) vyžaduje `ALL` operací — tedy podstatně přísnější a bezpečnější sémantiku.

**Scénář selhání:** Autor (nebo AI) napíše intent s policy `type: APPROVAL` bez explicitního `condition`, workflow obsahuje `deploy` (chráněno schválením) a `migrate-database`+`cleanup` (bez schválení). `CanonicalControlRequirementAuthority.assess()` vrátí pro intent-wide APPROVAL požadavek `SATISFIED`, `validationIssues()` nevygeneruje `SAFETY_REQUIRES_APPROVAL`, ačkoli 2 ze 3 rizikových operací fakticky bez schválení projdou dál k plánování/target generaci.

**Závažnost:** Vysoká — přímo ovlivňuje, zda je „approval" bezpečnostní kontrola vyhodnocena jako splněná; jde přesně o typ „logické díry v capability validaci / nekonzistence mezi kontrolami", na který zadání cílí.

**Návrh opravy:** Sjednotit na `ALL` (bezpečnější varianta), pokud nejde o záměrný produktový požadavek „stačí alespoň jedno schválení kdekoli v intentu" — v tom případě je nutné to zdokumentovat komentářem u `intentControlSteps` a přidat konformační test, který tento rozdíl exercíruje (aktuálně rozdíl není v kódu okomentován vůbec, což naznačuje spíše přehlédnutí než záměr).

---

### #3 — `AuthoredControlEvidenceTextAuthority` přijímá jakoukoli URI/cestu jako „konkrétní backup evidenci"

**Soubor/řádky:** `src/main/kotlin/org/flowlang/controls/AuthoredControlEvidenceTextAuthority.kt:65-70` (`concreteBackupReference`), `:97-100` (`concreteLocator`), `:145-147` (regexy `uriReference`/`unixPathReference`/`windowsPathReference`)

```kotlin
private fun concreteBackupReference(raw: String): Boolean {
    val value = raw.trim()
    return concreteLocator(value) || backupIdentifier.matches(value) || namedBackupReference.matches(value)
}
private fun concreteLocator(value: String): Boolean =
    uriReference.matches(value) || unixPathReference.matches(value) || windowsPathReference.matches(value)
...
private val uriReference = Regex("(?i)[A-Za-z][A-Za-z0-9+.-]*://\\S+")
```

`uriReference` je obecný „cokoli vypadá jako URI" regex — nekontroluje žádnou souvislost s pojmem backup/snapshot. Parametr `backup: "https://example.com/anything"` (i naprosto nesouvisející URL) projde `concreteBackupReference` → `concreteLocator` → `CONFIRMED`, a `backupEvidence()`/`confirmedParameterEvidence()` pak vrátí `ControlEvidenceStatus.SATISFIED`, čímž se splní `ControlRequirementKind.BACKUP` požadavek pro `DATABASE_MIGRATE`.

Stejný `concreteLocator` je použit i v `concreteSafetyControl` (řádek 88-95), takže i obecný „safety guard" požadavek lze splnit libovolnou URL/cestou v poli `safety`.

**Scénář selhání:** AI (nebo uživatel pod tlakem) vyplní `backup: "https://status.example.com"` jen proto, aby prošla validace, aniž by šlo o skutečnou zálohu. Systém to klasifikuje jako `CONFIRMED`/`SATISFIED` a `DATABASE_MIGRATE` bez skutečné zálohy projde bezpečnostní kontrolou.

**Závažnost:** Střední — jde o heuristiku (textová evidence je z principu slabší důkaz než strukturovaná data), ale vzhledem k tomu, že chrání destruktivní/nevratné operace (DB migrace), je záchytná síť zde neúměrně široká vůči záměru „concrete backup reference".

**Návrh opravy:** Vyžadovat, aby `uriReference`/cestové shody navíc obsahovaly backup-relevantní klíčové slovo (podobně jako `backupIdentifier`/`namedBackupReference` už dělají), nebo úplně odebrat obecný `concreteLocator` z `concreteBackupReference`/`concreteSafetyControl` a spoléhat jen na `backupIdentifier`/`namedBackupReference`, které už vyžadují prefix `backup|snapshot|archive`.

---

### #4 — `CallExpressionNode.function` není nikde validován (chybí `UNKNOWN_FUNCTION`)

**Soubor/řádky:** `src/main/kotlin/org/flowlang/validator/FlowValidator.kt:357`

```kotlin
is CallExpressionNode -> expr.args.forEach { checkExpr(it, scope, defaultScope, resultFields, issues, strictResultFields) }
```

`checkExpr` rekurzuje do argumentů, ale nikde v repozitáři (ověřeno grepem přes celý strom `org/flowlang`) neexistuje registr povolených built-in funkcí — parser (`parser/ExpressionParser.kt:172`) přijme jakýkoli identifikátor následovaný `(...)` jako platné volání. Typo v názvu funkce (např. `matchs(x, "...")` místo `matches(...)`) tedy neprodukuje žádnou diagnostiku ve validaci ani v plánování; teprve renderer (`generators/JenkinsGroovyExpr.kt:30`) jej doslovně přepíše do cílového jazyka, kde selže až za běhu cílové platformy (Jenkinsfile), tj. mimo standardizační vrstvu Horistum.

**Závažnost:** Nízká až střední — validace ostatních referencí (`UNKNOWN_RESULT_FIELD`) má obdobný „typo detection" účel jako warning; u volání funkcí ekvivalent chybí úplně.

**Návrh opravy:** Zavést `object KnownExpressionFunctions` (analogicky k `TargetExpressionFeatures`) a ve `checkExpr` přidat warning `UNKNOWN_FUNCTION`, pokud `expr.function` není v této množině ani v capability-specific rozšíření modulu.

---

### #5 — Široký `catch (e: Exception)` kolem parsování podmínky v `TargetExpressionSupport`

**Soubor/řádky:** `src/main/kotlin/org/flowlang/capabilities/TargetExpressionSupport.kt:85-99`

```kotlin
fun evaluate(target: TargetCapability, condition: String): TargetExpressionSupportDecision {
    val parsed = try {
        ExpressionParser.parseSource(condition)
    } catch (e: Exception) {
        return TargetExpressionSupportDecision(..., supported = false, reason = "Condition could not be parsed for target '${target.target}': ${e.message}")
    }
    ...
}
```

Toto je jediné `catch` v celém zkoumaném rozsahu. `ExpressionParser` nedefinuje vlastní typovanou parse-exception (ověřeno), takže `catch (e: Exception)` je jediná praktická volba — nicméně zachytí i neočekávané runtime chyby (např. `NullPointerException` způsobenou skutečnou chybou jinde v parseru) a tiše je promění na business rozhodnutí „target podmínku nepodporuje", což ztěžuje odhalení skutečných regresí v parseru (chyba se „schová" za legitimní compatibility zamítnutí).

**Závažnost:** Nízká — funkčně bezpečné (fail-closed směrem k `supported = false`), ale znesnadňuje diagnostiku parseru.

**Návrh opravy:** Pokud `ExpressionParser` získá vlastní `ParseException`, zúžit `catch` na něj; případně alespoň logovat/rozlišit `RuntimeException` od skutečně neočekávaných chyb.

---

### #6 — Jediný force-unwrap (`!!`) v celém rozsahu — nízké riziko, ale nekonzistentní styl

**Soubor/řádky:** `src/main/kotlin/org/flowlang/planner/WorkflowFailurePolicy.kt:66`

```kotlin
init {
    val handler = policy.handler
    require((handler == null) == handlerNodes.isEmpty()) { ... }
    require((handler == null) == (compatibilityBoundaryNodeId == null)) { ... }   // řádek 59
    if (handler != null) {
        require(handler.nodeIds == handlerNodes.map(PlanNode::id)) { ... }
        require(compatibilityBoundaryNodeId!!.isNotBlank()) { ... }               // řádek 66
    }
}
```

`!!` je zde fakticky nedosažitelné (předchozí `require` na řádku 59 už garantuje ne-null, pokud je `handler != null`), takže reálné riziko `NullPointerException` je nulové. Nicméně o pár řádků níž (`compatibilityMirror()`, řádek 75) stejná třída pro analogickou situaci používá `requireNotNull(compatibilityBoundaryNodeId)` — tedy bezpečnější/konzistentnější idiom se srozumitelnou chybovou zprávou. Grep přes celý rozsah (`validator/capabilities/planner/controls/effects/safety/obligations`) nenašel žádný jiný výskyt `!!`, žádný `TODO/FIXME/HACK` komentář a žádné prázdné `catch` bloky — v tomto ohledu je kód nadprůměrně disciplinovaný.

**Závažnost:** Nízká (kosmetická/stylová).

**Návrh opravy:** Nahradit `compatibilityBoundaryNodeId!!` za `requireNotNull(compatibilityBoundaryNodeId)` kvůli konzistenci a lepší chybové zprávě, kdyby se invariant v budoucnu porušil refaktoringem.

---

### #7 — Pozorování (nepotvrzený bug): `PlanningControlAuthority` odvozuje „reachable approval" z dependency grafu, ne z control-flow dominance

**Soubor/řádky:** `src/main/kotlin/org/flowlang/controls/PlanningControlAuthority.kt:114-150` (`approvalEvidence`), `:195-249` (`ControlGraph.ancestorsOf`/`index`)

`ancestorsOf(task.id)` prochází pouze `dependenciesByNode` (hrany `dependsOn` odvozené plannerem z datových/explicitních závislostí), nikoli explicitní control-flow dominanci. Task je považován za „chráněný" schválením, pokud je JAKÝKOLI uzel v `unconditionalApprovalIds` jeho ancestorem v tomto grafu. Teoreticky, pokud by `dependsOn` hrana vznikla mezi uzly z vzájemně se vylučujících větví (např. přes sdílenou `merge`/`Set` hranu), mohlo by dojít k falešně pozitivnímu pokrytí schválením. Vzhledem k tomu, že `FlowAvailabilityAnalyzer` (mimo rozsah tohoto zadání) je popsán jako plně path-sensitivní a `dependsOn` z něj čerpá, toto pravděpodobně NENÍ reálně vykonatelné, ale ověření vyžaduje analýzu `core/FlowAvailabilityAnalyzer.kt`, která je mimo přidělený rozsah.

**Závažnost:** Nízká/observační — doporučuji cílený unit test „schválení v jedné větvi if/else nesmí krýt akci v druhé větvi" jako regresní pojistku, pokud ještě neexistuje.

---

### #8 — Pozorování: `evaluateForParameter` v `SafetyBoundaryValidator` rozumí jen `==`/`!=`/`and`/`or` (bezpečně, ale omezeně)

**Soubor/řádky:** `src/main/kotlin/org/flowlang/validator/SafetyBoundaryValidator.kt:294-325`

`conditionProvesFiniteNonSensitiveEnvironment` (bypass plného schválení pro `onlyIf` podmínky) rozpoznává jen binární `==`/`!=`/`equals` porovnání a `and`/`or` logické kombinace. Běžný vzor `environment in ["dev", "test"]` (operátor `in`, pravá strana `ListLiteralNode`) není `referenceAndLiteral`-kompatibilní → `evaluateBinaryForParameter` vrátí `null` → hodnota je „nerozhodnutelná" → `conditionProvesFiniteNonSensitiveEnvironment` vrátí `false` pro daný parametr. Důsledek je **bezpečný** (autor musí použít plné schválení místo bypassu), ale jde o funkční mezeru/omezení pokrytí, ne o bezpečnostní díru.

**Závažnost:** Nízká — informační; fail-closed směr je správný, ale stojí za zvážení rozšířit podporu o `in`/`contains`, aby běžnější zápisy nebyly zbytečně nuceny k plnému schválení.

---

### Souhrn dle závažnosti

| # | Nález | Soubor:řádek | Závažnost |
|---|---|---|---|
| 1 | Chybí validace `operator` u Unary/UnaryPostfix výrazů + prokázaný tichý downstream dopad | `validator/FlowValidator.kt:352-353` | **Vysoká** |
| 2 | `.any` vs `.all` nekonzistence v pokrytí operací pro intent-wide APPROVAL evidenci | `controls/CanonicalControlRequirementAuthority.kt:497` vs `:521` | **Vysoká** |
| 3 | Obecná URI/cesta akceptována jako „konkrétní backup evidence" | `controls/AuthoredControlEvidenceTextAuthority.kt:65-100,145-147` | Střední |
| 4 | `CallExpressionNode.function` nikdy nevalidován (chybí UNKNOWN_FUNCTION) | `validator/FlowValidator.kt:357` | Nízká–Střední |
| 5 | Široký `catch (e: Exception)` maskuje neočekávané chyby parseru | `capabilities/TargetExpressionSupport.kt:88` | Nízká |
| 6 | Jediný `!!` v rozsahu — bezpečný, ale stylově nekonzistentní | `planner/WorkflowFailurePolicy.kt:66` | Nízká |
| 7 | Reachability schválení přes dependency graf místo control-flow dominance (nepotvrzeno) | `controls/PlanningControlAuthority.kt:114-150` | Nízká/observace |
| 8 | `onlyIf` bypass nerozumí `in`/`contains` (bezpečně degraduje) | `validator/SafetyBoundaryValidator.kt:294-325` | Nízká/observace |

Žádné `TODO`/`FIXME`/`HACK` komentáře, žádné prázdné `catch` bloky, žádné jiné force-unwrapy nebyly v rozsahu nalezeny.

---

## 4. Architektonická pozorování

1. **„Derived model integrity" jako opakující se vzor.** `capabilities/DerivedModelIntegrity.kt`, `ExecutionReadinessIntegrity.kt` a implicitně i invarianty v `ExecutionPlan.kt`/`WorkflowExecutionPlanSet.kt`/`ControlContracts.kt` tvoří konzistentní styl: každý veřejný report se po sestavení zpětně provalí proti vlastní evidenci a při nesouladu vyhodí `InvalidDerivedModelException`/`require`. Toto je silná ochrana proti tichému „lhaní" agregovaných polí (např. `recommendedTargets` bez skutečné evidence), ale zároveň zvyšuje riziko, že běžná provozní chyba (např. nekonzistence vzniklá budoucím refaktoringem) skončí jako runtime `IllegalArgumentException`/`IllegalStateException` namísto `ValidationIssue` — tedy fail-fast na úrovni procesu, ne na úrovni standardizovaného reportu chyb. To je architektonicky zamýšlené (fail closed), ale stojí za zdokumentování jako explicitní designové rozhodnutí, pokud tam ještě není.

2. **Fail-closed jako opakovaný princip.** `TargetCapability` (všechny úrovně podpory defaultně `UNSUPPORTED`), `CompatibilityAnalyzer.supportForCapability` (neznámá capability → `UNSUPPORTED`), `EnvironmentSafetyPolicy` (neznámá kombinace → `UNKNOWN`, nikdy tiše `NON_SENSITIVE`), `PlanningControlAuthority.failClosedForExecutionPlanning` (DYNAMIC evidence na hranici plánování → `UNKNOWN`) — to je konzistentně aplikovaný bezpečnostní princip napříč celou vrstvou. Zjištění #1, #2 a #3 jsou o to významnější, protože jde o výjimky z jinak velmi disciplinovaného vzoru.

3. **Neexhaustivní `when` s `else -> Unit` v traversal funkcích validátoru/planneru.** `FlowValidator.checkExpr` (řádek 363) a `FlowPlanner.collectRoots` (řádek 567) používají `else -> Unit` větev nad `sealed interface ExpressionNode`. Naproti tomu `TargetExpressionSupport.requiredFeatures` (`capabilities/TargetExpressionSupport.kt:183-239`) a `ExpressionRenderer.render` (`planner/ExpressionRenderer.kt:7-27`) jsou napsány jako plně exhaustivní `when` bez `else` — Kotlin kompilátor tedy u těchto dvou vynutí ošetření every nového podtypu `ExpressionNode` při rozšíření jazyka, zatímco `FlowValidator`/`FlowPlanner` by nový uzel tiše ignorovaly bez kompilační chyby. Doporučuji zvážit odstranění `else -> Unit` z `checkExpr`/`collectRoots` (explicitním výčtem zbylých uzlů, i když jde jen o `Unit`) jako prevenci budoucí tiché mezery analogické zjištění #1.

4. **Oddělení „declaration of intent" od „proof of enforcement" je hlavní architektonická myšlenka `controls/`.** Explicitně pojmenované datové typy (`ControlRequirement` vs `ControlEvidence` vs `ControlDecision`) a komentáře v kódu (`PlanningControlAuthority.kt:14-28`) ukazují vědomé rozhodnutí nezaměňovat `safety: requiresApproval` deklaraci s reálně dosažitelným schválením. To je koncepčně silné a zjištění #2 je o to relevantnější, protože poškozuje právě tento princip pro jeden konkrétní (ale důležitý) případ.

5. **Duplicitní/paralelní grafové abstrakce.** `CanonicalControlRequirementAuthority.IntentControlGraph` (intent-level, workflow `requires`), `PlanningControlAuthority.ControlGraph` (plan-level, `dependsOn`), `FlowPlanner.Ctx.findProviders` (continuity-level, `mergeDependencies`+`taskDependencies`) a `obligations/ArchitectureObligationGraph` (notes-level) jsou čtyři nezávislé, strukturálně podobné (ancestor/DFS s cyklovou ochranou) grafové implementace nad různými vrstvami stejného pipeline. To je architektonicky obhajitelné (různé vrstvy záměrně nesdílejí závislosti), ale zvyšuje pravděpodobnost přesně tohoto typu nekonzistence, jaký ukazuje zjištění #2 — čtyři nezávisle udržované implementace stejného vzoru („dosažitelnost přes ancestor hrany") mají čtyři nezávislé příležitosti k `.any`/`.all` chybě.

6. **`capabilities/` vrstva striktně odděluje „preliminary" (capability-only) od „evidence-based" (post-materializace) rozhodnutí** pomocí `readinessEvidenceAvailable: Boolean` flagu, který se táhne přes `TargetCapabilityNegotiationReport`, `ExecutionReadinessReport`, `TargetSelectionCandidate`. `CompatibilityAnalyzer`, `ExecutionReadinessAnalyzer` a `TargetSelectionAnalyzer` v tomto rozsahu vždy produkují `readinessEvidenceAvailable = false` (nebo ho vůbec nenastavují, tedy default `false`) — reálná evidence se doplňuje až mimo rozsah této analýzy (pravděpodobně v `generators/`). To je čistě informativní pozorování o hranicích zkoumané vrstvy.

7. **`ExpressionRenderer.specialName()` (`planner/ExpressionRenderer.kt:39`)** skládá řetězec `"secret"` z aritmetiky nad `charArrayOf` místo přímého literálu — funkčně neškodné (jde jen o interní rozlišení `secret(...)` volání od uživatelské funkce), ale čitelnostně matoucí a bez zjevného bezpečnostního přínosu (`Regex`/`String` literál by dělal totéž transparentně). Stojí za zjednodušení při příští údržbě tohoto souboru.

---

## 5. Statistická tabulka

| Soubor | Řádky | Tříd/objektů/enum/interface (top-level) | Veřejné funkce (nezanořené) |
|---|---:|---:|---:|
| validator/FlowValidator.kt | 479 | 1 | 1 |
| validator/SafetyBoundaryValidator.kt | 397 | 1 | 1 |
| validator/Validation.kt | 15 | 2 | 0 |
| capabilities/CompatibilityAnalyzer.kt | 319 | 1 | 2 |
| capabilities/CompatibilityReadiness.kt | 76 | 4 | 1 |
| capabilities/DerivedModelIntegrity.kt | 468 | 3 | 5 |
| capabilities/ExecutionReadiness.kt | 158 | 5 | 1 |
| capabilities/ExecutionReadinessIntegrity.kt | 126 | 1 | 2 |
| capabilities/PlannerCapabilityConstraints.kt | 59 | 4 | 2 |
| capabilities/TargetCapabilities.kt | 69 | 6 | 2 |
| capabilities/TargetCapabilityMatrix.kt | 128 | 3 | 1 |
| capabilities/TargetCompatibilityModels.kt | 79 | 7 | 1 |
| capabilities/TargetDecisionTrace.kt | 181 | 6 | 2 |
| capabilities/TargetExpressionSupport.kt | 249 | 5 | 14 |
| capabilities/TargetNegotiationReportAnalyzer.kt | 147 | 5 | 1 |
| capabilities/TargetSelection.kt | 94 | 3 | 1 |
| planner/CanonicalExecutionPlan.kt | 234 | 5 | 1 |
| planner/CanonicalExecutionPlanSemanticsAuthority.kt | 57 | 2 | 2 |
| planner/DependencyContinuity.kt | 112 | 5 | 4 |
| planner/ExecutionPlan.kt | 230 | 19 | 0 |
| planner/ExpressionRenderer.kt | 70 | 2 | 2 |
| planner/FlowPlanner.kt | 754 | 3 | 1 |
| planner/WorkflowExecutionPlanSet.kt | 200 | 5 | 3 |
| planner/WorkflowFailurePolicy.kt | 85 | 5 | 2 |
| controls/AuthoredControlEvidenceTextAuthority.kt | 162 | 3 | 1 |
| controls/CanonicalControlRequirementAuthority.kt | 573 | 1 | 4 |
| controls/ControlContracts.kt | 202 | 12 | 4 |
| controls/ControlRequirementIdentityAuthority.kt | 36 | 1 | 1 |
| controls/PlanningControlAuthority.kt | 262 | 1 | 3 |
| effects/CanonicalIntentEffectAuthority.kt | 161 | 1 | 2 |
| effects/ModuleEffectCanonicalizer.kt | 30 | 1 | 1 |
| effects/SemanticEffects.kt | 170 | 9 | 3 |
| safety/EnvironmentSafetyPolicy.kt | 204 | 8 | 3 |
| safety/StandardEnvironmentSafetyPolicyNotes.kt | 47 | 1 | 2 |
| obligations/ArchitectureObligationGraph.kt | 311 | 10 | 3 |
| **Celkem (35 souborů)** | **6 944** | **~150** | **~78** |

---

## Shrnutí

Analyzoval jsem 35 souborů (6 944 řádků) validační/plánovací vrstvy FlowAi: `validator/`, `capabilities/`, `planner/`, `controls/`, `effects/`, `safety/`, `obligations/`. Kód je nadprůměrně disciplinovaný — jediný `!!` v celém rozsahu (a ten je fakticky nedosažitelný), žádné `TODO/FIXME/HACK`, jediný `catch` blok, a opakovaně aplikovaný „fail-closed" princip (neznámé capability/prostředí/evidence vždy padají na bezpečnější stranu).

Nalezeno 8 zjištění, z toho 2 vysoké závažnosti:

1. **(Vysoká, potvrzeno zadáním)** `FlowValidator.checkExpr` (`validator/FlowValidator.kt:352-353`) nevaliduje pole `operator` u `UnaryExpressionNode`/`UnaryPostfixExpressionNode`, na rozdíl od `BinaryExpressionNode`/`LogicalExpressionNode` o pár řádků výš. Ověřil jsem i downstream dopad: renderery `generators/JenkinsGroovyExpr.kt:32-36` a `targets/builtin/GitHubActionsTargetExpressionTranslator.kt:53-56` u neznámého postfixového operátoru **tiše zahodí operátor** a vyrenderují jen operand — poškozený `exists`/`empty` test tak může beze stopy zmizet z generovaného Jenkinsfile/GitHub Actions workflow.
2. **(Vysoká, nové)** `controls/CanonicalControlRequirementAuthority.kt:497` (`intentControlSteps`, `.any`) vs `:521` (`controlStepsProtecting`, `.all`) — nekonzistentní sémantika pokrytí operací u intent-wide APPROVAL evidence: schválení chránící jen jednu z více operací může nesprávně splnit celointentové „vyžaduje schválení".

Dále: (3, Střední) `AuthoredControlEvidenceTextAuthority` akceptuje libovolnou URI/cestu jako „backup evidenci" (`controls/AuthoredControlEvidenceTextAuthority.kt:65-100,145-147`); (4, Nízká–Střední) `CallExpressionNode.function` se nikde nevaliduje; (5, Nízká) široký `catch(Exception)` v `capabilities/TargetExpressionSupport.kt:88`; (6, Nízká) jediný `!!` v `planner/WorkflowFailurePolicy.kt:66`; (7–8) dvě observace bez potvrzeného bugu.
