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

## Run the March 2027 budget

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
Open [March on this computer](http://127.0.0.1:8080/months/2027-03) and sign
in with the first account and its printed password. Select **Редактировать**,
enter the cumulative food total and select **Сохранить**. The other account
has its own copy of March.

This increment opens March 2027. [Issue #11](https://github.com/finance-lab-project/finance-lab/issues/11)
covers selecting a month or year and handling unsaved edits when switching.
[Issue #12](https://github.com/finance-lab-project/finance-lab/issues/12)
covers entering historical months and recalculating later opening balances.

For the phone walkthrough, set `BUDGET_BIND` to this computer's private Wi-Fi
IPv4 address before starting Java. On both devices, open the same address, for
example `http://<host-private-ip>:8080/months/2027-03`. Use the same test
account on both devices. This sign-in is for the private-network fixture.

SQLite creates `march-budget.sqlite` in the working directory. Restart from
the same directory, or set `BUDGET_DB` to the same file on persistent local
disk. Startup fills missing fixture rows and preserves saved amounts. To try
the initial fixture again, point `BUDGET_DB` at a new filename.

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
