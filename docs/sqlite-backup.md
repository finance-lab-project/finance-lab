# SQLite backup and recovery

## Take a snapshot

The application can keep running. The script opens the source read-only and uses
SQLite's online backup API to copy one consistent state, including committed
changes in the write-ahead log. Give it a fresh destination outside the checkout.
It publishes the standalone file only after the copy completes and exits nonzero
on failure. [SQLite Online Backup API](https://sqlite.org/backup.html)

With access to the database, set `BUDGET_DB` to its active path and run:

```sh
backup_dir=$(mktemp -d)
python3 ops/budget-snapshot.py create "$BUDGET_DB" "$backup_dir/budget.sqlite"
python3 ops/budget-snapshot.py check "$backup_dir/budget.sqlite"
```

The check runs SQLite integrity and foreign-key checks. A successful check prints
`SQLite snapshot verified.` Add `--reference` with the path to a private JSON file
to also compare the saved figures.

## Configure the weekly backup

The [service](../ops/finance-budget-backup.service) takes a snapshot on the pilot
VM and uploads an age-encrypted copy to private Object Storage under
`database/sqlite/`. It downloads the ciphertext to verify its SHA-256, then runs
the SQLite and figure checks against the snapshot used for that upload. The
[timer](../ops/finance-budget-backup.timer) starts the job every Sunday at
**03:00 Europe/Moscow** (00:00 UTC), including weeks without a release candidate.
Systemd starts a missed run when the timer resumes.

Install Python 3 with SQLite 3.37 or newer, age and the AWS CLI on the pilot VM.
Use the approved private backup bucket and provide put/get access to the
`database/sqlite/` prefix. Keep the age recovery identity off the VM and
put its public recipient in the service configuration.

```sh
sudo install -d -m 0755 /usr/local/libexec/finance-budget
sudo install -m 0755 ops/backup-budget /usr/local/libexec/finance-budget/
sudo install -m 0644 ops/budget-snapshot.py /usr/local/libexec/finance-budget/
sudo install -m 0644 ops/finance-budget-backup.service ops/finance-budget-backup.timer /etc/systemd/system/
```

Keep these files root-owned with mode `0600` under `/etc/finance-budget/`.

| File | Contents |
| --- | --- |
| `backup.env` | `S3_ENDPOINT=https://storage.yandexcloud.net`, `AWS_DEFAULT_REGION=ru-central1` and `BACKUP_BUCKET` set to the approved bucket, one assignment per line |
| `backup-aws.credentials` | The AWS CLI credentials file with a `[default]` profile |
| `backup-recipient` | The public recipient from `age-keygen -y "$RECOVERY_AGE_KEY"` |
| `backup-reference.json` | Saved figures from the pilot, updated after edits that change them |

The service defaults to the [pilot database path](private-pilot.md). If
the pilot uses another path, set `BUDGET_DB` in `backup.env` and include its
directory in the unit's `ReadWritePaths` so SQLite can open WAL sidecars.

For a fresh budget with no saved settings or actuals, use `{"empty":true}`.
Once there is a saved category, choose a current or completed month. Record its
plan, actual and opening buffer, then **Расходы** under **Факт за год** for that year. The
category code is the part after `row-` in its HTML row ID. Enter amounts as
integer rubles. This example uses figures from the synthetic drill below.

```json
{
  "month": "2026-03",
  "category": "synthetic-category-code",
  "plan": 4000,
  "actual": 2500,
  "opening_buffer": -1500,
  "yearly_spending": 8000
}
```

Validate the installed units and start the timer during the
[pilot cutover](https://github.com/finance-lab-project/finance-lab/issues/24).
Run the service once to check the storage access and references before enabling
the timer.

```sh
sudo systemd-analyze verify /etc/systemd/system/finance-budget-backup.service \
  /etc/systemd/system/finance-budget-backup.timer
systemd-analyze calendar 'Sun *-*-* 03:00:00 Europe/Moscow'
sudo systemctl daemon-reload
sudo systemctl start finance-budget-backup.service && sudo systemctl enable --now finance-budget-backup.timer
systemctl list-timers --all finance-budget-backup.timer
```

## Gate deployment on the weekly check

```sh
sudo journalctl --utc -u finance-budget-backup.service --since '7 days ago' --no-pager
```

Require the most recent attempt in the current Sunday maintenance window to have
completed successfully with a `Weekly budget backup verified.` receipt. The
receipt records the object key and checksum. Failed checks exit nonzero and log
the stage or field that failed. Keep the receipt and the reference used for the
run in the protected off-VM operations record. The journal contains no budget
amounts.

Defer deployment if the run failed, is still in progress, or lacks a success
receipt for that window. If there were accepted writes after the verified
snapshot, pause writes, update the references and rerun
`sudo systemctl start finance-budget-backup.service` before switching the release.

## Restore to a fresh database

On a protected recovery machine, set `KEY` and `SHA256` from the verified receipt.
Use the storage endpoint, bucket, recovery credentials and age identity kept
privately. Set `REFERENCE_JSON` to the private references for that backup.

```bash
restore_dir=$(mktemp -d)
(
  set -euo pipefail
  umask 077
  aws --endpoint-url "$S3_ENDPOINT" s3 cp "s3://$BACKUP_BUCKET/$KEY" "$restore_dir/budget.sqlite.age"
  printf '%s  %s\n' "$SHA256" "$restore_dir/budget.sqlite.age" | sha256sum --check
  age -d -i "$RECOVERY_AGE_KEY" -o "$restore_dir/snapshot.sqlite" "$restore_dir/budget.sqlite.age"
  python3 ops/budget-snapshot.py check "$restore_dir/snapshot.sqlite" --reference "$REFERENCE_JSON"
  python3 ops/budget-snapshot.py create "$restore_dir/snapshot.sqlite" "$restore_dir/budget.sqlite"
)
```

Use the backup's application JAR and set the password hash for `pilot-owner` as
described in the [pilot runbook](private-pilot.md#prepare-the-candidate). Start a
separate loopback instance on a free port. The `pilot` profile keeps demo seeding
disabled. Sign in and check the saved month and year before using the recovered
database.

```sh
BUDGET_DB="$restore_dir/budget.sqlite" BUDGET_BIND=127.0.0.1 \
  java --enable-native-access=ALL-UNNAMED -jar target/finance-lab-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=pilot --server.port=18086
```

## Rehearse with synthetic figures

1. Build with `mvn verify`. Use the startup command above with a fresh SQLite
   path, a disposable password, `--server.port=18085` and
   `--budget.current-month=2026-03`. Sign in as `pilot-owner`.
2. Save planning income of 10,000 ₽ and an investment target of 40%. Add an
   essential category with a 4,000 ₽ plan and a discretionary category with
   100% of the discretionary pool. In February history save 4,000 ₽ planned and
   5,500 ₽ spent in the essential category. In March save 2,000 ₽ for that
   category, then correct it to 2,500 ₽.
3. Record March's category plan, saved actual and opening buffer, and the
   year's actual spending. Take and check the snapshot while the source app
   runs. Copy the verified snapshot to a fresh restore directory with
   `budget-snapshot.py create`.
4. Start the copy with the same fixed month, the same pilot login and port
   18086. Verify the four recorded values. Save 2,800 ₽ in March on the copy
   and reopen March and the year from the original instance.

### Rehearsal result

On 21 November 2026, the synthetic source used WAL mode and stayed running
while the snapshot was taken. The same pilot owner signed in to the restored
instance and read back the saved settings and these figures.

| Figure | Source before backup | Restored instance |
| --- | ---: | ---: |
| March category plan | 4,000 ₽ | 4,000 ₽ |
| March saved actual | 2,500 ₽ | 2,500 ₽ |
| March opening buffer | −1,500 ₽ | −1,500 ₽ |
| 2026 actual spending | 8,000 ₽ | 8,000 ₽ |

The restored edit saved 2,800 ₽ and brought the restored year to 8,300 ₽.
The original still showed 2,500 ₽ and 8,000 ₽.

`mvn -B -o verify` passed all 20 tests on JDK 27 with a Java 25 target. The
weekly script passed an encrypted upload, download and restore check against
simulated Object Storage, including an empty-budget run. An upload failure, a
checksum mismatch and a figure mismatch each exited nonzero without a success
receipt. Live storage verification and timer activation are part of the pilot
cutover.
