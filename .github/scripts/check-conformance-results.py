#!/usr/bin/env python3
"""Validate a conformance results.json (ADR-0032).

usage: check-conformance-results.py <results.json> <vendor-dir> <expected-conformance-version>

Fails (exit 1) when results.json
  * does not conform to schema/results.schema.json,
  * reports conformance_version other than the pinned one,
  * has a `fail` case,
  * omits a binding whose code is present in this repository (bindings are derived from the
    source tree, never hand-written), or lists one whose code is absent,
  * omits a section (or a case id) that the manifest puts in scope for the reported bindings.
"""
import json
import os
import sys

from jsonschema import Draft202012Validator

PACKAGE_ROOT = "src/main/java/com/babelqueue"

# Sub-package of the core -> binding it carries (schema enum). Any transport package added to
# the core must be listed here, or the check below fails on the unknown package.
PACKAGE_BINDINGS = {
    "otel": "otel",
    "redis": "redis",
    "amqp": "amqp",
    "rabbitmq": "amqp",
    "sqs": "sqs",
    "azureservicebus": "asb",
    "asb": "asb",
    "pulsar": "pulsar",
    "kafka": "kafka",
    "artemis": "artemis",
}

# Sub-packages that are not bindings.
NON_BINDING_PACKAGES = {"gdpr", "idempotency", "outbox", "schema"}


def case_ids(section, body):
    """The case-id rule of the conformance README ("Sections, scopes and exceptions")."""
    def named(prefix, fallback, items):
        # `fallback` maps the 0-based index of an unnamed case to its id.
        return [prefix + (c["name"] if isinstance(c, dict) and isinstance(c.get("name"), str) else fallback(i))
                for i, c in enumerate(items)]

    if isinstance(body, list):
        return named("", lambda i: f"{section}#{i}", body)
    if not isinstance(body, dict):
        return []
    ids = []
    if isinstance(body.get("cases"), list):
        ids += named("", lambda i: f"{section}#{i}", body["cases"])
    for key, value in body.items():
        if key in ("description", "schema", "cases") or not isinstance(value, dict):
            continue
        if isinstance(value.get("cases"), list):
            ids += named(key + "/", str, value["cases"])
        else:
            ids.append(key)
    return ids


def derive_bindings():
    """Bindings carried by the source tree; fails on a package it cannot classify."""
    errors = []
    bindings = set()
    for entry in sorted(os.listdir(PACKAGE_ROOT)):
        if not os.path.isdir(os.path.join(PACKAGE_ROOT, entry)) or entry in NON_BINDING_PACKAGES:
            continue
        if entry in PACKAGE_BINDINGS:
            bindings.add(PACKAGE_BINDINGS[entry])
        else:
            errors.append(f"unknown package {PACKAGE_ROOT}/{entry}: add it to PACKAGE_BINDINGS (a transport) "
                          "or NON_BINDING_PACKAGES in this script")
    return sorted(bindings), errors


def main():
    results_path, vendor, pinned = sys.argv[1:4]
    results = json.load(open(results_path))
    manifest = json.load(open(os.path.join(vendor, "manifest.json")))
    schema = json.load(open(os.path.join(vendor, "schema", "results.schema.json")))
    errors = [e.message for e in Draft202012Validator(schema).iter_errors(results)]

    if results.get("conformance_version") != pinned:
        errors.append(f"conformance_version {results.get('conformance_version')!r} != pinned {pinned!r}")

    derived, derive_errors = derive_bindings()
    errors += derive_errors
    if sorted(results.get("bindings", [])) != derived:
        errors.append(f"bindings {sorted(results.get('bindings', []))} != bindings derived from the source tree {derived}")

    carried = {"core", *derived}
    sections = results.get("sections", {})
    for name, meta in manifest["sections"].items():
        if meta["scope"] not in carried:
            if name in sections:
                errors.append(f"section {name!r} (scope {meta['scope']}) is out of scope and must be absent")
            continue
        if name not in sections:
            errors.append(f"in-scope section {name!r} is missing from results.json")
            continue
        missing = [i for i in case_ids(name, manifest.get(name)) if i not in sections[name]]
        if missing:
            errors.append(f"section {name!r} is missing cases: {', '.join(missing)}")
    for name in sections:
        if name not in manifest["sections"]:
            errors.append(f"section {name!r} is not defined by the manifest")

    failed = [f"{s}/{i}" for s, cs in sections.items() for i, r in cs.items() if r.get("status") == "fail"]
    if failed:
        errors.append("failing cases: " + ", ".join(failed))

    if errors:
        print("results.json check FAILED:", file=sys.stderr)
        for e in errors:
            print(" - " + e, file=sys.stderr)
        return 1
    total = sum(len(cs) for cs in sections.values())
    print(f"results.json OK: {len(sections)} sections, {total} cases, bindings={results['bindings']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
