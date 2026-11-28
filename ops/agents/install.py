"""Install the personal Mac worker from a trusted Finance Lab checkout."""
import argparse
import base64
import json
import os
import plistlib
import shutil
import subprocess
import sys
from pathlib import Path

from worker import OWNER, REPO, ROOT, api, endpoint, protected

LABEL = "com.finance-lab.sprint-worker"
PLIST = Path.home() / "Library/LaunchAgents" / (LABEL + ".plist")


def configure():
    if api("user")["login"] != OWNER:
        sys.exit("Sign in to GitHub CLI as the repository owner.")
    repo = api("repos/" + REPO)
    if not repo["permissions"]["admin"]:
        sys.exit("Repository admin access is required to configure protected merges.")
    if not repo.get("allow_auto_merge") or not repo.get("allow_squash_merge"):
        api("repos/" + REPO, "-X", "PATCH", "-F", "allow_auto_merge=true", "-F", "allow_squash_merge=true")
    # Do not replace existing protections; they may contain other requirements.
    branch = api(endpoint("branches/main"))
    if branch["protected"]:
        print("Keeping the existing protection rules.")
    else:
        payload = {"required_status_checks": {"strict": True, "checks": [
            {"context": "verify", "app_id": 15368}]},
            "enforce_admins": True, "required_pull_request_reviews": {
                "dismiss_stale_reviews": True, "required_approving_review_count": 1},
            "restrictions": None}
        subprocess.run(["gh", "api", "-X", "PUT", endpoint("branches/main/protection"),
                        "--input", "-", "--silent"], input=json.dumps(payload), text=True, check=True)
    protected()


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--configure-github", action="store_true")
    p.add_argument("--auto-reload-is-off", action="store_true",
                   help="Confirm the ChatGPT account's automatic credit reload is off.")
    args = p.parse_args()
    if not args.auto_reload_is_off:
        sys.exit("Confirm automatic credit reload is off in ChatGPT Settings > Usage, then pass --auto-reload-is-off.")
    source = Path(__file__).resolve().parent
    workflow = api(endpoint("contents/.github/workflows/record-agent-review.yml?ref=main"))
    local_workflow = source.parents[1] / ".github/workflows/record-agent-review.yml"
    if (workflow.get("encoding") != "base64"
            or base64.b64decode(workflow["content"]) != local_workflow.read_bytes()):
        sys.exit("Install from a merged checkout matching the sprint review workflow on main.")
    if api("user")["login"] != OWNER:
        sys.exit("The GitHub CLI must be signed in as " + OWNER + ".")
    if args.configure_github:
        configure()
    else:
        protected()
    ROOT.mkdir(parents=True, exist_ok=True, mode=0o700)
    target = ROOT / "bin"
    target.mkdir(exist_ok=True, mode=0o700)
    for name in ["subscription.py", "board_status.py", "worker.py"]:
        tmp = target / (name + ".tmp")
        shutil.copyfile(source / name, tmp)
        tmp.replace(target / name)
    PLIST.parent.mkdir(parents=True, exist_ok=True)
    config = {"Label": LABEL, "RunAtLoad": True, "StartInterval": 300,
              "ProgramArguments": [sys.executable, str(target / "worker.py")],
              "WorkingDirectory": str(ROOT), "EnvironmentVariables": {
                  "PATH": "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"},
              "StandardErrorPath": str(ROOT / "worker.log")}
    tmp = PLIST.with_suffix(".tmp")
    tmp.write_bytes(plistlib.dumps(config))
    tmp.replace(PLIST)
    domain = f"gui/{os.getuid()}"
    existing = subprocess.run(["launchctl", "print", domain + "/" + LABEL], capture_output=True)
    if not existing.returncode:
        subprocess.run(["launchctl", "bootout", domain + "/" + LABEL], check=True)
    subprocess.run(["launchctl", "bootstrap", domain, str(PLIST)], check=True)
    print("Finance Lab sprint worker installed. Poll interval: 5 minutes while this Mac is awake.")


if __name__ == "__main__":
    main()
