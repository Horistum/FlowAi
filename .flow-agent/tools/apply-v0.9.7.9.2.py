from pathlib import Path
import json
import re


def read(path: str) -> str:
    return Path(path).read_text()


def write(path: str, text: str) -> None:
    Path(path).write_text(text)


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


# IntentToAstPlanner: one value conversion authority, no source-derived report,
# and no residual runtime-parameter dropping path.
path = "src/main/kotlin/org/flowlang/intent/IntentToAstPlanner.kt"
text = read(path)
text = replace_once(
    text,
    "import org.flowlang.lowering.IntentLoweringAuthority\n",
    "import org.flowlang.lowering.IntentLoweringAuthority\nimport org.flowlang.lowering.IntentValueExpressionLowering\n",
    "planner lowering import",
)
text = replace_once(
    text,
    "                sourceIntent = IntentLoweringAuthority.sourceMetadata(intent),\n                loweringReport = IntentLoweringAuthority.report(intent)\n",
    "                sourceIntent = IntentLoweringAuthority.sourceMetadata(intent),\n                loweringReport = null\n",
    "planner metadata report",
)
text = replace_once(
    text,
    "        val normalizedType = when (type) {\n            \"dockerRegistry\", \"containerRegistry\" -> \"docker\"\n            \"notification\", \"email\" -> \"notify\"\n            else -> type\n        }\n",
    "        val normalizedType = IntentLoweringAuthority.canonicalSystemType(type)\n",
    "planner system normalization",
)
text = replace_once(
    text,
    "                intent = intent,\n                dropBlockedParams = step.capability in blockedRuntimeCapabilities,\n                semanticEffects = semanticEffects\n",
    "                intent = intent,\n                semanticEffects = semanticEffects\n",
    "planner blocked runtime call",
)
text = replace_once(
    text,
    "        intent: IntentDocument,\n        dropBlockedParams: Boolean = false,\n        semanticEffects: List<SemanticEffect> = CanonicalIntentEffectAuthority.effectsFor(step.capability)\n",
    "        intent: IntentDocument,\n        semanticEffects: List<SemanticEffect> = CanonicalIntentEffectAuthority.effectsFor(step.capability)\n",
    "planner blocked runtime parameter",
)
text = replace_once(
    text,
    "        CanonicalIntentMeaningAuthority.semanticParameters(step)\n            .filterKeys { key -> !dropBlockedParams || key !in blockedParamNames }\n            .forEach { (key, value) -> params[key] = value.toExpression() }\n        if (dropBlockedParams && step.params.keys.any { it in blockedParamNames }) {\n            params[\"projection\"] = StringLiteralNode(value = \"notes-driven-materialization-required\")\n        }\n",
    "        CanonicalIntentMeaningAuthority.semanticParameters(step)\n            .forEach { (key, value) -> params[key] = value.toExpression() }\n",
    "planner blocked runtime implementation",
)
start = text.index("    private fun IntentValue.toExpression(): ExpressionNode = when (this) {")
end = text.index("    private fun Any.toExpressionNode(): ExpressionNode", start)
text = text[:start] + "    private fun IntentValue.toExpression(): ExpressionNode = IntentValueExpressionLowering.lower(this)\n\n" + text[end:]
string_start = text.index("    private fun stringToExpression(value: String): ExpressionNode {")
string_end = text.index("    private fun approvalPolicy", string_start)
text = text[:string_start] + text[string_end:]
text = re.sub(
    r"\n    companion object \{\n        private val blockedParamNames = setOf\(\"command\"\)\n        private val blockedRuntimeCapabilities = setOf\(.*?\n        \)\n    \}\n",
    "\n",
    text,
    count=1,
    flags=re.S,
)
write(path, text)

# FlowPlanner: issue the report only after the complete plan exists.
path = "src/main/kotlin/org/flowlang/planner/FlowPlanner.kt"
text = read(path)
text = replace_once(
    text,
    "import org.flowlang.topology.PlanningTopologyAuthority\n",
    "import org.flowlang.topology.PlanningTopologyAuthority\nimport org.flowlang.lowering.IntentLoweringAuthority\n",
    "flow planner lowering import",
)
text = replace_once(text, "        return ExecutionPlan(\n", "        val basePlan = ExecutionPlan(\n", "flow planner base plan")
text = replace_once(
    text,
    "            sourceIntent = document.metadata.sourceIntent,\n            loweringReport = document.metadata.loweringReport,\n",
    "            sourceIntent = document.metadata.sourceIntent,\n            loweringReport = null,\n",
    "flow planner report source",
)
text = replace_once(
    text,
    "            nodes = allNodes,\n            dependencyRelations = dependencyRelations\n        )\n    }\n\n    private fun TriggerNode.toPlanTrigger()",
    "            nodes = allNodes,\n            dependencyRelations = dependencyRelations\n        )\n        return if (basePlan.sourceIntent == null) {\n            basePlan\n        } else {\n            basePlan.copy(loweringReport = IntentLoweringAuthority.report(basePlan))\n        }\n    }\n\n    private fun TriggerNode.toPlanTrigger()",
    "flow planner report issuance",
)
write(path, text)

# Materialization validates a reproducible report, not its superficial shape.
path = "src/main/kotlin/org/flowlang/generators/manifest/MandatoryMaterializationAuthority.kt"
text = read(path)
text = replace_once(
    text,
    "import org.flowlang.standard.FlowStandardVersions\n",
    "import org.flowlang.standard.FlowStandardVersions\nimport org.flowlang.lowering.IntentLoweringAuthority\nimport org.flowlang.lowering.IntentLoweringDisposition\nimport org.flowlang.lowering.IntentLoweringReport\n",
    "materialization lowering imports",
)
function_start = text.index("    private fun validateLoweringEvidence(")
function_end = text.index("    private fun validateDependencyRelations(", function_start)
replacement = '''    private fun validateLoweringEvidence(
        plan: ExecutionPlan,
        issues: MutableList<PlanningEvidenceIssue>
    ) {
        val sourceIntent = plan.sourceIntent ?: run {
            if (plan.loweringReport != null) {
                issues += issue(
                    "planning.lowering.source-metadata.missing",
                    "sourceIntent",
                    "Lowering evidence is present without source intent metadata."
                )
            }
            return
        }
        val report = plan.loweringReport
        if (report == null) {
            issues += issue(
                "planning.lowering.report.missing",
                "loweringReport",
                "Intent-derived execution plan must preserve artifact-derived lowering evidence."
            )
            return
        }
        if (report.contractVersion != IntentLoweringReport.CONTRACT_VERSION) {
            issues += issue(
                "planning.lowering.version.unsupported",
                "loweringReport.contractVersion",
                "Unsupported lowering evidence contract '${report.contractVersion}'."
            )
        }
        if (report.artifactKind != IntentLoweringReport.ARTIFACT_KIND) {
            issues += issue(
                "planning.lowering.artifact-kind.invalid",
                "loweringReport.artifactKind",
                "Lowering evidence must certify '${IntentLoweringReport.ARTIFACT_KIND}', found '${report.artifactKind}'."
            )
        }
        if (!report.artifactDigest.matches(Regex("[0-9a-f]{64}"))) {
            issues += issue(
                "planning.lowering.artifact-digest.invalid",
                "loweringReport.artifactDigest",
                "Lowering evidence must declare a lowercase SHA-256 artifact digest."
            )
        }
        if (sourceIntent.fields.isEmpty()) {
            issues += issue(
                "planning.lowering.source-catalog.empty",
                "sourceIntent.fields",
                "Intent-derived execution plans require a non-empty stable source field catalog."
            )
        }
        sourceIntent.fields.groupBy { it.identity }.filterValues { it.size > 1 }.keys.forEach { identity ->
            issues += issue(
                "planning.lowering.source-identity.duplicate",
                "sourceIntent.fields.$identity",
                "Stable source identity '$identity' is declared more than once."
            )
        }
        sourceIntent.fields.groupBy { it.targetIdentity }.filterValues { it.size > 1 }.keys.forEach { identity ->
            issues += issue(
                "planning.lowering.target-identity.duplicate",
                "sourceIntent.fields.$identity",
                "Stable target identity '$identity' is claimed by more than one source field."
            )
        }
        sourceIntent.fields.forEachIndexed { index, field ->
            if (field.identity.isBlank() || field.sourcePath.isBlank() || field.targetIdentity.isBlank() || field.valueKind.isBlank()) {
                issues += issue(
                    "planning.lowering.source-field.malformed",
                    "sourceIntent.fields[$index]",
                    "Source field evidence must declare non-blank stable identities, source path and value kind."
                )
            }
            if (!field.sourceDigest.matches(Regex("[0-9a-f]{64}")) ||
                !field.expectedTargetDigest.matches(Regex("[0-9a-f]{64}"))) {
                issues += issue(
                    "planning.lowering.source-field.digest.invalid",
                    "sourceIntent.fields[$index]",
                    "Source and expected target digests must be lowercase SHA-256 values."
                )
            }
            if (field.disposition == IntentLoweringDisposition.PRESERVED && field.transform != null) {
                issues += issue(
                    "planning.lowering.preserved-transform.invalid",
                    "sourceIntent.fields[$index]",
                    "PRESERVED source fields must not declare a transform."
                )
            }
            if (field.disposition == IntentLoweringDisposition.TRANSFORMED && field.transform.isNullOrBlank()) {
                issues += issue(
                    "planning.lowering.transform.missing",
                    "sourceIntent.fields[$index]",
                    "TRANSFORMED source fields must declare the applied transform."
                )
            }
        }
        if (report.evidence.isEmpty()) {
            issues += issue(
                "planning.lowering.evidence.empty",
                "loweringReport.evidence",
                "Intent-derived execution plan must not claim empty lowering coverage."
            )
        }
        report.evidence.groupBy { it.sourceIdentity }.filterValues { it.size > 1 }.keys.forEach { identity ->
            issues += issue(
                "planning.lowering.evidence.duplicate",
                "loweringReport.evidence.$identity",
                "Stable source identity '$identity' has more than one lowering disposition."
            )
        }
        report.evidence.groupBy { it.targetIdentity }.filterValues { it.size > 1 }.keys.forEach { identity ->
            issues += issue(
                "planning.lowering.evidence.target-duplicate",
                "loweringReport.evidence.$identity",
                "Stable target identity '$identity' is certified more than once."
            )
        }
        report.evidence.forEachIndexed { index, evidence ->
            if (evidence.sourceIdentity.isBlank() || evidence.sourcePath.isBlank() ||
                evidence.targetIdentity.isBlank() || evidence.valueKind.isBlank()) {
                issues += issue(
                    "planning.lowering.evidence.malformed",
                    "loweringReport.evidence[$index]",
                    "Lowering evidence must declare non-blank stable source and target identities, source path and value kind."
                )
            }
            if (!evidence.sourceDigest.matches(Regex("[0-9a-f]{64}")) ||
                !evidence.targetDigest.matches(Regex("[0-9a-f]{64}"))) {
                issues += issue(
                    "planning.lowering.evidence.digest.invalid",
                    "loweringReport.evidence[$index]",
                    "Lowering evidence digests must be lowercase SHA-256 values."
                )
            }
        }

        val expectedFields = sourceIntent.fields.map { it.identity }.toSet()
        val actualFields = report.evidence.map { it.sourceIdentity }.toSet()
        (expectedFields - actualFields).forEach { identity ->
            issues += issue(
                "planning.lowering.evidence.missing",
                "loweringReport.evidence",
                "Accepted source field '$identity' has no artifact-derived lowering evidence."
            )
        }
        (actualFields - expectedFields).forEach { identity ->
            issues += issue(
                "planning.lowering.evidence.orphaned",
                "loweringReport.evidence.$identity",
                "Lowering report certifies source field '$identity' outside the source catalog."
            )
        }

        val derivedReport = runCatching {
            IntentLoweringAuthority.report(plan.copy(loweringReport = null))
        }.getOrElse { failure ->
            issues += issue(
                "planning.lowering.target-value.mismatch",
                "loweringReport",
                failure.message ?: "Lowering evidence does not resolve against concrete execution-plan values."
            )
            null
        }
        if (derivedReport != null && report != derivedReport) {
            issues += issue(
                "planning.lowering.report.stale-or-forged",
                "loweringReport",
                "Lowering report does not equal evidence re-derived from the concrete execution plan."
            )
        }

        val expectedSourceIds = sourceIntent.workflows.flatMap { it.stepIds }.toSet()
        val actualSourceIds = PlanDependencyRelations.flatten(plan.nodes).mapNotNull { node ->
            when (node) {
                is TaskNode -> node.sourceId
                is ApprovalNode -> node.sourceId
                else -> null
            }
        }.toSet()
        (expectedSourceIds - actualSourceIds).forEach { sourceId ->
            issues += issue(
                "planning.lowering.step-evidence.missing",
                "nodes",
                "Intent step '$sourceId' has no source identity in the execution plan."
            )
        }
        (actualSourceIds - expectedSourceIds).forEach { sourceId ->
            issues += issue(
                "planning.lowering.step-evidence.orphaned",
                "nodes.$sourceId",
                "Execution node claims source step '$sourceId' outside source intent metadata."
            )
        }
    }

'''
text = text[:function_start] + replacement + text[function_end:]
write(path, text)

# Execution-plan schema v2 stays the public artifact version; only the embedded
# lowering evidence contract advances from 1.0 to 2.0.
path = "schemas/execution-plan.schema.json"
schema = json.loads(read(path))
defs = schema["$defs"]
defs["intentSystemMetadata"] = {
    "type": "object",
    "required": ["name", "sourceType", "canonicalType", "config"],
    "properties": {
        "name": {"type": "string"},
        "sourceType": {"type": "string"},
        "canonicalType": {"type": "string"},
        "purpose": {"type": ["string", "null"]},
        "config": {"type": "object", "additionalProperties": {"type": "string"}},
    },
    "additionalProperties": False,
}
defs["intentSourceField"] = {
    "type": "object",
    "required": [
        "identity", "sourcePath", "targetIdentity", "disposition", "valueKind",
        "sourceDigest", "expectedTargetDigest"
    ],
    "properties": {
        "identity": {"type": "string", "minLength": 1},
        "sourcePath": {"type": "string", "minLength": 1},
        "targetIdentity": {"type": "string", "minLength": 1},
        "disposition": {"enum": ["PRESERVED", "TRANSFORMED"]},
        "valueKind": {"type": "string", "minLength": 1},
        "sourceDigest": {"type": "string", "pattern": "^[0-9a-f]{64}$"},
        "expectedTargetDigest": {"type": "string", "pattern": "^[0-9a-f]{64}$"},
        "transform": {"type": ["string", "null"]},
    },
    "additionalProperties": False,
}
source_metadata = defs["intentSourceMetadata"]
source_metadata["required"] = ["workflows", "policies", "systems", "systemPurposes", "failure", "fields"]
source_metadata["properties"]["systems"] = {
    "type": "array", "items": {"$ref": "#/$defs/intentSystemMetadata"}
}
source_metadata["properties"]["fields"] = {
    "type": "array", "items": {"$ref": "#/$defs/intentSourceField"}
}
defs["intentLoweringEvidence"] = {
    "type": "object",
    "required": [
        "sourceIdentity", "sourcePath", "targetIdentity", "disposition", "valueKind",
        "sourceDigest", "targetDigest"
    ],
    "properties": {
        "sourceIdentity": {"type": "string", "minLength": 1},
        "sourcePath": {"type": "string", "minLength": 1},
        "targetIdentity": {"type": "string", "minLength": 1},
        "disposition": {"enum": ["PRESERVED", "TRANSFORMED"]},
        "valueKind": {"type": "string", "minLength": 1},
        "sourceDigest": {"type": "string", "pattern": "^[0-9a-f]{64}$"},
        "targetDigest": {"type": "string", "pattern": "^[0-9a-f]{64}$"},
        "transform": {"type": ["string", "null"]},
    },
    "additionalProperties": False,
}
defs["intentLoweringReport"] = {
    "type": "object",
    "required": ["contractVersion", "artifactKind", "artifactDigest", "evidence"],
    "properties": {
        "contractVersion": {"const": "2.0"},
        "artifactKind": {"const": "execution-plan"},
        "artifactDigest": {"type": "string", "pattern": "^[0-9a-f]{64}$"},
        "evidence": {"type": "array", "items": {"$ref": "#/$defs/intentLoweringEvidence"}},
    },
    "additionalProperties": False,
}
write(path, json.dumps(schema, indent=2) + "\n")
