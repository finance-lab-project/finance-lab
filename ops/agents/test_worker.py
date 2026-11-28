import datetime as dt
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

import subscription
import worker


def pr_case():
    return {"number": 91, "html_url": "https://github.com/finance-lab-project/finance-lab/pull/91", "user": {"login": worker.OWNER}, "state": "open", "head": {
        "repo": {"full_name": worker.REPO}, "sha": "a" * 40}}


class SprintTests(unittest.TestCase):
    def test_only_owner_and_current_sprint(self):
        body = "### Sprint start (Moscow)\n\n2026-11-25\n\n### User outcome\n\nShow total\n\n### Acceptance examples\n\n100 + 60 = 160"
        issue = {"number": 90, "user": {"login": worker.OWNER}, "body": body}
        self.assertTrue(worker.eligible(issue, dt.date(2026, 11, 25)))
        self.assertFalse(worker.eligible(issue, dt.date(2026, 11, 24)))
        self.assertFalse(worker.eligible({**issue, "user": {"login": "other"}}, dt.date(2026, 11, 25)))

    def test_dependencies_and_invalid_inputs(self):
        self.assertEqual(worker.dependencies("### Dependencies\n\n#38, #39"), [38, 39])
        self.assertEqual(worker.dependencies("### Dependencies\n\n_No response_"), [])
        with self.assertRaises(worker.Blocked):
            worker.dependencies("### Dependencies\n\nexternal#38")

    def test_subscription_budget(self):
        base = {"planType": "prolite", "credits": {"hasCredits": False, "unlimited": False},
                "secondary": {"usedPercent": 86}}
        subscription.check_limits({"rateLimitsByLimitId": {"codex": base}})
        for change in ({"credits": None}, {"credits": {"hasCredits": True, "unlimited": False}},
                       {"secondary": {"usedPercent": 95}}, {"secondary": None},
                       {"planType": "unknown"}, {"rateLimitReachedType": "rate_limit_reached"}):
            with self.subTest(change=change), self.assertRaises(subscription.Waiting):
                subscription.check_limits({"rateLimits": {**base, **change}})

    def test_idle_does_not_call_model_or_require_settings(self):
        with patch.object(worker, "gh", return_value="[[]]"), patch.object(worker, "protected") as protection, \
                patch.object(worker, "agent") as model:
            worker.tick()
            protection.assert_not_called()
            model.assert_not_called()

    def test_product_edits_and_control_file_rejection(self):
        with tempfile.TemporaryDirectory() as tmp:
            repo, work = Path(tmp, "repo"), Path(tmp, "work")
            repo.mkdir()
            subprocess.run(["git", "init", "-q", str(repo)], check=True)
            (repo / "src").mkdir()
            (repo / "src/value.txt").write_text("wrong")
            worker.git(repo, "add", "-A")
            worker.snapshot(repo, work)
            (work / "src/value.txt").write_text("correct")
            worker.apply_edits(work, repo)
            self.assertEqual((repo / "src/value.txt").read_text(), "correct")
            (work / ".github").mkdir()
            (work / ".github/review.yml").write_text("untrusted")
            with self.assertRaises(worker.Blocked):
                worker.apply_edits(work, repo)

    def test_only_matching_bot_approval_can_merge(self):
        pr = pr_case()
        sha = pr["head"]["sha"]
        summary = "Tests match the issue"
        for review_sha, actor, decision, body, merge in [
            (sha, "github-actions[bot]", "APPROVED", summary, True),
            ("b" * 40, worker.REVIEWER, "APPROVED", summary, False),
            (sha, worker.OWNER, "APPROVED", summary, False),
            (sha, "finance-lab-sprint-reviewer[bot]", "APPROVED", summary, False),
            (sha, worker.REVIEWER, "APPROVED", "Another review", False),
            (sha, worker.REVIEWER, "CHANGES_REQUESTED", summary, False),
        ]:
            with self.subTest(actor=actor, sha=review_sha, decision=decision, body=body):
                state = {"review": {"sha": sha, "decision": "approve", "summary": summary}}
                review = [{"commit_id": review_sha, "user": {"login": actor}, "state": decision, "body": body}]
                with patch.object(worker, "api", side_effect=[[pr], {"behind_by": 0}]), \
                        patch.object(worker, "ci", return_value=("passed", "")), \
                        patch.object(worker, "gh", return_value=json.dumps([review])) as gh, \
                        patch.object(worker, "record") as record:
                    worker.advance({"number": 90}, state, lambda: None, Mock())
                    commands = [call.args for call in gh.call_args_list]
                    self.assertEqual(any(cmd[:2] == ("pr", "merge") for cmd in commands), merge)
                    if merge:
                        self.assertEqual(commands[-1][-2:], ("--match-head-commit", sha))
                        record.assert_not_called()

    def test_actions_approval_permission_is_required(self):
        for policy in ({}, {"can_approve_pull_request_reviews": False}):
            with self.subTest(policy=policy), patch.object(worker, "api", return_value=policy) as api:
                with self.assertRaisesRegex(worker.Waiting, "Allow GitHub Actions"):
                    worker.protected()
                api.assert_called_once_with(worker.endpoint("actions/permissions/workflow"))

    def test_solo_merges_require_a_pr_and_strict_verify(self):
        checks = {"strict": True, "checks": [{"context": "verify", "app_id": 15368}]}
        solo = {"required_approving_review_count": 0, "dismiss_stale_reviews": False}
        team = {"required_approving_review_count": 1, "dismiss_stale_reviews": True}
        policy = {"required_pull_request_reviews": solo, "required_status_checks": checks,
                  "enforce_admins": {"enabled": True}}
        for change, valid in [
                ({}, True), ({"required_pull_request_reviews": team}, True),
                ({"required_pull_request_reviews": None}, False),
                ({"required_pull_request_reviews": {**team, "dismiss_stale_reviews": False}}, False),
                ({"required_status_checks": {**checks, "strict": False}}, False),
                ({"required_status_checks": {"strict": True, "checks": []}}, False),
                ({"enforce_admins": {"enabled": False}}, False)]:
            replies = [{"can_approve_pull_request_reviews": True},
                       {"allow_auto_merge": True, "allow_squash_merge": True}, {**policy, **change}]
            with self.subTest(change=change), patch.object(worker, "api", side_effect=replies):
                if valid:
                    worker.protected()
                else:
                    with self.assertRaises(worker.OwnerAction):
                        worker.protected()

    def test_pending_ci_does_not_approve(self):
        with patch.object(worker, "api", side_effect=[[pr_case()], {"behind_by": 0}]), \
                patch.object(worker, "ci", return_value=("pending", "")), \
                patch.object(worker, "record") as record, patch.object(worker, "gh") as gh:
            worker.advance({"number": 90}, {}, lambda: None, Mock())
            record.assert_not_called()
            gh.assert_not_called()


if __name__ == "__main__":
    unittest.main()

