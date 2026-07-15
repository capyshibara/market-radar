#!/usr/bin/env python3
"""Run the checked-out Java Product publication gate against frozen bad-report cases."""

from __future__ import annotations

import argparse
import base64
import json
import math
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_FIXTURE = ROOT / "docs/evaluation/product-publication-quality-cases.json"
REQUIRED_FIELDS = {
    "department", "audience", "actionOwner", "externalEntityNames", "kiqCodes", "themeCode",
    "eventType", "citedEvidenceIds", "resolvedEvidenceIds", "eventIds", "sourceIds", "patternText",
    "nowWhat", "claimsTrend", "futureAction", "actionDeadline", "effectiveDate", "windowStart",
    "windowEnd", "publishedDates", "modelVersions", "pipelineVersions",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", type=Path, default=DEFAULT_FIXTURE)
    parser.add_argument("--json", action="store_true", help="print machine-readable results")
    return parser.parse_args()


def encoded(value: Any) -> str:
    if isinstance(value, list):
        value = ",".join(str(item) for item in value)
    if value is None:
        value = ""
    return base64.urlsafe_b64encode(str(value).encode("utf-8")).decode("ascii")


def merged(defaults: dict[str, Any], case: dict[str, Any]) -> dict[str, Any]:
    return {**defaults, **case}


def fixture_errors(fixture: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if fixture.get("schemaVersion") != "1.0":
        errors.append("schemaVersion must be 1.0")
    defaults = fixture.get("defaults")
    cases = fixture.get("cases")
    if not isinstance(defaults, dict):
        return errors + ["defaults must be an object"]
    if not isinstance(cases, list) or not cases:
        return errors + ["cases must be a non-empty array"]
    missing_defaults = REQUIRED_FIELDS - defaults.keys()
    if missing_defaults:
        errors.append(f"defaults missing fields: {sorted(missing_defaults)}")
    ids: set[str] = set()
    allowed_dispositions = {"DECISION_READY", "WATCH", "REJECT"}
    for case in cases:
        case_id = case.get("caseId")
        if not case_id or case_id in ids:
            errors.append(f"duplicate or missing caseId: {case_id!r}")
        ids.add(case_id)
        if not case.get("baselineDefect"):
            errors.append(f"{case_id}: baselineDefect is required")
        if case.get("expectedDisposition") not in allowed_dispositions:
            errors.append(f"{case_id}: invalid expectedDisposition")
        if not isinstance(case.get("expectedFailureCodes"), list):
            errors.append(f"{case_id}: expectedFailureCodes must be an array")
    for edition in fixture.get("editions", []):
        unknown = set(edition.get("candidateCaseIds", [])) - ids
        if unknown:
            errors.append(f"{edition.get('editionId')}: unknown cases {sorted(unknown)}")
        if edition.get("expectedStatus") not in {"READY", "INSUFFICIENT_EVIDENCE"}:
            errors.append(f"{edition.get('editionId')}: invalid expectedStatus")
    return errors


def java_args(case: dict[str, Any], as_of: str) -> list[str]:
    date = lambda key: case.get(key) or "-"
    return [
        case["caseId"], case["department"], case["audience"], encoded(case["actionOwner"]),
        encoded(case["externalEntityNames"]), encoded(case["kiqCodes"]), case["themeCode"],
        case["eventType"], encoded(case["citedEvidenceIds"]), encoded(case["resolvedEvidenceIds"]),
        encoded(case["eventIds"]), encoded(case["sourceIds"]), encoded(case["patternText"]),
        encoded(case["nowWhat"]), str(case["claimsTrend"]).lower(), str(case["futureAction"]).lower(),
        date("actionDeadline"), date("effectiveDate"), as_of, date("windowStart"), date("windowEnd"),
        encoded(case["publishedDates"]), encoded(case["modelVersions"]), encoded(case["pipelineVersions"]),
    ]


def run_rules(fixture: dict[str, Any]) -> dict[str, dict[str, Any]]:
    sources = [
        ROOT / "src/main/java/com/marketradar/quality/ProductPublicationQualityGate.java",
        ROOT / "src/main/java/com/marketradar/quality/ProductPublicationQualityCli.java",
    ]
    defaults = fixture["defaults"]
    with tempfile.TemporaryDirectory(prefix="product-publication-quality-") as compiled:
        compile_result = subprocess.run(
            ["javac", "-encoding", "UTF-8", "-d", compiled, *map(str, sources)],
            cwd=ROOT, capture_output=True, text=True,
        )
        if compile_result.returncode:
            raise RuntimeError("publication-quality rules did not compile:\n" + compile_result.stderr)
        results: dict[str, dict[str, Any]] = {}
        for raw_case in fixture["cases"]:
            case = merged(defaults, raw_case)
            process = subprocess.run(
                ["java", "-cp", compiled, "com.marketradar.quality.ProductPublicationQualityCli",
                 *java_args(case, fixture["asOfDate"])],
                cwd=ROOT, capture_output=True, text=True,
            )
            if process.returncode:
                raise RuntimeError(f"quality gate failed for {case['caseId']}:\n{process.stderr}")
            fields = process.stdout.rstrip("\n").split("\t")
            if len(fields) != 4:
                raise RuntimeError(f"unexpected output for {case['caseId']}: {process.stdout!r}")
            results[case["caseId"]] = {
                "caseId": fields[0],
                "disposition": fields[1],
                "resolvedEvidenceRatio": float(fields[2]),
                "failureCodes": [code for code in fields[3].split(",") if code],
            }
        return results


def evaluate(fixture: dict[str, Any], results: dict[str, dict[str, Any]]) -> dict[str, Any]:
    failures: list[str] = []
    for expected in fixture["cases"]:
        actual = results[expected["caseId"]]
        if actual["disposition"] != expected["expectedDisposition"]:
            failures.append(
                f"{expected['caseId']}: disposition {actual['disposition']} != {expected['expectedDisposition']}"
            )
        expected_codes = set(expected["expectedFailureCodes"])
        actual_codes = set(actual["failureCodes"])
        if actual_codes != expected_codes:
            failures.append(
                f"{expected['caseId']}: failure codes {sorted(actual_codes)} != {sorted(expected_codes)}"
            )
        expected_ratio = expected.get("expectedResolvedEvidenceRatio", 1.0)
        if not math.isclose(actual["resolvedEvidenceRatio"], expected_ratio, abs_tol=1e-9):
            failures.append(
                f"{expected['caseId']}: evidence ratio {actual['resolvedEvidenceRatio']} != {expected_ratio}"
            )

    edition_results: list[dict[str, Any]] = []
    for edition in fixture.get("editions", []):
        ready_count = sum(
            results[case_id]["disposition"] == "DECISION_READY"
            for case_id in edition["candidateCaseIds"]
        )
        status = "READY" if ready_count >= 3 else "INSUFFICIENT_EVIDENCE"
        edition_results.append({"editionId": edition["editionId"], "status": status, "decisionReady": ready_count})
        if status != edition["expectedStatus"]:
            failures.append(f"{edition['editionId']}: status {status} != {edition['expectedStatus']}")
    return {
        "fixture": str(DEFAULT_FIXTURE.relative_to(ROOT)),
        "caseCount": len(fixture["cases"]),
        "passedCases": len(fixture["cases"]) - len({failure.split(":", 1)[0] for failure in failures}),
        "editionResults": edition_results,
        "failures": failures,
        "passed": not failures,
        "results": list(results.values()),
    }


def main() -> int:
    args = parse_args()
    with args.fixture.open(encoding="utf-8") as handle:
        fixture = json.load(handle)
    errors = fixture_errors(fixture)
    if errors:
        print("Invalid fixture:\n- " + "\n- ".join(errors), file=sys.stderr)
        return 2
    try:
        report = evaluate(fixture, run_rules(fixture))
    except (OSError, RuntimeError) as error:
        print(str(error), file=sys.stderr)
        return 2
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"Product publication quality: {report['passedCases']}/{report['caseCount']} cases")
        for edition in report["editionResults"]:
            print(f"- {edition['editionId']}: {edition['status']} ({edition['decisionReady']} decision-ready)")
        for failure in report["failures"]:
            print(f"FAIL: {failure}")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
