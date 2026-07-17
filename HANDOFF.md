# Market Radar — Engineering Handoff (updated 2026-07-17 quality-remediation session)

> Special Issue design source of truth: `/Users/hanh/Downloads/workspace/hackathon/CLAUDE.md`.
> The reader/export uses Libre Caslon Text + Work Sans, 1056×816 landscape pages, explicit
> `data-content-type` catalog metadata, restrained editorial accents, cover/back-cover pattern
> artwork, and sourced exhibits rather than decorative pseudo-data.

## Explained source stories / report-to-corpus trace — 2026-07-17

- **A citation code is now a reading path, not a dead end.** Every human editorial takeaway lists
  links for all of its supporting fact codes; every current-development card and every cited
  machine-signal fact also links to `/report/story/{factCode}`. The link preserves the current
  Weekly/Monthly/Quarterly context and EN/VI language so a non-specialist can move from the brief
  to the supporting story without searching the Ops corpus.
- **The new Source Story reader explains one record in layers.** It shows the extractor's stored
  EN and VI retellings side by side, the background a non-Product reader needs, why that class of
  signal can affect a Product decision, the question to ask, a bounded validation step and the
  explicit limit of a single source. Regulation, product/benefit, metric and distribution stories
  each use their own bilingual reading lens rather than generic filler.
- **A richer full-article rewrite is available on demand and cached.** When the stored fact summary
  is not enough, the reader can run one explained-rewrite job. The configured writer receives at
  most 12,000 characters of the stored article plus the cited evidence span, returns a 4–7 sentence
  plain-language retelling and jargon explanations in both EN and VI, and stores the accepted result
  once in `story_explainer` for all future readers. EN/VI language-purity checks run before storage;
  one repair attempt is allowed, and a rejected result is shown to the operator rather than saved.
  Opening or reading a story never calls the writer automatically.
- **The rewrite never replaces the source.** The exact evidence span remains verbatim and is shown
  separately from the bilingual retelling. Below it, the reader exposes the complete text stored in
  `raw_docs` and highlights that exact span inside the crawled/uploaded document. Publisher URL,
  source-registry code/tier, document ID, publication/fetch dates, intake method and source language
  remain visible. If a safe EN or VI retelling is missing, the page says so instead of inventing a
  translation.
- **The evidence boundary remains unchanged.** Story context is deterministic reading guidance;
  it does not alter the underlying fact, classification, verifier result, review status or
  publication gate. International stories are labelled as comparison material, not evidence that
  the same opportunity or rule applies in Vietnam. One Vietnam source is not presented as a
  market-wide trend.
- **Verification:** Maven packaging, `git diff --check` and seven focused Product-report/market
  regressions pass. A non-web Spring integration test opened an isolated copied H2 corpus, resolved
  real fact `F-717`, rendered the complete EN and VI Source Story templates, confirmed that the
  stored document is longer than the extracted span, and rendered the Monthly Product PDF with the
  new links. The final 20-page landscape-Letter proof was text-checked; the takeaway-link page and
  a full-story evidence card were rendered to PNG and visually inspected without clipping. The
  user's live port-8081 database, review state and pipeline were not touched. A rebuild/restart is
  sufficient; no pipeline or report regeneration is required.

## Non-expert Product report reading layer — 2026-07-17

- **Weekly, Monthly and Quarterly now teach the reader before asking for a Product decision.**
  Each English and Vietnamese edition opens with a one-minute three-part orientation: the context
  a non-specialist needs, the story connecting that edition's signals, and a concrete recommended
  next step. The copy is cadence-specific rather than generic: Weekly pairs growth with control,
  Monthly connects portfolio pruning, reusable infrastructure and a slower market, and Quarterly
  explains the broader proposition system before proposing a bounded 30/60/90-day experiment.
- **The analysis itself is easier to decode.** Every takeaway now separates “what the evidence
  says” from “why this matters”; every exhibit carries a plain-language reading and a use/caveat;
  and the decisions section explains that recommendations are prompts for validation, not automatic
  approvals. A bilingual quick glossary explains first-year premium, portfolio/SKU/API and other
  cadence-specific Product terms without pretending to replace policy or legal definitions.
- **This remains a real human-review layer.** Product Report Review exposes the context, story,
  recommendation and every glossary definition for each cadence/language. Saves persist in
  `product_report_editorial_drafts` and immediately update the web reader and PDF. Older saved
  drafts automatically inherit the new guide and glossary while retaining their existing human
  narrative, market bridge, exhibits, decisions and evidence register.
- **No evidence boundary was loosened.** The new material interprets the already locked fact-code
  register; it does not rewrite facts, alter classifier/verifier results, approve claims or bypass
  publication gates. Recommendations are explicitly framed as bounded Product work to discuss and
  test rather than facts asserted by a source.
- **Verification used only the isolated `/tmp/market-radar-reader-qa.mv.db` copy on port 8097.**
  All six EN/VI readers and both editor locales returned HTTP 200. A save test confirmed that the
  story and glossary fields persist. The final Monthly EN and VI PDFs are 18-page landscape Letter
  documents; orientation, editor-read/chart and Vietnamese glossary pages were rendered to PNG and
  visually inspected with no blank pages, clipped copy or broken diacritics. Maven packaging,
  `git diff --check` and five focused Product-report/market regression suites pass. The user's live
  port-8081 database and pipeline were not touched. Rebuild and restart are sufficient; no pipeline
  rerun or report regeneration is required.

## Product Report Exhibit Studio + Meridian canvas — 2026-07-17

- **Weekly, Monthly and Quarterly now contain a real visual-intelligence layer, not a decorative
  chart slot.** The bilingual human editorial draft stores structured exhibits and renders the
  same component in the browser and PDF. Supported forms are comparison bars, KPI strips,
  timelines, capability flows, decision matrices and 30/60/90 roadmaps. The seeded current
  briefs contain 3, 4 and 5 reviewed exhibits respectively, including Fubon growth, Swiss Re's
  premium-growth reset, PVI's Vietnam market pulse, AIA's portfolio timeline, the HIVE capability
  flow, product-concept matrices and a bounded 90-day experiment plan.
- **Product Report Review now includes an Exhibit Studio.** For each cadence and language, a human
  editor can change the exhibit type, headline, takeaway, caveat, visibility, data rows, labels,
  values, annotations, bar width and data colour. Every citation is constrained to that edition's
  locked fact-code register. Computed comparisons must remain explained in the exhibit note. This
  adds editorial control without letting a reviewer rewrite or bypass source evidence.
- **The missing report-frame artwork is restored.** The interactive Product Report canvas uses
  the supplied design system's monochrome-blue Meridian D pattern around the warm-paper report,
  at the same 1400px top-centre scale and luminosity treatment as the official ProductReport kit.
  Print keeps pattern art on covers/section dividers and not behind body copy, matching the design
  rule in `CLAUDE.md`.
- **Backward compatibility is deliberate.** Existing saved EN/VI editorial drafts automatically
  inherit the new cadence-specific exhibit set if their old JSON predates Exhibit Studio; existing
  human-written narrative is retained. No pipeline or report regeneration is needed to obtain the
  new visuals after rebuilding and restarting the app.
- **Verification used only an isolated copied H2 database on port 8098.** All six EN/VI Product
  readers, the editor and pattern asset returned HTTP 200. The rendered PDFs are landscape Letter:
  Weekly 10 pages, Monthly 15 pages and Quarterly 20 pages in the QA corpus. Monthly and Quarterly
  visual pages were rendered to PNG and visually inspected; a roadmap-label spacing issue found in
  the first proof was fixed and re-rendered. An isolated editorial POST persisted and immediately
  appeared in the reader. Maven packaging and the focused Product-report/market regression suites
  pass. The user's live port-8081 database and pipeline were not touched by this QA run.

## Vietnam / international Product intelligence split — 2026-07-17

- **The rolling Product reports are now domestic-first without becoming inward-looking.** Weekly,
  Monthly and Quarterly open with a Market Map containing three deliberately separate reads:
  Vietnam developments with direct local implications, international signals used for comparison,
  and a human-written bridge explaining what is (and is not) transferable to Vietnam. A Product
  decision question makes the purpose of the comparison explicit.
- **Source developments are visibly partitioned by market before they are grouped by topic.** The
  evidence section has separate “Vietnam market moves” and “International product signals” areas,
  visible counts, geography labels and per-record market badges. The source register carries the
  same labels, so an international example cannot silently appear to be domestic evidence. If one
  side has no eligible development, the report says so instead of filling the gap with old content.
- **Market assignment is deterministic presentation metadata.** `ProductMarketScopeClassifier`
  uses event/entity, source-code and publisher/host signals; an explicit Vietnam event wins over a
  foreign publisher headquarters or an English source language. Best-effort international
  geography is retained for comparison. This classification balances report selection and layout
  only—it does not change facts, claim review, verification or publication gates.
- **The market bridge is human-editable in both languages.** Product Report Review now exposes
  domestic read, international read, Vietnam application and Product question fields for every
  cadence/language. Older saved drafts are normalized with the new bridge without discarding their
  existing editorial content.
- **Approval audit:** before touching review state, a stopped-database backup was created at
  `data/backups/marketradar-before-pass-entailed-approval-20260717-1415.mv.db`. The live corpus has
  239 non-superseded claims whose gate is `PASS` and whose latest verifier verdict is `ENTAILED`:
  36 are `APPROVED` and 203 are `AUTO_APPROVED`; **zero are pending**. Therefore no new approval was
  written. The remaining pending `PASS` claims have Neutral, Contradicted or Verifier Error verdicts
  and were deliberately left untouched.
- **Verification:** Maven packaging and five focused regression suites pass. A temporary build
  returned HTTP 200 for all Weekly/Monthly/Quarterly EN and VI readers, all three PDF downloads and
  the Product editor. The Monthly PDF is a 13-page landscape Letter document; its Market Map was
  rendered at 144 DPI and visually checked after adding PDF-safe market-marker colors. No pipeline
  stage or report regeneration was run during this verification.

## Product report human-editorial redesign + updated design system — 2026-07-17

- **Weekly, Monthly and Quarterly now have a separate human editorial layer.** The default EN/VI
  editions are fully written from the current copied corpus by `ProductReportEditorialService`:
  a lead argument, three evidence-linked takeaways, one sourced comparison chart, three Product
  decisions and a bounded watchlist. The human layer is explicitly labelled and never changes
  fact, claim, verification or publication-gate state. Its fact codes remain traceable to the
  immutable evidence layer.
- **Admins and Product makers can edit this layer** at
  `/report/product/edit?cadence=weekly|monthly|quarterly&lang=en|vi`. English and Vietnamese are
  stored separately in `product_report_editorial_drafts`; save immediately updates the reader and
  PDF. The editor shows a locked, source-linked evidence register and does not offer controls to
  rewrite evidence. Product Report Review is available in the Ops sidebar.
- **Report hierarchy changed from rule-first to intelligence-first.** Cover and human argument
  lead; a real-data exhibit, Product implications and decisions follow; current source
  developments form the inspectable evidence section. Machine-generated insights remain clearly
  labelled as gate-passed signals. Gate logic/principles are reduced to a compact expandable
  method note near the end instead of occupying the opening screen.
- **The updated Market Radar Design System is synchronized across the application.** Global Ops
  tokens now use the latest Work Sans + Libre Caslon display system, warm Meridian surfaces,
  ink-blue actions, near-square radii and the navy navigation shell while preserving red active
  navigation and the existing semantic gate/severity colors. The standalone Newsroom/Agents page
  was retokened too. Product reports use Libre Caslon Text + Work Sans (Lora for Vietnamese), warm
  paper, hairline furniture, Meridian chart colors and three cadence-specific pattern artworks
  copied from the supplied design-system library.
- **Product PDF is now Meridian landscape letter**, with the local cover artwork inlined for the
  offline renderer, browser grids restated as reliable PDF tables, full source quotations kept in
  the interactive reader rather than bloating print, and the source register grouping fact codes
  from the same document. The method and evidence registers are still present; the PDF is shorter
  and avoids splitting the human lead headline across pages.
- **QA used only `/tmp/mr-design-qa.mv.db`, copied from the real database.** No live pipeline ran
  and no user database row was edited. The isolated app served both languages, all cadences, the
  editor and Ops pages; an editorial POST persisted into the reader. The Monthly PDF rendered as
  landscape Letter with embedded fonts and was visually inspected page-by-page. Restart/rebuild is
  required to see these code/template changes; no pipeline rerun is required.

## Showcase completion — 2026-07-17

This section supersedes the older “not yet wired”, “weekly restyle not started”, and “Ops nav
cleanup not started” notes later in this historical handoff.

- **Manual intake is now observable.** `/documents/intake` shows the most recent uploaded/imported
  documents and a human-readable processing state. Admins can open `/corpus` to inspect all stored
  documents, full text, classification, evidence facts, interpreted claims and latest verification;
  filter the 724-document corpus; export CSV; and open a detail page. The browser table paginates
  50 rows at a time. Targeted Classify and Extract actions are available on the detail page. Upload
  still does not silently spend AI budget or bypass the evidence gates.
- **The current Special Issue has a real human-editor workflow.** Admins open
  `/product/special-issues/wellness-linked-life/edit`, choose EN or VI, edit the cover, executive
  summary, three key findings, all seven chapters and the complete source register, then preview
  the reader/PDF. Drafts persist in `special_issue_drafts` by slug and language with editor and
  update time. The seeded July issue is now a fully written bilingual learning dossier covering
  the three-layer product architecture, customer mechanics, reward economics, operating model,
  benefits/evidence limits, exclusions/fairness/data boundaries and a bounded pilot decision.
- **The Special Issue now uses the supplied Meridian visual catalog, not generic report cards.**
  The contents spread adapts the design system's triskelion Symbol Series artwork and
  `toc-visual` structure. Subsequent pages use evidence-count pictograms, a three-circle product
  architecture, a five-step customer journey, a source-backed 60% reward-cap exhibit, a three-year
  premium cycle, a five-owner operating chain, a value-versus-safety balance, and a four-gate pilot
  roadmap. Saturated colors remain confined to artwork and data; all quantitative visuals state
  their public source and distinguish a stated term from an observed result. CSS fallbacks keep
  the geometric artwork visible in the offline PDF renderer, which does not draw inline SVG.
- **The 7/30/90-day Product reports are intelligence-first.** A human-curated argument,
  evidence-linked takeaways, comparison exhibit and Product decisions lead; current verified
  developments then expose the inspectable evidence. Technical gate detail is a compact evidence
  note. Weekly, monthly and quarterly web pages use the
  Meridian report system (warm paper, navy/brand blue, Libre Caslon Text + Work Sans; Lora for
  Vietnamese/multilingual evidence). All three now expose matching `.pdf` downloads.
- **Ops Console design and language pass.** Topic Lab and all operational screens consume the
  updated system-wide Ops tokens (Work Sans + IBM Plex Mono, warm canvas, navy/blue chrome and red
  active navigation), sidebar labels and all new workflows are bilingual, manual intake is the
  intended two-action experience, and the BCG-specific placeholder was removed.
- **QA evidence.** The isolated copied-database smoke test returned HTTP 200 for intake, corpus,
  Topic Lab, both editor languages, and all three Product report cadences. The Special Issue
  reader had zero overflowing pages across its 11-page stack. English and Vietnamese Special
  Issue PDFs rendered 11 landscape pages; Product PDFs now render landscape Letter with embedded
  Work Sans, Libre Caslon Text and Lora. Visual inspection caught and fixed clipped rich text, an editor
  template error, two Thymeleaf news accessors, multilingual missing glyphs and an orphaned final
  PDF page. Maven packaging, `git diff --check`, and every standalone `*Test.java` suite pass.
  The visual-enrichment follow-up re-rendered all 22 EN/VI Special Issue pages at 96 DPI and
  inspected dense pages again at 144 DPI; economics, operating-model and Vietnamese pilot
  collisions found in the first proof were corrected before handoff.

## Product Special Issues / Product Academy — 2026-07-17

- A new Product Intelligence surface is available at `/product/special-issues`. It is deliberately
  separate from the rolling 7/30/90-day Radar: a Special Issue is a curated, long-form learning
  product rather than an automatic aggregation of current news.
- The **Topic Lab** is an Ops Console queue, not a report gallery. It shows one compact row per
  candidate with Product score, evidence-pack depth, readiness, research gap and next action. A
  candidate that does not meet its research-pack requirement cannot be commissioned. All Topic
  Lab and issue content is available in English and Vietnamese via the existing locale switch.
- `/product/special-issues/wellness-linked-life` is the editorial reader; its `.pdf` companion
  renders the same source-backed teaching content through the existing local HTML-to-PDF renderer.
  The reader is a fixed 1056×816 landscape page stack derived from the supplied Meridian Review
  system and the July 2026 Market Radar PDF reference. The 11-page issue includes contents,
  executive summary, system diagram, customer journey, reward mechanics and chart, operating-model
  table, fairness/limits, pilot decisions, and a source/method appendix. Full Work Sans and Lora
  font binaries are bundled and embedded so Vietnamese works offline in both browser and PDF.
- PDF rendering strips URL-based `@font-face` rules before registering the bundled fonts. Do not
  remove this step: failed web-font declarations shadow classpath fonts in OpenHTMLtoPDF and cause
  missing Vietnamese glyphs. Print CSS also restates colors explicitly because this renderer does
  not resolve the web template's CSS custom properties reliably.
- The initial issue is a seeded public-evidence demonstration with persisted manual editorial
  drafts. Future production issues must additionally freeze their selected manual-intake/registry
  evidence pack and persist a commissioned edition before publication; do not treat Topic Lab
  scores as automatic publication. A stronger editorial LLM can structure and synthesize that
  frozen pack, but model choice must not bypass source traceability or human approval. Automatic
  Special Issue topic research/generation is not yet wired; the showcase issue is human-curated.

## Latest report-language and evidence-traceability update — 2026-07-16

This update fixes two user-visible defects that were discovered while reviewing the live Product
reports and Reviewer Queue. It is additive: it does **not** rerun the pipeline, erase the
database, change claim decisions, or lower any publication gate.

### Reviewer evidence must survive reprocessing

- A claim stores the fact codes it cited when it was created. Reprocessing can supersede those
  facts, marking them inactive while retaining them for audit. The Reviewer Detail and Claim
  Audit screens now resolve the cited codes through the audit query, which includes archived
  editions; they must never use the active-report query for a historical review.
- Archived evidence is visibly labelled, retains its original-source HTTPS link, and can still be
  reviewed. If even one cited fact is genuinely missing, the screen lists the missing code(s) and
  blocks approve, edit+approve, and force-approve in both the UI and server. Reject remains
  available. This closes a previous partial-evidence approval gap.

### Product-report bilingual rules

- **Original title and original evidence remain in the source language in both report modes.**
  They are now labelled as original-language material rather than being mistaken for a failed
  translation. This is required for traceability and exact-citation review.
- Each Current Product News card uses the extractor's Vietnamese or English summary only as a
  report-language display summary. If no safe summary exists in the selected language, the UI
  explicitly says so; it never pretends that an original quotation is translated.
- Generated Product insight fields now have a deterministic language-purity gate. Vietnamese
  fields containing substantial English prose, or English fields containing substantial Vietnamese
  prose, are rejected and the bounded writer repair path is used. Proper names and acronyms are
  allowed. The writer prompt repeats the same rule.
- Remaining user-facing report labels, confidence badges, fact-type labels and report metadata
  are locale-aware. Mode codes and pipeline identifiers remain technical audit metadata only.

**Operator effect:** restart after rebuilding to see the UI/evidence changes. Existing persisted
Product insights are immutable; run Product regeneration only when you want a new edition to
apply the language-purity gate. Do not rerun Ingest/Classify/Extract merely to change language.

## Manual evidence intake — 2026-07-16

- `/documents/intake` deliberately has only two inputs: paste one official article/PDF URL, or
  choose one PDF/TXT file. The system deterministically detects content type, extracts main text,
  and derives title, publisher, publication date and language from HTML metadata/JSON-LD,
  registered host identity, PDF metadata, filename and document text. Unknown publication dates
  remain null and therefore cannot masquerade as current evidence.
- URL import reuses `SafeFetcher`: exact HTTPS host, public-DNS/SSRF guard, no redirects,
  expected HTML/PDF content type, timeouts and a bounded body. LinkedIn/lnkd.in URLs are refused;
  the operator imports the original publisher destination instead.
- A file upload stores an internal non-clickable URN, extracted-text hash and original artifact
  SHA-256. Reports and reviewer screens do not render a fake external link. PDF CreationDate is
  not treated as publication date because it is only a file-production timestamp.
- Imported documents reuse an existing registered publisher when the host matches (for example,
  `AIA_VN`). Otherwise they receive a stable inactive tier-3 publisher source, so BCG, AIA and
  Swiss Re count as separate independent sources while BCG article/PDF variants share one source.
- The original reason the linked AIA article was not discovered automatically is coverage, not
  parsing: `AIA_VN` scanned the `su-kien-noi-bat` listing, while this document lives under the
  separate `/truyen-thong/thong-bao/` section. BCG had no recurring source/parser registered.
- Manual intake is **not** a publication shortcut. A new unregistered publisher defaults to an
  inactive tier-3 source and every imported document must pass the ordinary Classify → Extract →
  review/verification/publication path.
- PDF extraction uses the existing PDFBox safeguards: encrypted and image-only PDFs fail loudly;
  files are limited to 10 MB, PDFs to 100 pages, and input text to 250,000 characters. Long text
  is then processed by the existing overlapping chunker—24,000 characters per chunk with overlap—
  rather than silently truncated to one LLM request.
- Do **not** crawl LinkedIn Showcase pages or public posts. LinkedIn’s terms prohibit scraping
  and its official Posts API is limited to organizations for which the authenticated user has the
  necessary role. Treat a LinkedIn post as discovery only; import the linked original publisher
  page/PDF separately after confirming internal rights. A direct public BCG PDF is suitable for
  the URL-import route, which keeps its BCG URL as the provenance link.


## Read-only batch source audit — 2026-07-16

- The Source Registry now has **Batch-audit research candidates**. Operators can paste up to 100
  HTTPS URLs, or rows copied from a Markdown research table. It extracts one URL per row,
  deduplicates candidates and safely tests each through `SafeFetcher`—the same HTTPS, exact-host,
  DNS/SSRF, no-redirect, timeout, body-size and content-type controls used by ingestion.
- It is intentionally **read-only**: auditing never creates, activates or edits a source. It also
  identifies an exact existing URL or an existing host so operators do not create duplicates.
- The recommendation is purpose-specific: a reachable RSS/Atom feed is a recurring-source
  candidate; reachable HTML/JSON requires a dedicated listing parser; and a reachable direct PDF
  is routed to **Add document**, because it is a point-in-time research artifact rather than a
  recurring crawler configuration. Rejected URLs expose the safe fetch reason for correction.
- The tool is a research triage aid, not a claim-quality or publisher-trust decision. Review the
  source scope, terms, tier and parser before using the existing **Add source** workflow.

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
   insight tier. These cards retain the original title and exact verbatim evidence span, plus an
   extractor-provided summary only when it is safe in the chosen report language; they never use
   an LLM translation, recommendation or trend claim. Admission requires an active, confirmed,
   full-text, non-duplicate tier 1–3 document
   in the exact cadence window, an 80+ character span that occurs verbatim in the source, a
   Product-relevant label, and life-scope/no-claims-only checks. This restores useful current
   coverage when the decision layer is sparse without reviving legacy unverified prose.
8. The unified Product surfaces now use an **editorial intelligence layout** rather than an
   audit-log layout. A deterministic executive layer counts verified developments, independent
   sources and Product priority areas; groups evidence into regulation, product/benefit, market
   metric and distribution lenses; and turns the leading lenses into 48-hour/30-day validation
   actions. These reading frames never create market claims. The publication gate remains
   authoritative, while its technical `INSUFFICIENT_EVIDENCE` detail is moved into a compact
   expandable trust note instead of dominating the report. Weekly, monthly, quarterly, Product
   and email surfaces share the same magazine-style template and source register.

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
