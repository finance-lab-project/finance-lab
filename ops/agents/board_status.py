"""Publish the installed sprint controller's durable status to project 11."""
import datetime as dt
import json
import subprocess
import sys

PROJECT = "11"
MARKER = "<!-- finance-lab:sprint-controller-status -->"
COLUMNS = {
    "working": "In Progress",
    "review": "In Progress",
    "ci": "In Progress",
    "waiting": "In Progress",
    "dependency": "Todo",
    "queued": "Todo",
    "owner": "Blocked",
    "sign-in": "Blocked",
    "blocked": "Blocked",
}
UNBLOCKER = {
    "working": "Product worker", "review": "Independent reviewer and sprint controller",
    "ci": "GitHub Actions", "waiting": "Sprint controller", "queued": "Sprint controller",
    "dependency": "Prerequisite issue owners", "blocked": "Maintainer", "owner": "Maintainer", "sign-in": "Maintainer",
}


def transition(state, stage, reason, action, persist, pr_url=None, issue=None):
    values = dict(stage=stage, reason=reason, next_action=action, pr=pr_url,
                  issue=issue, unblocker=UNBLOCKER[stage])
    old = state.get("progress", {})
    if all(old.get(key) == value for key, value in values.items()):
        return
    state["progress"] = dict(values, actor="Finance Lab sprint controller",
                             at=dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
                             delivered=[])
    persist()


def publish_comment(gh, repo, owner, number, event):
    path = f"repos/{repo}/issues/{number}/comments"
    pages = json.loads(gh("api", "--paginate", "--slurp", path))
    body = (f"{MARKER}\n**Sprint status · {event['stage']}**\n\n{event['reason']}\n\n"
            f"Next action: {event['next_action']}\n\n"
            f"Responsible: {event.get('unblocker') or UNBLOCKER[event['stage']]}\n\n"
            f"{event['actor']} · {event['at']}")
    if event["pr"]:
        body += f"\n\n[Pull request]({event['pr']}) · [CI checks]({event['pr']}/checks)"
    previous = next((c for page in pages for c in page
                     if c["user"]["login"] == owner and c["body"].startswith(MARKER)), None)
    if previous is None:
        gh("api", path, "-X", "POST", "-f", "body=" + body)
    elif previous["body"] != body:
        gh("api", f"repos/{repo}/issues/comments/{previous['id']}",
           "-X", "PATCH", "-f", "body=" + body)


def previous_blocker(gh, repo, owner, number):
    path = f"repos/{repo}/issues/{number}/comments"
    pages = json.loads(gh("api", "--paginate", "--slurp", path))
    for page in reversed(pages):
        for comment in reversed(page):
            body = comment["body"]
            if comment["user"]["login"] == owner and body.startswith("Sprint work blocked. "):
                reason = body.removeprefix("Sprint work blocked. ").split("\n\nEdit the issue to retry.")[0]
                return reason[:1200], comment["html_url"]
    return None


def publish_board(gh, repo, number, event):
    org = repo.split("/")[0]
    project = json.loads(gh("project", "view", PROJECT, "--owner", org, "--format", "json"))
    fields = json.loads(gh("project", "field-list", PROJECT, "--owner", org,
                           "--format", "json", "--limit", "100"))["fields"]
    status = next((field for field in fields if field["name"] == "Status"), {})
    column = COLUMNS[event["stage"]]
    option = next((o for o in status.get("options", []) if o["name"] == column), None)
    if not option:
        raise ValueError(f"Project {PROJECT} is missing the Status option {column!r}")
    item = json.loads(gh("project", "item-add", PROJECT, "--owner", org, "--format", "json",
                         "--url", f"https://github.com/{repo}/issues/{number}"))
    gh("project", "item-edit", "--project-id", project["id"], "--id", item["id"],
       "--field-id", status["id"], "--single-select-option-id", option["id"])


def deliver(state, persist, gh, repo, owner, number):
    event = state.get("progress")
    if not event:
        return
    for target in ("board", "comment"):
        if target in event["delivered"]:
            continue
        try:
            if target == "board":
                publish_board(gh, repo, number, event)
            else:
                publish_comment(gh, repo, owner, number, event)
        except (subprocess.SubprocessError, OSError, ValueError, KeyError) as exc:
            print(f"Could not publish sprint {target} for #{number} ({type(exc).__name__}); "
                  "will retry on the next poll.", file=sys.stderr)
            continue
        event["delivered"].append(target)
        persist()
