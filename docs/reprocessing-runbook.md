# Market Radar — Fix First, Reprocess Once

This runbook exists to prevent an expensive rerun from publishing another low-quality edition.
Do not use it until the integrated build and safe-copy acceptance tests pass.

## Non-negotiable preconditions

1. Stop the application before copying the H2 database.
2. Run `scripts/backup_before_reprocess.sh` and retain its SHA-256 output.
3. Start the application with real classifier, writer and verifier credentials.
4. Confirm Writer and Verifier use different provider families.
5. Call `/pipeline/reprocess/preflight.json?backupConfirmed=true`.
6. Continue only when `ready=true`. Warnings must have an explicit disposition.

The preflight is read-only. It blocks missing backup confirmation, STUB providers and concurrent
pipeline work. It reports title-only, short, parse-failed and pending-review inventory separately.
It also reports median/p90 article length and how many documents exceed the 24,000-character
extractor input cap. Inspect the read-only classification plan at
`/pipeline/classification/plan` and the extraction plan at `/extract/backfill/plan?limit=25`.

## One controlled run

Run stages in this order; never delete the old edition to make a stage appear pending:

1. **Targeted refetch** — inspect the network-free, write-free
   `GET /pipeline/refetch/plan.json` result. Execute only reviewed explicit IDs (maximum 25) with
   `POST /pipeline/refetch/execute.json?rawDocIds=12,34&confirm=true`. The old broad
   `/pipeline/run/refetch-fulltext` stage is retired; do not restore or call it. Review every
   `targeted_refetch_attempts` outcome before continuing. Failed/short/off-host refetches preserve
   the prior text and must be excluded or remediated separately.
2. **Classify stale** — only documents whose provider/model + prompt-contract + content
   signature is not current. Review the plan first; failed current-version attempts are held.
3. **Extract stale** — only confirmed documents whose extraction signature is not current.
   Dry-run explicit IDs with `POST /extract/backfill/run?...&confirm=false`; execute at most 25
   only after checking every rejection and adding `confirm=true`.
4. **Normalize events** — append the current event-pipeline version; preserve previous rows.
5. **Evaluate golden set** — run
   `python3 scripts/evaluate_product_golden_set.py --enforce-targets`; hard exclusions,
   evidence gates, current-rules provenance and canonical types must pass.
6. **Write and independently verify Product insights** — invoke the configured Product writer on
   closed-schema cluster evidence packs. Its bilingual factual fields must pass Grounding Gate L1,
   then an independent verifier from a different provider family. A writer or verifier failure
   creates an explicit `GENERATION_FAILED` current attempt; it never re-labels an older edition as
   current.
7. **Regenerate the exact Product cadences** — explicitly call
   `POST /report/product/regenerate-all`. It builds 7-, 30-, and 90-day editions independently and
   returns one status per cadence. This endpoint is operator-triggered and is never automatic.
8. **Apply the Product publication gate** — the service persists `DECISION_READY`, `WATCH`, or
   `REJECT` plus failure codes, evidence ratio, and gate version for every candidate. An edition is
   `READY` only with at least three `DECISION_READY` insights. The report adapter never renders
   `REJECT`, and references contain only evidence used by rendered insights.

Do **not** run the legacy Interpret, Verify, or Human Review stages merely to produce the unified
Product reports. Those claim/narrative stages are not consumed by Weekly, Monthly, Quarterly, or
`/report/product`, so doing so adds cost without changing the Product edition. Retain them only for
separate legacy surfaces that explicitly require them.

## Acceptance gates

- Every published factual sentence resolves to one or more exact evidence facts and source URLs.
- No title-only or insufficient-text document enters synthesis.
- Promotions, awards, CSR, generic banking and unsupported classifier labels are absent.
- A trend claim requires multiple documents and at least two independent sources.
- Single-source items are labelled as signals/hypotheses, never trends.
- Low-confidence insights do not enter the executive summary.
- Product actions state owner, decision/test, timeframe and uncertainty.
- The Product golden set passes the automated evaluator.
- The legacy-baseline negative control fails enforcement; otherwise the evaluator is not proving
  that the new rules are better than the old route.
- A Product SME signs off a sample covering Vietnam offers, regulation and regional transfer.
- The safe-copy edition is compared side-by-side with the current production edition before the
  live database is reprocessed.

## Rollback

Editions and versioned pipeline outputs are append-only. Rollback means selecting the prior edition;
it must not require deleting evidence, classifications, claims or verification history. If database
recovery is required, stop the application, preserve the failed database separately, then restore the
verified backup created in the preconditions.
