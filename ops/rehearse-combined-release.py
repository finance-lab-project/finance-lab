"""Exercise the direct upgrade using only disposable fixture data on the CI runner."""
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
    env = dict(os.environ, BUDGET_DB=str(database.resolve()),
               BUDGET_OWNER_A_PASSWORD=PASSWORD, BUDGET_OWNER_B_PASSWORD=secrets.token_urlsafe(24))
    process = subprocess.Popen(
        ["java", "--enable-native-access=ALL-UNNAMED", "-jar", jar,
         f"--spring.profiles.active={profile}", "--server.port=18080",
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
    assert amount(january, "#row-food .available") == amount(january, "#row-food .plan") - 2000
    assert page(session, "/investments/2027-03").h1.get_text(strip=True) == "Инвестиции"
    assert page(session, "/fire").h1.get_text(strip=True) == "FIRE"


old = Path("v010-fixture.sqlite")
upgraded = Path("upgrade-copy.sqlite")
with pilot("finance-lab-0.1.0.jar", "demo", old) as session:
    baseline = budget(session)
    assert baseline[:4] == (50000, 10000, 22000, 24000)
    assert baseline[4] > 0
snapshot(old, upgraded)
for phase in ("direct upgrade", "restart"):
    with pilot("candidate/finance-lab-0.4.0.jar", "fixture", upgraded) as session:
        check_upgrade(session, baseline)
        print(f"Passed {phase}: saved month, annual spending, December plan, January carry, Investments and FIRE", flush=True)
