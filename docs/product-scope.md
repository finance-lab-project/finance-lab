# Finance Lab: product needs and Budget v1 scope

Date: 2026-11-10. Status: agreed product requirements; delivery approach and
technology choices remain open.

This document records the agreed product needs and Budget v1 boundary for this
repository. Product documentation is maintained in `docs/`, with navigation from
the [README](../README.md).

## User, problem and outcome

The first version serves one person who currently maintains a personal budget
in a spreadsheet and periodically updates monthly totals, often weekly.
The application should make that work convenient through a browser on both a
computer and a phone, with the same budget available across devices.

The main job is to distribute expected income, update actual income, spending
and investment contributions, and understand category balances and monthly
and yearly results. The interface should make financial figures easy to read
and edit without relying on a wide spreadsheet.

Budget v1 succeeds when the regular budgeting workflow and its monthly/yearly
review can be completed in Finance Lab without the spreadsheet. Investment
portfolio management and FIRE calculations are later product areas.

## Budget v1 capabilities

1. **Monthly totals.** Select a month and year; manually enter and correct actual
   income, cumulative spending for each category and actual investment
   contributions. Updates replace the recorded monthly total rather than add
   individual purchase transactions.
2. **Planning.** Enter a prepared monthly planning-income amount, set a target
   investment share, calculate essential needs and distribute the remaining
   budget between discretionary categories.
3. **Category settings.** Add and rename categories, assign them to essential
   needs or discretionary spending, change the investment target and set
   discretionary-category shares.
4. **Plan versus actual.** Show category plans, actual amounts, monthly remaining
   amounts and accumulated buffers as distinct figures.
5. **Monthly and yearly review.** Show income, spending, investment contributions
   and category plan/actual results with their accumulated buffers. Annual plans
   aggregate the applicable monthly plans.
6. **Manual history entry.** Enter past monthly plans and actual amounts so that
   historical results and accumulated category buffers can be calculated.

## Planning rules

- Actual monthly income is separate from the income amount used for planning.
  In v1, the planning-income amount is supplied manually after an offline
  calculation. A later capability will calculate it from salary-tagged receipts
  for the previous full calendar year divided by twelve.
- Each essential category's monthly plan is the average of its expenses over
  the previous full calendar year. Large one-off expenses remain included.
- For the initial setup, prepared essential-category averages may use the
  completed months available in the reference data. That temporary starting
  baseline does not change the normal previous-calendar-year rule.
- A new essential category without expense history receives a manually entered
  initial monthly plan.
- The target investment share is configurable. If the target contribution plus
  essential needs exceeds planning income, reduce the planned investment
  allocation to the amount available after essential needs, down to zero.
  Show the reduced allocation relative to the target.
- If essential needs alone exceed income, planned investments and discretionary
  spending are zero and the uncovered shortfall remains visible.
- The remainder after essential needs and planned investments is the
  discretionary budget. The user assigns shares to its categories; those shares
  must total 100% of the discretionary budget.
- Changes to planning income or allocation rules apply to the current and future
  months. Previously recorded plans for past months remain unchanged.
- Saving each month of relevant previous-year expense history automatically
  recalculates current and future plans. Months not yet entered count as zero;
  no separate confirmation or history-completion step is required. Previously
  recorded past plans remain unchanged.

## Category buffers

The buffer shown for a selected month is carried in from recorded earlier,
completed months: their saved plans minus actual spending. It excludes the
selected month. That month's remaining amount is its own plan minus actual
spending, shown separately. Both savings and overspending carry between months
and across year boundaries; future planned months are not accumulated savings.

The monthly plan and buffer remain separate. In a synthetic example, spending
12,000 against a plan of 10,000 produces a buffer of -2,000; the next month's
plan stays 10,000. Accumulated savings can likewise explain how spending above
a monthly plan can remain within the accumulated budget.

Starting buffers are calculated from manually entered historical monthly plans
and actual amounts, rather than entered as standalone opening balances.

## First use, saving and review

A user can start with the current month and add historical monthly plans and
actual amounts later. Buffers initially reflect only the recorded earlier plans;
current rules are not backfilled into months without historical plans.

Monthly edits are committed with one explicit Save action. Calculated totals,
remaining amounts, buffers and reports use saved values and update only after
successful saving. Unentered actual amounts count as zero.

The yearly overview contains monthly income, spending and investment amounts,
plan comparisons, category totals and accumulated buffers. Actual averages use
only completed calendar months of the selected year: January through the month
before the current month for the current year, and all twelve months for a
completed past year. Unentered amounts within those months count as zero.
Yearly actual totals also include saved amounts from the current month.

The [user journey and acceptance criteria](budget-v1-user-journey.md) describe
the workflow, worked examples and separately identified UX recommendations.

## First-version boundary

The first version covers the Budget product area, browser access across devices,
manual data entry and the planning/review rules above. It does not require
individual-purchase bookkeeping, automatic bank import, investment assets or
trades, portfolio valuation, FIRE calculations, or dedicated desktop/phone apps.
Automatic calculation of the planning-income baseline is deferred.

Product areas do not prescribe repositories, services, frameworks, persistence
models or deployment topology. Those choices follow the product workflow and
its acceptance criteria.
