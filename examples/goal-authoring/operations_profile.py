"""Author unbound operation graphs using the installed catalog and user-supplied literals."""
import json
import re


def record(fields, required=None):
    return {"type": "object", "properties": fields, "additionalProperties": False,
            "required": list(fields) if required is None else required}


def literal_value(value):
    """Preserve literal data through the Intent 2.0 string reader."""
    if isinstance(value, str):
        if value.startswith(("ref:", "expr:", "secret:")) or re.fullmatch(r"\$(?:[A-Za-z_][\w.-]*|\{[A-Za-z_][\w.-]*})", value, re.ASCII):
            raise ValueError("The full profile is required for an Intent expression or reference.")
    elif isinstance(value, (dict, list)):
        for part in value.values() if isinstance(value, dict) else value:
            literal_value(part)
    return value


class GroundedArguments:
    """Only the original request, its context and submitted answers can supply a value."""

    def __init__(self, inputs):
        self.texts = [inputs["request"]["userText"], *(a["answer"] for a in inputs["userAnswers"])]
        self.context = {}
        self._index(inputs["request"]["context"], "")
        self.atoms = set()
        self.spans = []
        for text in self.texts:
            starts, ends = set(), set()
            for match in re.finditer(r"\S+", text):
                word = match.group()
                bare = word.lstrip('([{"\'').rstrip('.,:;!?)]}"\'')
                if bare:
                    start = match.start() + word.index(bare)
                    starts.update((match.start(), start))
                    ends.update((match.end(), start + len(bare)))
                    self.atoms.add(bare)
            self.spans.append((text, starts, ends))
            for match in re.finditer(r'''"([^"\n]+)"|(?<!\w)'([^'\n]+)'(?!\w)''', text):
                self.atoms.add(match.group(1) or match.group(2))
        self.atoms.discard("")

    def _index(self, value, path):
        if path:
            self.context[path] = value
        if isinstance(value, str):
            self.texts.append(value)
        elif isinstance(value, (dict, list)):
            items = value.items() if isinstance(value, dict) else enumerate(value)
            for key, item in items:
                segment = str(key).replace("~", "~0").replace("/", "~1")
                self._index(item, path + "/" + segment)

    def schema(self):
        text = {"type": "string", "minLength": 1}
        # Exact phrases can always be copied, including when a long source makes
        # an enumeration too expensive for the native decoder.
        forms = []
        if self.atoms and sum(len(s) + 1 for s in self.atoms) <= 12000:
            forms.append({"enum": sorted(self.atoms)})
        forms.extend([record({"quote": text}), record({"ask": text})])
        if self.context:
            forms.append(record({"context": text}))
        return {"oneOf": forms}

    def resolve(self, ref):
        if isinstance(ref, str) and ref in self.atoms:
            return literal_value(ref)
        if isinstance(ref, dict) and set(ref) == {"quote"}:
            quote = ref["quote"]
            if isinstance(quote, str) and quote.strip():
                if quote in self.atoms:
                    return literal_value(quote)
                for text, starts, ends in self.spans:
                    if any(i + len(quote) in ends and text.startswith(quote, i) for i in starts):
                        return literal_value(quote)
        if isinstance(ref, dict) and set(ref) == {"context"} and isinstance(ref["context"], str):
            if ref["context"] in self.context:
                return literal_value(self.context[ref["context"]])
        raise ValueError("The generated argument does not reference a supplied literal.")


class OperationDialogue:
    def __init__(self, payload):
        self.inputs = payload["input"]
        dialogue = any(option.get("properties", {}).get("kind", {}).get("const") == "clarify"
                       for option in payload["output_schema"].get("oneOf", []))
        if not dialogue or self.inputs["request"]["mode"] != "DRAFT":
            raise ValueError("The operations profile requires a draft goal dialogue.")
        self.arguments = GroundedArguments(self.inputs)
        self.catalog = {}
        for entry in self.inputs["capabilityCatalog"]:
            bound = set(entry["bindingOnlyParams"])
            required = entry["requiredParams"]
            if not bound.intersection(required):
                optional = [key for key in entry["optionalParams"] if key not in bound]
                self.catalog[entry["capability"]] = (entry, required, optional)

    def schema(self):
        steps = []
        text = {"type": "string", "minLength": 1}
        for name, (_, required, optional) in self.catalog.items():
            params = record({k: {"$ref": "#/$defs/argument"} for k in [*required, *optional]}, required)
            steps.append(record({"capability": {"const": name}, "params": params}))
        step = {"$ref": "#/$defs/step"}
        index = {"type": "integer", "minimum": 1}
        edge = record({"from": index, "to": index})
        answered = {"summary": text, "questions": {"const": []}}
        forms = [record({"summary": text, "questions": {"type": "array", "minItems": 1, "maxItems": 32, "items": text}})]
        definitions = {}
        if steps:
            definitions = {"argument": self.arguments.schema(), "step": {"oneOf": steps}}
            forms.extend([record({**answered, "steps": {"type": "array", "minItems": 1, "maxItems": 1, "items": step},
                                  "dependencies": {"const": []}}),
                          record({**answered, "steps": {"type": "array", "minItems": 2, "items": step},
                                  "dependencies": {"type": "array", "items": edge}})])
        forms.append(record({**answered, "limitation": text}))
        return {"$defs": definitions, "oneOf": forms}

    def messages(self):
        rows = []
        for name, (entry, required, optional) in self.catalog.items():
            fields = [name, entry["description"]]
            if required:
                fields.append("required: " + ", ".join(required))
            if optional:
                fields.append("optional: " + ", ".join(optional))
            if entry["requiredDesignQuestions"]:
                fields.append("design decisions: " + " ".join(entry["requiredDesignQuestions"]))
            rows.append(" | ".join(fields))
        directions = (
            "You are planning automation for the person who wrote the last request. "
            "Summarize their specific goal in one sentence. Then list the questions you must ask them. "
            "If they ask you to confirm a missing decision, ask them that specific question and stop. "
            "When all decisions have been supplied, leave questions empty and provide their complete operation graph. "
            "Choose capabilities and named parameters from the catalog. For each argument copy the exact user value. "
            "String arguments can be copied directly, quote copies an exact phrase, and context is a JSON pointer "
            "to a typed value in the supplied context. An ask argument requests a missing value. "
            "Number steps from 1. A dependency points from the earlier prerequisite number to the dependent step number. "
            "Use an empty dependencies array for independent work. Copy each resource name exactly. Omit unspecified optional "
            "parameters. Conditions, failure paths, produced values, schedules and module bindings need a richer "
            "document. Describe the limitation for such a request. Never execute work."
        )
        request = self.inputs["request"]
        context = {"context": request["context"], "previousResponse": self.inputs["previousResponse"],
                   "compilerFeedback": self.inputs["compilerFeedback"]}
        background = {k: v for k, v in context.items() if v}
        goal = request["userText"]
        if background:
            goal = "Session data: " + json.dumps(background, ensure_ascii=False) + "\n\n" + goal
        # Examples describe the wire format. Keep them outside user history and
        # outside the compiler's capability vocabulary and argument evidence.
        examples = (
            '\nFormat examples only. A pending date can be represented by '
            '{"summary":"A date needs to be selected.","questions":["Which date should be used?"]}. '
            'A two-step graph has this form: '
            '{"summary":"Perform the two requested operations in order.","questions":[],'
            '"steps":[{"capability":"<operation>","params":{"<parameter>":"<first value>"}},'
            '{"capability":"<operation>","params":{"<parameter>":"<second value>"}}],'
            '"dependencies":[{"from":1,"to":2}]}. Choose actual operations from the catalog below.'
        )
        messages = [{"role": "system", "content": directions + examples + "\nAvailable operations:\n" + "\n".join(rows)},
                    {"role": "user", "content": goal}]
        for answer in self.inputs["userAnswers"]:
            messages.extend([{"role": "assistant", "content": answer["question"]},
                             {"role": "user", "content": answer["answer"]}])
        return messages

    @staticmethod
    def clarification(questions):
        if (not isinstance(questions, list) or not 0 < len(questions) <= 32
                or not all(isinstance(q, str) and q.strip() for q in questions)):
            raise ValueError("Expected a nonempty list of questions.")
        return {"kind": "clarify", "questions": [
            {"id": f"decision-{i}", "question": q} for i, q in enumerate(questions, 1)]}

    def reply(self, result):
        if not isinstance(result, dict):
            raise ValueError("Expected an operation dialogue reply.")
        summary = result.get("summary")
        if not isinstance(summary, str) or not summary.strip():
            raise ValueError("Expected a goal summary.")
        if set(result) == {"summary", "questions"}:
            return self.clarification(result["questions"])
        if result.get("questions") != []:
            raise ValueError("A partial plan cannot accompany questions.")
        if set(result) == {"summary", "questions", "limitation"} and isinstance(result["limitation"], str) and result["limitation"].strip():
            return {"kind": "cannot-plan", "reason": result["limitation"]}
        if set(result) != {"summary", "questions", "steps", "dependencies"} or not isinstance(result["steps"], list) or not result["steps"]:
            raise ValueError("Expected questions, a limitation or a nonempty operation graph.")
        questions, steps = [], []
        for step in result["steps"]:
            if (not isinstance(step, dict) or set(step) != {"capability", "params"}
                    or not isinstance(step["capability"], str) or step["capability"] not in self.catalog):
                raise ValueError("Expected a catalog operation.")
            _, required, optional = self.catalog[step["capability"]]
            params = step["params"]
            if not isinstance(params, dict) or set(required) - params.keys() or params.keys() - set(required + optional):
                raise ValueError("Unexpected or missing catalog parameter.")
            values = {}
            for key, ref in params.items():
                if isinstance(ref, dict) and set(ref) == {"ask"}:
                    questions.append(ref["ask"])
                else:
                    try:
                        values[key] = self.arguments.resolve(ref)
                    except ValueError:
                        return {"kind": "cannot-plan", "reason": f"The argument for {step['capability']}.{key} "
                                "cannot be copied as a literal from this request, context or its answers."}
            steps.append({"id": f"step-{len(steps) + 1}", "capability": step["capability"], "params": values, "requires": []})
        if questions:
            return self.clarification(questions)
        if not isinstance(result["dependencies"], list):
            raise ValueError("Expected a dependency list.")
        for edge in result["dependencies"]:
            if not isinstance(edge, dict) or set(edge) != {"from", "to"}:
                raise ValueError("Expected a prerequisite and dependent step number.")
            before, after = edge["from"], edge["to"]
            if (type(before) is not int or type(after) is not int or
                    not 1 <= before < after <= len(steps) or steps[before - 1]["id"] in steps[after - 1]["requires"]):
                raise ValueError("Expected an explicit dependency on a distinct earlier operation.")
            steps[after - 1]["requires"].append(steps[before - 1]["id"])
        return {"normalizedIntent": {"intentVersion": "2.0", "kind": "FlowIntentDocument", "name": "goal", "description": summary,
                                     "workflows": [{"name": "goal", "kind": "CUSTOM", "steps": steps}]},
                "report": {"mode": "DRAFT", "classification": {"type": "operation-graph", "confidence": 0},
                           "confidence": {k: 0 for k in ("overall", "intentType", "entities", "dependencies", "safety")},
                           "explanation": ["The model selected catalog operations, supplied literals and dependency edges. "
                                           "The local profile supplies the proposal envelope. Confidence is unmeasured."]}}
