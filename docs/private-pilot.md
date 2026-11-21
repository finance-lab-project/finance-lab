# Private budget pilot

Both devices use the Spring form at <https://finance-lab-project.com/> on the existing Yandex Cloud VM after the [v0.1.0 cutover](https://github.com/finance-lab-project/finance-lab/issues/24). The single login is `pilot-owner`. Its name is also the stable key for budget reads and writes. The operator chooses the password and can replace its bcrypt hash independently of the SQLite file.

| Setting | Pilot value |
| --- | --- |
| Application | One Spring Boot process, managed by `finance-budget.service` |
| Local upstream for the existing HTTPS proxy | `127.0.0.1:18084` |
| Real budget on persistent VM disk | `/var/lib/finance-budget/budget.sqlite` |
| Disposable synthetic check on the same disk | `/var/lib/finance-budget/pilot-check.sqlite` |
| Credential configuration | Root-owned `/etc/finance-budget/pilot.env`, mode `0600` |

## Prepare the candidate

1. On the build machine, run `mvn verify` with JDK 25 and Maven 3.9+. Transfer the tested JAR and its SHA-256 checksum to the VM. On the VM, verify the checksum and install the JAR at a versioned path below `/opt/finance-budget/releases/`. Point the root-owned symlink `/opt/finance-budget/current.jar` at that JAR. The service expects a compatible Java runtime at `/usr/bin/java`.
2. As root, create the dedicated service account once with `useradd --system --user-group --no-create-home --shell /usr/sbin/nologin finance-budget`. Install the [service unit](../ops/finance-budget.service) at `/etc/systemd/system/finance-budget.service`. Systemd creates the private state directory on first start. Keep the releases and state on separate paths across upgrades.
3. Install `htpasswd` (Debian/Ubuntu package `apache2-utils`). Run the following in a root Bash shell on the VM. Enter the password at the hidden prompts. Only the hash goes into the environment file.

```bash
(
  set -eu
  umask 077
  install -d -m 0700 /etc/finance-budget
  entry=$(htpasswd -nBC 12 pilot-owner)
  staged=$(mktemp /etc/finance-budget/pilot.env.XXXXXX)
  trap 'rm -f "$staged"' EXIT
  printf 'BUDGET_PILOT_PASSWORD_HASH=%s\n' "${entry#pilot-owner:}" > "$staged"
  mv -f "$staged" /etc/finance-budget/pilot.env
)
```

## Rehearse with synthetic figures

Before the **first** start, select a fresh rehearsal file in a dedicated systemd drop-in. Install the following as `/etc/systemd/system/finance-budget.service.d/rehearsal.conf` (create the parent directory if needed).

```ini
[Service]
Environment=BUDGET_DB=/var/lib/finance-budget/pilot-check.sqlite
```

Run `systemctl daemon-reload`, `systemctl start finance-budget` and `systemctl status finance-budget`. A local `curl -I http://127.0.0.1:18084/` should redirect to sign-in. Keep the upstream bound to loopback. The existing Caddy HTTPS site will proxy to this upstream during #24. Before that switch, the public hostname still serves the previous app.

Run the following check through the permanent HTTPS route during #24 while the rehearsal file is selected and before admitting real writes.

1. Open a signed-out desktop browser on the permanent address. A direct month URL should lead to **Вход в бюджет**. Sign in as `pilot-owner` and confirm the current month has zero actual income and no plan.
2. Choose **Редактировать** on the month screen. Set **Фактический доход** to a synthetic **12 345 ₽** and select **Сохранить**. On the phone, open the same address, sign in independently and select the same month. Confirm **12 345 ₽** after refresh.
3. Run `systemctl restart finance-budget`. Sign in again from both devices and reopen that month. Confirm **12 345 ₽**. Record the candidate revision, month, device types, restart time and pass/fail in #24. Keep passwords and personal figures private.
4. Once the rehearsal passes, stop the service, remove only the rehearsal drop-in and run `systemctl daemon-reload`. For the first release confirm that the real budget path is unused. If a database already exists there, identify it before proceeding. Run `systemctl enable --now finance-budget`, sign in again at the permanent address and verify the real owner starts empty. Keep the rehearsal file separate. Later restarts and releases reuse the real file.

## Restore sign-in after a password change or loss

Confirm the service's active database path and that the expected file exists and is nonempty. Run the same password-generation block with the replacement password, then `systemctl restart finance-budget`. The restart loads the new hash and ends existing in-memory sessions. From a fresh browser check that the old password, when available, is rejected and the replacement opens the previously saved month. Keep the same service, database path and `pilot-owner` identity. Database loss or corruption uses the separate [backup and restore procedure tracked in #22](https://github.com/finance-lab-project/finance-lab/issues/22).
