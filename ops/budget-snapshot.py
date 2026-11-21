import argparse
from contextlib import closing
from datetime import date
import json
import os
from pathlib import Path
import re
import sqlite3
from tempfile import TemporaryDirectory


PILOT_OWNER = "pilot-owner"


class SnapshotError(Exception):
    pass


def read_only(path):
    if path.stat().st_size == 0:
        raise SnapshotError("SQLite file is empty.")
    return sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True)


def create_snapshot(source, destination):
    if destination.exists():
        raise SnapshotError("Snapshot destination already exists.")
    with TemporaryDirectory(dir=destination.parent) as work:
        staged = Path(work) / "snapshot.sqlite"
        with closing(read_only(source)) as original, closing(sqlite3.connect(staged)) as copy:
            original.backup(copy, pages=256)
            if copy.execute("PRAGMA journal_mode=DELETE").fetchone() != ("delete",):
                raise SnapshotError("Could not finalize a standalone SQLite snapshot.")
        os.link(staged, destination)


def check_figures(db, reference):
    if not isinstance(reference, dict):
        raise SnapshotError("Invalid budget reference.")
    if reference.get("empty") is True and len(reference) == 1:
        # The first pilot week may have no saved settings or amounts yet.
        has_budget = db.execute("""
            SELECT EXISTS (SELECT 1 FROM planning_settings)
                OR EXISTS (SELECT 1 FROM month_actuals)
                OR EXISTS (SELECT 1 FROM category_month)
            """).fetchone()[0]
        if has_budget:
            raise SnapshotError("Expected an empty budget.")
        return

    month = reference.get("month")
    category = reference.get("category")
    fields = ("plan", "actual", "opening_buffer", "yearly_spending")
    if (not isinstance(month, str) or not re.fullmatch(r"[0-9]{4}-(0[1-9]|1[0-2])", month)
            or month.startswith("0000-") or not isinstance(category, str) or not category
            or any(type(reference.get(field)) is not int for field in fields)):
        raise SnapshotError("Invalid monthly or yearly budget reference.")

    saved = db.execute("""
        SELECT plan, actual FROM category_month
         WHERE owner = ? AND period = ? AND category = ?
        """, (PILOT_OWNER, month, category)).fetchone()
    if saved is None:
        raise SnapshotError("The reference category has no saved month.")

    # Use the same cutoff as the month screen and sum the same saved rows as the year report.
    cutoff = min(month, date.today().isoformat()[:7])
    buffer = db.execute("""
        SELECT coalesce(sum(plan - actual), 0) FROM category_month
         WHERE owner = ? AND category = ? AND period < ?
        """, (PILOT_OWNER, category, cutoff)).fetchone()[0]
    year = month[:4]
    spending = sum(actual for (actual,) in db.execute("""
        SELECT actual FROM category_month
         WHERE owner = ? AND period BETWEEN ? AND ?
        """, (PILOT_OWNER, year + "-01", year + "-12")))
    for field, actual in zip(fields, (*saved, buffer, spending)):
        if reference[field] != actual:
            raise SnapshotError(f"Budget reference mismatch for {field}.")


def check_snapshot(path, reference_path):
    with closing(read_only(path)) as db:
        db.execute("BEGIN")
        if (db.execute("PRAGMA integrity_check(1)").fetchone() != ("ok",)
                or db.execute("PRAGMA foreign_key_check").fetchone() is not None):
            raise SnapshotError("SQLite snapshot integrity check failed.")
        if reference_path is not None:
            check_figures(db, json.loads(reference_path.read_text(encoding="utf-8")))


def main():
    parser = argparse.ArgumentParser(description="Create or check a private SQLite budget snapshot.")
    commands = parser.add_subparsers(dest="command", required=True)
    create = commands.add_parser("create")
    create.add_argument("source", type=Path)
    create.add_argument("destination", type=Path)
    check = commands.add_parser("check")
    check.add_argument("snapshot", type=Path)
    check.add_argument("--reference", type=Path)
    args = parser.parse_args()

    os.umask(0o077)
    try:
        if args.command == "create":
            create_snapshot(args.source, args.destination)
            print("SQLite snapshot created.")
        else:
            check_snapshot(args.snapshot, args.reference)
            print("SQLite snapshot verified.")
    except SnapshotError as error:
        parser.exit(1, f"{error}\n")
    except (OSError, ValueError, sqlite3.Error):
        parser.exit(1, f"SQLite snapshot {args.command} failed.\n")


if __name__ == "__main__":
    main()
