# Classifier model comparison — Claude Sonnet 4.6 vs DeepSeek V4 Flash

Live A/B on the real crawled corpus (~184 docs from 30+ whitelisted sources),
3-way self-consistency voting, identical prompts. Files in this directory are
the raw per-doc outputs of both runs.

## Runs

| | Claude Sonnet 4.6 (2026-07-11) | DeepSeek V4 Flash (2026-07-12, after HTTP fix) |
|---|---|---|
| Docs classified | 184 | 183 |
| Failed LLM calls | 0 / ~552 | 0 / ~552 |
| CONFIRMED | 39 | 48 |
| OUT_OF_SCOPE | 145 | 134 |
| Vote-split reviews | 0 | 1 |
| Wall time | ~15.6 min | ~2.5 h (peak-hour latency) |
| Est. cost | ~$2–3 | ~$0.02 |

## Agreement

**91%** (164/180 docs matched by id across runs). Disagreement breakdown:

- 10× DeepSeek over-labels (Claude OUT_OF_SCOPE): regulator housekeeping
  (audit seminars, FSA newsletters) and partnership PR stamped as
  PRODUCT_REGULATION / DISTRIBUTION_CHANNEL. Failure direction = over-inclusion
  → noise for the human review queue, never silent loss.
- 2× DeepSeek misses (Claude CONFIRMED): borderline DISTRIBUTION_CHANNEL stories.
- 3× same verdict, different label (genuinely ambiguous docs).
- 1× minor status difference.

## Decision

Classifier stage runs on **DeepSeek V4 Flash** (`marketradar.classifier.*` +
`CLASSIFIER_API_KEY`). Writer stays Claude; verifier stays DeepSeek (Invariant #2).
~100× cheaper at the cost of ~9% over-inclusive disagreement, absorbed by
the downstream gates + human review.

**Demo warning:** DeepSeek latency is volatile (observed 8× slower than Claude
at China peak hours). Never classify live in a demo — data + replay cache are
persisted in the file-backed H2 DB (`./data/`), so a demo re-run replays with
zero API calls. Back up = copy the `data/` directory.

## Reproduce

```
export CLASSIFIER_API_KEY=<deepseek key>  VERIFIER_API_KEY=<deepseek key>  ANTHROPIC_API_KEY=<claude key>
java -jar target/market-radar-0.1.0-MVP.jar \
  --marketradar.classifier.base-url=https://api.deepseek.com \
  --marketradar.classifier.model=deepseek-chat
curl -X POST localhost:8080/ingest/run && curl -X POST localhost:8080/classify/run
```
