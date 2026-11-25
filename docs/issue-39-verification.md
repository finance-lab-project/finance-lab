# Issue #39 portfolio review verification

On 2026-11-25, Chromium rendered all three states at both 1440 × 900
(desktop) and 390 × 844 (phone with touch emulation). At both widths the
summary and account fields fit without horizontal scrolling or clipping.

| Scenario | Verified at both widths |
| --- | --- |
| March 2027 example | Account values of 100,000 ₽ and 160,000 ₽ total 260,000 ₽. The previous total is 240,000 ₽. The February → March change in value is +20,000 ₽. The 1,000 ₽ credited coupon and 15,000 ₽ saved Budget contribution appear separately. |
| First recorded month | February account values of 95,000 ₽ and 145,000 ₽ total 240,000 ₽. Credited income shows 0 ₽. The January valuation and January → February comparison show as unavailable. |
| Missing account value | The second February account shows a missing-record message and blank fields. The first account still shows its 95,000 ₽ valuation and recorded zero income. The complete portfolio value, income total, and comparison show as unavailable. |

The browser loaded snapshots rendered from the synthetic SQLite fixture
through MockMvc, with the application stylesheet. The environment denied
binding a local server socket, so browser coverage was limited to rendering
and layout. The focused Maven regression passed on JDK 27 with the Java 25
target, including a saved Budget correction to 0 ₽, a skipped calendar
month, and partial and complete prior-month valuations.
