# Market Radar — Engineering Handoff (updated 2026-07-16 quality-remediation session)

## Latest live update — 2026-07-16 (supersedes the completion statement below)

The controlled live reprocess has since completed for **Classify** and **Extract**. A Product
regeneration was also run successfully: it did **not** crash, but all 7/30/90-day editions were
correctly stored as `INSUFFICIENT_EVIDENCE` rather than publishing unsupported prose. The result
is a corpus/freshness finding, not permission to lower the publication threshold:

- 7 days: only two eligible facts; no decision-ready life-Product signal.
- 30 days: insufficient safe candidates after the Product contract and publication minimum.
- 90 days: unsafe candidates were excluded by L1/L2 (one quoted demonstrative was misread as a
  name; another candidate asserted a launch/withdrawal that its evidence span did not state).

Follow-up source changes are built locally and require one application restart before the next
run. They are not yet a substitute for fresh source content:

1. L1 now ignores only a closed, auditable list of generic quoted references such as
   `"thủ tục này"`; actual company, product and regulation names remain verbatim-gated.
2. The Product writer may use `evidenceSpan` only for factual assertions and its one repair
   attempt explicitly forbids invented launch, withdrawal, availability or similar lifecycle
   claims. The bilingual verifier remains fail-closed on `NEUTRAL`.
3. Product materiality now excludes non-life stories and claims-payment stories without an
   evidenced product-design consequence.
4. An additive startup migration backfills any missing MOF and Vietnam life-insurer sources that
   already have dedicated ingestion parsers. It never overwrites or activates an existing source.
5. The crawler now allows a 10-second connection and 30-second request window, with one 400 ms
   retry for a transient **GET** transport failure only. It never retries POSTs, blocked hosts,
   redirects, HTTP/content failures, DNS/SSL/protocol errors, or oversized payloads. If broadly
   distributed timeouts persist after restart, treat them as an operator-network/proxy issue—not
   as an extraction or LLM failure.
6. The Product report now has a distinct **Current Watch Brief** tier. One or two current signals
   that pass exact grounding, independent verification and the publication gate may render with
   citations, a Product validation action and a stated limitation. It is never labelled a
   market-wide trend or Decision Brief; the full Decision Brief still requires three
   `DECISION_READY` insights. Zero safe signals remains `INSUFFICIENT_EVIDENCE`.
7. Every 7/30/90-day Product surface also renders **Current Product News** independently of the
   insight tier. These cards are source fields only—publication date, publisher, article link,
   fact code and an exact verbatim evidence span—not LLM summaries, recommendations or trend
   claims. Admission requires an active, confirmed, full-text, non-duplicate tier 1–3 document
   in the exact cadence window, an 80+ character span that occurs verbatim in the source, a
   Product-relevant label, and life-scope/no-claims-only checks. This restores useful current
   coverage when the decision layer is sparse without reviving legacy unverified prose.

After restarting the built JAR, inspect `/sources`, then run **Ingest → Classify → Extract →
Product regenerate** once (never the legacy Interpret/Verify stages). Do not rerun the current
Product regeneration against unchanged evidence; it will truthfully remain insufficient.

## 2026-07-16 completion status

The code remediation is complete and the live corpus has **not** been reprocessed. The final
offline release harness passed: Maven build, 31/31 standalone Java regressions, the 20/20 Product
golden set and all release thresholds, the required failing legacy negative control, and 15/15
publication-quality fixtures. A schema/render smoke test against an isolated copy of the database
returned HTTP 200 for Product, Weekly, Monthly, Quarterly, preflight, targeted-refetch plan and
extraction plan. All four report surfaces correctly showed `INSUFFICIENT_EVIDENCE` for the old
unverified editions, with no legacy fallback content or unused references.

Verified stopped-app backup:

- `data/backups/marketradar-before-reprocess-20260716-001315.mv.db`
- SHA-256 `983573bcb3a23420624478152f9d444a7db684ad8cd77c9a7a7316f4049a5f74`

The remaining operation is one controlled live-provider reprocess. It was not run because the
Codex process had no `WRITER_API_KEY`, `CLASSIFIER_API_KEY` or `VERIFIER_API_KEY`. Follow
`docs/reprocessing-runbook.md`; never run the retired broad refetch or the unused legacy
Interpret/Verify/Human Review stages just to populate the unified Product reports.

After the first live refetch review, classification received an additional fail-closed cost gate:
sample, blank, title-only/unverified and sub-600-character documents are excluded before any model
call. On the current copied corpus the plan is 503 stale documents, 185 content skips and 9 duplicate
skips, reducing the maximum three-sample classifier workload from 2,064 to 1,509 calls. Product
materiality and targeted-refetch length semantics now use the same stripped 600-character floor.

The safety and release notes in this section supersede older statements later in this document.
The historical sections remain for context, but their destructive rerun and bulk-approval
instructions must not be repeated.

## ⏭️ START HERE — what's pending / what to check first

1. **The build gates now pass; the live corpus still requires controlled reprocessing.** The
   application remains intentionally stopped. Start it against `data/marketradar.mv.db` only with
   all three real provider keys, confirm provider families in the boot log, then inspect the
   read-only preflight and bounded refetch/extraction plans before any mutation.

2. **Never wipe generated tables to force a rerun.** Classification, extraction and
   interpretation now use version/current-edition semantics and preserve prior output. Old
   facts and claims remain auditable; reports select only active/current, verified editions.
   Failed or empty replacements must leave the previous good edition active.

3. **Publication is fail-closed.** A claim must pass L1, have an approved review status, and
   have latest L2 verdict `ENTAILED`. `NEUTRAL`, `CONTRADICTED`, stale-verdict edited claims,
   superseded claims, title-only documents and insufficient text never publish. Do not use the
   historical bulk-approve recipe later in this file; in particular, never approve `NEUTRAL`
   claims merely to make a report fuller.

4. **All report surfaces now use one fail-closed Product path.** Product, Weekly, Monthly,
   Quarterly and email use the exact 7/30/90-day Product snapshot. Only persisted
   `DECISION_READY` and `WATCH` dispositions render; `REJECT`, null/legacy dispositions and old
   fallback narratives never render. Fewer than three decision-ready insights is explicitly
   `INSUFFICIENT_EVIDENCE`.

5. **Safe reprocessing controls:** read `docs/reprocessing-runbook.md`; create a stopped-app
   backup with `scripts/backup_before_reprocess.sh`; inspect
   `/pipeline/reprocess/preflight.json?backupConfirmed=true`; inspect extraction depth/staleness
   with `/extract/backfill/plan`; use targeted dry-runs before any confirmed mutation. AI stages
   are blocked server-side while their provider is STUB.

6. **Quality is measured, not inferred from HTTP 200.** Run `./scripts/validate_release.sh`.
   Automated and copied-database gates passed on 2026-07-16; a fresh real-provider edition still
   requires the Product-SME checklist and direct citation review before publication.

## How to run it right now

```bash
cd /Users/hanh/Downloads/workspace/hackathon/market-radar

export WRITER_API_KEY=<Hanh's OpenAI key>
export CLASSIFIER_API_KEY=<Hanh's DeepSeek key>
export VERIFIER_API_KEY=<Hanh's DeepSeek key — same value as CLASSIFIER_API_KEY, one DeepSeek account>

java -jar target/market-radar-0.1.0-MVP.jar \
  --server.port=8081 \
  --marketradar.classifier.base-url=https://api.deepseek.com \
  --marketradar.classifier.model=deepseek-chat
```

**⚠️ 2026-07-15 update (content-quality audit session, after this handoff was written):**
writer moved from `gpt-4o-mini` to **`gpt-5-mini`** — `gpt-4o-mini`'s output was part of
what read as shallow/fragmented (see `tmr-content-quality-audit.md`). `--marketradar.llm.*`
flags were **removed from the launch command above** on purpose: the model/base-url now live
in `application.yml` (`base-url: https://api.openai.com/v1`, `model: gpt-5-mini`) so this
doc can't drift out of sync with the code default again — don't re-add `--marketradar.llm.model`
overrides here unless you're intentionally testing a different writer model.

**Provider stack (current):** Writer (Extract AI#2 + Interpret AI#3) = **OpenAI `gpt-5-mini`**
(ChatGPT family per Hanh's cost call, not Claude). Classifier (AI#1) = DeepSeek. Verifier
(Gate L2) = DeepSeek (config already defaults to DeepSeek in `application.yml`, only needs
the env var). Confirm boot log shows:
```
LLM MODE (WRITER): OPENAI_COMPAT (base-url=https://api.openai.com/v1, model=gpt-5-mini)
CLASSIFIER MODE: OPENAI_COMPAT (base-url=https://api.deepseek.com, model=deepseek-chat)
VERIFIER MODE: OPENAI_COMPAT (base-url=https://api.deepseek.com, model=deepseek-chat) — khác họ với writer
```

**⚠️ The one rule that caused confusion before, still true:** never `mvn package` while the
app is running without restarting afterward. Every code change in this session was followed
by rebuild-then-ask-Hanh-to-restart, never a live reload.

**DB inspection**: the datasource intentionally uses single-process file mode. `AUTO_SERVER`
was removed after a fat-jar shutdown left a stale remote-server lock and prevented the next
app launch. Do not open the live database from a second Java process. Stop Market Radar and
query a copy of `data/marketradar.mv.db` instead:
```bash
cp data/marketradar.mv.db /tmp/marketradar-inspect.mv.db
java -cp ~/.m2/repository/com/h2database/h2/2.2.224/h2-2.2.224.jar org.h2.tools.Shell \
  -url "jdbc:h2:file:/tmp/marketradar-inspect" -user sa -password "" -sql "SELECT ..."
```
A pre-re-run backup exists at `data/backups/marketradar-pre-rerun-20260715-082750.mv.db` (from
before this session's destructive wipe-and-regenerate — see below) in case anything needs
rolling back to the Claude-era content.

## What changed this session (chronological, root-causes traceable)

### 1. Narrative synthesis feature (batch 10) — monthly went from story-cards to prose
Added a whole new AI capability: chapter-level narrative synthesis, sitting **after** Gate L2
in trust terms but architecturally a new Interpret sub-stage. New files:
- `interpret/Chapter.java` — enum `VN_COMPETITOR` / `VN_REGULATION` / `REGIONAL_LESSONS`,
  each carrying number/titles/subtitles/market/factTypes AND (added later) a
  `narrativeFocusVi()` per-chapter angle naming which business functions (product/actuary/
  distribution/marketing/legal) that chapter should speak to.
- `interpret/NarrativePack.java` — sibling to `EvidencePack`; built from already-Gate-L1-PASS
  per-doc claims (not raw facts) + their cited evidence, so synthesis reuses vetted analysis
  rather than re-deriving from scratch.
- `InterpretedClaim.Slot.NARRATIVE` + new `chapterCode` column.
- `InterpretationJob.runChapterNarrative()` — runs once per chapter, capped input (`selectNarrativeClaims`,
  ≤24 claims, ≤2/doc, named-company docs prioritized) so the model doesn't drown in 100+ claims
  and default to generic prose.
- `MonthlyReportController` rewritten around `Chapter`/`ChapterArticle` records instead of the
  old `Section`/`Story` (deleted, was per-fact story cards).
- `ClaimController.forceRetryNarrative()` + `ClaimVerificationRepository.deleteByClaimSlotAndChapterCodeAndOrigin`
  (needed because narrative claims, once verified, hit an FK constraint on naive delete —
  verifications must be deleted first).

**Bugs found and fixed along the way** (worth knowing if you see similar symptoms again):
- A Hibernate-6-generated CHECK constraint (`CONSTRAINT_2650`) restricted `SLOT` to the 3
  original enum values; adding `NARRATIVE` in code didn't retroactively widen it (`ddl-auto:
  update` doesn't touch existing check constraints). Fixed via direct `ALTER TABLE ... DROP/ADD
  CONSTRAINT` against the stopped database. If you add new enum values to any `@Enumerated`
  field again, expect the same issue.
- **Replay-cache didn't key on provider identity** — switching Writer/Verifier provider (e.g.
  STUB → real DeepSeek) still hit old cached responses under the new provider's label, because
  the cache hash was `sha256(system+user)` only. Fixed by including `providerName()` in the hash
  across `Interpreter`, `TopicClassifier`, `EntailmentVerifier` (`AnthropicLlmClient.providerName()`
  also now includes the model, not just "ANTHROPIC" bare, so Claude model switches are covered
  too). **This means every prompt change also naturally busts old cache** — a nice side effect,
  no manual cache purge needed after editing prompts.
- `LazyInitializationException` on `claim.getRawDoc().getSource()` outside a transaction (open-in-view
  is off) when ranking logic tried to check VN-vs-regional market from a claim. Fixed by deriving
  a `Set<Long> vnDocIds` from `EvidenceFact` (which DOES have `source` join-fetched via
  `findAllForReport()`) instead of touching the claim's lazy proxy. **If you add new ranking/
  filtering logic on claims, always route market/source lookups through facts, never
  `claim.getRawDoc().getSource()` directly.**

### 2. Report cadence: weekly / monthly (30d) / quarterly (90d)
`ReportWindow.java` now has `weeklyStart` (7d), `monthlyStart` (30d), `quarterlyStart` (90d),
and a separate `narrativeStart` (365d) — narrative synthesis intentionally uses a much wider
window than the display window, because this is a backfilled demo corpus (crawled once, not
incrementally), so the *substantive* competitor moves (product launches, partnerships) are
often 6-12 months old relative to "today," while only minor/administrative news is genuinely
recent. Display windows (monthly/quarterly) stay tight for "current" framing; the analytical
narrative pulls from the wide window so chapters aren't thin. `MonthlyReportController` now has
one shared `render(...)` method serving both `/report/monthly` and `/report/quarterly` (new
endpoint) — same template, different window + `cadenceLabel`.

**Ranking** (exec summary + competitor scan): sort order is (1) VN market first, (2) impact type
— PRODUCT_LAUNCH/FEE_CHANGE/REGULATION ranked above minor EVENT/METRIC (`impactRank()`, added
because a provincial claims-payout was leading the report over a real product launch), (3) named
company present, (4) recency, (5) risk tier, (6) id. Helpers `hasNamedCompany`/`recencyOf`/
`impactRank` are in `MonthlyReportController`.

### 3. Content quality overhaul — the big one, in two rounds
**Round 1** (tone + specificity): banned praise/PR adjectives ("leading", "prestigious",
"affirming leadership") across all Interpret + Extract prompts; forced narrative sentences to
cite a specific company/number/date instead of generic filler ("shows strength and
sustainability"); added per-chapter "function focus" so narrative ties findings to
product/actuary/distribution/marketing/legal.

**Round 2** (story-first — the critical fix, prompted directly by Hanh's feedback): the exec
summary and narrative were STILL bad after round 1, because they only ever displayed the
`IMPLICATION` claim (the bare conclusion — "this could create growth opportunities") while
silently discarding the `WHY_MATTERS` claim that actually contained the story (who/what/when/
number). Fixed at two levels:
  - **Prompt level**: all 3 Interpreter prompts (`SYSTEM_DOC`, `SYSTEM_EXEC`, `SYSTEM_NARRATIVE`)
    now explicitly require "kể chuyện trước, kết luận sau" (story before conclusion) — WHY must
    open with subject+action+date+number, IMPLICATION must stay tightly tied to that specific
    WHY. Narrative must also read as connected prose with transitions ("Đáng chú ý,", "Trong khi
    đó,"), grouped by theme, not a list of 6-8 independent verdict sentences.
  - **Report layer**: `MonthlyReportController.ExecItem` record now pairs `why` (WHY_MATTERS
    claim for that doc) + `impl` (the IMPLICATION), rendered together in the exec summary
    template (bold story sentence, then the implication). `ChapterArticle.paragraphs()` groups
    narrative sentences into ~3-sentence paragraphs instead of one sentence per line.

A **historical full pipeline re-run** was used in the earlier session (this destructive method
is now retired and must not be repeated): backed up DB → wiped `classifications`/`evidence_facts`/
`interpreted_claims`/`claim_verifications`/`llm_call_log` (kept `raw_docs`/sources/routing) →
re-ran Classify (627 docs, DeepSeek self-consistency) → Extract (154 CONFIRMED docs survived
classification, up from including 25 pure-banking docs before — see below) → Interpret → Verify
→ bulk-approved ENTAILED+NEUTRAL via `/review/{id}/approve` curl loop (skipped CONTRADICTED).
**This is also when the classifier's earlier "bancassurance clarifier" prompt fix got proven
out**: all 25 `Thời báo Ngân hàng` (pure banking) docs went `OUT_OF_SCOPE` and dropped out of
the report entirely, while named insurers (Prudential, Generali, AIA, BIDV MetLife, Chubb Life)
stayed `CONFIRMED` — i.e. the topic-relevance fix works at scale, not just on the 4-doc sample
tested earlier.

**Extraction was also widened**: `FactExtractionJob.MAX_FACTS_PER_DOC` 5→8, plus an instruction
to prefer information-dense spans (numbers + mechanism), addressing Hanh's "we extracted too
little to write sharp insights" hypothesis.

### 4. Editable AI Prompts — new ops page (batch 12)
Hanh asked for prompts to be visible/editable by ops, not buried in code. New package
`com.marketradar.prompt`:
- `PromptKey` enum — one entry per AI stage (CLASSIFY, EXTRACT, INTERPRET_DOC, INTERPRET_EXEC,
  INTERPRET_NARRATIVE, VERIFY), each with a Vietnamese label + description for the ops UI.
- `PromptOverride` (JPA entity, table `prompt_overrides`) + `PromptOverrideRepository`.
- `PromptService` — stages call `registerDefault(key, hardcodedPromptString)` once at
  construction (so the "factory default" is always the code's own constant, never lost); all
  runtime prompt reads go through `promptService.body(key)`, which returns the DB override if
  present, else the registered default.
- `PromptController` + `templates/prompts.html` — new `/prompts` ops page, one card per stage,
  textarea + "Lưu & áp dụng" (saves override, applies on next AI call — replay-cache
  automatically busts since the hash includes prompt text) + "Khôi phục mặc định" (deletes the
  override row). Linked from the ops sidebar under Pipeline Config.
- **All 4 stage classes** (`Interpreter`, `FactExtractionJob`, `TopicClassifier`,
  `EntailmentVerifier`) now take `PromptService` as a constructor dependency and call
  `promptService.body(PromptKey.X)` instead of referencing their `SYSTEM_*` constants directly
  at call time (the constants still exist, just as the registered defaults).

**Not yet tested by a human**: nobody has actually used the `/prompts` page to edit-and-verify a
live prompt change end-to-end this session (built and wired, confirmed `/prompts` returns 200,
but the save→regenerate→see-the-difference loop hasn't been exercised). Worth a smoke test.

### 5. Visual: Meridian design system forms adopted on monthly/quarterly
Per Hanh's ask to "learn the template by heart" — read the layout-decision guidance in the
package's `readme.md` + component `.prompt.md` files (column-count-by-density rules,
content-type-by-purpose catalog) before choosing forms, rather than guessing:
- **`quote` page** — full-navy Meridian pull-quote ("Market Radar analysis" attribution, no
  fabricated speaker), between exec summary and chapters.
- **`key-columns` "Competitor Scan"** — the literal answer to "x-column template for x
  competitors": top ~5 VN insurers side by side, each showing dev count + latest move +
  source, built from `MonthlyReportController.CompetitorColumn` (grouped/ranked from
  `EvidenceFact.company`, using the wide `narrativeStart` window same as chapters, since 90d
  alone was too thin — same root cause as the chapter-thinness issue above).
- **Proper `back-cover`** — replaced the old centered "Thank you" with the template's actual
  layout: top-left headline + editorial sub-line + issue/methodology meta, lens-cascade SVG
  pattern band at the bottom, matching `ReportMagazine.dc.html`'s real back-cover recipe.
- Font stays **Lora**, not Libre Caslon Text, throughout — that substitution (made much earlier
  this project, for Vietnamese diacritic support) is a deliberate, permanent deviation from the
  template; don't revert it even when copying more Meridian page forms later.

**Design system source**: the original zip Hanh shared is at
`/Users/hanh/Downloads/Meridian Review Design System.zip` (201KB, dated Jul 13). It was unzipped
into this session's scratchpad at a path under `/private/tmp/claude-501/...` which **will NOT
exist in a new session** — if you need to re-read `ReportMagazine.dc.html` or the guideline
files, re-unzip the Downloads copy first (`unzip -o "path.zip" -d <somewhere>`).

## Known remaining gaps / next candidates (roughly priority order)

- **Weekly report restyle** — not started (see START HERE #2).
- **Ops console nav cleanup** — not started (see START HERE #4); Hanh's original ask, deferred
  the whole session in favor of report content work.
- **Exec-summary/quote tuning** — the two rough edges in START HERE #3, both addressable via
  `/prompts` without code changes — good first real-world test of the new page.
- **No real authentication** — unchanged from before, still a known gap (Firebase Auth +
  Firestore-for-users plan was discussed in an earlier session, not acted on).
- **`deepseek-chat` alias deprecation** — DeepSeek flagged this alias for retirement around
  2026-07-24 (from the previous handoff) — re-check the current model name if relying on it
  past that date; unrelated to anything changed this session.

## Useful facts for whoever (human or Claude) picks this up

- This workspace is a **git repository with a deliberately dirty worktree**. Treat every
  pre-existing edit as user-owned; do not reset or discard unrelated changes.
- Plan file at `~/.claude/plans/sequential-noodling-harbor.md` covers the "major revision"
  scope (Phases 1-4) but is now **partially stale** — it doesn't mention the story-first
  round-2 fix or the editable-prompts page, both of which came from mid-session follow-up
  feedback after Phase 1-3 were already executed. Trust this HANDOFF.md over the plan file for
  "what's actually been done"; the plan file is still fine for the original Phase 3/4 intent
  (weekly restyle specifics, ops-nav-hide candidates).
- **The historical bulk-approval workflow is retired.** Never script approvals to increase
  report volume. `NEUTRAL` and `CONTRADICTED` are non-publishable; an edited claim returns to
  verification because its previous verdict is stale. Human approval must be evidence-viewed,
  attributable and limited to the current edition.
- **Provider A/B validation**: when GPT-4o-mini was first proposed as Writer, a real controlled
  comparison was run (save Claude-authored baseline text for a handful of docs, force-retry them
  under the new provider, compare) before committing — worth repeating this discipline for any
  future provider swap rather than assuming quality transfers.
- Cost reality: OpenAI `gpt-4o-mini` + DeepSeek is meaningfully cheaper than the earlier
  Claude+DeepSeek stack; no hard number re-measured this session, but no budget concerns arose
  despite a full 627-doc re-classification + 154-doc re-extraction + full re-interpretation.

## Codex follow-up — Product intelligence vertical slice (2026-07-15)

The quality audit concluded that the legacy report path is a verified-snippet pipeline, not yet
a decision-intelligence pipeline. The first Product-specific vertical slice is now implemented
alongside (not inside) the legacy monthly narrative:

- Product KIQ/editorial contract and a 20-case corpus-derived golden set:
  `docs/product-intelligence-contract.md` and `docs/evaluation/product-golden-set.json`.
- Versioned `MarketEvent` normalization (`market-event-v1`) with exact fact provenance,
  publication/source-event/occurred/effective/forecast dates kept separately, plus the extractor
  model version used for each fact.
- Versioned Product materiality rules (`product-materiality-v4`) that hard-gate full text,
  evidence-span depth, confirmed classification, duplicates and source credibility; source tier
  is intentionally not added to materiality. Award/CSR/promotion/generic banking/research-index
  and unsupported classifier-label false positives are suppressed.
- Immutable Product editions and structured insights (`product-brief-v7`), with explicit
  What / Pattern / So what / Now what / Confidence / Caveat / cited facts. Broad fallback events
  are not allowed to manufacture a trend; topically related facts may form one decision story.
- New UI and endpoints: `GET /report/product` and
  `POST /report/product/regenerate?windowDays=7|30|90|365`. Weekly/monthly reports link to it.

Validation used `/tmp/marketradar-product-test.mv.db`, copied from the real local DB; the real DB
was not mutated. The final 90-day smoke edition `PROD-20260715-871F361656` returned HTTP 200 and
reduced the initial loose run from 50 eligible facts / 4 partly incoherent clusters to 9 eligible
facts / 3 coherent, caveated insights. `market-event-v1` materialized 684 normalized events.
Standalone regression results: materiality 29 checks, event normalization all pass, synthesis all
pass; `mvn -q -DskipTests package` passes.

Important remaining work: calibrate the golden set with a Product SME and add scored precision /
recall evaluation; improve weak legacy fact summaries (the new layer can reject them but cannot
recover detail absent from extraction); then add an evidence-grounded AI synthesis pass that is
schema-constrained and evaluated against the deterministic brief. Do not route the new Product
brief through legacy `IMPLICATION` claims or bulk-approved NEUTRAL verdicts.

## Codex follow-up — remediation complete, live rerun still blocked (2026-07-15)

The implementation work for the next controlled run is complete and acceptance-tested on
`/tmp/marketradar-acceptance.mv.db`, never by running the live pipeline:

- Classification currentness is provider/model + effective prompt contract + content hash.
  The one-to-one row remains the active projection; an append-only `classification_attempts`
  ledger preserves prior/candidate snapshots. Uncertain/error reruns preserve the prior result.
- Extraction is `extract-facts-v2`, reads up to 24,000 characters and emits up to eight rich
  spans. Successful versions atomically activate new facts; error/schema/empty results preserve
  the prior edition. Reports and MarketEvent reads exclude superseded facts.
- Interpretation is `interpretation-v2`, keyed by provider/prompt signature and exact evidence-
  pack hash. New UUID editions activate atomically. Failed/empty/schema editions remain inactive
  audit rows. Stale chapter fallback is removed.
- Publication requires active claim + L1 PASS + approved human status + latest L2 ENTAILED.
  Edited claims return to verification. STUB classifier/writer/verifier calls are refused inside
  the services and by the pipeline runner.
- Product taxonomy is `product-event-taxonomy-v2`; brief synthesis is `product-brief-v8`.
  Weekly/Monthly/Quarterly use Product-edition evidence for Product-facing timelines, chapters,
  scans and exhibits, so legacy awards/CSR/noise do not leak back below the new executive page.
- Destructive force-retry paths are retired. Classification retry is single-document,
  append-only and bypasses replay reads without deleting call history. Claim retries supersede
  only after a valid replacement exists.

Validation: `mvn -q clean package` passed; every standalone `*Test.java` suite passed; current-
rules Product golden enforcement passed 20/20 and the legacy negative control failed as expected.
Copied-DB HTML returned 200 for Product, Weekly, Monthly and Quarterly; Weekly PDF returned 200
(71,000 bytes). The copied edition `PROD-20260715-2973EA3182` rendered one MEDIUM executive signal
and kept two LOW signals in the explicit “single-source watch · not a trend” block. Award/ranking
copy observed in the old competitor scan was absent after final Product-evidence filtering.

The read-only preflight is correctly **NO-GO** in the current shell: all three AI slots are STUB;
186 unique documents need refetch/exclusion (155 title-only, 31 short); full-text median is 4,585
characters, p90 11,319, and six documents exceed the 24,000-character input cap. Classification
plan: 688 legacy results are stale, 9 duplicate documents skipped. Extraction plan before that
reclassification: 133 READY_STALE, 369 NOT_CONFIRMED, 155 NEEDS_FULL_TEXT, 31 SHORT_TEXT, 9
DUPLICATE. The app is stopped. Next operator must configure three real providers, assess the
688-document classification cost, verify backup
`data/backups/marketradar-before-reprocess-20260715-234750.mv.db` (SHA-256
`f58dcc4c458ce1cda0ee9106685765a88615d7325360cd3e865db65271175d59`), obtain `ready=true`, and follow
`docs/reprocessing-runbook.md`; do not improvise a partial or destructive rerun.
