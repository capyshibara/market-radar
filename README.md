# Market Radar MVP — status after Batch 5 (steps 1–10 sequence: CORE COMPLETE)

**Quick start** (needs JDK 17+ and Maven; see `BATCH*-NOTES.md` for per-batch detail):
```bash
mvn clean package          # ⚠️ 4 batches never actually compiled — expect a few small errors, quick fixes
mvn spring-boot:run        # http://localhost:8080
```
Env vars (all OPTIONAL — missing ones fall back to a safe STUB mode: no auto-publish, no Slack fires):
`ANTHROPIC_API_KEY` (writer) · `VERIFIER_API_KEY` (Gate L2) · `SLACK_WEBHOOK_URL` (hot alert).

Main pages: `/report/weekly` (+ `.pdf`) · `/sources` · `/classifications` · `/claims`
· `/review` · `/labels` · `/dedup` · `/alerts` · `/h2-console`.
5-beat demo storyline: see the last section of `BATCH5-NOTES.md`.

---

# Market Radar MVP — Batch 1 (steps 1–3 / Section 9 sequence)

Scope of this batch: **DB schema + store · fetch/parse for 5 sources · weekly-report template with hand-entered facts**.
Not yet built: classifier (AI#1), interpreter (AI#3), gate, review page, alerts — later batches.

## ⚠️ Status: CODE NOT COMPILED/TESTED
Written in an offline environment (Maven dependencies couldn't be downloaded). Before trusting any of this:
```bash
mvn clean package        # expect a few small errors (imports/versions) — quick to fix
mvn spring-boot:run
```
Once running:
- `http://localhost:8080/report/weekly` — weekly report (hand-entered sample facts, canonical Section 7 template)
- `http://localhost:8080/sources` — auditable source registry + manual ingest-run button
- `http://localhost:8080/h2-console` — inspect the DB (JDBC URL: `jdbc:h2:mem:marketradar`, user `sa`)

## Pre-demo verification checklist (required)
1. **The 5 `fetchUrl` values in `SeedData.java` are offline placeholders** — open each URL by hand,
   correct the path (especially MOF/ISA's news-listing pages and TNCK's RSS), then set
   `urlUnverified = false`.
2. Sample facts (F-001, F-002) use a **fictional company** — replace with real facts once the pipeline runs.
3. `marketradar.ingest.enabled=false` by default — demo runs manually via `/sources` to stay deterministic.

## Crawl safety layers (requirement: "must not touch malware")
Every outbound request goes through **a single gate: `SafeFetcher`**:

| # | Layer | Defends against |
|---|---|---|
| 1 | HTTPS only | downgrade/MITM |
| 2 | Exact-match host whitelist from source_registry | fetching outside scope, including links found in RSS |
| 3 | DNS resolve → block private/loopback/link-local IPs | SSRF into internal networks |
| 4 | No redirect following (3xx = fail loud) | escaping the whitelist via redirect |
| 5 | Content-Type must match the source's declared type | executables disguised as HTML/PDF |
| 6 | 5 MB body cap + 5s/15s timeouts | oversized payloads, pipeline hangs |
| 7 | Content is data only: Jsoup `.text()`, PDFBox text-only, templates use only `th:text` | XSS / scripts embedded in crawled content |

**Remaining risk (stated plainly):** a malicious PDF/HTML exploiting a parser-library bug is a theoretical
risk — mitigated by the size cap, keeping PDFBox/Jsoup up to date, and only ingesting PDFs from tier 1–2 sources.
Full containment (out of hackathon scope): run the parser in an isolated container/sandbox.

## Structure
```
domain/    Source · RawDoc · EvidenceFact      (source_registry, raw_docs, evidence_store)
repo/      3 JPA repositories
fetch/     SafeFetcher                          (the single fetch gate, 7 defense layers)
parse/     ContentParsers                       (Jsoup / Rome / PDFBox — text-only, fail loud)
pipeline/  IngestionJob                         (orchestration + SHA-256 dedup + reasoned error logging)
seed/      SeedData                             (5 sources + sample facts)
report/    ReportController                     (/report/weekly · /sources · /ingest/run)
templates/ weekly-report.html · sources.html
```

## Invariants built into the code (cross-checked against the full architecture)
- **Whitelist + tier**: sources outside the registry have no way into the system.
- **Fail loud**: rejected fetch / parse error → logged + recorded with a reason, never guessed at.
- **Evidence spans keep the original language verbatim** (zh/vi); translations are labeled separately.
- **Zero unsourced claims**: the template forces every displayed line to carry an `F-xxx` fact code linking back to its source.
- The **Fact / AI-suggestion boundary** is visually explicit (Principle 3) — AI-derived areas get a blue background and clear labeling.

## Next batches (per sequence)
4. Classifier + routing (AI#1, JSON enum of 5 categories, self-consistency N=3)
5. Interpreter + Gate L1 exact-match → 6. Gate L2 (Option A: a different-family LLM)
7. Review page → 8. Hot alert → 9. Dedup/conflict → 10. CSS polish + PDF (OpenHTMLtoPDF)

Technical note: the schema currently uses `CLOB` (H2). If migrating to PostgreSQL, change `columnDefinition`
to `TEXT`.

---

# Batch 2 — Classifier (AI#1) + Routing (step 4 / sequence)

## Added
- `domain/` Category (closed 5-label enum) · Department · Classification · RoutingRule · LlmCallLog
- `llm/` LlmClient · AnthropicLlmClient (REST `/v1/messages`, format verified 07/2026) · StubLlmClient (offline) · LlmClientFactory
- `classify/` TopicClassifier (self-consistency N=3, schema rejection, ≥2/3 vote) · Router (lookup table)
- `pipeline/ClassificationJob` · `/classifications` page + `POST /classify/run`

## Running it
```bash
export ANTHROPIC_API_KEY=sk-ant-...   # unset → falls back to STUB mode (keyword-based, not AI)
mvn spring-boot:run
# 1) POST /ingest/run (or the button on /sources)  2) POST /classify/run  3) view /classifications
```

## Invariants built in (cross-checked against the architecture)
- **No verbalized confidence**: status is derived from votes across N independent runs; `votesJson` stores the evidence.
- **Schema rejection**: an out-of-enum label / malformed JSON → the run is discarded, never silently filtered.
- **Routing only via lookup table**: Router reads `routing_rules`; notes explicitly state "because category X maps to dept Y".
- **Fail loud**: <2 valid runs → UNCERTAIN_REVIEW; disagreement → NO_LABEL_REVIEW; category with no rule → ADMIN_QUEUE. No silent defaults.
- **Audit + replay**: every LLM response is logged to `llm_call_log`; the replay cache serves as a demo fallback.

## Things worth knowing
- The routing table is a **placeholder** (a `placeholder=true` column shown in the UI) — the real ontology remains a separate deliverable.
- STUB mode is logged VERY LOUDLY at startup + a red banner on `/classifications` — impossible to mistake for the real AI.
- The min-votes=2/3 threshold is a conservative proposal, NOT YET calibrated against real data (per the note in Section 3 of the decision doc).


## Batch 4 (steps 7–9): Gate L2 + Reviewer Console + Label log
- `POST /verify/run` — Gate L2: entailment via an LLM from a DIFFERENT FAMILY than the writer (Invariant #2 enforced at startup)
- `GET /review` — reviewer queue · `GET /review/{id}` — claim↔evidence, approve button locked until evidence is opened
- `GET /labels` — every review action becomes a label (label store, MVP just logs it)
- Verifier config: `marketradar.verifier.*` + env `VERIFIER_API_KEY` (no key → stub, everything goes to review)
- Details: `BATCH4-NOTES.md` · Standalone test: `Batch4LogicTest.java` (32/32 pass)
