# Evidence extraction and MarketEvent architecture

## Extraction rejection funnel

`GET /extract/backfill/plan` is read-only and exposes every pre-extraction outcome:

- duplicate canonicalization loser;
- parse failure;
- empty article text;
- title-only or unverified full text;
- article below the 600-character evidence minimum;
- classification not confirmed;
- current extraction signature;
- complete but stale evidence eligible for extraction.

Every state has a stable reason code, explanation, count and sample documents. The same endpoint
reports historical extraction-attempt outcomes and chunk-coverage totals. Targeted execution repeats
the gate and returns the precise rejection reason; it cannot force an incomplete document through.

Each `FactExtractionRun` stores pipeline/model/prompt/content provenance, input size, chunks planned
and completed, facts proposed/saved, verbatim/schema/metadata rejection counts, overlap duplicates and
a compact rejection summary. `LLM_ERROR`, `SCHEMA_REJECTED` and `EMPTY_RESULT` never supersede the
previous active evidence edition.

## Long documents

Extraction version `extract-facts-v3-chunked` replaces the former 24,000-character prefix cap.
`LongDocumentChunker` creates deterministic 24,000-character windows with 1,200-character overlap,
covering every source character. Each window is extracted and exact-span gated independently, then
overlap duplicates are collapsed before one atomic evidence edition is committed. A failure in any
chunk fails the whole attempt and preserves the prior edition.

The overlap permits complete sentences near boundaries to appear in one model input. There is no
maximum chunk count and no silent tail truncation; the dry-run plan makes expected chunk volume and
zero dropped characters visible before any API call.

## Date semantics and lifecycle

Extractor v3 separates:

- publication date (`RawDoc.publishedAt`);
- occurrence/event date;
- effective date;
- expiry/end date;
- forecast horizon.

Every model-supplied ISO date must also be present in the cited verbatim span. The grounding gate
normalizes common ISO, Vietnamese, English and numeric source-date forms. An invalid or ungrounded
occurrence/forecast date is nulled and counted in the extraction-run audit; an invalid or ungrounded
effective/expiry date rejects the entire proposed fact because it could reverse lifecycle meaning.
Year-only evidence cannot ground fabricated month/day precision.

Legacy `eventDate` remains as provenance and is mapped conservatively only when explicit semantic
dates are absent. `MarketEventTemporalRules` calculates status relative to an `asOf` date:
`UPCOMING`, `ACTIVE`, `EXPIRED`, `INVALID_DATE_RANGE` or `UNDATED`. Expired and invalid-range events
are ineligible for future-looking actions. Downstream code should use
`MarketEventReadService.futureActionCandidates(...)`, not rebuild date logic.

## Clustering and provenance

Fact-level `MarketEvent` rows remain immutable evidence adapters. `MarketEventClusterService` groups
them conservatively by normalized company, product, event type, geography and month. Cross-document
grouping requires both company and product; missing identity fails closed to a document-local cluster.

Every cluster records:

- evidence fact codes and source codes;
- fact and document counts;
- independent source count;
- `SINGLE_SOURCE` or `INDEPENDENT_SOURCES` provenance;
- explicit `NONE` or `DATE_CONFLICT` state.

The supported synthesis boundary is `MarketEventReadService.readForSynthesis(...)`, returning flat
`MarketEventIntelligenceView` records with evidence, cluster/provenance/conflict dimensions and all
temporal fields. It also exposes `sourceCode`, `sourceTier`, `pipelineVersion` and `modelVersion`
directly, so publication gates can persist the exact cited source/version set without reconstructing
it through lazy entity relationships. Product synthesis must group by `clusterKey`; independent
source count, not fact count, determines corroboration.

## Targeted shallow-document recovery

`GET /pipeline/refetch/plan.json` is a network-free, write-free plan for documents whose full text is
unverified or below 600 characters. It returns at most 25 deterministic candidates with source, URL,
reason, current length, host-policy decision and a full rejection funnel. Optional `rawDocIds`
produce the same read-only assessment for a requested subset.

Mutation is a separate operation:

`POST /pipeline/refetch/execute.json?rawDocIds=12,34&confirm=true`

The legacy broad stage `/pipeline/run/refetch-fulltext` is retired and no longer appears in the
runner's accepted stages. Its service entry point fails with guidance instead of mutating the corpus.

Execution requires one to 25 explicit IDs and rechecks every eligibility rule. Fetches still pass
through `SafeFetcher` and may target only the source's exact host or a source-specific article-host
override already declared by ingestion. The prior text/hash remains unchanged after host rejection,
network or parse failure, text below 600 characters, or duplicate content. Every confirmed attempt,
including missing or ineligible IDs, creates an append-only `targeted_refetch_attempts` audit row.

## Schema migration

Hibernate `ddl-auto=update` adds extraction-run funnel columns, semantic date columns, the
`market_event_clusters` table and nullable `market_events.cluster_id`. Existing facts and event rows
are preserved. It also adds `targeted_refetch_attempts`; this table is empty until a confirmed
explicit-ID run. Back up and validate a copied H2 database before the one controlled reprocessing run.
