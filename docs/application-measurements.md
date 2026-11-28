# Application measurements

Run the synthetic workload from a checkout of the release you want to measure, using JDK 25 and Maven. Supply the release tag or source revision as the release label.

```sh
mvn -Dtest=MarchBudgetTest#measuresSyntheticScreenTraffic \
  -Dbudget.measurement.release=RELEASE_OR_REVISION test
```

The test starts a loopback server with seeded demo data and a disposable SQLite database. After sign-in and one warm-up pass, one client visits Month, History, Investments, Year, Settings and FIRE in sequence. It repeats the six requests 20 times. A separate anonymous client sends a `HEAD /` probe after each pass, expecting the sign-in redirect. The measured window excludes startup, sign-in and warm-up. This workload measures sequential reads and does not establish capacity under concurrent traffic.

Keep the report header with the route rows. The header records the release, UTC window, attempted and completed synthetic journeys, and request totals. The expected sample is 120 screen requests and 20 probes, for 140 HTTP attempts. Each row reports count, errors, and nearest-rank p50/p95 in milliseconds, from sending the request through consuming the response body. Unexpected statuses, timeouts and transport failures count as errors and contribute to the latency sample. The test fails on errors without enforcing latency thresholds.

The report includes process CPU time spent during the window when available, initial JVM heap use, and maximum heap use sampled after each journey. The test client and server share the JVM, and the sampled heap can miss transient peaks. Compare releases under the same workload and comparable machine conditions.

## Traffic definitions

- A real visit is one human browsing session on the public or demo site, regardless of how many page requests it makes. Synthetic journeys and automated probes contribute zero real visits.
- An HTTP request is one attempt to fetch a resource. A visit can generate multiple requests, including assets, redirects and errors.
- A health probe is an automated availability request. Report it separately from screen requests and visits. It still contributes to the total HTTP request count.

Real-visit counts are unavailable until the privacy inventory defines the approved session signal, handling of automated traffic, and retention. Public and demo collection remains gated on that inventory. Measurement dimensions must exclude financial data and personal labels. Use fixed route templates and aggregate counts, with no query strings, financial identifiers, account names, client addresses or session identifiers in reports.
