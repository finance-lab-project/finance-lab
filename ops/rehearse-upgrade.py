"""Exercise the direct upgrade using only disposable fixture data on the CI runner."""
import argparse
import hashlib
import shutil
import tempfile
import zipfile
import os
from pathlib import Path
import re
import secrets
import sqlite3
import subprocess
import time
from contextlib import contextmanager

import requests
from bs4 import BeautifulSoup


ROOT = "http://127.0.0.1:18080"
PASSWORD = secrets.token_urlsafe(24)


@contextmanager
def pilot(jar, profile, database):
    env = dict({k: v for k, v in os.environ.items() if k in ("JAVA_HOME", "PATH", "HOME", "LANG")}, BUDGET_DB=str(database.resolve()),
               BUDGET_OWNER_A_PASSWORD=PASSWORD, BUDGET_OWNER_B_PASSWORD=secrets.token_urlsafe(24))
    process = subprocess.Popen(
        ["java", "--enable-native-access=ALL-UNNAMED", "-jar", jar,
         f"--spring.profiles.active={profile}", "--server.address=127.0.0.1", "--server.port=18080",
         "--budget.current-month=2027-03"],
        env=env)
    try:
        with requests.Session() as session:
            for _ in range(100):
                if process.poll() is not None:
                    raise RuntimeError(f"{jar} exited during startup")
                try:
                    response = session.get(ROOT + "/login", timeout=2)
                    if response.ok:
                        break
                except requests.ConnectionError:
                    pass
                time.sleep(0.5)
            else:
                raise TimeoutError(f"{jar} failed to start")
            token = BeautifulSoup(response.content, "html.parser").select_one('input[name="_csrf"]')["value"]
            signed_in = session.post(ROOT + "/login", data={
                "username": "owner-a", "password": PASSWORD, "_csrf": token}, timeout=15)
            signed_in.raise_for_status()
            assert "/login" not in signed_in.url
            yield session
    finally:
        process.terminate()
        process.wait(timeout=30)


def page(session, path):
    response = session.get(ROOT + path, timeout=10)
    response.raise_for_status()
    assert "/login" not in response.url
    return BeautifulSoup(response.content, "html.parser")


def amount(html, selector):
    value = html.select_one(selector).get_text().replace("−", "-")
    return int(re.sub(r"[^0-9-]", "", value))


def budget(session):
    march = page(session, "/months/2027-03")
    december = page(session, "/months/2026-12")
    return (amount(march, "#actual-income"), amount(march, "#actual-contributions"),
            amount(december, "#row-food .plan"), amount(december, "#row-food .actual"),
            amount(page(session, "/years/2027"), "#year-spending"))


def snapshot(source, target):
    with sqlite3.connect(source) as src, sqlite3.connect(target) as dst:
        src.backup(dst)
        assert dst.execute("PRAGMA integrity_check").fetchone() == ("ok",)


def check_upgrade(session, baseline):
    assert budget(session) == baseline
    january = page(session, "/months/2027-01")
    assert amount(january, "#row-food .buffer") == -2000
    assert amount(january, "#row-food .plan") == 24000
    assert amount(january, "#row-food .actual") == 25000
    assert amount(january, "#row-food .available") == -3000
    assert page(session, "/investments/2027-03").h1.get_text(strip=True) == "Инвестиции"
    assert page(session, "/fire").h1.get_text(strip=True) == "FIRE"


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def require_digest(path, expected, label):
    if digest(path) != expected:
        raise SystemExit(f"{label} checksum mismatch")


def download(args, work):
    previous = work / f"finance-lab-{args.previous_tag[1:]}.jar"
    subprocess.run(["gh", "release", "download", args.previous_tag, "--repo", args.repository,
                    "--pattern", previous.name, "--dir", str(work)], check=True)
    require_digest(previous, args.previous_sha256, "previous JAR")
    archive = work / "candidate.zip"
    with archive.open("wb") as stream:
        subprocess.run(["gh", "api",
                        f"repos/{args.repository}/actions/artifacts/{args.artifact_id}/zip"],
                       stdout=stream, check=True)
    require_digest(archive, args.archive_sha256, "candidate archive")
    candidate = work / "candidate.jar"
    with zipfile.ZipFile(archive) as bundle:
        if bundle.read("SOURCE_REVISION").decode().strip() != args.revision:
            raise SystemExit("candidate revision mismatch")
        jars = [entry for entry in bundle.infolist() if entry.filename.endswith(".jar")]
        if len(jars) != 1:
            raise SystemExit("expected one candidate JAR")
        with bundle.open(jars[0]) as src, candidate.open("wb") as dst:
            shutil.copyfileobj(src, dst)
    require_digest(candidate, args.candidate_sha256, "candidate JAR")
    print(f"Verified candidate {args.revision} JAR SHA-256 {args.candidate_sha256}", flush=True)
    return previous, candidate


def rehearse(args, work):
    previous, candidate = download(args, work)
    source = work / "source.sqlite"
    with pilot(previous, "demo", source) as session:
        baseline = budget(session)
        assert baseline[:4] == (50000, 10000, 22000, 24000)
        assert baseline[4] > 0
    backup = work / "backup.sqlite"
    snapshot(source, backup)
    original = digest(source)
    upgraded = work / "upgraded.sqlite"
    snapshot(backup, upgraded)
    recovered = work / "recovered.sqlite"
    for phase, database in (("upgrade", upgraded), ("restart", upgraded), ("recovery", recovered)):
        if phase == "recovery":
            snapshot(backup, recovered)
        with pilot(candidate, "fixture", database) as session:
            check_upgrade(session, baseline)
        print(f"Passed {phase}: Budget {baseline}, January carry, Investments and FIRE", flush=True)
    assert digest(source) == original


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", default="finance-lab-project/finance-lab")
    parser.add_argument("--previous-tag", required=True)
    parser.add_argument("--artifact-id", required=True, type=int)
    parser.add_argument("--revision", required=True)
    for label in ("previous", "archive", "candidate"):
        parser.add_argument(f"--{label}-sha256", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"v\d+\.\d+\.\d+(?:[-+][\w.-]+)?", args.previous_tag):
        parser.error("expected a version tag such as v0.1.0")
    if not __debug__:
        parser.error("run with Python assertions enabled")
    with tempfile.TemporaryDirectory(prefix="finance-lab-upgrade-") as directory:
        rehearse(args, Path(directory))
