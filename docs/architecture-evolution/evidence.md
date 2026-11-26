# Finance Lab architecture evolution

Evidence reviewed on 26 November 2026 for [issue #26][issue]. Dates identify recorded milestones and may overlap. **Documented** identifies what a commit, decision or PR records. **Reported** identifies a historical run or observation recorded by its participants. **Recollection** identifies the author's later account. **Interpretation** identifies an inference from the records.

This review checked the original frontend commit, the Python run record and adapter, the architecture decisions, the merged PRs, the launch issue and the release-candidate CI log. Historical tests and provider calls are attributed to the people or CI jobs that ran them. The archive preserves the evidence needed to review the timeline.

## Dated timeline

### 19 September–2 November 2026 · Early frontend and AI-assisted prototype

- **19 September, documented.** The original repository contains a Vite, React and TypeScript initialization commit. Anton identified this repository as the project's starting point. That identification is author recollection. The commit establishes the frontend baseline. The later frontend import identifies which React revision persisted budgets in the browser. [Initialization commit][frontend-init]
- **6–10 October, documented and reported.** A separate Python learning prototype contained a Yandex AI Studio adapter. The dated run note reports Anton making a live request using `yandexgpt-5.1` with a synthetic budget. PR #3 credits Codex with implementation corrections and tests, and reports 15 offline tests using fake transports. PR #4 reports 57 offline tests with the read-only tool included. On 7 October, a separate live experiment recorded the model requesting `get_budget` and the local Python function returning a synthetic October budget after validating the month. The record establishes Yandex AI Studio's role as model provider for the experiment. Yandex authorship of application code, integration with real financial data and a production deployment are unverified. [Run record][live] · [Adapter][adapter] · [PR #3][agent3] · [PR #4][agent4] · [Tool run][tool]
- **19 October, documented.** ADR-026 explicitly names the Python repository as the learning prototype. It describes a planned product agent with least privilege and human-approved writes and distinguishes that plan from engineering assistance. **Interpretation.** The read-only fixture kept the experiment scoped to learning how to call the model and validate a tool request. The records do not establish why Yandex was selected over other providers, what the experiment cost, or whether it influenced the choice of the subsequent Vaadin stack. Anton reported about two hours implementing the budget tool and five more minutes reviewing its contract. Total development time, ongoing operating effort and the reason the prototype ended or changed remain undocumented. [ADR-026][adr] · [Tool PR][agent4]
- **24 October–2 November, documented and reported.** The organization imported the React frontend from an identified revision of the original repository. The import PR reports tests covering monthly budgets, localStorage and export/restore. The subsequent Settings PR describes browser-local storage and manual backup. Neither source establishes a Yandex integration in the frontend. [Frontend import][fe1] · [Settings and backup][fe35]

### 4–10 November 2026 · Java/Vaadin modular monolith

- **4–5 November, documented.** The platform repository redirected its active roadmap to the Budget-first Java/Vaadin rewrite on 4 November. The app decision describes a Java modular monolith with a Vaadin UI, no Spring, a custom ORM and PostgreSQL using Anchor Modeling. The design put the Budget UI and application logic in one Java application with persistence through the custom mapping layer. **Interpretation.** This kept application delivery within one deployable with explicit control over database mappings. The available evidence does not establish the full selection rationale or alternatives considered. [Platform planning change][pf21] · [Architecture decision][vaadin]
- **By 10 November, reported.** App PR #16 reports six ORM unit tests and 75 integration tests against PostgreSQL. Separate probes reproduced a category-history defect in the Budget reader. App PR #12 records the follow-up fix and the 10 November release. An authenticated browser exercised read paths on the deployed app. Saves and restart persistence were checked on a separate database on the selected VM. The public decision and launch issue identify the earlier single-VM deployment on Yandex Cloud. Production traffic, sustained latency, reliability and total operator time remain unquantified. [Launch issue][launch] · [ORM PR #16][app16] · [Budget PR #12][app12]
- **Overhead and revision.** The custom ORM required a mapping layer and PostgreSQL integration checks. A PostgreSQL-backed setup also requires managing database access, upgrades and backups or using a managed service, as the later decision explains. On 14 November, the one-owner pilot decision selected embedded SQLite under a durable-disk and infrequent-write assumption. **Interpretation.** This reduced the number of services needed for the pilot. Evidence is missing for actual time or money saved and for whether Vaadin or the ORM independently drove the revision. [ORM PR #16][app16] · [14 November decision][spring]

### 14–21 November 2026 · Spring Boot / Spring MVC / Thymeleaf / SQLite

- **14 November, documented.** PR #8 accepted a single Spring Boot application for an online, one-owner pilot. Desktop and phone browsers use responsive HTML generated by Spring MVC and Thymeleaf. The server owns validation, budget calculations and saves through JDBC to SQLite on persistent local disk. The goal was to save a monthly total once and read the same saved state from either device. The screen walkthrough reached desktop setup. Prepared screens covered additional paths, and the decision called for a working save to test arbitrary input, persistence and cross-device use. [Decision PR #8][spring8] · [Accepted decision][spring] · [Walkthrough notes](../budget-v1-prototype-review.md)
- **Rationale and overhead, documented.** The decision describes occasional short writes and one application instance with durable storage, making SQLite's serialized writes acceptable for the pilot. The shared forms and server-side database avoid a separate database service and offline synchronization. The remaining work includes driver and schema updates, durable storage, backups and recovery checks. The decision identifies frequent write contention or loss of durable local disk as reasons to revisit PostgreSQL. These are design trade-offs. Comparative performance and operating costs were not measured in the cited records. [Accepted decision][spring]
- **21 November, reported.** The launch issue records deployment of revision `220cfdb47b737f248192b396dbf5c3185407ac3a`. The release-candidate CI log records 20 Maven tests with zero failures, errors or skips. Separately, the launch issue reports HTTPS sign-in, a synthetic save on desktop, readback on a physical phone and readback after restarting the rehearsal service. It also records off-VM decryption and integrity checks of the initial SQLite backup. The release includes an empty budget at first use, monthly plans and spending, historical entry and a yearly overview. These checks cover the release conditions described. Long-term usage, performance and recovery outcomes remain unverified. No subsequent architecture replacement is established by this timeline. [Launch issue][launch] · [Candidate CI log][ci] · [Release record][release] · [Deployed revision][revision]

## Source and claim matrix

| Claim | Evidence and classification | Limit for reuse |
| --- | --- | --- |
| Original project baseline included a React/TypeScript frontend in September. | [Initialization commit][frontend-init], documented. Starting-point attribution, author recollection supplied with issue. | [Frontend import][fe1] and [Settings PR][fe35] document browser-local persistence in later revisions. Evidence for Yandex use comes from the Python prototype. |
| Python prototype called Yandex AI Studio on synthetic budget input. | [Dated run record][live], reported live call. [Python adapter][adapter], documented implementation. | Provider use is scoped to the prototype and the recorded run. |
| Codex assisted with implementation and tests, and the prototype added read-only budget access. | [PR #3][agent3], documented attribution and offline tests. [PR #4][agent4], documented fixture-backed tool. | No evidence of Yandex code authorship or production financial-data access. |
| Product-agent architecture was a separate plan. | [ADR-026][adr], documented link and proposed approval boundary. | Product integration and write approval are design intent in this record. Only individual task times are recorded. Total effort, ongoing operations and the reason for revision remain unknown. |
| Java/Vaadin modular monolith used PostgreSQL Anchor Modeling and a custom ORM. | [Architecture decision][vaadin], documented design. [PR #16][app16], reported integration checks. | No comparative performance or quantified development effort. |
| Earlier Budget release reached live acceptance on 10 November. | [PR #12][app12], reported release and checks. | The decision and [launch issue][launch] identify Yandex Cloud. Production read checks and isolated write checks had different scopes. |
| Spring MVC, Thymeleaf and SQLite were selected for the online single-owner pilot. | [PR #8][spring8] and [decision][spring], documented architecture and trade-offs. | Fewer services is a topology comparison. Cost, maintenance effort and speed gains are unmeasured. |
| v0.1.0 was deployed and exercised on desktop and phone. | [Launch issue][launch], reported device and backup checks. [CI log][ci], 20 passing candidate tests. [Release][release] and [commit][revision], identified version. | Synthetic acceptance, restart persistence and backup integrity are the scope of the cited checks. |

Disclosure review against [issue #26][issue] uses public project references and synthetic examples only. Private financial data, internal employer architecture and source code, and non-public operational details are excluded. Evidence gaps above remain open for the technical report.

[issue]: https://github.com/finance-lab-project/finance-lab/issues/26
[fe1]: https://github.com/finance-lab-project/frontend/pull/1
[fe35]: https://github.com/finance-lab-project/frontend/pull/35
[pf21]: https://github.com/finance-lab-project/platform/pull/21
[tool]: https://github.com/anton415/finance-lab-agent/blob/e46ffee744661117654e54ec048908be61c520fd/docs/t1/t1-2-read-only-budget-tool.md
[launch]: https://github.com/finance-lab-project/finance-lab/issues/24
[ci]: https://github.com/finance-lab-project/finance-lab/actions/runs/44869615186/job/136740965177
[frontend-init]: https://github.com/anton415/finance-lab/commit/c6a542972c1ad2738cc708c49fbe72e13d1c84f6
[live]: https://github.com/anton415/finance-lab-agent/blob/1996491138679510a32824defdcae8d7b453bf0b/docs/t1/day2-yandex-model-call.md#L3-L23
[adapter]: https://github.com/anton415/finance-lab-agent/blob/1996491138679510a32824defdcae8d7b453bf0b/src/finance_lab_agent/yandex.py
[agent3]: https://github.com/anton415/finance-lab-agent/pull/3
[agent4]: https://github.com/anton415/finance-lab-agent/pull/4
[adr]: https://github.com/anton415/finance-lab/blob/a214a664f5409e91c10ec4468a53c2a104936bc5/docs/architecture/decisions/0026-least-privilege-ai-agent-human-approved-writes.md#L3-L13
[vaadin]: https://github.com/finance-lab-project/app/blob/43d51524ad1c8938b57624b1a9ee393da72d42e4/docs/architecture.md#L3-L29
[app16]: https://github.com/finance-lab-project/app/pull/16
[app12]: https://github.com/finance-lab-project/app/pull/12
[spring8]: https://github.com/finance-lab-project/finance-lab/pull/8
[spring]: https://github.com/finance-lab-project/finance-lab/blob/b158ae68e6d6a0f415ade9fc8430238e60869121/docs/implementation-approach.md#L3-L25
[release]: https://github.com/finance-lab-project/finance-lab/releases/tag/v0.1.0
[revision]: https://github.com/finance-lab-project/finance-lab/commit/220cfdb47b737f248192b396dbf5c3185407ac3a
