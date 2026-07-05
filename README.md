# Market Radar

Product-intelligence radar: ingests market/competitor news from a whitelisted set of sources, has an LLM classify and interpret it into evidence-backed claims, runs those claims through a two-gate verification pipeline before anything is auto-published, and renders the result as a weekly report (HTML + PDF) with a full audit trail (review, labels, dedup, alerts).

## Quick start
```bash
mvn clean package
mvn spring-boot:run        # http://localhost:8080
```
All env vars below are optional — anything unset falls back to a safe STUB mode (no LLM calls, no auto-publish, no Slack, everything routed to human review):
`ANTHROPIC_API_KEY` (writer LLM) · `VERIFIER_API_KEY` (Gate L2 verifier) · `SLACK_WEBHOOK_URL` (hot alerts).

## Pipeline
```
sources (whitelist) → SafeFetcher/parse → IngestionJob → TopicClassifier (AI#1, N=3 self-consistency)
   → Interpreter (AI#3, evidence-grounded claims) → Gate L1 (exact-match grounding)
   → Gate L2 (entailment verifier, different LLM family than the writer)
        ├─ AUTO_APPROVED → weekly report + hot alert (tier T3-T4)
        └─ else → human /review queue → approve/edit/reject → labels + (if approved) hot alert
DedupJob (72h window, exact/hash → Jaccard → LLM pairwise) → duplicates filtered out of the report
```

## Pages / endpoints
| Path | Purpose |
|---|---|
| `GET /report/weekly`, `GET /report/weekly.pdf` | Weekly report, HTML and PDF (same template, same data) |
| `GET /sources`, `POST /ingest/run` | Source registry (whitelist + tier) and manual ingest trigger |
| `GET /classifications`, `POST /classify/run` | Classifier output and trigger |
| `GET /claims`, `POST /interpret/run`, `POST /verify/run` | Interpreted claims, interpreter and Gate L2 verifier triggers |
| `GET /review`, `GET /review/{id}`, `POST /review/{id}/approve\|edit\|force-approve\|reject` | Human review console |
| `GET /labels` | Append-only log of every review decision |
| `GET /dedup`, `POST /dedup/run` | Duplicate/conflict detection and audit |
| `GET /alerts`, `POST /alerts/test` | Hot-alert (Slack) audit + smoke test |
| `POST /demo/inject-ungrounded`, `POST /demo/inject-duplicate` | Seed edge cases for demoing the gates |
| `GET /h2-console` | In-memory DB inspector (dev only) |

## Structure
```
domain/    JPA entities: Source, RawDoc, EvidenceFact, Classification, InterpretedClaim, ClaimVerification,
           DedupDecision, AlertLog, LabelLog, RoutingRule, LlmCallLog
repo/      Spring Data repositories for the above
fetch/     SafeFetcher — the single fetch gate (see safety layers below)
parse/     ContentParsers — Jsoup/Rome/PDFBox, text-only, fail loud
classify/  TopicClassifier (AI#1) + Router (category → department lookup)
interpret/ Interpreter (AI#3), EvidencePack, GroundingGateL1 (exact-match grounding)
verify/    EntailmentVerifier (Gate L2), VerificationJob
review/    ReviewController, ReviewRules, RiskTierRouter
dedup/     DedupRules (Jaccard/normalization), DedupJob, DedupController
alert/     AlertRules, HotAlertService (Slack webhook), AlertController
pipeline/  IngestionJob, ClassificationJob — orchestration + SHA-256 dedup-on-ingest
llm/       LlmClient abstraction: AnthropicLlmClient, OpenAiCompatibleLlmClient, Stub*, factories
report/    ReportController, ClassificationController, ClaimController, PdfExportService
seed/      SeedData — 5 seed sources + sample facts
templates/ Thymeleaf views (weekly-report, sources, classifications, claims, review, labels, dedup, alerts)
```

## Safety invariants
- **Whitelist + tier**: only registered sources are fetchable; nothing outside `source_registry` gets in, including links discovered inside RSS.
- **SafeFetcher is the only egress path**, enforcing: HTTPS-only · exact host whitelist · DNS-resolved SSRF blocking (private/loopback/link-local) · no redirect following · Content-Type must match declared source type · 5 MB body cap + 5s/15s timeouts · content is parsed as text only (Jsoup `.text()`, PDFBox text-only, templates use `th:text` only) — no live HTML/script ever reaches a browser.
- **Fail loud everywhere**: rejected fetches, parse errors, schema-invalid LLM output, and disagreeing verifier votes are logged with a reason and routed to review — never silently dropped or guessed.
- **No verbalized LLM confidence**: classifier status comes from majority vote across N independent runs, not a self-reported score.
- **Gate L2 requires a different LLM family than the writer** (enforced at startup) — the verifier can't share blind spots with the writer.
- **Zero unsourced claims**: every line in the report carries an `F-xxx` fact code linking back to its evidence span, kept in its original language (zh/vi); translations are labeled separately.
- **Fact vs. AI-suggestion boundary is visually explicit** in the report (distinct styling), and manually-approved (`FORCE_APPROVED`) claims are labeled as such.
- **Full audit trail**: every LLM call (`llm_call_log`), review action (`label_log`), dedup decision (`dedup_decisions`), and alert (`alert_log`) is append-only.

Residual, accepted risk: a malicious PDF/HTML exploiting a bug in the parser library itself is theoretical and out of scope for a hackathon MVP; mitigated by the size cap, keeping PDFBox/Jsoup current, and only ingesting PDFs from tier 1–2 sources. Full containment would mean running the parser in an isolated sandbox.

## Configuration
See `src/main/resources/application.yml` for all `marketradar.*` settings (LLM model/sampling, verifier provider, alert thresholds, dedup windows/thresholds, fetch limits). Key points:
- `marketradar.ingest.enabled=false` by default — ingest is triggered manually via `/ingest/run` for deterministic demos.
- `marketradar.verifier.model` is a placeholder (`gpt-4o-mini`) pending a final provider decision; the app refuses to start if the verifier model is in the same family as the writer.
- Missing `ANTHROPIC_API_KEY` / `VERIFIER_API_KEY` / `SLACK_WEBHOOK_URL` each independently fall back to STUB behavior rather than failing — STUB mode is logged loudly at startup and flagged in the UI.

## Known gaps
- The 5 seed source URLs in `SeedData.java` are unverified placeholders.
- Jaccard thresholds (0.90 same / 0.50 gray) for dedup are conservative defaults, not calibrated against real data.
- Verifier LLM provider/model is not finalized.
