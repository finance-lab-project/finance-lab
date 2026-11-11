# Finance Lab: product needs and Budget v1 scope

Date: 2026-11-11. Status: agreed product requirements; delivery approach and
technology choices remain open.

This document records the agreed product needs and Budget v1 boundary for this
repository. Product documentation is maintained in `docs/`, with navigation from
the [README](../README.md).

## Market, language and currency

The application targets the Russian market. Version one provides a fully Russian
interface: application labels, actions, month names, validation messages, errors,
empty states and report headings are in Russian. An English interface and
language switcher are outside the first-version scope.

RUB is the default and only budget currency in version one. Present amounts in
Russian notation without kopecks, for example 1 234 ₽. Multi-currency support is deferred to a
later version.

Use [Google Material Design](https://m3.material.io/) as the primary UI/UX
reference, with Google's guidance on clear, task-oriented interface text.
This guides interaction and screen design; implementation choices remain open.

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

## Whole-ruble amounts and allocation

Whole RUB is the agreed monetary precision for v1: entered amounts, saved
monthly plans and displayed monetary figures have no kopecks. A non-zero
fractional monetary input is explained as invalid rather than silently rounded.
Percentage shares can be fractional; monetary precision does not round shares.

The following calculation detail is the agreed v1 rule:

1. Keep intermediate averages and percentage quotas unrounded until an integer
   monetary amount is needed. Round a single calculated amount to the nearest
   ruble; an exact half ruble rounds away from zero (10.5 becomes 11, -10.5
   becomes -11).
2. Round each essential category's calculated monthly average once to obtain
   its whole-ruble plan. Essential needs are the sum of those category plans.
3. Round the investment target amount once, then cap it at the whole-ruble
   amount left after essential needs, down to zero. Compute the discretionary
   budget as the exact remainder of planning income minus essential needs and
   planned investments.
4. For valid nonnegative discretionary shares totalling 100%, calculate each
   category's exact share of the discretionary budget and first take its whole
   part. Assign the remaining rubles one at a time to categories with the largest
   discarded fractional parts. If parts are equal, the earlier-added category
   receives the ruble first. Renaming a category or reopening the budget does
   not change this tie order.
5. A zero discretionary budget gives zero to every discretionary category.
   Invalid percentage totals are still rejected, not repaired by rounding.

When income covers essential needs, the saved essential plans, investments and
discretionary category plans add up exactly to planning income. If essential
needs exceed income, retain the existing shortfall behaviour instead of forcing
a balance through negative or reduced essential-category amounts.

Totals and buffers use the saved whole-ruble amounts. Statistical averages are
rounded only for presentation; totals are not built by adding rounded averages.
A displayed average may therefore differ from a sum of independently rounded
category averages. Rounding does not change entered actuals or past saved plans.
The allocation remainder is not an extra expense, income or carried buffer.

This is a v1 RUB rule. Multi-currency precision remains later work.

## Category buffers

The buffer shown for a selected month is carried in from recorded earlier,
completed months: their saved plans minus actual spending. It excludes the
selected month. That month's remaining amount is its own plan minus actual
spending, shown separately. Both savings and overspending carry between months
and across year boundaries; future planned months are not accumulated savings.

The monthly plan and buffer remain separate. In a synthetic example with no
earlier carry, a completed month's spending of 12,000 against a plan of 10,000
leaves -2,000 carried into the following month; that next month's plan stays
10,000. Accumulated savings can likewise explain how spending above a monthly
plan can remain within the accumulated budget.

Starting buffers are calculated from manually entered historical monthly plans
and actual amounts, rather than entered as standalone opening balances.

## First use, saving and review

A user can start with the current month and add historical monthly plans and
actual amounts later. Buffers initially reflect only the recorded earlier plans;
current rules are not backfilled into months without historical plans.

Monthly edits are committed with one explicit **Сохранить** (Save) action.
Calculated totals, remaining amounts, buffers and reports use saved values and
update only after successful saving. Unentered actual amounts count as zero.

The yearly overview contains monthly income, spending and investment amounts,
plan comparisons, category totals and accumulated buffers. Actual averages use
only completed calendar months of the selected year: January through the month
before the current month for the current year, and all twelve months for a
completed past year. Unentered amounts within those months count as zero.
Yearly actual totals also include saved amounts from the current month.

The [user journey and acceptance criteria](budget-v1-user-journey.md) describe
the workflow, worked examples and agreed interaction rules.

## First-version boundary

The first version covers the Budget product area, browser access across devices,
manual data entry and the planning/review rules above. It does not require
individual-purchase bookkeeping, automatic bank import, investment assets or
trades, portfolio valuation, FIRE calculations, or dedicated desktop/phone apps.
Automatic calculation of the planning-income baseline is deferred.

Product areas do not prescribe repositories, services, frameworks, persistence
models or deployment topology. Those choices follow the product workflow and
its acceptance criteria.
