# Market-intelligence practice benchmark

Reviewed 2026-07-16. Vendor pages are used to understand current product practice,
not as independent proof of performance.

## What established platforms separate

Current platforms consistently treat intelligence as more than collection:

- [Klue](https://klue.com/competitive-intelligence-software) describes a lifecycle of
  collect, analyze, create, distribute and measure. It distinguishes the monitored
  feed from curated, audience-specific outputs such as profiles, comparisons,
  executive summaries and battlecards.
- [AlphaSense](https://www.alpha-sense.com/solutions/market-intelligence-platform/)
  combines broad search/monitoring with repeatable research grids and exact-snippet
  citations. Its comparison workflows organize the same questions across many
  documents rather than summarizing each document independently.
- [Crayon](https://www.crayon.co/product/organize) organizes signals with labels and
  tracks competitive changes over time; the useful unit is a changing subject or
  competitor, not an isolated article.
- [Contify](https://www.contify.com/case-study/analytics-company-improves-bid-management-with-real-time-intelligence/)
  describes a maintained domain taxonomy, strategic-signal feed and analyst workflow
  that turns monitored items into executive briefings.
- [Meltwater](https://www.meltwater.com/en/capabilities/media-intelligence) combines
  monitoring, historical trends, benchmarking and audience-specific reporting in one
  workflow. Collection supplies the evidence; a later layer supplies context.

The common design pattern is therefore:

```text
collect broadly -> normalize and organize -> compare over time/entities
-> analyst/AI synthesis -> audience-specific product -> measure usefulness
```

## Analytic tradecraft adopted for Market Radar

Although written for government analysis, the public
[ODNI analytic standards](https://www.dni.gov/files/documents/ICD/ICD-203.pdf) provide
useful quality disciplines for decision intelligence: describe source quality,
explain uncertainty, distinguish evidence from assumptions and judgments, consider
alternatives, demonstrate customer relevance, and address implications.

Market Radar implements the commercial and tradecraft patterns as follows:

| Practice | Market Radar requirement |
|---|---|
| Broad monitored corpus | One shared crawl/evidence base for all departments |
| Maintained taxonomy | Versioned canonical MarketEvent and DepartmentProfile |
| Exact traceability | Every factual sentence resolves to exact evidence spans |
| Comparison grid | Product features, segment, economics, channel and dates are structured fields |
| Signal over time | Facts cluster into events; events cluster into themes and patterns |
| Uncertainty | Confidence is separate from materiality and states evidence gaps |
| Evidence vs judgment | `what` is sourced; `soWhat` and `nowWhat` are labelled analysis |
| Audience curation | Product KIQs and actions differ from Distribution or IT lenses |
| Human workflow | Weak/conflicting items are watch/review, not polished as conclusions |
| Honest sparsity | Insufficient evidence produces an explicit status, never filler |

## Scope decision for the hackathon

The demo will not copy the breadth of enterprise platforms. It will prove the core
architecture with one Product lens:

1. broad shared crawl;
2. evidence-preserving event normalization;
3. cross-document comparison;
4. grounded Product synthesis;
5. immutable audience-specific editions;
6. measurable publication gates.

Distribution and IT should later reuse documents, evidence and MarketEvents while
adding their own KIQs, materiality weights and output contract.
