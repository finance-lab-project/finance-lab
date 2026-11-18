# Budget v1 implementation verification

18 November 2026 · [Issue #13](https://github.com/finance-lab-project/finance-lab/issues/13)

## Working application walkthrough

Chrome at 1280 px and a separate 390 px iPhone-emulated Chrome session each started with an empty owner budget. Both saved a plan, entered March spending, income and contributions, opened **Итоги** from the budget menu, and followed the March link back to the saved month. The report used tables on desktop and cards at 390 px with no horizontal overflow.

With the March 2027 demo fixture, the desktop session replaced **Продукты** at 6,000 ₽ with 25,000 ₽. The phone-sized session signed in as the same owner and saw 150,000 ₽ annual spending, a 55,500 ₽ average and the saved 25,000 ₽ on the March screen. Both sessions reached **Итоги** through its labelled menu link and displayed **Январь — февраль 2027** under the completed-month average.

An independent participant's ability to find **Итоги** and explain the averaging period remains unverified. The setup-to-report walkthrough and shared-data check also remain pending on a physical phone. The [participant task](budget-v1-participant-task.md) has the review questions.

## Acceptance examples

The [user journey](budget-v1-user-journey.md#acceptance-examples) defines the examples. The browser checks used synthetic budgets. `mvn -o verify` passed all 19 tests on Java 25 using temporary SQLite files.

| Example | Check and observation |
| --- | --- |
| AC-01 | Desktop and phone emulation started without history and saved setup and actuals. The first monthly actuals and carried buffers were zero. |
| AC-02 | Planning tests verified 40,000 ₽ in needs, 40,000 ₽ in investments and a 20,000 ₽ discretionary pool, allocated 12,000 ₽ and 8,000 ₽. |
| AC-03 | The fixture and history tests retained the 13,000 ₽ housing plan with the December one-off and the 3,000 ₽ initial health plan. |
| AC-04 | The HTTP test replaced an independent 20,000 ₽ Food actual with 25,000 ₽ and reopened the saved month. The browser showed the previously saved total while the new value was still a draft. |
| AC-05 | Saving 60% and 50% returned a validation error identifying 110%. The previous plan remained available. |
| AC-06 | With 50,000 ₽ of planning income and 40,000 ₽ in essential plans, the calculator returned 10,000 ₽ for investment and zero discretionary spending. |
| AC-07 | With income of 30,000 ₽ and needs of 40,000 ₽, the calculator returned a 10,000 ₽ shortfall and zero investment and discretionary amounts. |
| AC-08 | January, February and March opened with Food buffers of −2,000 ₽, −3,000 ₽ and −2,000 ₽ and the same 24,000 ₽ base plan. |
| AC-09 | The report returned actual totals of 250,000 / 131,000 / 90,000 ₽ and January–February averages of 100,000 / 55,500 / 40,000 ₽. After the March correction, total spending was 150,000 ₽ and the average stayed 55,500 ₽. The 1 March buffer included earlier carry and stayed at −5,000 ₽ overall and −2,000 ₽ for Food. A completed year included all 12 months in its divisor and January showed **Нет завершённых месяцев**. |
| AC-10 | Saving a separate 12,000 ₽ increase to December 2026 Food actuals recalculated March Food to 25,000 ₽, needs to 41,000 ₽ and the discretionary split to 11,400 / 7,600 ₽. January and February kept their plans. The March opening and year-report Food buffers both became −14,000 ₽. |
| AC-11 | Independently raising planning income to 120,000 ₽ kept the January and February plans. The March investment plan became 48,000 ₽ and the discretionary pool 32,000 ₽. The annual report showed 840,000 ₽ of planned spending and 560,000 ₽ of planned investment. |
| AC-12 | Each successful historical entry immediately updated dependent plans and buffers. Failed validation and a failed database write retained the previously committed figures. |
| AC-13 | Desktop and iPhone-emulated Chrome completed setup, editing, year selection and month drill-down against the running app. Two separate sessions saw the same committed March totals. The physical-phone and participant checks above remain open. |
| AC-14 | Both browser sessions showed Russian month and report labels, grouped whole rubles and the ₽ sign on the checked paths. |
| AC-15 | Entering `1234,50` produced a Russian whole-ruble validation message. The screen retained the draft and the saved amount. |
| AC-16 | The allocation test produced 33 / 33 / 34 ₽ from 100 ₽ with shares of 33.3% / 33.3% / 33.4%. |
| AC-17 | The calculator split a 7 ₽ tie into 4 / 3 ₽ and zero pools into zero amounts. The HTTP test reopened the same 4 / 3 ₽ split after renaming the first category. |
| AC-18 | Both browser sessions entered a 366 ₽ annual essential baseline, 101 ₽ of income and a 50% investment target. The saved allocations were 31 ₽ essential, 51 ₽ investment and 11 / 8 ₽ discretionary. The calculator retained the 366 ₽ source value. |

## Unsaved navigation

| Check | Observation in Chrome |
| --- | --- |
| Clean navigation | A month switch with an untouched edit form proceeded directly. |
| Visible draft state | Editing March showed the unsaved notice while the expense total showed the previous save. |
| Stay, close and Escape | Each kept the draft and the edited month on screen. |
| Discard and switch | The year report opened with the previously saved figures after discarding a monthly draft on desktop and a history draft in the phone-sized session. |
| Save and switch | The year report opened after the server confirmed the save and showed the new spending total. |
| Failed save | Both fractional input and a simulated offline request kept the entered amount on the same month and showed an error. Retrying the offline save after reconnection succeeded. |
