# Finance Lab sprint worker

Anton authorizes product work by opening a **Sprint work** issue. Select the release milestone as usual and enter the first date the worker may start, the outcome, acceptance examples, and any prerequisite issues. The worker accepts open issues authored by `anton415` in `finance-lab-project/finance-lab`. A normal issue or a future sprint date does not start an agent. Dates use Moscow time. A blocked issue gets a GitHub comment explaining the problem. Editing its title or description makes it eligible again.

Every five minutes while the Mac is awake, a local process checks for work. Empty polls use GitHub only. For eligible work it runs the ChatGPT-signed-in Codex CLI with API key environment variables removed. The implementer edits an isolated copy. The reviewer gets a fresh, read-only session and the PR diff after Maven passes. The controller posts the decision through a default-branch workflow using the built-in `github-actions[bot]` identity. Only the review job requests `pull-requests: write` and GitHub supplies its temporary token. The Mac model has no reviewer token. It requires a matching issue branch, PR author, repository, and review commit. GitHub enforces `verify`, one current approval, and an up-to-date branch. Two failed repair attempts return the issue to planning. The worker does not deploy releases.

## Install on the Mac

Use the ChatGPT desktop app's Codex CLI, Python 3.9 or later, Git, and an authenticated GitHub CLI for `anton415`. Confirm automatic credit reload is off in ChatGPT **Settings → Usage**. A dedicated checkout at the merged setup revision is sufficient. In GitHub organization **Settings → Actions → General → Workflow permissions**, enable **Allow GitHub Actions to create and approve pull requests**. Verify the same checkbox in the Finance Lab repository settings. Keep the default token permissions read-only. The organization setting can affect repositories that inherit it.

Run these commands from the checkout root.

```sh
python3 ops/agents/install.py --configure-github --auto-reload-is-off
python3 ops/agents/worker.py --check
```

The installer enables squash auto-merge. It installs classic protection on an unprotected main branch and preserves existing rules. The installer and worker verify that the repository permits Actions to approve a PR. Before requesting a merge, the worker matches the bot, decision, commit and review text to the independent local verdict. The required Maven job is `verify` from GitHub Actions. The worker also verifies these settings before handling an issue.

The Mac keeps its worker files and progress under `~/Library/Application Support/FinanceLabSprint`. The installed LaunchAgent is `com.finance-lab.sprint-worker`. GitHub CLI credentials stay on the Mac. The review workflow runs on a standard GitHub-hosted runner and never checks out or executes PR code. The reviewer and implementer share the same ChatGPT allowance. Before each model call the worker checks Codex's account limit response, requires a verified zero paid-credit balance and pauses once reported usage reaches 95% of a window. Unreadable usage, a paid balance, or unavailable authentication causes it to wait. Credit reload must stay off. GitHub and account usage may change between a check and a run.

```sh
launchctl print "gui/$(id -u)/com.finance-lab.sprint-worker"
tail -n 30 "$HOME/Library/Application Support/FinanceLabSprint/worker.log"
```

To stop the worker across logins, unload the agent and remove its plist. Issue and PR history remain on GitHub.

```sh
launchctl bootout "gui/$(id -u)/com.finance-lab.sprint-worker"
rm "$HOME/Library/LaunchAgents/com.finance-lab.sprint-worker.plist"
```
