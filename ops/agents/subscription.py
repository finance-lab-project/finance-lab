"""Run Codex with the signed-in personal account and check its included allowance."""
import json
import os
import shutil
import subprocess
import tempfile
import threading
from pathlib import Path


class Waiting(Exception):
    """A later poll may retry without changing the issue."""


class OwnerAction(Waiting):
    """A maintainer must restore access or configuration before a retry can succeed."""

    stage = "owner"


class SignInRequired(OwnerAction):
    stage = "sign-in"


def binary():
    bundled = Path("/Applications/ChatGPT.app/Contents/Resources/codex-cli/bin/codex")
    return str(bundled) if bundled.is_file() else shutil.which("codex") or "codex"


def environment():
    return {key: val for key, val in os.environ.items()
            if key in {"HOME", "PATH", "USER", "LOGNAME", "TMPDIR", "LANG", "LC_ALL"}}


def check_limits(data):
    bucket = (data.get("rateLimitsByLimitId") or {}).get("codex") or data.get("rateLimits") or {}
    windows = [bucket[k] for k in ("primary", "secondary") if bucket.get(k)]
    credits = bucket.get("credits") or {}
    if bucket.get("planType") not in ("plus", "prolite", "pro", "promax"):
        raise Waiting("Personal subscription limit information is unavailable")
    if credits.get("hasCredits") is not False or credits.get("unlimited") is not False:
        raise Waiting("A zero paid-credit balance could not be verified")
    if (not windows or bucket.get("rateLimitReachedType") or bucket.get("spendControlReached")
            or any(w.get("usedPercent", 100) >= 95 for w in windows)):
        raise Waiting("Waiting for included allowance (5% reserve)")


def authenticated_cli():
    args = [binary(), "-c", 'forced_login_method="chatgpt"']
    login = subprocess.run(args + ["login", "status"], env=environment(), capture_output=True,
                           text=True, timeout=15)
    if login.returncode or "logged in using chatgpt" not in (login.stdout + login.stderr).lower():
        raise SignInRequired("Codex requires Sign in with ChatGPT on this Mac")
    return args


def preflight():
    args = authenticated_cli()
    with tempfile.TemporaryDirectory() as empty, subprocess.Popen(
            args + ["app-server", "--stdio"], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, text=True, cwd=empty, env=environment()) as proc:
        timer = threading.Timer(35, proc.kill)
        timer.start()
        def send(message):
            proc.stdin.write(json.dumps(message) + "\n")
            proc.stdin.flush()
        def reply(req):
            for line in proc.stdout:
                msg = json.loads(line)
                if msg.get("id") == req:
                    if "result" in msg:
                        return msg["result"]
                    break
            raise Waiting("Could not read account usage; will retry")
        try:
            send({"method": "initialize", "id": 1, "params": {
                "clientInfo": {"name": "finance_lab_worker", "version": "1"}}})
            reply(1)
            send({"method": "initialized"})
            send({"method": "account/rateLimits/read", "id": 2, "params": {}})
            check_limits(reply(2))
        finally:
            timer.cancel()
            proc.kill()


def agent(workspace, prompt, schema, writable):
    preflight()
    fields = {name: {"type": "string"} for name in schema}
    if not writable:
        fields["decision"]["enum"] = ["approve", "changes", "blocked"]
    else:
        fields["decision"]["enum"] = ["implemented", "blocked"]
    with tempfile.TemporaryDirectory() as tmp:
        spec, output = Path(tmp, "schema.json"), Path(tmp, "result.json")
        spec.write_text(json.dumps({"type": "object", "properties": fields,
                                   "required": list(fields), "additionalProperties": False}))
        cmd = [binary(), "-a", "never", "exec", "--ignore-user-config",
               "-c", 'model_provider="openai"', "-c", 'forced_login_method="chatgpt"',
               "-s", "workspace-write" if writable else "read-only", "--skip-git-repo-check",
               "--ephemeral", "--output-schema", str(spec), "-o", str(output), "-"]
        result = subprocess.run(cmd, input=prompt, cwd=workspace, env=environment(),
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                text=True, timeout=1800)
        if result.returncode or not output.is_file():
            raise Waiting("Codex stopped before producing a decision")
        message = json.loads(output.read_text())
        if message.get("decision") not in fields["decision"]["enum"] or not message.get("summary", "").strip():
            raise Waiting("Codex returned an incomplete decision")
        return message
