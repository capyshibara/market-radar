# Product golden-set release gate

For the complete offline release sequence, including application compilation, every root standalone
Java test, the legacy negative control and publication fixtures, run
`./scripts/validate_release.sh`; see [Release validation](release-validation.md). A passing automated
run must be followed by the [Product-SME review checklist](product-sme-review-checklist.md).

Run from `market-radar/`:

```bash
python3 scripts/evaluate_product_golden_set.py --enforce-targets
```

The default mode compiles these Java sources into a temporary directory and invokes them for every
fixture case:

- `ProductEventTaxonomy`
- `ProductMaterialityRules`
- `ProductGoldenPredictionAdapter`

It therefore evaluates the checked-out deterministic rules, not a copied Python approximation or
the old classifier-label heuristic. `--legacy-baseline` remains available as a diagnostic negative
control and is not release-eligible.

The fixture intentionally contains observed titles, content-depth flags and legacy labels, but not
article bodies, evidence spans, publication dates, entity extraction, source tier or classification
status. The adapter lists those unavailable fields in each prediction and never substitutes the
title for evidence. Consequently it can release-gate relevance, canonical taxonomy, exclusions and
title-only blocking; evidence-dependent publication decisions remain `NEEDS_EVIDENCE` or `EXCLUDE`.
Add real, reviewed evidence fields to the fixture before using it to score lead/supporting materiality
decisions.

## Product publication-quality regression gate

Run the frozen bad-report cases against the checked-out Java publication rules:

```bash
python3 scripts/evaluate_product_publication_quality.py
```

The evaluator compiles `ProductPublicationQualityGate` and its thin CLI adapter into a temporary
directory. It then verifies the expected disposition, exact machine-readable failure codes and
citation-resolution ratio for every case in
`product-publication-quality-cases.json`. The cases are derived from the frozen
`2026-07-16-current-report-baseline.md`; that baseline remains unchanged.

The release contract is deliberately fail closed:

- every cited evidence ID must resolve;
- the audience and action owner must be our Product function;
- event types may appear only in compatible chapters;
- expired future actions, stale evidence and mixed/legacy versions are rejected;
- every insight must map to a Product KIQ;
- a trend needs at least two events and two independent sources;
- a non-trend single-source item is `WATCH`, never decision-ready;
- generic “monitor/watch/track” actions are rejected; and
- an edition needs three `DECISION_READY` insights, otherwise it is
  `INSUFFICIENT_EVIDENCE`.

The dependency-free Java regression suite exercises the same rule class directly:

```bash
rm -rf /tmp/publication-quality-test
javac -d /tmp/publication-quality-test \
  src/main/java/com/marketradar/quality/ProductPublicationQualityGate.java \
  ProductPublicationQualityGateTest.java
java -ea -cp /tmp/publication-quality-test ProductPublicationQualityGateTest
```

### Integration hook

Before persisting or rendering an edition, adapt each synthesis draft to
`ProductPublicationQualityGate.InsightCandidate`, call `evaluate`, and persist the disposition and
findings alongside the new edition. The adapter must resolve fields as follows:

| Gate input | Authoritative source |
|---|---|
| `citedEvidenceIds` | draft fact/evidence IDs |
| `resolvedEvidenceIds` | evidence rows that exist and pass the factual publication gate |
| `eventIds`, `sourceIds`, dates and versions | normalized events behind those same evidence IDs |
| `themeCode`, canonical `eventType` | synthesis theme and `ProductEventTaxonomy` result |
| `department`, `audience`, `actionOwner` | explicit report contract fields, never inferred from competitor prose |
| `claimsTrend` | structured synthesis flag; do not infer it from text because “not a trend” is a caveat |

Only `DECISION_READY` items count toward the minimum of three. `WATCH` items may appear in a clearly
labelled watchlist, and `REJECT` items must stay out of publication. Findings are stable enums plus
field/detail values, so the funnel can aggregate rejection reasons. Reprocessing must append a new
edition and its results; it must not update the prior edition in place.
