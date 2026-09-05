import json
import os
from pathlib import Path
import tempfile
import unittest

from report import redact, verify


class RuntimeReportTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.instance = Path(directory.name) / "instance.json"
        self.instance.write_text(json.dumps({"pid": 2147483647}))
        self.summary = {
            "status": "passed", "failed": 0,
            "runs": [{
                "result": {"passed": 19, "failed": 0},
                "backgroundDisplay": {"provider": "xvfb", "display": ":2147483647"},
                "artifactFiles": {"instance": str(self.instance)},
            }],
        }

    def test_success_requires_executed_tests(self):
        verify(self.summary)
        self.summary["runs"][0]["result"] = {"passed": 0, "failed": 0, "skipped": 19}
        with self.assertRaises(ValueError):
            verify(self.summary)

    def test_success_requires_stopped_minecraft(self):
        self.instance.write_text(json.dumps({"pid": os.getpid()}))
        with self.assertRaises(ValueError):
            verify(self.summary)

    def test_failed_report_is_rejected(self):
        self.summary["status"] = "failed"
        with self.assertRaises(ValueError):
            verify(self.summary)

    def test_credentials_are_removed_at_every_depth(self):
        value = {"token": "root", "runs": [{"health": {"token": "runtime", "status": "ready"}}]}
        self.assertEqual({"runs": [{"health": {"status": "ready"}}]}, redact(value))
        self.assertEqual("root", value["token"])
