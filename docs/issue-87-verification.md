# Release candidate review for issue 87

Reviewed 28 November 2026 against the application source, SQL, configuration and runbooks in the supplied checkout.

| Area | Evidence and status |
| --- | --- |
| Authentication and ownership | The Spring Security chain requires sign-in for budget routes. Controllers derive the owner from the authenticated principal. Budget and settings SQL scope by owner and period. Investment writes resolve account ID and owner in the same statement. FIRE selection replacement is transactional and rejects account IDs outside the owner's account set. Existing Maven tests exercise separate owners and sessions with synthetic data. |
| Sessions and password handling | The normal profile requires a bcrypt verifier with a minimum cost of 10. Demo sign-ins require two distinct supplied passwords and are restricted to the fixture profile. CSRF remains enabled in the filter chain. Session tracking now requires cookies. New regression coverage checks CSRF on logout and access after logout. Live HTTPS cookie attributes and secret provisioning remain unverified. |
| CI and backup access | Workflow definitions and backup scripts and units are absent from this checkout. The runbook specifies scoped storage access, restrictive local permissions and an off-host recovery identity. Effective CI permissions, deployed file permissions, bucket policy, timer and encrypted restore remain unverified. |
| Local restore | A synthetic WAL database built from the application schema was backed up through SQLite's read-only source connection while the writer remained open. The copy passed integrity and foreign-key checks, preserved saved figures including zero, and could be edited independently. |
| Simulated encrypted restore | The [backup rehearsal](sqlite-backup.md#rehearsal-result) records a successful encrypted upload, download and restore against simulated Object Storage, including an empty-budget run. Live storage access and an encrypted restore from live storage remain open. |
| Budget amounts | SQLite stores signed whole rubles. A missing budget actual projects to zero in month and year summaries, while history tracks whether a category row exists. A present zero participates in rollover calculations. The old monthly SQL buffer could overflow on a sum of valid amounts. Month and year now use the same exact Java aggregation over owner-scoped committed rows. Regression coverage checks both rendered buffers above the range of a single amount. |
| Investment and FIRE amounts | A missing investment month stays absent through the left join and view model. An explicit zero counts as a saved observation. Investment totals require complete account data, and FIRE income averages use complete months. Month, history and investment inputs now share ruble field validation directly. |
| Startup backfill | Startup now sums historical actuals exactly and preserves existing planning rows. The save-and-reopen regression saves two maximum-size historical actuals, checks that both remain readable after restart, and verifies the exact backfill for a synthetic legacy owner. |

## Follow-ups

| Priority | Work and verification needed |
| --- | --- |
| Before release | The controller should verify Maven results, effective workflow permissions, secret isolation on untrusted events and the password verifier's provisioning path against the delivery configuration. Check HTTPS session cookies through the live proxy. |
| Before migrating a legacy database | Check whether a backfilled annual total exceeds the signed 64-bit range. Startup stores the exact total as text, while the settings calculator still parses annual values as a single signed 64-bit amount. |
| Before admitting pilot data | The operations owner should verify backup access scope and local permissions, then restore an encrypted backup to an isolated database and compare representative saved figures. |
| After release checks | Review the read-triggered plan refresh in MonthStore and PlanStore. Preserve the current rollover behavior while defining when writes should occur and how concurrent first reads are handled. Compare the history JavaScript percentage preview with the server display at signed 64-bit boundaries before changing that calculation. |

Use the private reporting process in [SECURITY.md](../SECURITY.md) for sensitive operational evidence or findings.

Maven could not resolve the Spring Boot 4.2.0 parent from the available offline cache. The Java regression tests are pending CI.
