import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import board_status
import worker


class BoardStatusTests(unittest.TestCase):
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
