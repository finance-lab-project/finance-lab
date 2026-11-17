# Finance Lab

Finance Lab is a personal budgeting application for planning monthly income,
spending and investment contributions, with monthly and yearly review.

This repository contains the new product iteration. Budget is the first-version
scope; investment portfolio management and FIRE calculations are later areas.

The first version targets the Russian market: a Russian-only interface and RUB
budgets in whole rubles, without kopecks. Multi-currency support is later work.
Google's Material Design is the primary reference for UI/UX decisions.

## Documentation

- [Product needs and Budget v1 scope](docs/product-scope.md) — user needs, monthly
  workflow, planning rules, category settings, buffers and first-version limits.
- [Budget v1 user journey and acceptance criteria](docs/budget-v1-user-journey.md) —
  setup, regular updates, reporting and worked acceptance examples.
- [Budget v1 screens and navigation](docs/budget-v1-screen-sketches.md) — final
  desktop designs, phone sketches, states and links to the public Figma file.
- [Budget v1 prototype walkthrough](docs/budget-v1-prototype-review.md) — desktop and
  phone review tasks, expected results and participant feedback status.
- [Budget v1 implementation approach](docs/implementation-approach.md) — accepted
  technology choice, trade-offs and the first cross-device monthly save.

The `docs/` directory is the main source of project documentation. Changes are
recorded in Git; the Wiki is reserved for possible future use.

## Run the budget

Use JDK 25 and Maven 3.9 or newer. From the repository root, build the
application and set two distinct disposable passwords for the synthetic
accounts `owner-a` and `owner-b`. The following example uses a POSIX shell.

```sh
mvn verify
export BUDGET_OWNER_A_PASSWORD="$(openssl rand -hex 12)"
export BUDGET_OWNER_B_PASSWORD="$(openssl rand -hex 12)"
printf 'owner-a: %s\nowner-b: %s\n' "$BUDGET_OWNER_A_PASSWORD" "$BUDGET_OWNER_B_PASSWORD"
java --enable-native-access=ALL-UNNAMED -jar target/finance-lab-0.1.0-SNAPSHOT.jar
```

The Java option allows the SQLite JDBC driver to load its native library.
Open [the budget on this computer](http://127.0.0.1:8080/) and sign in. A new
database opens the current calendar month with an empty budget for each owner.
In **Настройки**, enter the prepared planning income and investment target,
then add essential and discretionary categories. Enter a monthly essential
plan directly or supply a previous-year expense total to calculate an average.
The linked ruble and percentage fields use total planning income. **Доля
свободного бюджета** sets the split of the remainder, including when it is zero.
**Сохранить** opens the saved month. Later income or category changes take
effect from the current month. The initial monthly editor can update the actual
for the first category you added.

The optional `demo` Spring profile opens March 2027 with the worked example for
both test accounts. To run that walkthrough, start the same jar with
`--spring.profiles.active=demo`. Use a separate `BUDGET_DB` file when trying the
fixture alongside an empty budget. In March, select **Редактировать** to
replace the cumulative food total from issue #9. A database from that
increment keeps its recorded plans and actuals.

For the phone walkthrough, set `BUDGET_BIND` to this computer's private Wi-Fi
IPv4 address before starting Java. Open the same address on both devices,
for example `http://<host-private-ip>:8080/`, and use the same test account.
This sign-in is for the private-network fixture. SQLite uses
`march-budget.sqlite` in the working directory by default. Restart from the
same directory or set `BUDGET_DB` to a file on persistent local disk.

[Issue #11](https://github.com/finance-lab-project/finance-lab/issues/11)
covers the period picker and editing all monthly actuals.
[Issue #12](https://github.com/finance-lab-project/finance-lab/issues/12)
covers historical entry and recalculation from newly entered expenses.

### Issue #9 verification · 14 November 2026

- **Real phone on private Wi-Fi.** The participant entered a total of their
  choice with the phone's on-screen keyboard and found **Сохранить** without
  a hint. An independent desktop browser signed in as the same owner, reopened
  March and still showed the changed food total after refresh.
- **Automated browser checks.** Desktop Chrome and a separate 390-pixel mobile
  browser context exercised the 6 000 → 25 000 ₽ edit, refresh, repeated
  Save, fractional input and a stale edit. Disconnecting the mobile context
  before Save kept the draft editable and the desktop total unchanged.
  Reconnecting and explicitly retrying made the replacement visible on desktop.
- **Integration tests.** `mvn test` checks signed-in HTTP requests against a
  real SQLite file, direct month requests by a second owner, stale revisions,
  validation, and reopening the database after an application restart. It also
  covers the independent 20 000 → 25 000 ₽ replacement in AC-04.

## Earlier implementation

The [previous application](https://github.com/finance-lab-project/app) preserves
the earlier implementation and release history.
