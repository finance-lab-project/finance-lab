# Budget v1: user journey and acceptance criteria

Date: 2026-11-11. Related work: [issue #1](https://github.com/finance-lab-project/finance-lab/issues/1).

The [product scope](product-scope.md) is the source of agreed requirements.
This document records the clarified user journey, checkable examples and the
approved unsaved-navigation interaction. The first version uses a Russian-only
interface and RUB. Screen layout, implementation and technologies are separate work.

## User and successful session

One person opens Finance Lab in a browser and consults a banking application
on the phone. The regular sequence is category spending, income and investment
contributions, followed by checking the saved budget and its trends.

A session is complete when the updated monthly totals have been saved and the
user can assess plans, remaining amounts, carried buffers and monthly/yearly
results. The same tasks must be possible in a browser on a computer or phone.

## Main journey

### 1. Start and prepare the current month

1. Open the current calendar month. Historical data is optional for starting.
   Actual amounts not yet entered are zero.
2. Supply the prepared planning-income amount and investment target. Configure
   essential and discretionary categories and the discretionary shares.
3. Use the agreed initial essential-category baselines. A new essential category
   without relevant history receives a manually entered initial monthly plan.
4. Save the setup. The saved plan shows essential needs, the planned investment
   allocation and the discretionary distribution. Invalid discretionary shares
   are explained rather than silently normalised.
5. Read the month's plan and actual amounts. With no earlier recorded plans,
   the carried buffer is zero; it does not include this month's unused plan.
6. Add past monthly plans and actual amounts whenever convenient. Each successful
   historical-month save updates dependent buffers and reports. Relevant
   previous-year expense changes automatically recalculate current/future plans;
   past monthly plans remain as recorded.

There is no separate requirement to complete a full year's history before using
the application. During backfill, still-unentered actuals count as zero and each
month's save can change the current plan. There is no extra approval or
"history complete" action.

### 2. Perform the regular update

1. Select the month to update and consult the banking application.
2. Replace category spending with the latest cumulative monthly totals, then
   enter cumulative actual income and investment contributions.
3. Until Save, calculated figures remain based on the last saved values.
   Editing actual income does not change the separate planning-income baseline.
4. Select **Сохранить** once for the edited month.
5. After successful saving, read the updated plan/actual figures, monthly
   remaining amounts and relevant reports. The selected month's spending does
   not alter its carried-in buffer; it contributes to later months' buffers.
6. Check the monthly series for the selected year, overall/category totals,
   completed-month averages and progress against the applicable plans.
7. Correct a value by editing the monthly total and saving again. A correction
   replaces that total rather than adding another transaction.

### 3. Review months and years

- Monthly remaining amount = that month's plan minus its saved actual amount.
- The buffer for a month uses recorded completed months before that month.
  It can be positive or negative and remains separate from the monthly plan.
  Current or future unused plans are not counted as accumulated savings.
- A historical correction updates later carried buffers, including across years.
- Yearly actual totals include all saved actuals in that year, including the
  in-progress current month; unentered actual amounts are zero.
- For the current year, averages use January through the last completed calendar
  month, excluding the current month from both the numerator and denominator.
  For a completed past year, averages use all twelve months.
- If there are no completed months, no numeric average can be calculated.
  Explain in Russian that no completed-month average is available, for example
  "Нет завершённых месяцев", instead of displaying a division error or treating
  an undefined average as a real zero.
- A yearly buffer is the accumulated balance at the report's completed-month
  cutoff, including carry from earlier years. It is not the sum of monthly
  opening buffers.
- Annual plans sum the applicable monthly plans, including changes made during
  the year; they are not the latest monthly plan multiplied by twelve.
  Label full-year plans and actual totals with their periods.

## Worked example

All figures below are synthetic RUB amounts. Comma-grouped values in these
English documentation tables specify amounts, rather than the final interface
format; the application uses Russian monetary presentation. Assume the review
date is 15 March 2027. The fixture includes history entered after starting; that
history is not a prerequisite for the first current-month session.

### Planning inputs and history

- Planning income: 100,000 per month. Investment target: 40%.
- Housing expenses in 2026: 12,000 in each of eleven months and 24,000 in
  December. The one-off expense remains included: 156,000 / 12 = 13,000.
- Food expenses in 2026: 24,000 each month, so the monthly average is 24,000.
- Health is a new essential category without history: initial plan 3,000.
- Discretionary shares: Leisure 60%, Travel 40%.
- Earlier recorded plans: Housing 12,000 throughout 2026; Food 24,000 for
  January–November and 22,000 in December. There is no earlier history and
  no earlier plans for the other categories.

| Allocation | Monthly amount |
| --- | ---: |
| Essential needs: Housing + Food + Health | 40,000 |
| Planned investments | 40,000 |
| Discretionary budget | 20,000 |
| Leisure | 12,000 |
| Travel | 8,000 |

The five spending-category plans total 60,000. With planned investments of
40,000, the complete allocation equals the 100,000 planning-income amount.
Historical January/February 2027 plans use this same fixture allocation.

### Saved monthly actuals and carried buffers

| Category | Monthly plan | January actual | February actual | March actual | Buffer entering January | Buffer entering March |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Housing | 13,000 | 12,000 | 13,000 | 13,000 | -12,000 | -11,000 |
| Food | 24,000 | 25,000 | 23,000 | 6,000 | -2,000 | -2,000 |
| Health | 3,000 | 1,000 | 2,000 | 0 | 0 | 3,000 |
| Leisure | 12,000 | 10,000 | 14,000 | 1,000 | 0 | 0 |
| Travel | 8,000 | 5,000 | 6,000 | 0 | 0 | 5,000 |
| Total spending | 60,000 | 53,000 | 58,000 | 20,000 | -14,000 | -5,000 |

Food illustrates the distinction between plan, remaining amount and carry:
January's opening buffer is -2,000 and remaining amount is -1,000.
February's opening buffer becomes -3,000 while its plan stays 24,000.
March's opening buffer is -2,000 and its remaining amount is 18,000.
March spending does not change the -2,000 opening buffer.

Travel shows positive carry: February's plan stays 8,000 and its opening buffer
is 3,000. Explain that the accumulated budget permits 11,000 of spending for
that month. After the saved 6,000 actual, the monthly remaining amount is 2,000;
remaining room including carry is 5,000. Do not replace the base plan with
either of those carried-budget figures.

| Actual measure | January | February | March | Year total | Completed-month average |
| --- | ---: | ---: | ---: | ---: | ---: |
| Income | 98,000 | 102,000 | 50,000 | 250,000 | 100,000 |
| Spending | 53,000 | 58,000 | 20,000 | 131,000 | 55,500 |
| Investment contributions | 35,000 | 45,000 | 10,000 | 90,000 | 40,000 |

March contributes to year totals, but only January and February contribute
to averages on the assumed review date. Unentered April–December actuals are zero.

| Spending category | Year actual total | Completed-month average |
| --- | ---: | ---: |
| Housing | 38,000 | 12,500 |
| Food | 54,000 | 24,000 |
| Health | 3,000 | 1,500 |
| Leisure | 25,000 | 12,000 |
| Travel | 11,000 | 5,500 |

## Acceptance examples

Each example starts from the fixture above unless stated otherwise.

| ID | Input or action | Expected visible result |
| --- | --- | --- |
| AC-01 | Start the current month without entering history. | Setup and monthly updates are possible; unentered actuals and carried buffer are zero. Prepared essential baselines or manual initial plans can be supplied. |
| AC-02 | Save the fixture's planning inputs. | Needs 40,000; investments 40,000; discretionary budget 20,000, split 12,000/8,000. |
| AC-03 | Housing's 2026 expense history contains the December one-off; Health has no history. | Housing plan is 13,000; Health uses its manually supplied 3,000 plan. |
| AC-04 | A saved Food actual of 20,000 is edited to 25,000. | Before Save, calculations still use 20,000. After successful Save and reopening the month, Food is 25,000, not 45,000. |
| AC-05 | Discretionary shares are changed to 60% and 50%. | Saving the invalid allocation does not replace the valid saved plan; the total-share problem is explained. |
| AC-06 | Planning income becomes 50,000; needs remain 40,000 and the investment target is 40%. | Planned investments reduce to 10,000; discretionary budget is zero. |
| AC-07 | Planning income becomes 30,000 with needs of 40,000. | Planned investments and discretionary budget are zero; the uncovered shortfall is 10,000. |
| AC-08 | Review Food in January, February and March. | Opening buffers are -2,000, -3,000 and -2,000 respectively; monthly plans remain 24,000. Current-month actuals affect remaining amounts, not the selected month's opening buffer. |
| AC-09 | Review the year on 15 March 2027. | Actual totals are income 250,000, spending 131,000 and investments 90,000; completed-month averages are 100,000, 55,500 and 40,000. |
| AC-10 | In March, correct 2026 Food expenses by adding 12,000 and save that historical month. | Food's annual average becomes 25,000 and needs become 41,000. March/future discretionary budgets become 19,000, split 11,400/7,600. Past January/February plans remain unchanged; March's Food opening buffer becomes -14,000. |
| AC-11 | Independently of AC-10, change planning income to 120,000 in March, keeping needs 40,000 and target 40%. | March/future investments become 48,000 and discretionary budget 32,000. January/February plans stay unchanged. With these plans through December, annual spending plan is 840,000 and investment plan 560,000. |
| AC-12 | Save each month while backfilling relevant previous-year history. | Dependent current/future plans recalculate after every successful month save, using zero for still-unentered actuals; no history-completion confirmation is required. |
| AC-13 | Follow the journey in a browser on a computer or phone. | All required inputs, period selection, Save and reports are reachable; the same saved month is available across devices. |
| AC-14 | Open a budget, edit amounts and inspect month/year reports. | All application-provided text and month names are Russian; monetary amounts use RUB and Russian notation, for example 1 234,56 ₽. English switching and other budget currencies are outside v1. |

## Agreed handling of unsaved navigation

The user approved this interaction for the workflow with one explicit Save
action. It preserves the choice to save, discard edits or continue editing.

When a different month/year is requested and there are unsaved edits, ask whether
to save before switching. Use explicit action labels rather than "Yes" and "No".

| Action label | Outcome |
| --- | --- |
| **Сохранить и перейти** | Save the edited month and switch to the requested period only after successful saving. |
| **Перейти без сохранения** | Discard only the unsaved edits and switch; previously saved data remains intact. |
| **Остаться** | Cancel switching and keep the edited values in the current month. |

Acceptance checks:

- With no unsaved edits, switching needs no confirmation.
- Closing the prompt or pressing Escape behaves like "Остаться".
- If saving fails or validation prevents it, keep the current month and the
  entered values; explain the problem rather than switching or claiming success.
- A visible indication distinguishes unsaved edits from figures based on the
  last successful Save.

Google guidance is the primary UI/UX reference:
[Material Design dialog guidelines](https://m3.material.io/components/dialogs/guidelines),
[Google's dialog guidance](https://developer.android.com/develop/ui/views/components/dialogs?hl=en)
for decision prompts, and
[Google's UI elements and interaction guide](https://developers.google.com/style/ui-elements)
for clear labels and task-oriented instructions. These references inform the
screen design while preserving the agreed navigation outcomes.

## Review points and boundary

The business and unsaved-navigation rules above follow the recorded decisions.
Wording and layout can be refined with the screen sketches while preserving
those outcomes and the Russian-only interface. Monetary rounding for fractional
allocations is not resolved by these exact-value examples.

This document does not select technologies or implement screens, imports,
individual transactions, portfolio management or FIRE.
