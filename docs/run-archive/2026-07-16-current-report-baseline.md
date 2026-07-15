# Current report quality baseline — 2026-07-16

This is the frozen baseline before the second Product-intelligence remediation. The
application was stopped and the database was backed up before these read-only counts
were taken from a disposable copy.

Backup:

- `data/backups/marketradar-before-reprocess-20260716-001315.mv.db`
- SHA-256: `983573bcb3a23420624478152f9d444a7db684ad8cd77c9a7a7316f4049a5f74`

## Corpus funnel

| Stage | Count | Finding |
|---|---:|---|
| Raw documents | 697 | Starting corpus |
| Duplicates | 9 | Must remain excluded from synthesis |
| Title-only / no full text | 155 | Cannot support evidence or insights |
| Short full text (<600 characters) | 31 | Cannot support evidence or insights |
| Evidence-ready documents | 511 | Full text, parse OK, at least 600 characters |
| Classifications | 689 | All 689 have legacy/null version signatures |
| Confirmed classifications | 157 | 531 were out of scope; 1 needs review |
| Documents with extracted facts | 151 | 684 active evidence facts |
| Normalized event rows | 684 | One row per fact; no real event clustering yet |
| Product-eligible signals (90 days) | 9 | Severe funnel collapse |
| Product insights | 3 | 1 medium-confidence, 2 low-confidence |

Full-text length median was 4,573 characters, p90 11,315, maximum 47,223. Six
documents exceeded the 24,000-character extractor input cap and were therefore at
risk of silent evidence loss.

## Observed report defects

- Monthly executive summary mixed Product, HR and Marketing recommendations.
- Monthly regulation included promotions, CASA eligibility and application-period
  changes with no direct product-regulatory consequence.
- July output included 2025 and early-2026 items without a continuing-relevance test.
- Unrelated facts were concatenated into paragraphs with no relationship or thesis.
- Recommendations were sometimes addressed to competitor departments rather than
  our Product team.
- Weekly said there were zero verified developments while presenting a 90-day Product
  edition, creating a reporting-window contradiction.
- Quarterly contained two empty chapters although additional regional evidence appeared
  in references.
- The certificate-conversion insight was generated after its deadline/effective date but
  still recommended action before the effective date.
- All three Product insights were single-source; two were low-confidence and two disclosed
  legacy pipeline/model evidence.

## Release comparison targets

The candidate build must show the funnel with explicit rejection reasons, prevent all
listed defects, and either publish at least three decision-ready Product insights or show
an explicit `INSUFFICIENT_EVIDENCE` edition. A sparse period must never be filled with
legacy claims.
