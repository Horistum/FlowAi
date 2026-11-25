"""Translate a reviewer's CLI operation list into independent compiler requirements."""
from collections import Counter

from .contracts import require


def expected_checks(terms, forbidden, ordered, catalog, only=False):
    by_name = {entry["capability"]: entry for entry in catalog}
    require(terms or forbidden, "Supply a required operation with --expect or an excluded one with --forbid")
    require(len(terms) + len(forbidden) <= 256, "Specify at most 256 checks")
    require(not only or terms, "List every expected operation with --expect for a complete operation check")
    require(not ordered or len(terms) >= 2, "--in-order requires at least two operations")
    checks, selectors = [], []
    for name, *arguments in terms:
        capability = name.upper()
        require(capability in by_name, f"Unknown operation {ascii(name)}. Use flow-core catalog to list capabilities.")
        entry = by_name[capability]
        fields = set(entry["requiredParams"]) | set(entry["optionalParams"])
        parameters = {}
        for argument in arguments:
            key, sep, value = argument.partition("=")
            require(sep and key in fields and key not in parameters,
                    f"Expected a distinct catalog parameter name=value for {capability}. Available fields: {', '.join(sorted(fields))}")
            parameters[key] = value
        selector = {"capability": capability, "parameters": parameters}
        if selector in selectors:
            require(only and not ordered, "A repeated expectation needs distinct arguments to identify the operation")
        selectors.append(selector)
    if only:
        checks.extend(_exact_counts(selectors))
    else:
        checks.extend({"kind": "capability-count", "selector": selector, "minimum": 1} for selector in selectors)
    if ordered:
        checks.extend({"kind": "before", "before": earlier, "after": later}
                      for earlier, later in zip(selectors, selectors[1:]))
    excluded = [name.upper() for name in forbidden]
    require(len(set(excluded)) == len(excluded), "Duplicate excluded operation")
    for capability in excluded:
        require(capability in by_name, f"Unknown excluded operation {ascii(capability)}. Use flow-core catalog to list capabilities.")
        require(all(s["capability"] != capability for s in selectors), "An operation cannot be both required and excluded")
    if only:
        listed = {selector["capability"] for selector in selectors}
        excluded.extend(sorted(by_name.keys() - listed - set(excluded)))
    checks.extend({"kind": "forbid-capability", "capability": capability} for capability in excluded)
    require(len(checks) <= 256, "Specify at most 256 checks, including order and exclusions")
    return checks


def _exact_counts(selectors):
    counts = Counter((s["capability"], tuple(sorted(s["parameters"].items()))) for s in selectors)
    totals = Counter(s["capability"] for s in selectors)
    for (capability, fields), count in counts.items():
        params = dict(fields)
        # Distinct selectors must be disjoint: one real step cannot cover two requested objects.
        for other_capability, other_fields in counts:
            if other_capability == capability and other_fields != fields:
                require(any(key in params and params[key] != value for key, value in other_fields),
                        f"Distinguish {capability} occurrences using different values of a shared parameter")
        yield {"kind": "capability-count", "selector": {"capability": capability, "parameters": params},
               "minimum": count, "maximum": count}
    for capability, count in totals.items():
        # Matching the named objects alone would still admit an extra object of the same capability.
        if (capability, ()) not in counts:
            yield {"kind": "capability-count", "selector": {"capability": capability, "parameters": {}},
                   "minimum": count, "maximum": count}
