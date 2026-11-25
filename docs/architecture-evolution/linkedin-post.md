One question shaped the latest iteration of Finance Lab, a personal budgeting app. What needs to be running so one person can save a monthly budget on a laptop and open the same figures on a phone?

Finance Lab started as a web app built with React, TypeScript and Vite. The next iteration ran on Yandex Cloud and stored budgets in PostgreSQL. For the single-owner pilot, I chose one Spring Boot application that serves the same responsive forms to both devices and stores the budget in SQLite on the server's persistent disk.

SQLite let me remove a database service from the pilot's operating setup. Writes take turns, which fits occasional monthly saves by one owner. The disk and the recovery process still matter because both devices rely on that one stored copy. If concurrent edits routinely delay saves, or if the app moves to a host without persistent local disk, I'd revisit PostgreSQL.

My takeaway is to make each piece of infrastructure earn its place against a specific product need or operating constraint. Writing down what would change the decision makes it easier to revisit when those conditions change.

Architecture report — [Zenodo DOI pending publication]
