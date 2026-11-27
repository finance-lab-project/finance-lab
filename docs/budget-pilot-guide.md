# Budget pilot and release guide

This guide records the Budget pilot setup, fixture walkthrough and original verification evidence. For the released demo, start with the [quick start](../README.md#run-the-local-fixture).

## Releases

Each milestone delivers a usable version at the same HTTPS address. Milestone releases and smaller patch releases use the weekly maintenance window. Build and test the candidate in advance and retain the versioned artifact, its checksum and the previous working binary.

- The [weekly SQLite backup](sqlite-backup.md) starts on Sunday at 03:00 Europe/Moscow. It verifies the stored copy and checks saved budget data using private reference values, including weeks without a release candidate.
- If a tested candidate is ready and the checks pass, deploy it in that window. Keep writes consistent across the switch, verify sign-in and compare the saved month and yearly total before reopening writes. A failed backup or pre-update check defers the update. If post-update verification fails, recover the service while preserving accepted saves.
- Publish the matching Git tag and GitHub Release after verification at the permanent address. Include the deployed revision and user-visible changes. Close the milestone when its version is available.

The [first personal release, v0.1.0](https://github.com/finance-lab-project/finance-lab/releases/tag/v0.1.0) replaces the [previous Yandex Cloud application](https://github.com/finance-lab-project/app/issues/6) at the existing address. The new owner starts with an empty SQLite budget. The [launch issue](https://github.com/finance-lab-project/finance-lab/issues/24) covers the switch and retirement of the legacy PostgreSQL budget database.

## Run the private pilot

The single-owner pilot uses the existing Yandex Cloud VM and HTTPS address. See the
[private pilot runbook](private-pilot.md) for `pilot-owner` sign-in, persistent
SQLite storage, the systemd unit, credential recovery and the two-device release check.

## Run the local fixture

Use JDK 25 and Maven 3.9 or newer. From the repository root, build the
application and set two distinct disposable passwords for the synthetic
accounts `owner-a` and `owner-b`. The following example uses a POSIX shell.

```sh
mvn verify
export BUDGET_OWNER_A_PASSWORD="$(openssl rand -hex 12)"
export BUDGET_OWNER_B_PASSWORD="$(openssl rand -hex 12)"
printf 'owner-a: %s\nowner-b: %s\n' "$BUDGET_OWNER_A_PASSWORD" "$BUDGET_OWNER_B_PASSWORD"
java --enable-native-access=ALL-UNNAMED -jar target/finance-lab-0.1.0.jar --spring.profiles.active=fixture
```

The Java option allows the SQLite JDBC driver to load its native library.
Open [the budget on this computer](http://127.0.0.1:8080/) and sign in. A new
database opens the current calendar month with an empty budget for each owner.
In **Настройки**, enter the prepared planning income and investment target,
then add essential and discretionary categories. Enter a monthly essential
plan directly or supply a previous-year expense total to calculate an average.
The linked ruble and percentage fields use total planning income. **Доля
свободного бюджета** sets the split of the remainder, including when it is zero.
**Сохранить** opens the saved month. Later planning income or category changes
take effect from the current month.

Choose a month and year and select **Перейти**, then **Редактировать**. Replace
the cumulative spending for each category in rubles or as a percentage of
planning income. Enter actual income and investment contributions separately.
**Сохранить** commits the whole month. **Отменить** restores the saved figures.
When switching periods with edits, choose whether to save, discard or stay.

The optional `demo` Spring profile opens March 2027 with the worked example for
both test accounts. To run that walkthrough, replace the `fixture` profile on the command line with
`--spring.profiles.active=demo`. Use a separate `BUDGET_DB` file when trying the
fixture alongside an empty budget. In March, the initial figures include
50 000 ₽ actual income, 10 000 ₽ in contributions and 6 000 ₽ spent on food.
A database from issue #9 keeps its recorded plans and category actuals.

For the phone walkthrough, set `BUDGET_BIND` to this computer's private Wi-Fi
IPv4 address before starting Java. Open the same address on both devices,
for example `http://<host-private-ip>:8080/`, and use the same test account.
This sign-in is for the private-network fixture. SQLite uses
`march-budget.sqlite` in the working directory by default. Restart from the
same directory or set `BUDGET_DB` to a file on persistent local disk.

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
