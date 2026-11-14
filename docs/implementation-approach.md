# Budget v1 implementation approach

**Proposed for review · 14 November 2026 · [Issue #5](https://github.com/finance-lab-project/finance-lab/issues/5)**

Build one Java/Spring Boot web application in this repository. It serves responsive HTML with Spring MVC and Thymeleaf and keeps the budget in a server-side SQLite database on durable local disk. Both browsers open the same application. A successful **Сохранить** commits the month once, and either device can read it on its next request.

## What the prototype established

The participant found the **24 000 ₽** food plan on desktop and understood the return to the month after saving setup. Keep that destination and show the saved values there. They also confirmed that the timing of Save and the distinction between plan, remaining amount and opening buffer were understandable. Keep all three labels visible. Clicking desktop income opened a phone-sized screen. That prototype fix remains deferred in [#7](https://github.com/finance-lab-project/finance-lab/issues/7).

The participant stopped after desktop setup. The phone route, replacement edit and year report were checked on prepared screens. Independent completion of those tasks, arbitrary input, persistence after reload and access from a second device still need a working application. These limits are why the first increment exercises a real monthly save. [Walkthrough findings](budget-v1-prototype-review.md) · [Reviewed screens](budget-v1-screen-sketches.md)

## Why this approach

The [reviewed screens](budget-v1-screen-sketches.md) guide the layout and use [Google Material Design](https://m3.material.io/) as the primary UI/UX reference. Thymeleaf fills HTML templates with the saved values and field errors supplied by Spring MVC. Shared CSS implements the typography, spacing and focus/error states from the designs.

The regular task is a periodic replacement of monthly totals. One set of responsive forms can serve both screens. A small amount of browser-side JavaScript handles linked fields and the unsaved-navigation dialog. The server owns the calculations and the saved budget. Both options below support Russian text, whole-ruble amounts, monthly history and yearly reporting. [Product scope](product-scope.md) · [User journey and AC-01–AC-18](budget-v1-user-journey.md)

| Decision factor | Spring MVC with embedded SQLite · selected for v1 | Same application with PostgreSQL |
| --- | --- | --- |
| Delivery | Package templates, rules and JDBC access in one application. A durable file beside the app is enough to exercise cross-device Save. | The same forms and rules work. Development and testing also need a database instance, credentials and a connection. |
| Maintenance | Update one application and its driver, apply schema changes and test restoring a consistent database backup. Writes serialize, which fits occasional monthly saves. | Maintain the app plus database upgrades, access and backups, or arrange a managed service. Concurrent writers can update different budgets independently. |
| Operating cost | One running host with persistent disk plus backup storage. The database runs inside the application process. | The database can share the app host, costing memory and operator time. A managed instance has its own service cost. |
| When to choose it | One application instance owns durable local storage and short writes are infrequent. | Prefer this option if the host offers only ephemeral app storage but already provides durable PostgreSQL, or concurrent writes routinely block saves. |

**Confirmed for v1: online use only.** Each device must be able to reach the server to open a budget or save changes. Keeping one authoritative copy on the server avoids a persistent offline draft, a queue of pending saves and background synchronization. If a connection drops during editing, keep the input in the open form and let the user retry after reconnecting. Confirm success only after the server acknowledges the save.

Start with SQLite for v1. Revisit PostgreSQL if the hosting or write-load conditions above change. A later migration would need to preserve monthly records and reproduce the same balances and reports.

The target host and its disk are unconfirmed. The first check can use two devices on the same private network with synthetic data. Host and backup costs depend on the eventual operating arrangement. [Spring MVC and templates](https://spring.io/guides/gs/serving-web-content) · [SQLite deployment trade-offs](https://sqlite.org/whentouse.html) · [PostgreSQL architecture](https://www.postgresql.org/docs/current/tutorial-arch.html)

## Responsibilities within the application

- **Browser interaction.** Use one responsive form for desktop and phone. Show the draft separately from the last saved figures, with Russian labels, month names, validation and whole-ruble formatting. On a period change, offer **Сохранить и перейти**, **Перейти без сохранения** and **Остаться**. Escape keeps the draft. Navigate after a requested save succeeds.
- **Budget rules.** Calculate with exact decimal shares and integer rubles. Apply the agreed half-away-from-zero rounding, investment cap, visible shortfall and largest-remainder allocation with stable category addition order. The reviewed form shows percentages of total planning income. For example, 12% and 8% of 100 000 ₽ correspond to 60% and 40% of the 20 000 ₽ discretionary pool. A zero pool allocates zero while preserving the configured split. Actual spending can differ from the plan.
- **Save and read.** Use the signed-in owner's identity to scope every read and write. Store categories and addition order, effective planning settings, monthly plan snapshots and cumulative actuals. In one transaction validate and replace that owner's month. Reject a stale edit from a second device and retain its draft for review. A failed save leaves the last saved figures available. The real-user sign-in method is open. Two distinct test identities can demonstrate budget isolation in the first increment.
- **History and reports.** A historical save updates later opening buffers and recalculates current/future plans from relevant previous-year actuals, treating missing months as zero. Preserve recorded past plans. Derive the monthly remainder from the selected month and carry from earlier completed months. Annual plans sum applicable monthly plans. Yearly totals include saved current-month actuals, while averages use completed months and show «Нет завершённых месяцев» when there are none.

## First increment · replace a monthly total and reopen it on the phone

Start from a private test budget with the synthetic March 2027 plan and history already saved. Deliver one editable category, **Продукты**, on the month screen. This lets the user carry out one regular budgeting task with arbitrary input before the setup, history and reporting screens are built.

| Try it | Expected result |
| --- | --- |
| Open March on desktop and change food from 6 000 ₽ to 25 000 ₽. | The field shows the draft. Saved spending stays 20 000 ₽ and the food remainder stays 18 000 ₽ until Save. |
| Select **Сохранить**, reopen March on the phone as the same owner, then refresh. | Food is 25 000 ₽, March spending is 39 000 ₽, food plan is 24 000 ₽, remainder is −1 000 ₽ and opening buffer is still −2 000 ₽. Repeating the save leaves the totals unchanged. |
| Enter `1 234,50` and select **Сохранить**. | A Russian message asks for a whole-ruble amount. The draft remains editable and the saved total stays unchanged. |
| Disconnect the phone before selecting **Сохранить**, then reconnect. | The open form keeps the draft and shows a connection error in Russian. After reconnection, the desktop still shows the previous total. Retry **Сохранить** on the phone and refresh the desktop to see the replacement total. |
| Reopen after restarting the application. Open March as a different test owner. | The first owner's figures survive the restart. The second owner can access only their own budget, including when requesting a month directly. |

This checks the replacement rule in **AC-04**, the monthly path through **AC-13/14**, and fractional-input rejection in **AC-15**. The walkthrough fixture starts at 6 000 ₽. Also check AC-04's independent 20 000 ₽ → 25 000 ₽ case. On a real phone, ask the participant to enter the total with the keyboard and find Save without prompting.

**Completion of Budget v1** still requires all [18 acceptance examples and the unsaved-navigation checks](budget-v1-user-journey.md). Remaining work includes first-use setup, editable categories and shares, income and contributions, arbitrary periods and history, allocation edge cases, and monthly/yearly review on both devices. The working increment can prove a saved edit. The later year-review walkthrough can test whether the user identifies which months enter the average.
