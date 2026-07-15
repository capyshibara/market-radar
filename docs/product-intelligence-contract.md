# Product Intelligence Contract (Hackathon v1)

Status: normative for the Product department demo
Owner: Product Intelligence
Contract version: `product-v1.0`
Fixture: [`evaluation/product-golden-set.json`](evaluation/product-golden-set.json)

## 1. Purpose and boundary

Market Radar uses one shared crawl and evidence base, then applies a department-specific intelligence contract. This contract defines what a life-insurance Product reader should receive. It is not a generic insurance-news brief and it does not define what Distribution, IT, Corporate Affairs, Operations, or Finance should receive.

The report helps a Vietnam life insurer decide what to investigate, design, change, test, or stop. A story is not Product intelligence merely because it mentions an insurer.

The primary reader is a Product leader or product manager. The default action horizon is 30–90 days; longer-term signals must state why they matter now.

## 2. Key intelligence questions (KIQs)

Every main-report item must answer at least one KIQ. `P-KIQ-01` through `P-KIQ-07` are stable IDs and should be stored with generated insights.

| ID | Question | Examples of qualifying evidence |
|---|---|---|
| `P-KIQ-01` | Which Vietnam competitors changed a life product, benefit, price/fee, fund, target segment, underwriting rule, or product availability? | Launch, rider, benefit expansion, premium/fee change, withdrawal, suspension |
| `P-KIQ-02` | Which repeated competitor moves indicate an emerging market pattern rather than an isolated announcement? | Two or more independent companies moving toward the same benefit, segment, proposition, or pricing mechanism |
| `P-KIQ-03` | Which regulatory changes require a change to product design, wording, pricing, approval, disclosure, or product governance? | Final rule or consultation with an explicit product consequence and effective date |
| `P-KIQ-04` | Which regional life-insurance innovations are sufficiently transferable to Vietnam to merit investigation? | New health, longevity, savings, protection, digital-product, or underserved-segment proposition with an explicit transferability test |
| `P-KIQ-05` | Which customer-need, protection-gap, or behavior signals reveal an underserved segment or unmet product need? | Credible research, claims/health pattern, protection gap, demand or lapse behavior tied to a product decision |
| `P-KIQ-06` | What should Product investigate, validate, prototype, or decide in the next 30–90 days? | A named action, owner role, time horizon, and success/stop condition |
| `P-KIQ-07` | What history, contrary evidence, constraints, or uncertainty weakens the apparent signal? | Prior event, conflicting source, market-specific constraint, missing evidence, or alternative explanation |

`P-KIQ-06` and `P-KIQ-07` are synthesis KIQs: every lead insight must answer both in addition to one of `P-KIQ-01`–`P-KIQ-05`.

## 3. Canonical event types

The intelligence layer should normalize source documents to these event types. These types are deliberately more specific than the current classifier categories.

| Event type | Definition |
|---|---|
| `PRODUCT_LAUNCH` | A newly available life product, rider, fund, or materially distinct proposition |
| `PRODUCT_CHANGE` | A material change to an existing product that is not better described below |
| `BENEFIT_CHANGE` | Addition, removal, expansion, reduction, or eligibility change to a benefit or coverage |
| `PRICING_CHANGE` | Change to premium, fee, charge, crediting mechanism, guaranteed value, or product-linked financial incentive |
| `PRODUCT_WITHDRAWAL` | Suspension, closure, withdrawal, replacement, or end of sale of a product or product list |
| `REGULATORY_CHANGE` | Proposed or final rule with a direct product design, wording, pricing, disclosure, approval, or governance consequence |
| `CUSTOMER_NEED_SIGNAL` | Credible evidence of a protection gap, segment need, behavior change, or demand pattern |
| `COMPETITIVE_PERFORMANCE` | Product-, segment-, or channel-specific performance that reveals demand or portfolio direction |
| `DISTRIBUTION_CHANGE` | Channel, partner, adviser, or sales-process change; normally belongs to Distribution unless it changes the proposition |
| `SERVICE_EXPERIENCE_CHANGE` | Customer journey or after-sales change; Product-relevant only when it changes proposition value, persistency, or product use |
| `MARKETING_PROMOTION` | Temporary campaign, gift, contest, or incentive; not a product change unless product economics materially change |
| `CORPORATE_NEWS` | Award, CSR, appointment, brand, sponsorship, general financial result, or corporate transaction without a Product decision link |
| `OTHER` | Insufficiently specific to normalize; cannot lead the report |

Current-category migration guidance:

- `PRODUCT_LAUNCH` can map to canonical `PRODUCT_LAUNCH`, but launches of services, campaigns, or corporate initiatives must not.
- `FEE_BENEFIT_COMMISSION_CHANGE` must split into `BENEFIT_CHANGE`, `PRICING_CHANGE`, `MARKETING_PROMOTION`, or Distribution-owned commission events.
- `PRODUCT_REGULATION` must pass the direct-product-consequence test before becoming `REGULATORY_CHANGE`.
- `SALES_DATA` becomes `COMPETITIVE_PERFORMANCE` only when product, segment, mix, or demand direction is identifiable.
- `DISTRIBUTION_CHANNEL` normally routes outside Product; dual-route only when the evidence states a proposition or product consequence.

## 4. Report insight contract

A main-report insight is an evidence-backed decision object, not an article summary. It must contain:

| Field | Required contract |
|---|---|
| `what` | One or two sentences stating the verified change: actor, action, object, market, and relevant semantic date. No inference belongs here. |
| `pattern` | Relationship to history, peers, or another signal. Cite at least two independent events for a market pattern. If only one exists, explicitly label it `single signal`; never invent a trend. |
| `soWhat` | The Product decision affected and the mechanism connecting evidence to that decision. Do not use generic language such as “should monitor.” |
| `nowWhat` | A concrete `investigate`, `validate`, `prototype`, `compare`, `decide`, or `stop` action; owner role; 30–90-day horizon; and success or stop condition. Recommendations may infer actions but may introduce no new factual claims. |
| `confidence` | `HIGH`, `MEDIUM`, or `LOW`, plus a 0–100 score and reasons based on evidence quality, corroboration, and inference distance. Confidence is separate from materiality. |
| `caveat` | The strongest missing evidence, contrary signal, transferability constraint, or alternative explanation. Use `none identified` only after an explicit contrary-evidence check. |

Required metadata: `insightId`, `department`, `kiqIds`, `eventIds`, `evidenceIds`, `eventType`, `materialityScore`, `market`, `publishedAt`, relevant semantic dates (`occurredAt`, `effectiveFrom`, `forecastHorizon` as applicable), `pipelineVersion`, and `generatedAt`.

An article title or URL is not a citation. Each factual sentence must link to the evidence span that supports it.

## 5. Inclusion and exclusion rubric

### 5.1 Hard inclusion gate

An item is directly Product-relevant only when all are true:

1. It concerns life insurance or a clearly transferable life/health/savings proposition.
2. It identifies a product decision object: product, rider, benefit, price/fee, fund, segment, availability, product-linked customer experience, product-specific performance, or direct product regulation.
3. It answers at least one of `P-KIQ-01`–`P-KIQ-05`.
4. Its relevance can be stated without adding a fact absent from the evidence.

Regional research or innovation may be `CONDITIONAL` when it meets items 2–4 and states the Vietnam transferability question. Conditional evidence cannot be presented as proof of Vietnamese demand.

### 5.2 Hard exclusions for the Product main report

Exclude or route to another department when the only subject is:

- award, ranking, anniversary, appointment, employer brand, sponsorship, CSR, donation, or community event;
- generic profit, revenue, premium, MDRT, or market-share result with no product/segment/mix implication;
- generic macroeconomics, banking, securities, reinsurance, or non-life insurance;
- broad regulator activity, accreditation, internal audit, AML, capital, solvency, or conduct news with no stated product consequence;
- adviser training, office opening, bancassurance partnership, sales promotion, or channel campaign with no product-proposition consequence;
- claims payment, fraud warning, website notice, administrative reminder, or service outage with no product design/persistency consequence;
- a temporary gift or promotion presented as a permanent price/benefit change;
- a company claim that a product is “innovative” or “award winning” without a verifiable change.

An excluded story may still be useful to another department. Preserve it in the shared intelligence base; do not force it into Product.

### 5.3 Evidence-readiness gate

- `PASS`: clean article text or authoritative primary document is available; event claims have evidence spans; entity and semantic dates are resolved.
- `FAIL_TITLE_ONLY`: title/listing metadata only. It may be classified and queued for refetch, but cannot become an insight or support a pattern.
- `FAIL_INCOMPLETE`: body exists but is truncated, mostly navigation, missing the claimed change, or lacks the relevant attachment/table.
- `FAIL_CONFLICT`: material claims conflict and the conflict is unresolved. It may appear only as a caveated review item.

No materiality score overrides a failed evidence gate.

## 6. Materiality rubric

Score each factor from 0 (none) to 4 (very high). Do not ask a model for a single unexplained materiality number.

| Factor | Weight | Level 4 anchor | Level 0 anchor |
|---|---:|---|---|
| `decisionRelevance` | 30% | Directly changes a current Product decision/KIQ | No identifiable Product decision |
| `decisionImpact` | 25% | Could require launch, redesign, repricing, withdrawal, or portfolio decision | No plausible Product consequence |
| `novelty` | 15% | First-of-kind or material departure from known baseline | Repetition with no new information |
| `marketProximity` | 15% | Vietnam life market/direct competitor; regional level 2–3 only with transfer case | Unrelated market/domain |
| `timeliness` | 10% | New and actionable within 30–90 days | Stale or action window passed |
| `patternStrength` | 5% | Corroborated by three or more independent entities/events | Isolated assertion with no comparison |

Formula:

```text
score = decisionRelevance × 7.5
      + decisionImpact × 6.25
      + novelty × 3.75
      + marketProximity × 3.75
      + timeliness × 2.5
      + patternStrength × 1.25
```

Disposition after hard and evidence gates:

- `75–100` → `LEAD_SIGNAL`
- `55–74.99` → `SUPPORTING_SIGNAL`
- `35–54.99` → `APPENDIX`
- `<35` → `EXCLUDE`

Hard exclusions remain `EXCLUDE` even when proximity/timeliness inflate the numeric score. Evidence failures become `NEEDS_EVIDENCE`, regardless of score.

## 7. Quality gates and acceptance criteria

The pipeline must pass these gates in order:

1. **Document quality:** canonical URL, clean content, publication date, source, language, and no title-only report item.
2. **Event quality:** normalized actor/object/market/event type and separate publication, occurrence, effective, and forecast dates.
3. **Factual quality:** every factual claim is entailed by cited spans; unsupported names, numbers, and dates are zero-tolerance defects.
4. **Department quality:** KIQ match, hard inclusion/exclusion, and materiality factors are recorded.
5. **Synthesis quality:** `what`, `pattern`, `soWhat`, `nowWhat`, `confidence`, and `caveat` pass their field contracts; a single event is never called a trend.
6. **Editorial quality:** Product is the audience, action belongs to our Product team, prose is concise, and a reviewer approves lead insights.

The deterministic publication implementation is
`ProductPublicationQualityGate`. Publication requires a `DECISION_READY` disposition: 100% cited
evidence resolution, a Product KIQ, a Product audience/internal Product action owner, chapter/event
compatibility, current homogeneous model and pipeline versions, evidence within the edition window,
and a concrete decision action. A claimed trend requires at least two independent events and two
independent sources. A non-trend item supported by only one source is `WATCH`; it cannot count as a
decision-ready insight. A future recommendation whose deadline or effective date has passed is
rejected.

An edition is `READY` only when at least three insights are `DECISION_READY`. Otherwise its explicit
status is `INSUFFICIENT_EVIDENCE`; old claims must not be reused to fill a sparse period. Gate failures
are machine-readable codes with field-level details and are stored with the newly appended edition.
Prior editions remain immutable and may only be marked superseded by a successful replacement.

Hackathon release targets on the golden set:

- 100% hard-exclusion precision for awards, CSR, broad finance/regulation, and wrong-department items;
- 100% blocking of `FAIL_TITLE_ONLY` from report synthesis;
- at least 90% agreement on `departmentRelevant` and primary `eventType`;
- all computed materiality scores exactly match the fixture formula;
- zero unsupported names, numbers, or dates in generated insights;
- every lead insight answers `P-KIQ-06` and `P-KIQ-07` and has at least two citations for any claimed pattern.

## 8. Reuse for future departments

The crawl, document, evidence, entity, event, and history layers remain shared. A new department adds a versioned lens, not a duplicate pipeline:

```text
DepartmentProfile = {
  departmentId,
  contractVersion,
  reader,
  decisionHorizon,
  kiqs[],
  directEventTypes[],
  conditionalEventTypes[],
  hardExclusions[],
  materialityWeights,
  actionOwnerRoles[],
  reportSchemaVersion
}
```

Event types describe what happened; department profiles describe who should care. The same event can therefore route to Product and Distribution with different `soWhat`, `nowWhat`, and materiality scores, while retaining the same facts and evidence citations.

Fixture cases use stable string IDs. Database row IDs are included only as observed corpus references and must not be used as test keys.
