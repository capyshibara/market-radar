# Vietnam acquisition saturation audit — 5 August 2026

## Executive conclusion

This audit covers **acquisition only**: source discovery, safe fetching, metadata/date
integrity, full-text extraction and raw-corpus fitness. It deliberately does not use
classifier, verifier or report-generation quality to make the acquisition result look better.

The recurring public-source layer has reached the practical saturation gate for the current
Vietnam life-insurance scope:

- 111 registry rows exist; **70 are operational**, comprising 53 Tier 1, 16 Tier 2 and one
  Tier 3 channel. Inactive rows remain visible for audit and are not counted as coverage.
- **69/70 operational channels produced data (98.6%)** in the isolated clean corpus. The sole
  zero-document channel is `MB_LIFE_FINANCIALS`: its listing is public, but the origin returns
  HTTP 423 to server clients and the bounded Reader transport receives the same 423 response.
- The source map has practical coverage of **19/19 incumbent Vietnam life-insurer legal
  entities**. LP Life is represented by its official Ministry of Finance licence, but does not
  yet expose a stable corporate publishing endpoint.
- The clean corpus contains **766 documents**. 672 (87.7%) have usable full text of at least
  600 characters; 726 (94.8%) have a publication date; median body length is 3,490 characters,
  P90 is 12,764 and the maximum is 118,513.
- For H1 2026, the corpus contains **243 dated documents from 55 channels**; 227 (93.4%) have
  usable full text. For the latest 90-day window, 175/182 (96.2%) are usable full text.
- Against the 42 information cells required by the supplied CFO presentation, **42/42 are
  serviceable**, while **33/42 (78.6%) are decision-grade** after manual evidence review.
  Nine cells remain partial. Adding another general-news site is unlikely to close any of
  those nine gaps.
- A complete future-date scan now returns **zero**. One Bảo Việt article had incorrectly
  inherited a 30 September programme end date as its publication date; the parser now uses
  the 31 July article-boundary date and rejects implausible future publication dates.

This is not a claim that the internet cannot contain one more useful page. It means the
**recurring public endpoint layer is saturated enough that its remaining report gaps are caused
by publication timing, blocked assets or genuinely sparse disclosure—not by the absence of
another broad news whitelist**. Future discovery should therefore be targeted gap research and
manual/document import, not an unbounded wider net.

## What changed in this acquisition pass

The operational registry grew from **64 to 70 channels** (105 to 111 total audit rows) with six
high-marginal-value additions:

1. [MOF/VIDI: Global Risks Report 2026 implications for Vietnam insurance](https://vidi.mof.gov.vn/vien-phat-trien-bao-hiem-viet-nam/nghien-cuu-trao-doi/tu-bao-cao-rui-ro-toan-cau-2026-cua-dien-dan-kinh-te-the-gioi-mot-so-khuyen-nghi-cho-thi-truong-bao-hiem-viet-nam)
   through the publisher's stable JSON article API: 11,600 characters.
2. [Cà Mau Police: personal-data protection in insurance business](https://congan.camau.gov.vn/ch40/7679-Bao-ve-du-lieu-ca-nhan-trong-hoat-dong-kinh-doanh-bao-hiem.mhtml):
   3,534 characters. The server omitted its GlobalSign intermediate; the application now
   completes the exact PKIX chain for this host without disabling TLS verification.
3. [Vietnam Insurance Association: Chubb Life–Igloo digital distribution](https://hiephoibaohiemvietnam.vn/tin-hoat-dong-khoi-nhan-tho/345136-chubb-life-viet-nam-ky-ket-hop-tac-cung-igloo-thuc-day-kha-nang-tiep-can-bao-hiem-thong-qua-nen-tang-so):
   1,243 characters.
4. [Milliman: Vietnam's life-insurance landscape after the mis-selling crisis](https://media.milliman.com/v1/media/edge/images/millimaninc5660-milliman6442-prod27d5-0001/media/Milliman/PDFs/2026-Articles/6-8-26_Vietnam-e-alert_Vietnams_life_insurance_landscape_after_the_mis-selling_crisis.pdf):
   a three-page June 2026 PDF, 9,812 extracted characters, with market history, new-business
   trajectory, competitive share, regulation and a survey of 14 executives from 11 insurers.
5. [Journal of Economics and Finance: four-dimensional impact of personal-data law on insurance](https://nghiencuu.tapchikinhtetaichinh.vn/danh-gia-da-chieu-va-du-bao-tac-dong-cua-luat-bao-ve-du-lieu-ca-nhan-den-nganh-bao-hiem-viet-nam-trong-ky-nguyen-so-131986.html):
   24,744 characters. Its true publication date is retained as 17 December 2025, so it is
   background evidence and is not misrepresented as an H1 2026 publication.
6. [LuatVietnam: data and cybersecurity duties for online insurance from July 2026](https://english.luatvietnam.vn/legal-updates/personal-data-protection-required-for-online-insurance-services-from-july-1-2026-892-110396-article.html):
   2,335 characters. Its 14 July 2026 date keeps it out of the H1 score while making it
   available for current briefs.

Manulife's official newsroom was also re-crawled from a clean state. It produced 36/36 usable
full-text articles, including the current Xanh Phú Quý product release. The earlier H1 audit
missed that article because midnight Vietnam time was compared as the prior UTC date; the audit
now evaluates reporting windows in `Asia/Ho_Chi_Minh`.

## Coverage is channels, not an inflated publisher count

The 70 operational rows are acquisition **channels**, not 70 unique companies. A company may
have a newsroom, product notices and a statutory-report channel because they require different
parsers and answer different evidence questions.

| Acquisition lane | Operational channels | Purpose |
|---|---:|---|
| Official statutory financial reports | 18 | Premium, profit, assets, reserves/solvency and capital |
| Regulators and macro authorities | 7 | Market totals, rules, GDP/CPI, rates, FX, bonds, cyber/data duties |
| Independent/professional lenses | 16 | Corroboration, market narrative, competitor comparison and specialist analysis |
| Official insurer/product/disclosure channels | 28 | Product, partnership, service, distribution and company-specific evidence |
| Regional group-result channel | 1 | Vietnam-filtered parent-company evidence; never substitutes another legal entity |

Transport mix: 60 HTML, seven JSON API, one RSS, one sitemap and one direct PDF channel.
The crawler also follows bounded detail links, parses PDFs up to 100 pages, applies local OCR to
image PDFs where needed, uses fixed Reader transport only for an explicit allow-list, and pins
exact missing certificate intermediates for named hosts. HTTPS, exact-host, redirect, SSRF,
content-type, timeout and body-size gates remain enabled.

The per-source cap of 100 means **up to 100 items per source per cycle**, not 100 documents for
the whole crawl.

## Raw-corpus quality

| Measure | Result | Assessment |
|---|---:|---|
| Operational channels that produced at least one document | 69/70 (98.6%) | Pass; MB Life financial file transport is the exception |
| All usable full text (>=600 chars and full-text flag) | 672/766 (87.7%) | Acceptable overall; official short notices remain intentionally visible but ineligible |
| All documents with publication date | 726/766 (94.8%) | Pass; undated documents are not silently assigned a guessed date |
| H1 2026 usable full text | 227/243 (93.4%) | Pass |
| Latest 90 days usable full text | 175/182 (96.2%) | Pass |
| Future publication dates after correction | 0 | Pass |
| Median / P90 / max body length | 3,490 / 12,764 / 118,513 chars | Supports both news and long reports |

Important exclusions behind the headline number:

- `MOF_ISA` has 95 rows, but 70 are short official notices and 27 are not marked full text.
  They remain traceable raw records but do not count as usable full text.
- `CATHAY_VN` exposes 15 dated API summaries but none reaches 600 characters. It is a valid
  discovery channel, not a report-grade full-text source; independent coverage supplies detail.
- `FWD_VN` has 100 rows and 96 usable bodies, but much of the volume is promotion mechanics.
  Volume is not treated as importance.
- `MB_LIFE_FINANCIALS` is the only operational channel with no raw document. The public listing
  is [visible here](https://mblife.vn/bao-cao-tai-chinh), while its PDF asset returns HTTP 423 to
  both the direct server client and the Reader transport. This remains an explicit manual-browser
  import exception, not a silent success.

## 42-cell report fitness review

Grades:

- **A — decision-grade raw pack:** current, attributable evidence strong enough to support the
  cell, normally combining an authoritative/primary source with an independent lens.
- **B — serviceable/partial:** enough to explain the subject, but one important dimension is
  sparse, not time-aligned or not yet publicly released.
- **C — gap:** not enough raw evidence to responsibly write the cell.

The deterministic pre-screen reported 41/42 cells with a Tier 1 + Tier 2 keyword match. That is
not the accepted quality result: manual review downgraded eight additional cells because a
keyword in a FY2025 statement does not prove H1 2026 movement, and company names such as “AIA
Group” are not evidence of a group-insurance product.

| Report cell | Grade | Main raw support / remaining limitation |
|---|:---:|---|
| Macro — GDP | A | NSO monthly/Q1 releases + MOF/independent context |
| Macro — inflation | A | NSO CPI releases + company sensitivity disclosures |
| Macro — interest rates | A | SBV market operations + HNX + insurer disclosures |
| Macro — FX | A | SBV/NSO and financial-report sensitivity evidence |
| Macro — government bonds | A | HNX monthly primary data + insurer asset disclosures |
| Macro — demographics/affluence | B | NSO and Milliman provide context; no dedicated H1 demographic dataset |
| Market — total premium | A | MOF monthly bulletin + Milliman/independent analysis |
| Market — new business | A | MOF monthly bulletin + Milliman trajectory |
| Market — policies in force | A | MOF monthly primary count |
| Market — product mix | A | MOF bulletin includes product composition; official product releases add detail |
| Market — market share | A | MOF bulletin + Milliman competitive-share view |
| Market — regulation | A | MOF, Government News and dated legal explainers |
| Competitor — Bảo Việt Life | A | Official newsroom/financials + group results + independent coverage |
| Competitor — Prudential Vietnam | A | Official newsroom/financials + multiple independent sources |
| Competitor — Manulife Vietnam | A | 36 official full-text articles after clean refresh + four independent families |
| Competitor — Dai-ichi Life Vietnam | A | Official newsroom/financials + MOF/IAV/independent coverage |
| Competitor — AIA Vietnam | A | Official newsroom/notices/financials + MOF/independent coverage |
| Competitor — FWD Vietnam | A | Official product/distribution/financial sources + IAV and independent coverage |
| Competitor — Sun Life Vietnam | A | Official current newsroom/financials + independent coverage |
| Competitor — MB Life | B | Official newsroom + independent coverage; financial PDF remains HTTP 423-blocked |
| Competitor — Generali Vietnam | A | Official API/financials + TBNH/other corroboration |
| Competitor — challengers | A | Techcom Life, Shinhan, BIDV MetLife, MAP and Phú Hưng official channels + media |
| Product — new products/riders | A | Numerous dated official launches + independent reproduction |
| Product — health/critical illness | A | Official terms/notices/releases + market coverage |
| Product — retirement/longevity | A | Government retirement policy + Sun Life launch + independent context |
| Product — investment-linked | A | MOF composition data + insurer notices/launches/financial disclosures |
| Product — group/employee benefits | B | Usable retirement-benefit and group references exist, but current life-specific depth is sparse |
| Product — embedded protection | A | IAV Chubb–Igloo primary case + VIR/VietnamNet/Báo Đầu tư corroboration |
| Distribution — agency | A | Official adviser initiatives + independent market analysis |
| Distribution — bancassurance | A | Official bank/insurer disclosures + broad independent coverage |
| Distribution — digital | A | Chubb–Igloo, insurer digital services and independent evidence |
| Distribution — partnerships | A | Official partner announcements + independent coverage |
| Distribution — adviser quality | A | MOF professional standards + company training + independent reporting |
| Financial — premium movement | B | 18 official FY2025 report channels published in H1; actual H1 2026 statements not yet broadly public |
| Financial — profit movement | B | Same timing limitation; do not label FY2025 as H1 2026 performance |
| Financial — assets/investments | B | Strong FY2025 baseline, sparse actual H1 2026 filings as of the audit date |
| Financial — reserves/solvency | B | Strong statutory baseline, weak H1 2026 movement disclosure |
| Financial — capital | B | Official capital baselines/actions exist; cross-company H1 movement remains incomplete |
| Technology — AI/data | A | MOF recommendations + Techcom Life and insurer implementation evidence + independent coverage |
| Technology — digital service | A | Multiple official customer-service cases + independent coverage |
| Technology — automated underwriting/operations | B | AI and automation cases exist, but hard Vietnam operating metrics are sparse |
| Technology — cyber/privacy | A | MOF, police guidance, insurer notices, specialist research and the July online-insurance rule explainer |

**Accepted result:** A = 33/42 (78.6%); B = 9/42 (21.4%); C = 0/42. Serviceable =
42/42. “Decision-grade” must not be reported as the automated 41/42 keyword score.

## Why more general-news sources are no longer the right acquisition move

The remaining B cells are not a single “more articles” problem:

1. Five cells depend on actual H1 2026 insurer financial statements. Search and official-channel
   checks found FY2025 statements published during H1, but not broad H1 2026 statutory releases
   as of 5 August. No crawler can safely manufacture an unpublished period.
2. MB Life's financial asset exists but rejects server clients with HTTP 423. This needs a
   browser-assisted/manual import lane or publisher-side access change, not another whitelist row.
3. Group/employee-benefit life products and concrete automated-underwriting operating metrics
   are genuinely sparsely disclosed in current Vietnam public sources. These are suitable topics
   for targeted deep research and human-supplied documents.
4. Generic media additions would mostly repeat the same releases already represented by 16
   independent/professional channels, increasing duplicate and curation cost without closing the
   missing evidence dimensions.

## Acquisition gate and next boundary

The public recurring-source gate is **passed with explicit exceptions**:

- legal-entity coverage: pass;
- live working-channel rate: pass at 98.6%;
- H1 full-text rate: pass at 93.4%;
- time integrity: pass after correction, zero future dates;
- serviceable report coverage: pass at 100%;
- decision-grade raw coverage: pass at 78.6%, above the 75% working threshold;
- unresolved exceptions: MB Life financial transport, sparse group-product/automation metrics,
  and not-yet-broadly-published H1 2026 statutory statements.

No downstream classifier, verifier or report-generation logic was changed as part of this audit.
The next phase should start with source weighting, legal-entity resolution and temporal/period
validation—not with another expansion of broad news sites.

## Verification record

- Clean isolated database: `/tmp/marketradar-acquisition-audit-v7-20260805.mv.db`.
- Base full crawl: 60/60 configured channels completed successfully; subsequent additions and
  targeted corrections were crawled in the same isolated corpus.
- Final operational result: 69/70 channels with data; the only zero-document row is the explicit
  MB Life HTTP 423 exception.
- Focused live runs returned success for Manulife (36 documents), MOF cyber/data, Cà Mau Police,
  IAV Chubb–Igloo, Milliman PDF, the finance research article and LuatVietnam.
- `mvn -q -DskipTests package`: pass.
- `VietnamAcquisitionParsersTest`: all pass.
- `DocumentMetadataDetectorTest`: all pass.
- Final future-publication-date query: zero rows.
