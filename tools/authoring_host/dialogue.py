"""The goal authoring protocol supports a decision before a compilable proposal exists."""
import re

from .contracts import keys, nonblank, require

VERSION = "goal-dialogue/1"
INSTRUCTIONS = """Ask for missing decisions with {"kind":"clarify","questions":[{"id":"...","question":"..."}]}.
If the available contracts cannot express the goal, return {"kind":"cannot-plan","reason":"..."}.
Neither reply contains a draft. Once enough is known, return the complete proposal described below.
Previously answered questions and the original request are authoritative inputs to that proposal."""
IDENTIFIER = r"[a-z][a-z0-9-]{0,63}"
MAX_QUESTIONS = 32


def output_schema(proposal):
    def record(fields):
        return {"type": "object", "properties": fields, "required": list(fields), "additionalProperties": False}
    text = {"type": "string", "pattern": r"\S", "maxLength": 1000}
    question = record({"id": {"type": "string", "pattern": "^" + IDENTIFIER + "$"}, "question": text})
    return {"$defs": proposal["$defs"], "oneOf": [
        {k: v for k, v in proposal.items() if k != "$defs"},
        record({"kind": {"const": "clarify"}, "questions": {"type": "array", "items": question,
                                                             "minItems": 1, "maxItems": MAX_QUESTIONS}}),
        record({"kind": {"const": "cannot-plan"}, "reason": text})]}


def read_questions(reply):
    keys(reply, ("kind", "questions"))
    questions = reply["questions"]
    require(reply["kind"] == "clarify" and isinstance(questions, list) and 0 < len(questions) <= MAX_QUESTIONS,
            "Invalid clarification request")
    seen = set()
    for question in questions:
        keys(question, ("id", "question"))
        key = question["id"]
        require(isinstance(key, str) and re.fullmatch(IDENTIFIER, key) and key not in seen,
                "Invalid or duplicate clarification ID")
        nonblank(question["question"])
        seen.add(key)
    return questions


def read_limitation(reply):
    keys(reply, ("kind", "reason"))
    require(reply["kind"] == "cannot-plan", "Unknown goal dialogue reply")
    nonblank(reply["reason"])
    return reply["reason"]


def scope_questions(questions, turn):
    # A stale answer cannot satisfy a later question with the same provider ID.
    return [{**item, "id": f"{turn}:{item['id']}"} for item in questions]


def answered_questions(pending, answers):
    by_id = {item["id"]: item["question"] for item in pending}
    return [{**item, "question": by_id[item["id"]]} for item in answers]
