# Analýza vrstvy: generators/ + targets/ (projekční vrstva FlowAi / Horistum)

Rozsah: `generators/` (1 top-level + 20 v `manifest/`), `targets/` (5 top-level + 30 v `builtin/`) = 56 souborů, 7729 řádků (ověřeno `wc -l`). Všechny soubory přečteny celé.

---

## 1. Přehled vrstvy

Tato vrstva je **projekční hranice** standardu: bere `ExecutionPlan` + `CompatibilityReport` (výsledek capability negotiation) a produkuje `TargetManifest` — cílově-neutrální, auditovatelný artefakt — který teprve volitelně převede konkrétní renderer (Jenkins/GitHub Actions/Tekton) na vendor syntax.

Klíčové vrstvy uvnitř:

1. **`generators/manifest/`** — jádro kontraktu:
   - `MandatoryMaterializationAuthority.kt` (1096 řádků) — jediná autorita, která validuje `ExecutionPlan` (efekty, kontinuita, control evidence, topologie, lowering evidence z intent vrstvy) a vydává `TargetProjectionAuthorization`. Bez ní nelze manifest vůbec vygenerovat.
   - `TargetMaterializationResolver(Engine)` — pro každý `TaskNode` odvozuje `MaterializationDecision` (MATERIALIZABLE/ADAPTER_REQUIRED/UNSUPPORTED/BLOCKED) čistě z deklarativních `TargetProjectionRule` v target registry — bez byznys logiky typu "shell.run je zakázán natvrdo" (to je jedna z mála hard-coded výjimek, viz `TargetMaterializationResolverEngine.kt:167-177`).
   - `TargetNativeProjectionCatalog.kt` (534 řádků) — typový kontrakt "provider vlastní a dokazuje" pro akce, approvaly a strukturální konstrukty (condition/parallel/loop/match/retry/error-boundary). Renderer nikdy nesestavuje payload sám — jen konzumuje již ověřený `TargetRendererPayload`.
   - `TargetRenderPolicy.kt` — realizuje trojstavový model **ready/degraded/blocked** jako `EXECUTABLE` / `REVIEW_ONLY` / `FAIL_FAST`.
   - `TargetCompatibilityReadinessAnalyzer.kt` — slučuje capability-level compatibility s konkrétním materialization/projection evidence do `effectiveStatus` (monotónní — nikdy nesmí "vylepšit" blokující nález).
   - `TargetReviewArtifactRenderer.kt` — cílově-neutrální YAML "review" výstup pro `REVIEW_ONLY` stav (žádný vendor syntax).

2. **`targets/`** — registry a honesty vrstva: `TargetRegistry.kt` (YAML loader s fail-closed validací), `TargetRegistryHonesty.kt` (vynucuje, že `PRODUCTION_SUPPORTED`/`TESTED` apod. musí mít odpovídající evidenci — nelze "tvrdit" úroveň podpory bez důkazu), `StandardTargetRegistryHonestySnapshots.kt` (baseline: jenkins/github-actions/tekton = TESTED, shell = BLOCKED).

3. **`targets/builtin/`** — konkrétní implementace pro 3 cíle (Jenkins, GitHub Actions, Tekton): generator (`*ManifestGenerator`), renderer (`*ManifestRenderer`), native katalog (`*NativeProjectionCatalog`), expression translator (`*TargetExpressionTranslator`) a sdílené hodnotové validátory (checkout, image-build).

Architektonický princip "renderer neplánuje" je v kódu **skutečně vynucen typovým systémem a runtime gaty** (`TargetRendererContractValidator`, `TargetRenderPolicy.requireSafe`), ne jen deklarován v komentářích — to je pozitivní zjištění popsané v sekci 4.

---

## 2. Inventář souborů a tříd

### 2.1 `generators/` (top-level, 1 soubor)

**`JenkinsGroovyExpr.kt`** (111 ř.)
- `class GroovyExpr(private val inputs: Set<String>)` — překládá Flow `ExpressionNode` do Groovy výrazu pro Jenkins. Veřejná fn: `render(e: ExpressionNode): String`. Privátní pomocníci: `renderRef`, `renderBinary`, `patternFor`, `slashRegex`, `builtinPattern`, `renderTemplate`, `sq` (single-quote escape), `gstr` (GString literal escape — escapuje i `$`).
- **Pozn.:** `sq()`/`gstr()` escapují `$` korektně, ale `slashRegex()` použitá pro `matches` operátor **ne** — viz Bug B1 v sekci 3.

### 2.2 `generators/manifest/` (20 souborů)

| Soubor | Řádky | Top-level typy | Netriviální veřejné fn |
|---|---|---|---|
| `AdapterManifestLowering.kt` | 16 | `object AdapterManifestLowering` | `input`, `trigger`, `metadata`, `mappingNotes`, `emptyStep`, `id` — tenká fasáda nad `TargetManifestLowering` internals pro adaptery |
| `AdapterWorkflowProjectionLowering.kt` | 162 | `object AdapterWorkflowProjectionLowering` | `nodePreservingSteps` (Jenkins styl — zachová vnořenou strukturu vč. `WorkflowFailurePolicy` error-handleru), `jobPerTask` (GitHub/Tekton styl — jeden job na task/approval, s `errorHandler`/`condition` metadaty) |
| `ExecutionPlanDerivedProjectionValidator.kt` | 76 | `internal object ExecutionPlanDerivedProjectionValidator` | `validate(plan)` — hlídá, že legacy `dependencies`/`effects` pole jsou *přesně* odvozená z `dependsOn`/`effectModel` (žádná nezávislá pravda) |
| `ExecutionPlanTopologyValidator.kt` | 152 | `class UnresolvedExecutionTopologyException`, `internal object ExecutionPlanTopologyValidator` | `validate`, `assess`, `requireMatched`, `blockers` — validuje kanonickou i odvozenou topologii plánu vůči `TargetCapability.topologyProfile` |
| `TargetCapabilityDegradationAnalyzer.kt` | 198 | `object TargetCapabilityDegradationAnalyzer`, `enum TargetCapabilityDegradationStatus`, `data class …Report/…Entry` | `analyze`, `requireAcceptable` — mapuje `TargetMaterializationStatus`/`TargetMappingNote` level na SUPPORTED/DEGRADED/BLOCKED s explicitním "preserved/approximated/blocked" textem (audit trail) |
| `TargetCompatibilityReadinessAnalyzer.kt` | 397 | `internal object TargetReadinessDiagnosticCodeAuthority`, `object TargetCompatibilityReadinessAnalyzer` | `analyze`, `reconcile` (3 přetížení pro `ExecutionReadinessReport`/`TargetCapabilityNegotiationReport`/`TargetSelectionReport`) — jednosměrné, monotónní sjednocení capability a konkrétní evidence |
| `TargetEnvironmentSafetyEvidence.kt` | 113 | `data class TargetApprovalEnvironmentEvidence`, `class TargetEnvironmentSafetyEvidenceResolver` | `resolve(manifest, approvalJob)` — odvozuje sensitivitu prostředí z downstream akcí approval jobu (nikdy nevymýšlí environment, jen pozoruje) |
| `TargetManifest.kt` | 110 | `TargetManifest`, `TargetMappingNote`, `TargetTrigger`, `TargetInput`, `TargetJob`, `TargetRendererPayload`, `TargetStep`, `TargetMaterialization` (8 data-tříd) | `TargetMaterialization.{native,adapterRequired,semanticOnly,blocked}` — základní datový kontrakt celé vrstvy |
| `TargetManifestBindingValidation.kt` | 45 | `internal data class TargetBindingValidationIssue/TargetUnresolvedBinding`, `internal object TargetManifestBindingValidation` | `issues(payload)`, `unresolved(payload)` |
| `TargetManifestCompatibilityReconciliation.kt` | 45 | — (extension) | `TargetManifest.reconcileCompatibilityReadiness()` — aplikuje readiness evidenci na `compatibility.status` |
| `TargetManifestContractValidator.kt` | 372 | `object TargetManifestContractValidator`, `data class …Report/…Issue` | `validate(manifest)` — strukturální kontrakt (id patterny, duplicity, `SUPPORTED_WITHOUT_EXECUTABLE_*` hlídky) |
| `TargetManifestLowering.kt` | 362 | `enum TargetMaterializationStatus` + ~12 top-level (module-internal) fn | `toTargetSteps`, `toTargetStep`, `sanitizeId`, `unquote`, `normalizeTargetParam`, `combineConditions`, `mapOfNotNull`, `emptyProjectionStep` — jádro AST→TargetStep lowering |
| `TargetMaterializationResolver.kt` | 76 | `internal data class TargetMaterializationResolution`, `internal object TargetMaterializationEvidenceAuthority` | `mappingNote`, `requireValid` — validuje, že graph/negotiation/projectionPlan/artifact jsou vzájemně konzistentní evidence chain |
| `TargetMaterializationResolverEngine.kt` | 341 | `internal object TargetMaterializationResolver` | `resolve(authorization, task, targetName, rules, catalog)` — hlavní per-task rozhodovací logika (MATERIALIZABLE/ADAPTER_REQUIRED/UNSUPPORTED/BLOCKED); **jediné hard-coded pravidlo**: `shell.run` je vždy BLOCKED (řádek 167-177) |
| `TargetNativeProjectionCatalog.kt` | 534 | 9 typů (`TargetNativeProjectionDefinition`, `TargetNativeApprovalProjectionDefinition`, `TargetStructuralProjectionKind` enum, `TargetNativeProjectionCatalog` třída atd.) | `compile`, `compileApproval`, `resolveStructure`, `requirePayload`, `requireManifest`, `requireCompatibleRules` — "provider vlastní a dokazuje" kontrakt; jediné místo, kde smí vzniknout `TargetRendererPayload` |
| `TargetProjectionProvider.kt` | 225 | `TargetManifestGenerator` (interface), `ReconciledTargetManifestGenerator` (abstract), `TargetManifestRenderer` (interface), `TargetProjectionProvider`, `TargetProjectionRegistry`, `TargetManifestGenerationPipeline` (8 typů) | `generate`, `render`, `effectiveTarget(s)` — veřejný vstupní bod pipeline (Intent→…→Manifest) |
| `TargetRenderPolicy.kt` | 215 | `enum TargetRenderMode`, `TargetRenderReadiness`, `TargetRenderBlockedException`, `object TargetRenderPolicy` | `evaluate`, `requireSafe`, `requireExecutable` — realizace ready/degraded/blocked jako EXECUTABLE/REVIEW_ONLY/FAIL_FAST |
| `TargetRendererContractValidator.kt` | 119 | `object TargetRendererContractValidator`, report/issue data-třídy | `validate`, `requireRenderable` — hlídá, že renderer nedostane manifest pro jiný target, nekonzistentní `dependsOn`, prázdné parallel/try větve |
| `TargetReviewArtifactRenderer.kt` | 188 | `object TargetReviewArtifactRenderer` | `render(manifest, readiness)` — cílově-neutrální `TargetProjectionReview` YAML pro REVIEW_ONLY případ |
| `MandatoryMaterializationAuthority.kt` | 1096 | `TargetProjectionAuthorization`, `MandatoryMaterializationAuthority`, `ExecutionPlanMaterializationValidator` (internal), `ExecutionPlanContinuityValidator`, `ExecutionPlanControlValidator` + 3 exception třídy (10 typů) | `authorize`, `authorizeDiagnosticEvidence` — obří validátor: efekty, kontinuita, control evidence, topologie, intent-lowering evidence (SHA-256 digest kontroly), rederivace `ControlRequirement`/`ControlDecision` |

### 2.3 `targets/` (top-level, 5 souborů)

| Soubor | Řádky | Typy | Veřejné fn |
|---|---|---|---|
| `StandardTargetRegistryHonestySnapshots.kt` | 54 | `object` | `baseline()` — hard-coded baseline (jenkins/github-actions/tekton=TESTED, shell=BLOCKED) |
| `TargetRegistry.kt` | 133 | `object TargetRegistryYamlLoader` | `load(file)`, `loadDirectory(dir)` — fail-closed YAML loader; vyžaduje topology evidenci, expression profil, validuje capability/support-level vokabulář |
| `TargetRegistryContractVocabulary.kt` | 46 | `object` | jen `val` mapy (capabilityNames, supportLevels, topologySupportLevels, projectionModes) — uzavřený wire vokabulář |
| `TargetRegistryHonesty.kt` | 240 | 9 typů (`TargetRegistryStatus` enum, `TargetRegistryEvidenceKind` enum, entry/snapshot/report data-třídy, `TargetRegistryHonestyValidator`) | `validate(snapshot)` — vynucuje, že úroveň podpory (`PRODUCTION_SUPPORTED` apod.) má odpovídající evidenci; explicitně zakazuje frázi typu "fallback shell"/"assume supported" v textu (`FORBIDDEN_READY_CLAIMS`) |
| `TargetRegistryModels.kt` | 189 | 6 data-tříd (`TargetRegistryDocument`, `TargetExpressionProfileDescriptor`, `TargetProjectionPayloadDescriptor`, `TargetProjectionRuleDescriptor`, `TargetTopologyProfileDescriptor`, `TargetDescriptor`) | `toDeclaration`, `toTemplate`, `toRule`, `toProfile`, `toCapability` — YAML descriptor → doménový model mapping |

### 2.4 `targets/builtin/` (30 souborů) — s capabilities/omezeními

| Soubor | Řádky | Deklarované capabilities / omezení |
|---|---|---|
| `BuiltInNativeProjectionCatalogs.kt` | 13 | `@Deprecated` fasáda agregující 3 katalogy (jenkins/githubActions/tekton); žádná vlastní logika |
| `BuiltInTargetProjections.kt` | 24 | `@Deprecated` fasáda nad `ReferenceTargetProjections`; žádná vlastní logika |
| `CheckoutProjectionRenderingSupport.kt` | 38 | Sdílené checkout validátory (`gitUrl`, `branch`, `depth`, `workspace`, `text`). **`gitUrl` kontroluje jen non-blank** — žádná validace schématu/hostitele (na rozdíl od GH-specifické verze) |
| `GitHubActionsCheckoutProjectionValues.kt` | 45 | GH-specifická validace: povoluje jen `github.com` (https/http/ssh/git schéma nebo `git@github.com:` SCP), přesně 1 owner/1 repo segment, whitelist znaků repa. Nejpřísnější checkout validace ze 3 cílů |
| `GitHubActionsImageBuildProjectionValues.kt` | 16 | `githubContext`/`githubDockerfile` — mapuje obecný build-context/dockerfile na `docker/build-push-action` syntax (`{{defaultContext}}:path`) |
| `GitHubActionsManifestGenerator.kt` | 50 | target="github-actions"; **job-per-task** model; `workspaceContinuityMechanism=workflow-artifact-transfer` (cross-job workspace řeší přes GH artifact upload/download, ne shared filesystem) |
| `GitHubActionsManifestRenderer.kt` | 227 | Vykresluje `on:`, `jobs:`, `needs:`, `if:`, `environment:` (pro approval s provider payloadem), `env:` (opaque secrets); native payloady: checkout, docker build-push, workspace upload/download; ostatní → `renderGenericBindings` |
| `GitHubActionsNativeProjectionCatalog.kt` | 51 | Native: `actions/checkout@v4`, `docker/build-push-action@v7`, upload/download-artifact. **Žádná native approval ani strukturální (condition/loop/parallel/retry/error-boundary) definice** — ty jdou přes `GitHubJobConditionAuthority`/job-graph, ne přes katalog |
| `GitHubActionsProjectionInspection.kt` | 13 | Read-only introspekce (`jobCondition`, `triggerDocument`) pro conformance testy — explicitně "neautorizuje generování" |
| `GitHubActionsProjectionRenderingSupport.kt` | 41 | `ProjectionBindingKind.ARTIFACT` → `error()` (obecné artifact bindingy nejsou podporované mimo workspace planner) |
| `GitHubActionsTargetExpressionTranslator.kt` | 102 | Podporuje `==,!=,>,>=,<,<=,contains,in,startsWith,endsWith,and,or,not,exists,empty`, JSON pole; **`matches` (regex) je explicitně BLOCKED** (`unsupported(...)`) — fail-closed, žádná aproximace |
| `GitHubActionsTriggerProjectionPlanner.kt` | 109 | MANUAL (workflow_dispatch), SCHEDULE (jen CRON), EVENT jen `push/pull_request/release` (`SUPPORTED_EVENTS`); jiný typ → `error()` |
| `GitHubActionsWorkspaceContinuityPlanner.kt` | 256 | Bounded workspace continuity (1 scoped-support deklarace `githubActionsCheckoutBuildWorkspace`); nenalezená shoda → explicitní `adapter-continuity-blocked` diagnostický step (ne ticho) |
| `GitHubJobConditionAuthority.kt` | 45 | Skládá `!cancelled()` + vlastní podmínku + `needs.X.result==success` (nebo `success||skipped` pro approval-backed závislost); **error handler** dostává `!cancelled() && failure()` |
| `ImageBuildProjectionRenderingSupport.kt` | 93 | Sdílené: `image` (whitespace/control char guard), `buildContext`/`dockerfile` (default `.`/`Dockerfile`), `push` (striktní boolean), `requireLiteralWorkspacePath` (blokuje `..`, absolutní cesty, Windows cesty, mezery, control chars) — silná, sdílená validace pro všechny 3 cíle |
| `JenkinsImageBuildProjectionValues.kt` | 16 | Volí `groovyString` vs. interpolovaný `"${params.X}"` string podle přítomnosti `${params.` v hodnotě; sanitizuje Groovy proměnnou |
| `JenkinsManifestGenerator.kt` | 38 | target="jenkins"; **jeden job na celý flow** (node-preserving, ne job-per-task); `nodePreserving=true` |
| `JenkinsManifestRenderer.kt` | 292 | Declarative pipeline; parametry/triggery(cron)/environment(credentials)/stages; native: condition(if), error-boundary(try/catch s `WorkflowFailurePolicy`), approval (**jen `mode=manual`**, jiný mód `require()`-fail), leaf: git checkout, docker-build. **loop/match/retry/parallel nemají native strukturální definici v katalogu** → v praxi nikdy nedosáhnou EXECUTABLE (viz Bug B4) |
| `JenkinsNativeProjectionCatalog.kt` | 68 | Native: `git`, `docker-build`; approval: `approval.manual`/input; strukturální: jen CONDITION a ERROR_BOUNDARY. Žádné PARALLEL/LOOP/MATCH/RETRY |
| `JenkinsProjectionRenderingSupport.kt` | 45 | `jenkinsArgument`: `TASK_OUTPUT` a `ARTIFACT` binding kind → `error()` (Jenkins nepodporuje cross-task output/artifact binding obecně, na rozdíl od GH Actions `needs.X.outputs.Y`) |
| `JenkinsTargetExpressionTranslator.kt` | 26 | Deleguje na `GroovyExpr`; `catch(e: Exception)` → `TargetExpressionTranslationException` (fail-closed, ale široký catch) |
| `TargetExpressionTranslationException.kt` | 5 | Sdílená výjimka pro všechny 3 translátory (žádná duplicita) |
| `TargetExpressionTranslator.kt` | 17 | `@Deprecated` fasáda delegující na 3 konkrétní translátory |
| `TargetProjectionRenderingSupport.kt` | 81 | `ProjectionValueSyntax` interface + sdílené `yamlScalar`/`safeEnvName`/`safeIdentifier` (používá GH Actions i Tekton, ne Jenkins) |
| `TektonImageBuildProjectionValues.kt` | 6 | Jen `tektonDockerfile` default-path helper |
| `TektonManifestGenerator.kt` | 63 | target="tekton"; **job-per-task**; metadata **vždy** `supportLevel=partial` (jediný generátor, co se takto trvale označuje za částečný); generuje explicitní `error`-level mapping note, pokud podmínku nelze přeložit na `when:` guard (fail-closed) |
| `TektonManifestRenderer.kt` | 195 | Native tasky: `git-clone`, `buildah`, jinak `renderGenericTask` passthrough. **Žádná native approval, žádná native strukturální definice** — condition jde přes `when:` mimo katalog. `require(materializedSteps.size==1)` (řádek 179-181) — tvrdý požadavek na přesně 1 leaf step na job |
| `TektonNativeProjectionCatalog.kt` | 36 | Native: jen `git-clone`, `buildah`. Nejomezenější katalog ze 3 cílů — žádné secrets, žádné approval, žádné strukturální konstrukty |
| `TektonProjectionRenderingSupport.kt` | 37 | `tektonValue`: `SECRET` i `ARTIFACT` binding kind → `error()` ("vyžaduje explicit workspace or secretKeyRef evidence") — Tekton nemá vůbec nativní secret/artifact binding |
| `TektonTargetExpressionTranslator.kt` | 66 | Podporuje jen `==,!=,in` (BinaryExpressionNode) a `and` (LogicalExpressionNode) → `when:` guard; vše ostatní `null` (blocked). **Bug**: `in` s `ListLiteralNode` tiše zahazuje nepřeložitelné položky (`mapNotNull`, řádek 33) |

---

## 3. Zjištěné chyby a nedostatky

### B1 — [KRITICKÁ] Groovy code injection přes neescapovaný `$` v `matches` regex vzoru (Jenkins)
**Soubor:** `src/main/kotlin/org/flowlang/generators/JenkinsGroovyExpr.kt:61` a `:76`

```kotlin
"matches" -> "($l ==~ /${patternFor(e.right)}/)"   // řádek 61
...
private fun slashRegex(value: String): String = value.replace("/", "\\/")   // řádek 76
```

`patternFor` pro `StringLiteralNode` (uživatelem/AI zadaný regex vzor ve Flow `matches` výrazu) vrací `slashRegex(node.value)`, která escapuje **jen `/`**. Groovy "slashy string" (`/…/`) je ale **GString** — podporuje interpolaci `$jméno` a `${výraz}` stejně jako `"…"`. Metoda `gstr()` (řádek 109-110) ve stejném souboru correctně escapuje `$` pro dvojité uvozovky — `slashRegex` na to zapomíná.

**Scénář selhání:** Flow podmínka `x matches "${@@some.groovy.Expr}"` (nebo cokoliv obsahující `${...}`/`$identifier`) se přeloží do Jenkinsfile jako `(x ==~ /${@@some.groovy.Expr}/)`. Jenkins vykoná Jenkinsfile jako Groovy skript — GString se vyhodnotí za běhu pipeline → **libovolný Groovy kód se spustí** při vyhodnocení `if` podmínky. Jde přesně o injection cestu, kterou má zadání explicitně hledat.

**Návrh opravy:** V `slashRegex` escapovat i `$` (`.replace("$", "\\$")` po escapu `/`), nebo se vyhnout slashy-string interpolaci úplně (např. `Pattern.compile(${groovyString(...)})` s korektně quotovaným literálem místo `/…/`).

### B2 — [VYSOKÁ] `groovyEscape` neescapuje zpětné lomítko — nekonzistentní s `groovyString`
**Soubor:** `src/main/kotlin/org/flowlang/targets/builtin/JenkinsProjectionRenderingSupport.kt:43-45`

```kotlin
internal fun groovyString(value: String): String = "'" + value.replace("\\", "\\\\")
    .replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r") + "'"
internal fun groovyEscape(value: String): String = value.replace("'", "\\'")
```

`groovyEscape` escapuje pouze `'`, nikoliv `\`. Použití: `JenkinsManifestRenderer.kt:49-51` — `booleanParam(name: '${groovyEscape(input.name)}', ...)`, `choice(...)`, `string(...)`. `input.name` je `TargetInput.name` (odvozeno z `PlanInput.name`, `src/main/kotlin/org/flowlang/planner/ExecutionPlan.kt:101`), což je **plain `String` bez jakékoliv znakové validace** kdekoliv v prověřené vrstvě.

**Scénář selhání:** Flow input pojmenovaný tak, aby končil zpětným lomítkem těsně před uzavírací uvozovkou, "escapuje" uzavírací `'` a vylomí se z Groovy single-quote literálu — následující vygenerovaný zdrojový text se stane součástí stringu (nebo naopak string "spolkne" další kód), což při vhodné kombinaci umožňuje injektáž do generovaného Jenkinsfile.

Pozn.: volání `bindingValue(name)`/`mappingSpec(name)` (tentýž soubor, řádky 15,18) jsou v praxi bezpečná, protože `name` tam pochází z regexu `opaqueRefRegex = Regex("secret:([A-Za-z0-9_.-]+)")` (whitelist znaků) — problém je specificky u `input.name`.

**Návrh opravy:** Sjednotit — `groovyEscape` by mělo escapovat `\` před `'` stejně jako `groovyString`, nebo tuto funkci úplně zrušit a všude použít `groovyString` (bez obalujících uvozovek v šabloně).

### B3 — [STŘEDNÍ] Tekton `in` translator tiše zahazuje nepřeložitelné položky seznamu
**Soubor:** `src/main/kotlin/org/flowlang/targets/builtin/TektonTargetExpressionTranslator.kt:30-38`

```kotlin
"in" -> {
    val rightExpression = e.right
    val values = if (rightExpression is ListLiteralNode) {
        rightExpression.items.mapNotNull { tektonValue(it, inputs) }   // řádek 33
    } else { listOf(right) }
    "when:\n  - input: ${yamlScalar(left)}\n    operator: in\n    values:\n" +
        values.joinToString("") { "      - ${yamlScalar(it)}\n" }
}
```

Pokud jedna položka seznamu (`e.g. status in [ok, computed_expr, "done"]`) nejde přeložit (`tektonValue` vrátí `null`), `mapNotNull` ji tiše vynechá ze `values:` — vzniklý `when:` guard bude mít **jiný (užší) match set**, než Flow definoval, **bez jakéhokoliv varování**. Tohle je přesně "silent fallback místo explicitního blocked" — porušuje vlastní README pravidlo *"Do not hide unsupported target behavior behind silent fallback"*.

**Návrh opravy:** Pokud kterákoliv položka selže, celá funkce má vrátit `null` (stejně jako ostatní nepodporované tvary), aby volající (`TektonManifestGenerator`/`TektonManifestRenderer`) korektně přešli do REVIEW_ONLY/error mapping note.

### B4 — [STŘEDNÍ] Nezajištěná vazba mezi native katalogem a renderer switchem pro nepodporované strukturální konstrukty (Jenkins i Tekton)
**Soubory:** `JenkinsManifestRenderer.kt:106-108`, `TektonManifestRenderer.kt:179-181`

Jenkins renderer pro `"try-body", "error-handler", "parallel", "parallel-branch", "loop", "match", "retry"` prostě rekurzivně vykreslí děti (flatten na sekvenční skript) — ztrácí sémantiku loop/match/retry/parallel, pokud by kdy tyto typy dosáhly `EXECUTABLE` stavu. Tekton má analogicky `require(materializedSteps.size == 1)` — tvrdý předpoklad 1 leaf stepu na job.

Aktuálně je to **nedosažitelné** (dead-path ochrana), protože `JenkinsNativeProjectionCatalog`/`TektonNativeProjectionCatalog` nedeklarují `TargetNativeStructuralProjectionDefinition` pro PARALLEL/LOOP/MATCH/RETRY (u Tekton ani pro CONDITION/ERROR_BOUNDARY) — `TargetRenderPolicy` proto takový manifest nikdy nepustí do `EXECUTABLE`, jen do `REVIEW_ONLY`. Nic v typovém systému ale nevynucuje, že katalog (`*NativeProjectionCatalog.kt`) a renderer switch (`renderJenkinsStep`/`renderTektonTask`) zůstanou synchronizované — přidání nové strukturální definice do katalogu bez odpovídající úpravy rendereru by tiše začalo generovat sémanticky špatný výstup (Jenkins) nebo neelegantní `IllegalArgumentException` místo strukturovaného `TargetRenderBlockedException` (Tekton).

**Návrh opravy:** Exhaustivní `when` bez implicitní "spadni do default" větve pro tyto typy, případně test, který ověří `catalog.structuralDefinitions.map{it.structure}` ⊆ množina typů explicitně zpracovaných v příslušném rendereru.

### B5 — [STŘEDNÍ] Tichý fallback při nevalidních readiness metadatech
**Soubor:** `src/main/kotlin/org/flowlang/generators/manifest/TargetCompatibilityReadinessAnalyzer.kt:79-81`

```kotlin
val capabilityStatus = manifest.metadata["capabilityCompatibility"]
    ?.let { value -> runCatching { SupportLevel.valueOf(value) }.getOrNull() }
    ?: manifest.compatibility.capabilityStatus
```

Pokud je klíč `capabilityCompatibility` v metadatech přítomný, ale nevalidní (překlep, přejmenovaný enum), chyba se tiše spolkne a analyzátor potichu nahradí hodnotu jiným zdrojem pravdy (`manifest.compatibility.capabilityStatus`) — místo aby to bylo nahlášeno jako porušení kontraktu, jak to dělá sesterský `TargetManifestContractValidator` pro jiná metadata pole (`MANIFEST_STANDARD_VERSION_MISMATCH` apod.).

**Návrh opravy:** Pokud je klíč přítomen, musí se úspěšně naparsovat; jinak vyhodit chybu/kontraktní issue místo tichého fallbacku.

### B6 — [STŘEDNÍ/NÍZKÁ] Neúplné escapování řídicích znaků v generovaném YAML
**Soubory:** `src/main/kotlin/org/flowlang/targets/builtin/TargetProjectionRenderingSupport.kt:79-80` (`yamlScalar`), `src/main/kotlin/org/flowlang/generators/manifest/TargetReviewArtifactRenderer.kt:182-185` (`quoted`)

```kotlin
fun yamlScalar(value: String): String = "\"" + value.replace("\\", "\\\\")
    .replace("\"", "\\\"").replace("\n", "\\n") + "\""
```

Escapuje jen `\`, `"`, `\n`. Literální `\r`, tabulátor nebo jiný C0 control znak ve Flow stringu (branch name, message, image tag…) projde do YAML double-quoted scalar neescapovaný — což je podle YAML specifikace nevalidní a striktnější parsery (GitHub Actions/Tekton control plane) na to mohou spadnout až při nasazení, ne při generování manifestu. Stejná mezera existuje na dvou nezávislých místech (GH Actions/Tekton sdílené `yamlScalar` a `TargetReviewArtifactRenderer.quoted`).

**Návrh opravy:** Escapovat celý C0 rozsah (`\t`, `\r`, ostatní `\x00-\x1F`) konzistentně s tím, jak `gstr()`/`groovyString()` už řeší `\r`/`\n` pro Groovy stranu.

### B7 — [NÍZKÁ] Duplicitní/nekonzistentní zpracování "prázdného flow" napříč 3 generátory
**Soubory:** `GitHubActionsManifestGenerator.kt:27-33`, `TektonManifestGenerator.kt:28-34`, `JenkinsManifestRenderer.kt:85-86`

GitHub Actions a Tekton generátor mají **doslovně duplikovaný** blok `jobs.ifEmpty { listOf(TargetJob(id=…, steps=listOf(AdapterManifestLowering.emptyStep(...)))) }` — není sdílený přes `AdapterManifestLowering`/`AdapterWorkflowProjectionLowering`, ačkoliv by mohl být. Jenkins řeší stejný případ (job bez stepů) **třetím, jiným způsobem** — vygeneruje `error("Flow job '${job.id}' has no materialized target steps.")`, tedy runtime chybu v Groovy skriptu místo no-op stepu. Tři různé reprezentace stejné sémantické situace ztěžují jednotné uvažování a zvyšují riziko divergence.

**Návrh opravy:** Vytáhnout `jobs.ifEmpty {...}` do sdíleného helperu v `AdapterManifestLowering`, a sjednotit chování napříč cíli (nebo explicitně zdokumentovat, proč se Jenkins liší).

### B8 — [NÍZKÁ] Široký `catch (e: Exception)` ve všech 3 expression translátorech
**Soubory:** `JenkinsTargetExpressionTranslator.kt:21-24`, `GitHubActionsTargetExpressionTranslator.kt:21-24`, `TektonTargetExpressionTranslator.kt:15-20`

Fail-closed záměr je legitimní, ale širokým `catch(e: Exception)` (Tekton dokonce `catch (_: Exception)` — řádek 19, zahazuje i příčinu) se maskují i skutečné programátorské chyby (NPE ap.) jako běžná "nepodporovaná podmínka". Tekton variantu navíc nelze vůbec diagnostikovat — volající (`TektonManifestGenerator`) uvidí jen `null`, nikdy důvod.

**Návrh opravy:** Alespoň zachovat `cause`/message i v Tekton větvi (jako mají Jenkins/GitHub); zvážit užší except-list (`ParseException`, `TargetExpressionTranslationException`) místo obecného `Exception`.

### B9 — [NÍZKÁ] Nekonzistentní přísnost validace checkout URL napříč cíli
**Soubory:** `CheckoutProjectionRenderingSupport.kt:9-12` vs. `GitHubActionsCheckoutProjectionValues.kt`

`CheckoutProjectionValues.gitUrl` (sdílené pro Jenkins `git` step a Tekton `git-clone` task) kontroluje jen `isNotBlank()` — žádné schéma/host. GitHub Actions má vlastní přísnou validaci (jen `github.com`, owner/repo tvar). Protože hodnota vždy prochází korektním escapováním (`groovyString`/`yamlScalar`), nejde o injection, ale o reálnou nekonzistenci v "rychlosti selhání" — Jenkins/Tekton odhalí špatnou URL až při běhu vendor CI, GitHub Actions už při generování manifestu.

### Souhrn dle závažnosti
| Závažnost | Počet | ID |
|---|---|---|
| Kritická | 1 | B1 |
| Vysoká | 1 | B2 |
| Střední | 3 | B3, B4, B5 |
| Nízká | 4 | B6, B7, B8, B9 |

Ostatní pozorování: **žádné** `TODO`/`FIXME`/`HACK` komentáře, **žádné** force-unwrapy (`!!`) v celé vrstvě (56 souborů) — kód důsledně používá `require`/`requireNotNull`/explicitní `error()` s popisnou zprávou namísto implicitních NPE, což je nad průměrem typické Kotlin codebase.

---

## 4. Architektonická pozorování

**Princip "renderery neplánují" je reálně vynucen, ne jen deklarován.** Všechny tři renderery (`JenkinsManifestRenderer`, `GitHubActionsManifestRenderer`, `TektonManifestRenderer`) volají na začátku `render()` `TargetRendererContractValidator.requireRenderable` + `TargetRenderPolicy.requireSafe`, a při čemkoliv menším než plně `NATIVE`/executable stavu se přepnou na cílově-neutrální `TargetReviewArtifactRenderer` namísto pokusu o "chytré" doplnění chybějící sémantiky. Renderer navíc fyzicky nemůže sestavit `TargetRendererPayload` sám — jediné místo, kde payload vzniká, je `TargetNativeProjectionCatalog.compile`/`compileApproval`/`resolveStructure`, což je odděleno od renderer kódu a validováno (`requirePayload`) proti deklarovanému kontraktu bindings. Toto je nadstandardně rigorózní pro generátor CI/CD manifestů.

**Evidence chain je hluboká a end-to-end validovaná.** `MandatoryMaterializationAuthority` → `TargetMaterializationResolverEngine` (obligation graph → negotiation → projection plan → artifact → materialization) → `TargetMaterializationEvidenceAuthority.requireValid` tvoří řetězec, kde každý krok cituje evidenci předchozího a je nezávisle re-validovatelný. To přímo brání renderer/generator vrstvě "vymýšlet sémantiku" — nejde vytvořit `TargetMaterialization.native(...)` bez skutečné evidence z katalogu.

**Model ready/degraded/blocked je konzistentně namapován** na `TargetRenderMode` (EXECUTABLE/REVIEW_ONLY/FAIL_FAST) a `SupportLevel` (SUPPORTED/PARTIAL/REQUIRES_RUNTIME/UNSUPPORTED), s monotónním "stricter wins" slučováním (`TargetCompatibilityReadinessAnalyzer.stricterSupport`) — nelze, aby konkrétní evidence *zlepšila* už zjištěnou blokaci. Výjimky z "no silent fallback" pravidla jsou reálně jen B3 a B5 výše — poměrně malý počet vzhledem k rozsahu.

**Cross-target konzistence je udržována konvencí a sdílenými pomocníky, ne typovým systémem.** `AdapterManifestLowering`, `AdapterWorkflowProjectionLowering`, `CheckoutProjectionValues`, `ImageBuildProjectionValues`, `TargetProjectionRenderingSupport` dobře snižují duplicitu (sdílené workspace-path/image/checkout validátory). Slabina: nic nekontroluje, že renderer-switch pro strukturální kroky zůstává v souladu s tím, co katalog deklaruje (B4), a každý cíl si escapování "vendor stringu" implementuje odděleně s mírně odlišnou úplností (Groovy `sq`/`gstr`/`groovyString`/`groovyEscape` vs. sdílené YAML `yamlScalar`) — právě odtud pramení B1/B2/B6. Jedna sdílená, jednotkově otestovaná "safe scalar" abstrakce na cílovou syntaxi (Groovy string, YAML scalar) by tuto celou třídu nálezů řešila najednou.

**Registry honesty vrstva (`targets/`) je neobvykle striktní governance mechanismus** — `TargetRegistryStatus` (DECLARED_ONLY → EXPERIMENTAL → IMPLEMENTED → TESTED → PRODUCTION_SUPPORTED) vyžaduje odpovídající typ evidence (`IMPLEMENTATION`/`PROJECTION_PLAN`/`TEST`/`CONFORMANCE`) a explicitně zakazuje frázová tvrzení typu "fallback shell"/"assume supported" (`FORBIDDEN_READY_CLAIMS`). To je systémové vynucení "capability tvrzení vyžaduje důkaz", nejen v dokumentaci, ale ve validátoru.

**Rozšiřitelnost:** přidání nového cíle vyžaduje `TargetManifestGenerator` + `TargetManifestRenderer` + `TargetNativeProjectionCatalog` + (volitelně) expression translator — jasně definovaný, opakovatelný vzor demonstrovaný 3× stejně. Cena za rigoróznost je vysoká hustota boilerplate validace (`MandatoryMaterializationAuthority.kt` samo o sobě 1096 řádků) — udržovatelnost při budoucích změnách kontraktu (např. nové pole v `ExecutionPlan`) bude vyžadovat současné úpravy na mnoha místech validace.

---

## 5. Statistická tabulka

| Soubor | Řádky | Počet tříd/objektů/interfaces | Počet veřejných fn (netriviálních) |
|---|---:|---:|---:|
| generators/JenkinsGroovyExpr.kt | 111 | 1 | 1 |
| generators/manifest/AdapterManifestLowering.kt | 16 | 1 | 6 |
| generators/manifest/AdapterWorkflowProjectionLowering.kt | 162 | 1 | 2 |
| generators/manifest/ExecutionPlanDerivedProjectionValidator.kt | 76 | 1 | 1 |
| generators/manifest/ExecutionPlanTopologyValidator.kt | 152 | 2 | 4 |
| generators/manifest/TargetCapabilityDegradationAnalyzer.kt | 198 | 4 | 2 |
| generators/manifest/TargetCompatibilityReadinessAnalyzer.kt | 397 | 2 | 5 |
| generators/manifest/TargetEnvironmentSafetyEvidence.kt | 113 | 2 | 1 |
| generators/manifest/TargetManifest.kt | 110 | 8 | 4 |
| generators/manifest/TargetManifestBindingValidation.kt | 45 | 3 | 2 |
| generators/manifest/TargetManifestCompatibilityReconciliation.kt | 45 | 0 | 1 |
| generators/manifest/TargetManifestContractValidator.kt | 372 | 3 | 1 |
| generators/manifest/TargetManifestLowering.kt | 362 | 1 | 12 |
| generators/manifest/TargetMaterializationResolver.kt | 76 | 2 | 2 |
| generators/manifest/TargetMaterializationResolverEngine.kt | 341 | 1 | 1 |
| generators/manifest/TargetNativeProjectionCatalog.kt | 534 | 9 | 11 |
| generators/manifest/TargetProjectionProvider.kt | 225 | 8 | 15 |
| generators/manifest/TargetRenderPolicy.kt | 215 | 5 | 3 |
| generators/manifest/TargetRendererContractValidator.kt | 119 | 3 | 2 |
| generators/manifest/TargetReviewArtifactRenderer.kt | 188 | 1 | 1 |
| generators/manifest/MandatoryMaterializationAuthority.kt | 1096 | 10 | 8 |
| targets/StandardTargetRegistryHonestySnapshots.kt | 54 | 1 | 1 |
| targets/TargetRegistry.kt | 133 | 1 | 2 |
| targets/TargetRegistryContractVocabulary.kt | 46 | 1 | 0 (3 veřejné vlastnosti) |
| targets/TargetRegistryHonesty.kt | 240 | 9 | 3 |
| targets/TargetRegistryModels.kt | 189 | 6 | 5 |
| targets/builtin/BuiltInNativeProjectionCatalogs.kt | 13 | 1 | 0 (4 property) |
| targets/builtin/BuiltInTargetProjections.kt | 24 | 1 | 1 |
| targets/builtin/CheckoutProjectionRenderingSupport.kt | 38 | 1 | 5 |
| targets/builtin/GitHubActionsCheckoutProjectionValues.kt | 45 | 1 | 1 |
| targets/builtin/GitHubActionsImageBuildProjectionValues.kt | 16 | 1 | 2 |
| targets/builtin/GitHubActionsManifestGenerator.kt | 50 | 1 | 1 |
| targets/builtin/GitHubActionsManifestRenderer.kt | 227 | 1 | 1 |
| targets/builtin/GitHubActionsNativeProjectionCatalog.kt | 51 | 1 | 0 (1 val) |
| targets/builtin/GitHubActionsProjectionInspection.kt | 13 | 1 | 2 |
| targets/builtin/GitHubActionsProjectionRenderingSupport.kt | 41 | 3 | 2 |
| targets/builtin/GitHubActionsTargetExpressionTranslator.kt | 102 | 1 | 1 |
| targets/builtin/GitHubActionsTriggerProjectionPlanner.kt | 109 | 2 | 2 |
| targets/builtin/GitHubActionsWorkspaceContinuityPlanner.kt | 256 | 1 (+3 private data) | 2 |
| targets/builtin/GitHubJobConditionAuthority.kt | 45 | 1 | 1 |
| targets/builtin/ImageBuildProjectionRenderingSupport.kt | 93 | 1 | 9 |
| targets/builtin/JenkinsImageBuildProjectionValues.kt | 16 | 1 | 2 |
| targets/builtin/JenkinsManifestGenerator.kt | 38 | 1 | 1 |
| targets/builtin/JenkinsManifestRenderer.kt | 292 | 1 | 1 |
| targets/builtin/JenkinsNativeProjectionCatalog.kt | 68 | 1 | 0 (1 val) |
| targets/builtin/JenkinsProjectionRenderingSupport.kt | 45 | 3 | 5 |
| targets/builtin/JenkinsTargetExpressionTranslator.kt | 26 | 1 | 1 |
| targets/builtin/TargetExpressionTranslationException.kt | 5 | 1 | 0 |
| targets/builtin/TargetExpressionTranslator.kt | 17 | 1 | 3 |
| targets/builtin/TargetProjectionRenderingSupport.kt | 81 | 4 | 6 |
| targets/builtin/TektonImageBuildProjectionValues.kt | 6 | 1 | 1 |
| targets/builtin/TektonManifestGenerator.kt | 63 | 1 | 1 |
| targets/builtin/TektonManifestRenderer.kt | 195 | 1 | 1 |
| targets/builtin/TektonNativeProjectionCatalog.kt | 36 | 1 | 0 (1 val) |
| targets/builtin/TektonProjectionRenderingSupport.kt | 37 | 2 | 1 |
| targets/builtin/TektonTargetExpressionTranslator.kt | 66 | 1 | 1 |
| **Celkem** | **7729** | **~115** | **~135** |

---

## Shrnutí

Prošel jsem všech 56 souborů (7729 řádků) v `generators/` a `targets/` — projekční vrstvě, která proměňuje `ExecutionPlan` na `TargetManifest` a volitelně na Jenkinsfile/GitHub Actions YAML/Tekton YAML. Architektura je nadprůměrně disciplinovaná: princip "renderer neplánuje" je vynucen typově (renderer nikdy nesestaví `TargetRendererPayload`, jen konzumuje evidenci z `TargetNativeProjectionCatalog`), model ready/degraded/blocked je konzistentně realizován jako `EXECUTABLE`/`REVIEW_ONLY`/`FAIL_FAST`, a registry (`targets/`) vynucuje, že tvrzení o úrovni podpory musí mít odpovídající evidenci. V celé vrstvě nejsou žádné TODO/FIXME/HACK ani force-unwrapy (`!!`).

**Klíčové zjištění — CVE-třídy bezpečnostní chyba:** **1 kritický** (B1) — v `generators/JenkinsGroovyExpr.kt:61,76` chybí escapování `$` v regex vzorech pro Flow `matches` operátor; protože Jenkins Groovy slashy-string (`/…/`) je GString podporující `${...}` interpolaci, vzniká reálná **Groovy code injection** do generovaného Jenkinsfile. **1 vysoký** (B2) — `groovyEscape()` v `targets/builtin/JenkinsProjectionRenderingSupport.kt:45` neescapuje zpětné lomítko (na rozdíl od sesterské `groovyString()`), což u nevalidovaných Flow input names umožňuje vylomení z Groovy single-quote literálu. **3 střední**: Tekton `in`-operátor tiše zahazuje nepřeložitelné položky seznamu místo fail-closed bloku; nezajištěná vazba mezi native katalogy a renderer-switchem pro loop/match/retry/parallel (Jenkins i Tekton) — dnes neškodná díky gatingu, ale latentní past; tichý fallback na neplatná readiness metadata. **4 nízké**: neúplné escapování control znaků v YAML scalar (dvě místa), duplicitní/nekonzistentní zpracování prázdného flow (3 různá řešení), příliš široký `catch(Exception)` v expression translátorech, nekonzistentní přísnost validace checkout URL napříč cíli.
