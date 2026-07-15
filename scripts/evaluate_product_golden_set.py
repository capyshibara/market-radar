#!/usr/bin/env python3
"""Validate the Product golden fixture and score the current deterministic rules.

Examples:
  python3 scripts/evaluate_product_golden_set.py
  python3 scripts/evaluate_product_golden_set.py --predictions /tmp/product-predictions.json
  python3 scripts/evaluate_product_golden_set.py --enforce-targets
  python3 scripts/evaluate_product_golden_set.py --legacy-baseline

Prediction JSON may be either {caseId: {...}} or {"predictions": [{"caseId": ...}]}.
Each prediction should contain departmentRelevant, primaryEventType and qualityDecision.
Without it, the evaluator compiles and invokes the current Java Product taxonomy,
materiality and fixture-routing adapter directly from source. The legacy-label
baseline is diagnostic only and must be requested explicitly.
"""

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
DEFAULT_GOLDEN = ROOT / "docs/evaluation/product-golden-set.json"
KIQ_IDS = {f"P-KIQ-{n:02d}" for n in range(1, 8)}
LEGACY_LABELS = {
    "PRODUCT_LAUNCH",
    "FEE_BENEFIT_COMMISSION_CHANGE",
    "PRODUCT_REGULATION",
    "SALES_DATA",
    "DISTRIBUTION_CHANNEL",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--golden", type=Path, default=DEFAULT_GOLDEN)
    prediction_group = parser.add_mutually_exclusive_group()
    prediction_group.add_argument("--predictions", type=Path)
    prediction_group.add_argument("--legacy-baseline", action="store_true")
    parser.add_argument("--enforce-targets", action="store_true")
    parser.add_argument("--json", action="store_true", help="Print machine-readable metrics")
    return parser.parse_args()


def load(path: Path) -> Any:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def fixture_errors(fixture: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    cases = fixture.get("cases")
    if fixture.get("schemaVersion") != "1.0":
        errors.append("schemaVersion must be 1.0")
    if not isinstance(cases, list) or not cases:
        return errors + ["cases must be a non-empty array"]

    allowed = fixture.get("allowedValues", {})
    event_types = set(allowed.get("eventType", []))
    relevance_modes = set(allowed.get("relevanceMode", []))
    evidence_gates = set(allowed.get("evidenceGate", []))
    decisions = set(allowed.get("qualityDecision", []))
    materiality = fixture.get("materiality", {})
    weights = materiality.get("weights", {})
    thresholds = materiality.get("thresholds", {})
    factor_range = materiality.get("factorRange", [0, 4])
    seen: set[str] = set()

    for case in cases:
        case_id = case.get("caseId")
        if not case_id or case_id in seen:
            errors.append(f"duplicate or missing caseId: {case_id!r}")
            continue
        seen.add(case_id)
        expected = case.get("expected", {})
        if expected.get("relevanceMode") not in relevance_modes:
            errors.append(f"{case_id}: unsupported relevanceMode")
        if expected.get("evidenceGate") not in evidence_gates:
            errors.append(f"{case_id}: unsupported evidenceGate")
        if expected.get("qualityDecision") not in decisions:
            errors.append(f"{case_id}: unsupported qualityDecision")
        all_events = [expected.get("primaryEventType"), *expected.get("secondaryEventTypes", [])]
        if any(event not in event_types for event in all_events):
            errors.append(f"{case_id}: unsupported canonical event type")
        if any(kiq not in KIQ_IDS for kiq in expected.get("kiqIds", [])):
            errors.append(f"{case_id}: unsupported KIQ id")
        labels = case.get("observedLegacyLabels", [])
        if any(label not in LEGACY_LABELS for label in labels):
            errors.append(f"{case_id}: unsupported observed legacy label")

        factors = expected.get("factors", {})
        if set(factors) != set(weights):
            errors.append(f"{case_id}: factor keys do not match weights")
            continue
        low, high = factor_range
        if any(not isinstance(value, int) or not low <= value <= high for value in factors.values()):
            errors.append(f"{case_id}: factor outside {factor_range}")
        computed = sum(factors[name] * weights[name] for name in weights)
        declared = expected.get("materialityScore")
        if not isinstance(declared, (int, float)) or not math.isclose(computed, declared, abs_tol=1e-9):
            errors.append(f"{case_id}: materiality {declared} != formula {computed}")

        expected_decision = disposition(expected, thresholds)
        if expected.get("qualityDecision") != expected_decision:
            errors.append(
                f"{case_id}: decision {expected.get('qualityDecision')} != contract {expected_decision}"
            )

    return errors


def disposition(expected: dict[str, Any], thresholds: dict[str, float]) -> str:
    if not expected.get("departmentRelevant"):
        return "EXCLUDE"
    if expected.get("evidenceGate") != "PASS":
        return "NEEDS_EVIDENCE"
    score = expected["materialityScore"]
    if score >= thresholds["LEAD_SIGNAL"]:
        return "LEAD_SIGNAL"
    if score >= thresholds["SUPPORTING_SIGNAL"]:
        return "SUPPORTING_SIGNAL"
    if score >= thresholds["APPENDIX"]:
        return "APPENDIX"
    return "EXCLUDE"


def legacy_prediction(case: dict[str, Any]) -> dict[str, Any]:
    labels = set(case.get("observedLegacyLabels", []))
    title = case.get("title", "").casefold()
    relevant = bool(labels)
    if "FEE_BENEFIT_COMMISSION_CHANGE" in labels:
        if any(term in title for term in ("khuyến mại", "khuyến mãi", "promotion", "hoàn phí")):
            event = "MARKETING_PROMOTION"
        elif any(term in title for term in ("benefit", "quyền lợi", "coverage")):
            event = "BENEFIT_CHANGE"
        else:
            event = "PRICING_CHANGE"
    elif "PRODUCT_LAUNCH" in labels:
        event = "PRODUCT_LAUNCH"
    elif "PRODUCT_REGULATION" in labels:
        event = "REGULATORY_CHANGE"
    elif "SALES_DATA" in labels:
        event = "COMPETITIVE_PERFORMANCE"
    elif "DISTRIBUTION_CHANNEL" in labels:
        event = "DISTRIBUTION_CHANGE"
    else:
        event = "OTHER"

    if not relevant:
        decision = "EXCLUDE"
    elif case.get("contentDepth") == "TITLE_ONLY":
        decision = "NEEDS_EVIDENCE"
    else:
        decision = "SUPPORTING_SIGNAL"
    return {
        "departmentRelevant": relevant,
        "primaryEventType": event,
        "qualityDecision": decision,
    }


def b64(value: str) -> str:
    return base64.urlsafe_b64encode(value.encode("utf-8")).decode("ascii")


def current_rule_predictions(
    cases: list[dict[str, Any]], snapshot_date: str
) -> dict[str, dict[str, Any]]:
    sources = [
        ROOT / "src/main/java/com/marketradar/intelligence/ProductEventTaxonomy.java",
        ROOT / "src/main/java/com/marketradar/intelligence/ProductMaterialityRules.java",
        ROOT / "src/main/java/com/marketradar/evaluation/ProductGoldenPredictionAdapter.java",
        ROOT / "src/main/java/com/marketradar/evaluation/ProductGoldenPredictionCli.java",
    ]
    with tempfile.TemporaryDirectory(prefix="product-golden-rules-") as compiled:
        compile_result = subprocess.run(
            ["javac", "-encoding", "UTF-8", "-d", compiled, *map(str, sources)],
            cwd=ROOT, capture_output=True, text=True,
        )
        if compile_result.returncode:
            raise RuntimeError("current Product rules did not compile:\n" + compile_result.stderr)

        predictions: dict[str, dict[str, Any]] = {}
        for case in cases:
            result = subprocess.run(
                [
                    "java", "-cp", compiled,
                    "com.marketradar.evaluation.ProductGoldenPredictionCli",
                    case["caseId"], b64(case.get("title", "")),
                    case.get("contentDepth", "UNKNOWN"),
                    b64(",".join(case.get("observedLegacyLabels", []))),
                    snapshot_date,
                ],
                cwd=ROOT, capture_output=True, text=True,
            )
            if result.returncode:
                raise RuntimeError(
                    f"current Product rules failed for {case['caseId']}:\n{result.stderr}"
                )
            fields = result.stdout.rstrip("\n").split("\t")
            if len(fields) != 10:
                raise RuntimeError(f"unexpected predictor output for {case['caseId']}: {result.stdout!r}")
            predictions[case["caseId"]] = {
                "caseId": fields[0],
                "departmentRelevant": fields[1] == "true",
                "primaryEventType": fields[2],
                "qualityDecision": fields[3],
                "materialityScore": int(fields[4]),
                "publishEligible": fields[5] == "true",
                "evidenceEvaluable": fields[6] == "true",
                "rulesVersion": fields[7],
                "unavailableFields": [v for v in fields[8].split(",") if v],
                "productKiqs": [v for v in fields[9].split(",") if v],
            }
        return predictions


def load_predictions(
    path: Path | None, cases: list[dict[str, Any]], legacy_baseline: bool,
    snapshot_date: str,
) -> tuple[str, str, dict[str, dict[str, Any]]]:
    if legacy_baseline:
        return "legacy-label baseline (diagnostic only)", "legacy", {
            case["caseId"]: legacy_prediction(case) for case in cases
        }
    if path is None:
        return (
            "current deterministic Java Product rules",
            "current-rules",
            current_rule_predictions(cases, snapshot_date),
        )
    raw = load(path)
    if isinstance(raw, dict) and "predictions" in raw:
        raw = raw["predictions"]
    if isinstance(raw, list):
        return path.name, "supplied", {item["caseId"]: item for item in raw}
    if isinstance(raw, dict):
        return path.name, "supplied", raw
    raise ValueError("predictions must be an object keyed by caseId or a predictions array")


def ratio(numerator: int, denominator: int) -> float:
    return numerator / denominator if denominator else 0.0


def evaluate(cases: list[dict[str, Any]], predictions: dict[str, dict[str, Any]]) -> dict[str, Any]:
    tp = fp = tn = fn = 0
    event_hits = 0
    hard_exclusion_hits = hard_exclusion_total = 0
    title_block_hits = title_block_total = 0
    quality_hits = quality_total = 0
    unavailable_closed_hits = unavailable_total = 0
    prediction_count = rules_version_count = 0
    failures: list[dict[str, Any]] = []
    rules_versions: set[str] = set()

    for case in cases:
        case_id = case["caseId"]
        expected = case["expected"]
        prediction = predictions.get(case_id)
        if prediction is None:
            failures.append({"caseId": case_id, "failure": "MISSING_PREDICTION"})
            prediction = {}
        else:
            prediction_count += 1
            rules_version_count += int(bool(prediction.get("rulesVersion")))
            if prediction.get("rulesVersion"):
                rules_versions.add(str(prediction["rulesVersion"]))
        truth = bool(expected["departmentRelevant"])
        predicted = bool(prediction.get("departmentRelevant", False))
        if truth and predicted:
            tp += 1
        elif truth:
            fn += 1
        elif predicted:
            fp += 1
        else:
            tn += 1

        event_ok = prediction.get("primaryEventType") == expected["primaryEventType"]
        event_hits += int(event_ok)
        if not truth:
            hard_exclusion_total += 1
            hard_exclusion_hits += int(not predicted)
        if expected["evidenceGate"] == "FAIL_TITLE_ONLY":
            title_block_total += 1
            title_block_hits += int(prediction.get("qualityDecision") == "NEEDS_EVIDENCE")

        evidence_evaluable = bool(prediction.get("evidenceEvaluable", True))
        decision_compared = evidence_evaluable or expected["evidenceGate"] == "FAIL_TITLE_ONLY"
        decision_ok = prediction.get("qualityDecision") == expected["qualityDecision"]
        if decision_compared:
            quality_total += 1
            quality_hits += int(decision_ok)

        unavailable = prediction.get("unavailableFields", [])
        if unavailable:
            unavailable_total += 1
            fail_closed_decision = "NEEDS_EVIDENCE" if predicted else "EXCLUDE"
            unavailable_closed_hits += int(
                not prediction.get("publishEligible", False)
                and prediction.get("qualityDecision") == fail_closed_decision
            )

        if predicted != truth or not event_ok or (decision_compared and not decision_ok):
            failures.append({
                "caseId": case_id,
                "expectedRelevant": truth,
                "predictedRelevant": predicted,
                "expectedEvent": expected["primaryEventType"],
                "predictedEvent": prediction.get("primaryEventType"),
                "expectedDecision": expected["qualityDecision"],
                "predictedDecision": prediction.get("qualityDecision"),
                "qualityCompared": decision_compared,
                "unavailableFields": unavailable,
            })

    precision = ratio(tp, tp + fp)
    recall = ratio(tp, tp + fn)
    return {
        "cases": len(cases),
        "relevanceAgreement": ratio(tp + tn, len(cases)),
        "relevancePrecision": precision,
        "relevanceRecall": recall,
        "relevanceF1": ratio(2 * precision * recall, precision + recall),
        "canonicalEventAgreement": ratio(event_hits, len(cases)),
        "hardExclusionPrecision": ratio(hard_exclusion_hits, hard_exclusion_total),
        "titleOnlyBlockRate": ratio(title_block_hits, title_block_total),
        "qualityDecisionAgreement": ratio(quality_hits, quality_total) if quality_total else None,
        "qualityDecisionCases": quality_total,
        "unavailableEvidenceFailClosedRate": (
            ratio(unavailable_closed_hits, unavailable_total) if unavailable_total else 1.0
        ),
        "unavailableEvidenceCases": unavailable_total,
        "predictionCoverage": ratio(prediction_count, len(cases)),
        "rulesVersionCoverage": ratio(rules_version_count, len(cases)),
        "rulesVersions": sorted(rules_versions),
        "confusion": {"tp": tp, "fp": fp, "tn": tn, "fn": fn},
        "remainingFailures": failures,
    }


def pct(value: float) -> str:
    return f"{value * 100:.1f}%"


def main() -> int:
    args = parse_args()
    fixture = load(args.golden)
    errors = fixture_errors(fixture)
    if errors:
        for error in errors:
            print(f"FIXTURE ERROR: {error}", file=sys.stderr)
        return 2

    try:
        source, source_mode, predictions = load_predictions(
            args.predictions, fixture["cases"], args.legacy_baseline,
            fixture.get("snapshotDate", "2026-07-15"),
        )
    except (OSError, RuntimeError, ValueError) as exc:
        print(f"PREDICTION ERROR: {exc}", file=sys.stderr)
        return 2
    metrics = evaluate(fixture["cases"], predictions)
    targets = {
        "relevanceAgreement": metrics["relevanceAgreement"] >= 0.90,
        "canonicalEventAgreement": metrics["canonicalEventAgreement"] >= 0.90,
        "hardExclusionPrecision": metrics["hardExclusionPrecision"] == 1.0,
        "titleOnlyBlockRate": metrics["titleOnlyBlockRate"] == 1.0,
        "predictionCoverage": metrics["predictionCoverage"] == 1.0,
        "unavailableEvidenceFailClosed": metrics["unavailableEvidenceFailClosedRate"] == 1.0,
        "currentRulesProvenance": source_mode == "current-rules"
        and metrics["rulesVersionCoverage"] == 1.0,
    }
    output = {"fixtureValid": True, "predictionSource": source, "metrics": metrics, "targets": targets}
    if args.json:
        print(json.dumps(output, ensure_ascii=False, indent=2))
    else:
        print(f"Product golden set: VALID ({metrics['cases']} cases; scores/formula/enum/KIQ checks passed)")
        print(f"Prediction source: {source}")
        print(f"Relevance agreement: {pct(metrics['relevanceAgreement'])} "
              f"(precision {pct(metrics['relevancePrecision'])}, recall {pct(metrics['relevanceRecall'])})")
        print(f"Canonical event agreement: {pct(metrics['canonicalEventAgreement'])}")
        print(f"Hard-exclusion precision: {pct(metrics['hardExclusionPrecision'])}")
        print(f"Title-only block rate: {pct(metrics['titleOnlyBlockRate'])}")
        print(f"Unavailable-evidence fail-closed rate: {pct(metrics['unavailableEvidenceFailClosedRate'])} "
              f"({metrics['unavailableEvidenceCases']} fixture cases lack article fields)")
        if metrics["qualityDecisionAgreement"] is None:
            print("Quality-decision agreement: NOT SCORED (article evidence unavailable in fixture)")
        else:
            print(f"Quality-decision agreement: {pct(metrics['qualityDecisionAgreement'])} "
                  f"({metrics['qualityDecisionCases']} decision-comparable cases; title-only gates included)")
        print("Release targets: " + ", ".join(f"{name}={'PASS' if ok else 'FAIL'}" for name, ok in targets.items()))
        print("Remaining failures: " + ", ".join(item["caseId"] for item in metrics["remainingFailures"]))

    return 1 if args.enforce_targets and not all(targets.values()) else 0


if __name__ == "__main__":
    raise SystemExit(main())
