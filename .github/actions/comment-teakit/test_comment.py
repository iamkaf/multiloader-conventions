import importlib.util
import io
import json
from pathlib import Path
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("comment", Path(__file__).with_name("comment.py"))
comment = importlib.util.module_from_spec(spec)
spec.loader.exec_module(comment)


class FakeGitHub:
    def __init__(self, archive=b""):
        self.archive = archive
        self.calls = []
        self.comments = []

    def request(self, path, method="GET", body=None):
        self.calls.append((path, method, body))
        if path.endswith("/zip"):
            return self.archive
        return None

    def pages(self, path, key):
        return iter(self.comments)


class CommentTests(unittest.TestCase):
    def test_report_counts_without_exposing_test_text(self):
        payload = {"runs": [{"result": {"passed": 2, "failed": 1, "tests": [
            {"status": "skipped", "name": "private | [text](url)"}]}}]}
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w") as archive:
            archive.writestr("build/teakit/ci-summary.json", json.dumps(payload))
        self.assertEqual(comment.report_from_zip(data.getvalue()), (2, 1, 1))
        run = {"id": 8, "run_attempt": 1}
        jobs = [{"name": "build / TeaKit 26.3-fabric", "id": 9,
                 "conclusion": "failure", "started_at": "2026-09-24T01:00:00Z"}]
        artifacts = [{"name": "teakit-26.3-fabric", "id": 10,
                      "created_at": "2026-09-24T01:01:00Z"}]
        body = comment.render(run, jobs, artifacts, FakeGitHub(data.getvalue()), "iamkaf/mod")
        self.assertIn("2 passed, 1 failed, 1 skipped", body)
        self.assertNotIn("private", body)

    def test_old_artifact_cannot_supply_rerun(self):
        run = {"id": 8, "run_attempt": 2}
        jobs = [{"name": "build / TeaKit 26.3-fabric", "id": 9,
                 "conclusion": "failure", "started_at": "2026-09-24T02:00:00Z"}]
        artifacts = [{"name": "teakit-26.3-fabric", "id": 10,
                      "created_at": "2026-09-24T01:01:00Z"}]
        body = comment.render(run, jobs, artifacts, FakeGitHub(), "iamkaf/mod")
        self.assertIn("Report unavailable", body)

    def test_current_pr_must_match_commit_and_repository(self):
        run = {"head_sha": "abc", "head_repository": {"full_name": "fork/mod"}}
        pr = {"state": "open", "head": {"sha": "abc", "repo": {"full_name": "fork/mod"}},
              "base": {"repo": {"full_name": "iamkaf/mod"}}}
        self.assertTrue(comment.match_pr(pr, run, "iamkaf/mod"))
        pr["head"]["sha"] = "def"
        self.assertFalse(comment.match_pr(pr, run, "iamkaf/mod"))

    def test_comment_updates_and_older_run_is_ignored(self):
        github = FakeGitHub()
        github.comments = [{"id": 4, "user": {"login": "github-actions[bot]"},
                            "body": comment.MARKER + " /actions/runs/9/attempts/1"}]
        comment.update_comment(github, "iamkaf/mod", 3, "new", {"id": 8, "run_attempt": 2})
        self.assertFalse(github.calls)
        comment.update_comment(github, "iamkaf/mod", 3, "new", {"id": 10, "run_attempt": 1})
        self.assertEqual(github.calls[0][1:], ("PATCH", {"body": "new"}))

    def test_no_teakit_jobs_is_explicit(self):
        body = comment.render({"id": 8, "run_attempt": 1}, [], [], FakeGitHub(), "iamkaf/mod")
        self.assertIn("No TeaKit checks ran", body)


if __name__ == "__main__":
    unittest.main()
