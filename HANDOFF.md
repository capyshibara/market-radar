# Market Radar — Engineering Handoff (updated 2026-07-14 afternoon/evening session)

This is a **technical/operational** handoff for continuing engineering work on
market-radar. For the business pitch / demo script / product framing, see
`../handoff-hackathon-presentation.md` (one directory up) — that doc is now
partially stale on a few points this session fixed (see "What changed
tonight" below); this file is the up-to-date technical picture.

## ⏭️ START HERE — what Hanh wants next (2026-07-14 evening ask)

Hanh asked for a fresh session to pick up exactly these four items, in her
own words (lightly trimmed), each with what I already know that'll save you
investigation time:

**1. Failed/blocked data sources — need a fix, manual or automatic.**
18 sources are `active=false` in `source_registry` (confirmed blocked/dead
after real investigation, not guesses — see `SeedData.java` per-source dated
comments for exactly what was tried): `AIA_SG, AIR, BNM_MY, BVNT, CATHAY_TW,
HANWHA_GLOBAL, IC_PH, INS_BIZ_ASIA, MANULIFE_VN, MAP_LIFE, MAS_SG,
MCKINSEY_INS, MSAD, OJK_ID, PHILAM_PH, SUNLIFE_VN, THAILIFE_TH, TNCK_VN`.
Separately, 4 more sources are `active=true` but were investigated-and-parked
as *weak*, not blocked: `PINGAN_MEDIA` (domain blocked by the Claude Browser
tool's own policy, never actually tested against a plain curl/headless
fetch — worth retrying with a different tool), `CBIRC_NEWS` (no dateless-safe
parse path found), `LIMRA` (real search API found but needs a session
cookie/CSRF the stateless crawler can't establish), `INS_ASIA_NEWS` (listing
has no date field, would need per-article detail fetches). **Policy from
earlier this project: never spoof a browser User-Agent or weaken TLS/security
settings to force a blocked source through — verify the block is real
(A/B test UA if unsure) and if so, deactivate with a documented reason
instead of working around it.** A `/pipeline/history` page now exists
(see below) — its per-document trail will show you exactly which docs from
which source are failing at which stage, which should make prioritizing this
easier than guessing.

**2. Ops UI — sidebar/menus are hard to understand, needs a UI pass.**
No specific investigation done yet this session; go in cold. The ops console
has 4 role personas (Checker/Maker/Compliance Admin/Auditor, switchable via
the account picker at `/ops/login` or the footer switcher) with different
sidebar visibility per role (`ops-pipeline-guard.js` hides Pipeline
Config entirely for Checker/Maker). Sidebar structure lives in
`fragments/ops-sidebar.html`; groups are "Needs action", "Audit trail",
"Pipeline config", "Reference". Worth asking Hanh specifically *what* is
confusing (nav labels? too many pages? unclear what each page is for?
role-switching UX?) before redesigning — this is a vague ask and deserves
clarifying questions, not assumptions.

**3. Weekly report** (`weekly-report.html`, `ReportController`):
   - **Fonts**: body/headline font is `'Libre Caslon Text', Georgia, serif`,
     used **italic** throughout (`.masthead .sub`, `.section-insight`,
     `.market-note`, `.pull-quote .txt`, `.fact-quote .txt`, `.orig-span` —
     grep `font-family` in the file, ~10 hits). Libre Caslon Text is a
     Latin-only display serif with poor/no Vietnamese diacritic coverage —
     this is almost certainly why it reads badly for Vietnamese text. Needs
     a serif (or non-serif, ask Hanh) with proper Vietnamese glyph support
     (e.g. a Google Fonts serif with Vietnamese subset — check availability
     before picking one) and probably **drop most of the italic** (or keep
     it only for genuinely short accent text, not body paragraphs).
   - **Off-topic "insurance" news that's actually just banking news**: not
     yet root-caused this session. Hypothesis to check first: `FactExtractionJob`
     tags every doc's market as `VN` vs `REGIONAL` (see `market()` static
     method) purely by host/language, with **no topic relevance filter** —
     so a bank-only source like Thời báo Ngân hàng (which *is* in the
     registry, tier 2, general banking news, not insurance-specific) will
     surface anything it publishes, including pure-banking stories with no
     insurance angle. Confirmed today's pipeline run: several
     `Thời báo Ngân hàng` claims in the live report are about loan
     collateral rules / AML circulars / FX transaction bans — banking
     regulation with no explicit life-insurance connection, riding along
     because the *source* is whitelisted, not because the *classifier*
     judged the specific article insurance-relevant. Check whether AI#1
     (classifier, `TopicClassifier`) is actually scoring topic relevance per
     doc or just confirming "is this from a whitelisted source" — if it's
     the latter, that's the bug, and the fix is a real relevance check in
     the classify prompt/labels, not a source-level fix (the source *does*
     publish real insurance-adjacent news sometimes, so deactivating it
     isn't right either).

**4. Monthly report** (`monthly-report.html`, `MonthlyReportController`,
   design system "Meridian Review" per the file's own header comment —
   handed off from an earlier separate Claude Code session, template
   reference lives only as images/PDF Hanh shared in **that** chat, not
   as a file in this repo — **you don't have it; ask Hanh to re-share it
   if you need the visual reference**):
   - **Fonts**: same Libre Caslon Text Vietnamese-readability problem as
     the weekly report — grep `font-family`/`.caslon` in the file (~8 hits,
     including inline SVG `<text>` labels which use Work Sans already —
     just the display/serif type needs replacing).
   - **Layout doesn't use the template's full visual system** — no specifics
     gathered this session; ask Hanh what elements from the original
     template are missing (probably needs the reference images re-shared
     to compare against).
   - **Content cut short / 13 pages / section 3 has titles but no stories
     under them — root cause found, not yet fixed**: two compounding bugs
     in `MonthlyReportController.monthly()`:
     1. `ReportWindow.monthlyStart(today)` = **first day of the current
        calendar month** (`today.withDayOfMonth(1)`) — run on 2026-07-14,
        that's a 14-day window (Jul 1–14). Most real articles' actual
        publish dates don't fall inside a random 2-week slice, so most
        otherwise-good content gets filtered out before it even reaches the
        `.limit(6)` cap per section.
     2. Every section (`section()` helper, called 3×) hard-caps to
        `.limit(6)` stories with **no fallback UI when a section gets 0-2
        stories** — the template likely assumes ~6 and lays out fixed
        slots, so a section with fewer stories either overflows blank space
        or (per Hanh's report) shows the title/header with nothing
        underneath. Fix needs both: (a) reconsider whether "monthly" should
        mean *docs ingested this run* / *rolling 30 days* / *calendar
        month-to-date* — calendar month-to-date is almost certainly wrong
        for a demo run that ingested a backlog in one shot — and (b) make
        the template gracefully handle 0/1/2/3-story sections instead of
        assuming a fixed 6.
   - **Missing a "References" page before the Exhibit/chart pages** — add one.
   - **Last page should read as a "Thank you" page, not a compliance
     slogan** — the back-cover page already exists (`monthly-report.html`
     lines ~285-300, `data-content-type="back-cover"`), it just currently
     shows the tagline *"Không nhận định nào thiếu nguồn." / "No statement
     without a source."* as its headline (line 294) — that's a methodology
     slogan, not a closing/thank-you message, and Hanh specifically doesn't
     want that phrasing there. Swap the headline text (and probably the
     sub-line below it) for an actual thank-you message; keep the page
     structure/background/pattern as-is unless the re-shared template
     reference suggests otherwise.

## How to run it right now

```bash
cd /Users/hanh/Downloads/workspace/hackathon/market-radar

export ANTHROPIC_API_KEY=<Hanh's Claude key>
export CLASSIFIER_API_KEY=<Hanh's DeepSeek key>
export VERIFIER_API_KEY=<Hanh's DeepSeek key>

java -jar target/market-radar-0.1.0-MVP.jar \
  --server.port=8081 \
  --marketradar.classifier.base-url=https://api.deepseek.com \
  --marketradar.classifier.model=deepseek-chat
```

**This is the settled, cost-optimal config**: Writer (Extract AI#2 + Interpret
AI#3) = Claude, via the `ANTHROPIC_API_KEY` fallback (no
`--marketradar.llm.base-url` flag = falls through to native Anthropic).
Classifier (AI#1, highest call volume) = DeepSeek. Verifier (Gate L2) =
DeepSeek (already the default in `application.yml`, no flag needed). Confirm
the boot log shows all three lines:
```
LLM MODE (WRITER): ANTHROPIC (model=claude-haiku-4-5-20251001)
CLASSIFIER MODE: OPENAI_COMPAT (base-url=https://api.deepseek.com, model=deepseek-chat)
VERIFIER MODE: OPENAI_COMPAT (base-url=https://api.deepseek.com, model=deepseek-chat) — khác họ với writer ANTHROPIC
```
**Writer model changed from `claude-sonnet-4-6` to `claude-haiku-4-5-20251001`
on 2026-07-14** (`application.yml` line ~32) — Hanh's Anthropic account ran
out of credit mid-session (real error: `HTTP 400 "Your credit balance is too
low"`, not a code bug), and given tight budget, Haiku was chosen over Sonnet
for the retry since Extract/Interpret are narrow template-filling tasks with
strict JSON schemas, not creative writing — quality held up fine (199/200
docs interpreted cleanly, only 3 genuine schema rejects). If quality becomes
an issue, swap back to a Sonnet model id in that one config line + restart;
no other code changes needed. Minimum Anthropic top-up recommended: $5.

Data lives in `./data/marketradar.mv.db` (file-backed H2, persists across
restarts — **not** committed to git, see `.gitignore`). Ops console:
`http://localhost:8081/ops/login` → pick a role → sidebar has everything
(Pipeline Runner, LLM Settings, Source Registry, Review Queue, reports).

## ⚠️ The one rule that caused most of tonight's confusion

**Never rebuild the jar (`mvn package`) while the app is running, without
restarting it afterward.** A long-lived process whose underlying jar file
gets overwritten on disk develops silent corruption — symptoms observed
tonight: static CSS files hang forever with no response, H2 `AUTO_SERVER`
connections throw `NoClassDefFoundError`, while normal dynamic pages keep
working fine (which makes it confusing — it *looks* like only one small
thing is broken, but the whole process is quietly rotting). **The fix is
always: stop it (Ctrl+C in Hanh's terminal) and start it again** with the
command above. This is not something I can do for Hanh — it's her terminal.

Rule of thumb for future-me: any time I say "I rebuilt/fixed X," the next
sentence must be "please restart before testing."

**New failure mode found 2026-07-14 (different from the `NoClassDefFoundError`
above, easy to confuse the two):** if a boot fails with
`org.h2.jdbc.JdbcSQLNonTransientConnectionException: Connection is broken:
"java.net.ConnectException: Connection refused: localhost:XXXXX"` followed by
`Unable to determine Dialect without JDBC metadata` — that's a **stale H2
lock file**, not data loss and not the jar-corruption bug. It happens if an
`AUTO_SERVER=TRUE` H2 shell (see "inspect the live DB" section below) was
used to query the live DB and that shell process exited without cleanly
releasing the lock — `data/marketradar.lock.db` is left pointing at a
now-dead port. **Fix: `rm data/marketradar.lock.db` (never delete
`.mv.db`!), then start the app normally.** The lock file only tracks a port
registration; the actual data lives entirely in `.mv.db` and is untouched.

## Pipeline stages, providers, and "is it really done" semantics

| Stage | Provider (current) | Skip-if-already-done check | Retries a technical failure automatically? |
|---|---|---|---|
| 1. Ingest | none (deterministic fetch + SHA-256 hash dedup) | URL exists + `fullTextFetched=true` | N/A — designed to re-run repeatedly for new content |
| 2. Dedup + Classify (AI#1) | DeepSeek | `Classification` row exists for the doc | ❌ No — a bad/uncertain result is permanent unless the row is manually deleted |
| 3. Extract evidence (AI#2) | Claude (Writer) | `EvidenceFact` rows actually exist for the doc | ✅ Yes — if zero facts were saved, it naturally retries next run |
| 4. Interpret + Gate L1 (AI#3) | Claude (Writer) | *any* `InterpretedClaim` exists for the doc, **including a failed one** | ❌ No — this is the gap "Force Retry" (see below) now closes |
| 5. Verify — Gate L2 | DeepSeek | claim's `reviewStatus` has left `PENDING_VERIFICATION` | Intentionally permanent — verification is a one-time audit decision |

**Force Retry** (new tonight, `/claims` page): a button next to any
`SCHEMA_REJECTED` claim that deletes that claim row + its cached LLM
response, so the next Interpret run genuinely retries the doc instead of
being silently skipped forever. Endpoint: `POST /claims/force-retry/{rawDocId}`.
**Classification now has the equivalent too** (added after this handoff was
first written): a doc stuck in `UNCERTAIN_REVIEW`/`NO_LABEL_REVIEW` gets a
Force Retry button on `/classifications`, endpoint
`POST /classify/force-retry/{rawDocId}`, same delete-row-plus-cache pattern.

## What changed tonight (chronological, so root causes are traceable)

1. **Full-article fetch fix** (`dd6a9ee`) — the backfill logic that upgrades
   headline-only docs to full article text was checking "does this URL
   already exist" instead of "do we already have full text for it" — meant
   it could never actually backfill anything. Fixed with a new
   `fullTextFetched` flag on `RawDoc`.
2. **11 dead source URLs repaired, 11 permanently retired** (`d97d31a`) — see
   `SeedData.java` comments dated 2026-07-14 for exactly which and why
   (bot-blocked WAFs, broken TLS certs, genuinely dead pages — all
   documented per-source, not silently dropped).
3. **Gemini abandoned as Writer** — Google now requires a ~$12 minimum
   prepayment even for light use; switched back to Claude, which is
   actually cheap here since Writer is low-volume (~35-45 calls/run) —
   Classifier was always the expensive stage and that's on DeepSeek.
4. **Pipeline Runner UX overhaul** (`28e837a`) — jobs run on a background
   executor now (`PipelineRunStatusService`), so clicking Run returns
   instantly instead of blocking the page for minutes; live
   RUNNING/SUCCESS/FAILED polling; per-stage LLM provider labels.
5. **`/llm-settings` page + boot-time symmetry** (`28e837a`, `34789e9`) — any
   of Classifier/Writer/Verifier can be set to Anthropic or any
   OpenAI-compatible endpoint, at runtime (no restart) via the page, or at
   boot via env vars (`CLASSIFIER_API_KEY`/`marketradar.classifier.anthropic-model`,
   same pattern for verifier). Only rule enforced: Writer's model family ≠
   Verifier's model family (`Invariant2.assertDifferentFamily`) — tested to
   correctly refuse booting when violated.
6. **`max-tokens` bug** (`945271c`) — was 1024, tuned for the old
   single-sentence interpreter task; the newer Extract stage (up to 5
   structured facts/doc) routinely got cut off mid-JSON. Raised to 4096.
7. **JSON quote-escaping bug** (`0157b46`, `4ce1521`) — source documents
   (Vietnamese insurance/legal text) often wrap terms in "quotation marks",
   and the model — despite explicit prompt instructions — inconsistently
   forgot to escape them as `\"` in JSON output (confirmed: escaped
   correctly in `text_en` but not `text_vi` for the identical term, same
   response). Prompt fix alone wasn't reliable enough, so added
   `JsonRepair.repairUnescapedQuotes()` as a code-level fallback (only
   triggers when strict parsing fails first). The tricky part: Vietnamese
   sentences often have a quoted phrase immediately followed by a comma
   (`"PNJ", cho thấy...`), which looks identical to real JSON structure
   (`"value", "nextKey"`) — resolved by checking one token further (a comma
   only counts as a real string-closer if a quote follows it).
8. **Claim/fact code generation bug** (`505eab7`) — `nextCode()` used
   `count()+1`, which breaks the moment any row is deleted (exactly what
   manual cache-clearing does) — the next insert collides with an
   already-used code. Fixed to compute from the actual max code in use.
9. **Force Retry** (`1006114`) — see above.

## What changed 2026-07-14 afternoon/evening (full reseed + observability session)

1. **Full reseed executed** — DB wiped (`data/*.db` deleted) and reseeded
   clean to pick up all the source-resolution work from the marathon session
   earlier that day (60 sources, 42 active). Uncovered a real bug during
   reseed: `Source.fetchUrl` had no `@Column(length=...)`, defaulting to
   Hibernate's 255-char cap — `MUNICHRE`'s AEM search URL (416 chars) blew
   past it and crashed the seed insert. Fixed by widening to `length = 1000`
   (`Source.java`) — a general fix, not MUNICHRE-specific, since other
   long-query-string sources could hit the same wall later.
2. **Ran the full 5-stage pipeline end-to-end on the fresh DB** — Ingest
   (627 docs, 0 rejected across all 42 active sources) → Classify → Extract
   → Interpret → Verify. Hit the Anthropic credit exhaustion mid-run (see
   "How to run it right now" above) — Extract/Interpret both 100%
   `LLM_ERROR`'d the first time; diagnosed via the literal `(LLM_ERROR)`
   placeholder text stored in the claim rows (proof it's an infra failure,
   not a JSON/schema bug), fixed by topping up + switching Writer to Haiku,
   re-ran Extract (183 docs, 424 facts) and Interpret (199/200 docs clean)
   successfully. Verify then processed 475 claims → 315 auto-approved.
   **Force Retry was used at scale for the first time**: 42 doc-level +
   1 exec-summary claim stuck at `SCHEMA_REJECTED` from the failed run were
   cleared via `POST /claims/force-retry/{rawDocId}` +
   `POST /claims/force-retry-exec-summary` before the successful re-run
   (otherwise Interpret's `existsByRawDocAndOrigin` guard would have
   silently skipped them forever — same class of bug the Force Retry
   feature was built to close).
3. **Pipeline progress bars** (Hanh: "very very helpful") —
   `PipelineRunStatusService` now tracks live `completed`/`total` per
   running stage (`startProgress`/`stepProgress`, called from inside each
   Job's loop in `IngestionJob`/`ClassificationJob`/`FactExtractionJob`/
   `InterpretationJob`/`VerificationJob`), exposed via `/pipeline/status.json`
   and rendered as a real percentage + fill bar on `/pipeline`
   (`pipeline.html`'s poll() JS).
4. **Durable pipeline observability — new tables + new page** (closes the
   actual root cause of "hard to see what's blocked where," which used to
   only live in an ephemeral in-memory `StringBuilder` per run, gone on the
   next run or a restart):
   - `PipelineRunLog` (new entity/table `pipeline_run_log`) — one row per
     stage-click, with a `batchId`: every Ingest click opens a new batch,
     every other stage-run gets folded into the batch of the most recent
     Ingest. `PipelineRunStatusService.trigger()` now creates/finishes these
     rows around the existing in-memory tracking.
   - `PipelineItemLog` (new entity/table `pipeline_item_log`) — one row per
     item outcome (source for Ingest, doc for Classify/Extract/Interpret,
     claim for Verify) per run, with status + message, durable. Wired into
     all 5 Job classes.
   - New page **`/pipeline/history`** (`PipelineHistoryController` +
     `pipeline-history.html`, linked from the sidebar under Pipeline
     Config): batch list up top, per-document trail table below (one row
     per doc, one column per stage, Excel-style dropdown filters
     auto-populated from whatever values are actually present, plus a title
     search box). Ran into and fixed a `LazyInitializationException` here
     (this app has `spring.jpa.open-in-view: false` — an explicit existing
     setting — so lazy `doc.getSource()` access outside a transaction 500s;
     fixed with a `join fetch` query, `RawDocRepository.findAllWithSource()`,
     matching the pattern already used elsewhere in this codebase like
     `ClassificationRepository.findAllForDisplay()`).
5. **Reviewer Queue filter bar** (`review-queue.html`) — client-side dropdown
   filters (Gate status / Verdict / Risk tier / Type) with a live "shown X/Y"
   count, since 221 unfiltered claims was "overwhelming" per Hanh. Pure
   `data-*` attribute + JS show/hide, no backend change.

**All of the above is on top of the existing DB, not a fresh reseed** — do
**not** reseed again to pick up these code changes; they're additive schema
updates (`ddl-auto: update`) that apply cleanly to the live `.mv.db`.

## Known remaining gaps (roughly priority order)

*(Hanh's 4 explicit next-session priorities are in "START HERE" at the top of
this file, not repeated here — this list is longer-tail/lower-priority items.)*

- ~~**Classification has no Force Retry equivalent**~~ — closed 2026-07-13: mirrors
  `ClaimController#forceRetry` exactly. `POST /classify/force-retry/{rawDocId}`
  (`ClassificationController`), guarded on `UNCERTAIN_REVIEW`/`NO_LABEL_REVIEW`, deletes
  the `Classification` row (`ClassificationRepository.deleteByRawDocId`, new) + its
  `CLASSIFY`-purpose `LlmCallLog` entries (reused the existing generic
  `deleteByPurposeAndRawDocId`). Button added to `/classifications` next to the status
  badge, same visibility rule as the claims page.
- **No mobile pass on the ops console** (by design — desk-bound tool).
- **Maker→Checker handoff isn't real** — Maker's edit still self-approves
  server-side.
- **No real authentication** — role picker is client-side only. Hanh asked
  about Firebase Auth + Firestore-for-users-only (keep the existing
  relational data layer as-is) as the plan when this gets tackled — see
  conversation history for the cost reasoning (Cloud SQL has no free tier;
  Firestore genuinely does, no card required).
- **`deepseek-chat` alias deprecation** — DeepSeek flagged this alias for
  retirement around 2026-07-24; re-check the current model name before
  relying on it much past that date.
- Only 2 of the "known gaps" from the original business handoff remain
  meaningfully true after tonight — worth re-reading that doc's §4 and
  pruning what's now fixed (seed URLs, verifier placeholder, and the
  "only 2 evidence facts" gap are all closed).

## Useful facts for whoever (human or Claude) picks this up

- Repo: `capyshibara/market-radar`, all work pushed to `main` through
  commit `1006114` as of this handoff.
- To inspect the live DB without risking corruption: **copy the `.mv.db`
  file to `/tmp` first**, then run a plain (non-`AUTO_SERVER`) H2 shell
  against the copy. Only use `AUTO_SERVER=TRUE` against the *live* file
  when you need to, and expect it to occasionally fail with
  `NoClassDefFoundError` if the process has survived a jar rebuild since
  it booted (see the ⚠️ rule above) — if that happens, it's not a data
  problem, it just means the app needs a restart.
- `docs/run-archive/` has the archived Claude-vs-DeepSeek classifier A/B
  test (91% agreement, ~100x cheaper) — the reasoning behind the current
  provider split is fully documented there, not just asserted.
- Cost reality check per full pipeline run at current settings: Classifier
  (DeepSeek) ~$0.02-0.05, Writer (Claude, Extract+Interpret combined,
  ~35-45 docs) ~$0.50-1.00, Verifier (DeepSeek) ~$0.02-0.05. Total under
  $1.50 for a full run over the current ~200-document corpus. **Updated
  2026-07-14**: real corpus is now 627 docs (Extract touched 183 of them,
  Interpret 200) on Haiku, not Sonnet — expect meaningfully less than the
  Sonnet-based $0.50-1.00 estimate for the same doc count now, but this
  hasn't been precisely re-measured; ask Hanh for the actual Anthropic
  console spend if you need a hard number.
- **Habit worth keeping**: after using an `AUTO_SERVER=TRUE` H2 shell to
  inspect the live DB, remember it leaves `data/marketradar.lock.db`
  registered to that shell's port — if the app won't boot afterward with a
  `Connection refused` error, that's why (see the ⚠️ section above), not
  data corruption.
- A persistent memory file exists for this project at
  `~/.claude/projects/-Users-hanh-Downloads-workspace/memory/project_market_radar.md`
  (auto-loaded every session, not something you need to seek out) — it
  duplicates some of this file's key points (the jar-rebuild-while-running
  rule, the reseed-is-destructive rule) as a faster-loading summary; this
  HANDOFF.md remains the source of truth for anything more detailed.
