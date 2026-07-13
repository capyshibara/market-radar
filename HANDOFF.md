# Market Radar — Engineering Handoff (2026-07-13 night session)

This is a **technical/operational** handoff for continuing engineering work on
market-radar. For the business pitch / demo script / product framing, see
`../handoff-hackathon-presentation.md` (one directory up) — that doc is now
partially stale on a few points this session fixed (see "What changed
tonight" below); this file is the up-to-date technical picture.

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
AI#3) = Claude Sonnet, via the `ANTHROPIC_API_KEY` fallback (no
`--marketradar.llm.base-url` flag = falls through to native Anthropic).
Classifier (AI#1, highest call volume) = DeepSeek. Verifier (Gate L2) =
DeepSeek (already the default in `application.yml`, no flag needed). Confirm
the boot log shows all three lines:
```
LLM MODE (WRITER): ANTHROPIC (model=claude-sonnet-4-6)
CLASSIFIER MODE: OPENAI_COMPAT (base-url=https://api.deepseek.com, model=deepseek-chat)
VERIFIER MODE: OPENAI_COMPAT (base-url=https://api.deepseek.com, model=deepseek-chat) — khác họ với writer ANTHROPIC
```

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
**Classification does not have an equivalent yet** — a doc stuck in
`UNCERTAIN_REVIEW`/`NO_LABEL_REVIEW` still needs manual SQL to retry. This is
the natural next thing to build if it comes up again (mirror the same
pattern: delete the `Classification` row + its `CLASSIFY`-purpose
`LlmCallLog` entries for that doc, add a button on `/classifications`).

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

## Known remaining gaps (roughly priority order)

- **Classification has no Force Retry equivalent** (see table above).
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
  $1.50 for a full run over the current ~200-document corpus.
