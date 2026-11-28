import hashlib
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import board_status
import subscription
import worker


class BoardStatusTests(unittest.TestCase):
    def test_active_issue_does_not_hide_a_legacy_blocker_or_its_resumption(self):
        body = ("### Sprint start (Moscow)\n2020-01-01\n### User outcome\nShow status"
                "\n### Acceptance examples\nDisplay the next action")

        def issue(number):
            return {"number": number, "title": "Sprint work", "body": body, "user": {"login": worker.OWNER}}

        waiting, active = issue(78), issue(84)
        digest = hashlib.sha256((waiting["title"] + body).encode()).hexdigest()
        with tempfile.TemporaryDirectory() as tmp, patch.object(worker, "ROOT", Path(tmp)), \
                patch.object(worker, "gh", return_value=json.dumps([[waiting, active]])) as gh, \
                patch.object(worker, "api", return_value={"login": worker.OWNER}), \
                patch.object(worker, "protected"), patch.object(worker, "advance") as advance, \
                patch.object(board_status, "publish_board") as board, patch.object(board_status, "publish_comment"):
            path = Path(tmp, "78.json")
            worker.save(path, {"spec": digest, "blocked": digest, "attempts": 3})
            worker.save(Path(tmp, "active.json"), 84)
            worker.tick()
            blocked = json.loads(path.read_text())["progress"]
            self.assertEqual((78, "blocked", "Maintainer"), (blocked["issue"], blocked["stage"], blocked["unblocker"]))
            self.assertEqual(84, advance.call_args.args[0]["number"])
            # Simulate a lost acknowledgement immediately before the issue description changes.
            state = json.loads(path.read_text())
            state["progress"]["delivered"] = ["comment"]
            worker.save(path, state)
            waiting["body"] += "\nReproduce with a July category."
            gh.return_value = json.dumps([[waiting, active]])
            board.reset_mock()
            worker.tick()
            state = json.loads(path.read_text())
            self.assertNotIn("blocked", state)
            self.assertNotIn("attempts", state)
            self.assertEqual("queued", state["progress"]["stage"])
            self.assertEqual(["queued"], [c.args[-1]["stage"] for c in board.call_args_list])
            # A second edit must retain both the queued event and its comment acknowledgement.
            queued = dict(state["progress"], delivered=["comment"])
            state["progress"] = queued
            worker.save(path, state)
            waiting["body"] += "\nClarify the expected total."
            gh.return_value = json.dumps([[waiting, active]])
            board.reset_mock()
            worker.tick()
            self.assertEqual(dict(queued, delivered=["comment", "board"]), json.loads(path.read_text())["progress"])
            self.assertEqual(["queued"], [c.args[-1]["stage"] for c in board.call_args_list])
            advance.reset_mock()
            worker.save(Path(tmp, "active.json"), 78)
            worker.tick()
            self.assertEqual("working", json.loads(path.read_text())["progress"]["stage"])
            self.assertEqual(78, advance.call_args.args[0]["number"])

    def test_pending_publications_require_the_configured_github_account(self):
        issue = {"number": 76, "title": "Publish status", "user": {"login": worker.OWNER},
                 "body": "### Sprint start (Moscow)\n2020-01-01\n### User outcome\nX\n### Acceptance examples\nY"}
        with patch.object(worker, "gh", return_value=json.dumps([[issue]])), \
                patch.object(worker, "api", return_value={"login": "different-account"}), \
                patch.object(worker, "prepare_status") as prepare:
            with self.assertRaises(worker.OwnerAction):
                worker.tick()
            prepare.assert_not_called()

    def test_owner_block_clears_before_work_starts_after_repository_recovery(self):
        issue = {"number": 76, "title": "Show resumed work", "user": {"login": worker.OWNER},
                 "body": "### Sprint start (Moscow)\n2020-01-01\n### User outcome\nX\n### Acceptance examples\nY"}
        with tempfile.TemporaryDirectory() as tmp, patch.object(worker, "ROOT", Path(tmp)), \
                patch.object(worker, "gh", return_value=json.dumps([[issue]])), \
                patch.object(worker, "api", return_value={"login": worker.OWNER}), \
                patch.object(worker, "protected", side_effect=[worker.OwnerAction("Restore settings"), None]), \
                patch.object(board_status, "publish_board"), patch.object(board_status, "publish_comment"), \
                patch.object(worker, "advance") as advance:
            with self.assertRaises(worker.OwnerAction):
                worker.tick()
            advance.assert_not_called()
            observed = []
            advance.side_effect = lambda issue, state, persist, report: observed.append(state["progress"]["stage"])
            worker.tick()
            self.assertEqual(["working"], observed)

    def test_legacy_blocker_uses_the_latest_worker_explanation(self):
        older = {"user": {"login": worker.OWNER}, "body": "Sprint work blocked. Old reason", "html_url": "old"}
        last = {**older, "body": "Sprint work blocked. Needs formula fix\n\nEdit the issue to retry.", "html_url": "latest"}
        unrelated = {**last, "user": {"login": "someone-else"}}
        gh = Mock(return_value=json.dumps([[older], [last, unrelated]]))
        self.assertEqual(("Needs formula fix", "latest"), board_status.previous_blocker(gh, worker.REPO, worker.OWNER, 78))

    def test_sign_in_block_remains_until_login_recovers(self):
        issue = {"number": 76, "title": "Show resumed work", "user": {"login": worker.OWNER},
                 "body": "### Sprint start (Moscow)\n2020-01-01\n### User outcome\nX\n### Acceptance examples\nY"}
        error = subscription.SignInRequired("Sign in")
        with tempfile.TemporaryDirectory() as tmp, patch.object(worker, "ROOT", Path(tmp)), \
                patch.object(worker, "gh", return_value=json.dumps([[issue]])), \
                patch.object(worker, "api", return_value={"login": worker.OWNER}), patch.object(worker, "protected"), \
                patch.object(worker, "advance", side_effect=[error, None]) as advance, \
                patch.object(worker, "authenticated_cli", side_effect=[error, []]), \
                patch.object(board_status, "publish_board") as board, patch.object(board_status, "publish_comment"):
            with self.assertRaises(subscription.SignInRequired):
                worker.tick()
            board.reset_mock()
            with self.assertRaises(subscription.SignInRequired):
                worker.tick()
            board.assert_not_called()
            self.assertEqual(advance.call_count, 1)
            worker.tick()
            self.assertEqual(["working"], [c.args[-1]["stage"] for c in board.call_args_list])
            self.assertEqual(advance.call_count, 2)

    def test_account_action_and_retryable_quota_have_distinct_status_and_keep_pr_links(self):
        issue = {"number": 78, "title": "Publish status", "user": {"login": worker.OWNER},
                 "body": "### Sprint start (Moscow)\n2020-01-01\n### User outcome\nX\n### Acceptance examples\nY"}
        pr = "https://github.com/finance-lab-project/finance-lab/pull/121"
        with tempfile.TemporaryDirectory() as tmp, patch.object(worker, "ROOT", Path(tmp)), \
                patch.object(worker, "gh", return_value=json.dumps([[issue]])), \
                patch.object(worker, "api", return_value={"login": worker.OWNER}), patch.object(worker, "protected"), \
                patch.object(board_status, "publish_board"), patch.object(board_status, "publish_comment"):
            for error, stage, column in [(worker.OwnerAction("Sign in"), "owner", "Blocked"),
                                         (worker.Waiting("Allowance"), "waiting", "In Progress")]:
                def wait(issue, state, persist, report):
                    state["pr_url"] = pr
                    raise error

                with self.subTest(stage=stage), patch.object(worker, "advance", side_effect=wait), \
                        self.assertRaises(worker.Waiting):
                    worker.tick()
                event = json.loads(Path(tmp, "78.json").read_text())["progress"]
                self.assertEqual((stage, column, pr), (event["stage"], board_status.COLUMNS[event["stage"]], event["pr"]))

    def test_retry_board_without_duplicate_comment_or_change_of_timestamp(self):
        state = {}
        saved = Mock()
        reason = "Missing release input"
        board_status.transition(state, "blocked", reason, "Maintainer: supply it.", saved)
        stamp = state["progress"]["at"]
        with patch.object(board_status, "publish_comment") as comment, patch.object(
                board_status, "publish_board", side_effect=[subprocess.CalledProcessError(1, "gh"), None]) as board:
            board_status.deliver(state, saved, None, worker.REPO, worker.OWNER, 76)
            self.assertEqual(["comment"], state["progress"]["delivered"])
            board_status.transition(state, "blocked", reason, "Maintainer: supply it.", saved)
            self.assertEqual(stamp, state["progress"]["at"])
            board_status.deliver(state, saved, None, worker.REPO, worker.OWNER, 76)
            self.assertCountEqual(["comment", "board"], state["progress"]["delivered"])
            comment.assert_called_once()
            self.assertEqual(board.call_count, 2)
            board_status.transition(state, "working", "Resumed", "Worker implements.", saved)
            self.assertEqual([], state["progress"]["delivered"])
            self.assertEqual("In Progress", board_status.COLUMNS[state["progress"]["stage"]])

    def test_publish_board_selects_existing_blocked_option_on_project_eleven(self):
        gh = Mock(side_effect=[json.dumps({"id": "project-id"}), json.dumps({"fields": [
            {"id": "field-id", "name": "Status", "options": [
                {"id": "blocked-id", "name": "Blocked"},
                {"id": "working-id", "name": "In Progress"}]}]}), json.dumps({"id": "item-id"}), ""])
        board_status.publish_board(gh, worker.REPO, 76, {"stage": "blocked"})
        self.assertEqual(gh.call_args.args, ("project", "item-edit", "--project-id", "project-id",
                         "--id", "item-id", "--field-id", "field-id", "--single-select-option-id", "blocked-id"))

    def test_comment_recovers_when_post_succeeded_but_acknowledgement_was_lost(self):
        state = {}
        board_status.transition(state, "blocked", "Missing input", "Maintainer: check.", Mock())
        gh = Mock(return_value="[[]]")
        board_status.publish_comment(gh, worker.REPO, worker.OWNER, 76, state["progress"])
        body = gh.call_args.args[-1][5:]
        self.assertTrue(body.startswith(board_status.MARKER))
        gh.reset_mock()
        gh.return_value = json.dumps([[{"id": 3, "user": {"login": worker.OWNER}, "body": body}]])
        board_status.publish_comment(gh, worker.REPO, worker.OWNER, 76, state["progress"])
        self.assertEqual(1, gh.call_count)
        board_status.transition(state, "working", "Resumed", "Worker acts.", Mock())
        board_status.publish_comment(gh, worker.REPO, worker.OWNER, 76, state["progress"])
        self.assertIn("PATCH", gh.call_args.args)
        self.assertIn("repos/" + worker.REPO + "/issues/comments/3", gh.call_args.args)

    def test_block_is_durable_and_a_changed_issue_resumes(self):
        issue = {"number": 76, "title": "First attempt", "user": {"login": worker.OWNER},
                 "body": "### Sprint start (Moscow)\n2020-01-01\n### User outcome\nVisible status"
                         "\n### Acceptance examples\nAn accepted issue is shown."}
        with tempfile.TemporaryDirectory() as tmp, patch.object(worker, "ROOT", Path(tmp)), \
                patch.object(worker, "gh", side_effect=lambda *args: json.dumps([[issue]])), \
                patch.object(worker, "api", return_value={"login": worker.OWNER}), \
                patch.object(worker, "protected"), patch.object(board_status, "publish_board"), \
                patch.object(board_status, "publish_comment"), patch.object(worker, "advance") as advance:
            advance.side_effect = worker.Blocked("Needs owner decision")
            worker.tick()
            snapshot = Path(tmp, "76.json")
            blocked = json.loads(snapshot.read_text())["progress"]
            self.assertEqual("blocked", blocked["stage"])
            self.assertEqual("Needs owner decision", blocked["reason"])
            self.assertFalse(Path(tmp, "active.json").exists())
            worker.tick()
            self.assertEqual(1, advance.call_count)
            issue["title"] = "Owner supplied the decision"
            advance.side_effect = None
            worker.tick()
            self.assertEqual("working", json.loads(snapshot.read_text())["progress"]["stage"])
            self.assertEqual(2, advance.call_count)
