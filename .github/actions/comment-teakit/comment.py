"""Post a compact TeaKit report from a completed, untrusted pull request build."""

import io
import json
import os
import re
import sys
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
import zipfile

MARKER = "<!-- teakit-ci-results -->"
MAX_REPORT_BYTES = 1_000_000
MAX_COMMENT_CHARS = 60_000
TEAKIT_JOB = re.compile(r"(?:^| / )TeaKit ([A-Za-z0-9_.-]+)$")


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        redirected = super().redirect_request(request, fp, code, msg, headers, newurl)
        if redirected and urllib.parse.urlparse(newurl).hostname != "api.github.com":
            redirected.remove_header("Authorization")
        return redirected


class GitHub:
    def __init__(self, token):
        self.token = token
        self.opener = urllib.request.build_opener(SafeRedirect())

    def request(self, path, method="GET", body=None, max_bytes=25_000_000):
        url = "https://api.github.com/" + path.lstrip("/")
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(url, data=data, method=method, headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {self.token}",
            "X-GitHub-Api-Version": "2022-11-28",
        })
        with self.opener.open(request, timeout=30) as response:
            raw = response.read(max_bytes + 1)
            if len(raw) > max_bytes:
                raise ValueError("GitHub response exceeds size limit")
        return json.loads(raw) if raw and "json" in response.headers.get("Content-Type", "") else raw

    def pages(self, path, key):
        for page in range(1, 11):
            separator = "&" if "?" in path else "?"
            items = self.request(f"{path}{separator}per_page=100&page={page}")
            if key:
                items = items[key]
            yield from items
            if len(items) < 100:
                break


def report_from_zip(data):
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            matches = [name for name in archive.namelist() if name.endswith("ci-summary.json")]
            if len(matches) != 1:
                return None
            info = archive.getinfo(matches[0])
            if info.file_size > MAX_REPORT_BYTES:
                return None
            with archive.open(info) as file:
                summary = json.loads(file.read(MAX_REPORT_BYTES + 1))
        if len(summary.get("runs", [])) != 1:
            return None
        run = summary["runs"][0]
        result = run.get("result")
        if result is None:
            if run.get("status") != "failed" or not isinstance(run.get("error"), str):
                return None
            # Reports are untrusted. Describe known failures without publishing raw logs.
            if re.fullmatch(r"Minecraft launch process exited early with code \d+", run["error"]):
                tail = run.get("runLogTail", [])
                if isinstance(tail, list) and any(
                    isinstance(line, str) and line.startswith("BUILD FAILED") for line in tail
                ):
                    return {"summary": "Tests did not run — build failed (see job log)", "tests": []}
                return {"summary": "Tests did not run — Minecraft failed to start (see job log)", "tests": []}
            return {"summary": "No completed test results — runner failed (see job log)", "tests": []}
        passed, failed = result["passed"], result["failed"]
        if any(type(count) is not int or count < 0 for count in (passed, failed)):
            return None
        tests = result.get("tests", [])
        if not isinstance(tests, list) or any(
            not isinstance(test, dict)
            or not isinstance(test.get("name"), str)
            or test.get("status") not in ("passed", "failed", "skipped", "todo")
            for test in tests
        ):
            return None
        skipped = sum(test["status"] in ("skipped", "todo") for test in tests)
        return {
            "summary": f"{passed} passed · {failed} failed · {skipped} skipped",
            "tests": tests,
            "failed": failed,
            "runtime_failed": run.get("runtimeCapabilityAuditResult", {}).get("passed") is False,
        }
    except (OSError, ValueError, KeyError, TypeError, AttributeError, IndexError, zipfile.BadZipFile):
        return None


def markdown_text(value):
    return str(value).replace("|", "\\|").replace("\n", " ").replace("\r", " ").replace("[", "\\[").replace("]", "\\]")


def test_line(test):
    # Keep report text inside a single code span: no mentions, links, HTML, or fences.
    name = " ".join("".join(
        " " if unicodedata.category(char).startswith("C") else char for char in test["name"]
    ).split())
    if len(name) > 240:
        name = name[:239] + "…"
    name = name or "Unnamed test"
    fence = "`" * (1 + max((len(match[0]) for match in re.finditer(r"`+", name)), default=0))
    icon = {"passed": "✅", "failed": "❌", "skipped": "⏭️", "todo": "⏭️"}[test["status"]]
    line = f"- {icon} {fence} {name} {fence}"
    duration = test.get("durationMs")
    if type(duration) is int and 0 <= duration <= 86_400_000:
        line += f" — {duration / 1000:.1f}s" if duration >= 1000 else f" — {duration}ms"
    if test["status"] == "todo":
        line += " — not implemented"
    elif test["status"] == "skipped" and test.get("reason") == "target-mismatch":
        line += " — target does not apply"
    return line


def render_report(report, status, budget):
    if report is None:
        return "⚠️ Report unavailable. See the job log for details."
    lines = [f"**{report['summary']}**"]
    if report.get("runtime_failed"):
        lines += ["", "⚠️ **Runtime capability check failed.** See the job log for details."]
    elif status == "cancelled":
        lines += ["", "⏹️ **The job was cancelled after these results were recorded.**"]
    elif status not in ("success", "skipped") and report.get("failed") == 0:
        lines += ["", "⚠️ **The job failed outside the test assertions.** See the job log for details."]
    if report.get("failed", 0):
        lines += ["", "Failure details are available in the job log."]
    tests = sorted(report["tests"], key=lambda test: {"failed": 0, "passed": 1, "skipped": 2, "todo": 2}[test["status"]])
    if not tests and "failed" in report:
        lines += ["", "No individual test results were included in the report."]
    lines.append("")
    used = len("\n".join(lines))
    for index, test in enumerate(tests):
        line = test_line(test)
        if used + len(line) + 150 > budget:
            lines.append(f"\n… {len(tests) - index} more tests; see the job log for the full list.")
            break
        lines.append(line)
        used += len(line) + 1
    return "\n".join(lines).rstrip()


def match_pr(pr, run, repository):
    return (pr.get("state") == "open"
            and pr.get("head", {}).get("sha") == run.get("head_sha")
            and pr.get("head", {}).get("repo", {}).get("full_name") == run.get("head_repository", {}).get("full_name")
            and pr.get("base", {}).get("repo", {}).get("full_name") == repository)


def render(run, jobs, artifacts, github, repository):
    url = f"https://github.com/{repository}/actions/runs/{run['id']}/attempts/{run['run_attempt']}"
    header = f"{MARKER}\n### TeaKit results\n\n[Build run]({url}) · attempt {run['run_attempt']}\n\n"
    sections = []
    used = len(header)
    for job in jobs:
        match = TEAKIT_JOB.search(job["name"])
        if not match:
            continue
        node = match.group(1)
        if used + 1000 > MAX_COMMENT_CHARS:
            sections.append("Additional nodes omitted; see the build run for all results.")
            break
        created_after = job.get("started_at") or ""
        candidates = [artifact for artifact in artifacts
                      if artifact["name"] == f"teakit-{node}"
                      and not artifact.get("expired")
                      and artifact.get("created_at", "") >= created_after]
        summary = None
        if candidates:
            artifact = max(candidates, key=lambda item: item["created_at"])
            raw = github.request(f"repos/{repository}/actions/artifacts/{artifact['id']}/zip")
            if len(raw) <= 25_000_000:
                summary = report_from_zip(raw)
        status = job.get("conclusion") or "unknown"
        job_url = f"https://github.com/{repository}/actions/runs/{run['id']}/job/{job['id']}"
        icon = {"success": "✅", "failure": "❌", "skipped": "⏭️", "cancelled": "⏹️"}.get(status, "⚠️")
        heading = f"#### {icon} {markdown_text(node)}\n\n[Job log]({job_url}) · {markdown_text(status)}\n\n"
        section = heading + render_report(summary, status, MAX_COMMENT_CHARS - used - len(heading) - 200)
        sections.append(section)
        used += len(section) + 2
    if not sections:
        return header + "No TeaKit checks ran for this commit."
    return header + "\n\n".join(sections)


def update_comment(github, repository, number, body, run):
    path = f"repos/{repository}/issues/{number}/comments"
    existing = next((item for item in github.pages(path, None)
                     if item.get("user", {}).get("login") == "github-actions[bot]"
                     and MARKER in item.get("body", "")), None)
    if existing:
        # A delayed older run must not replace the latest result for this PR.
        old_run = re.search(r"/actions/runs/(\d+)/attempts/(\d+)", existing["body"])
        if old_run and (int(old_run[1]), int(old_run[2])) > (run["id"], run["run_attempt"]):
            return
        github.request(f"repos/{repository}/issues/comments/{existing['id']}", "PATCH", {"body": body})
    else:
        github.request(path, "POST", {"body": body})


def main():
    repository = os.environ["GITHUB_REPOSITORY"]
    with open(os.environ["GITHUB_EVENT_PATH"], encoding="utf-8") as event_file:
        run = json.load(event_file)["workflow_run"]
    if run.get("event") != "pull_request" or run.get("repository", {}).get("full_name") != repository:
        return
    github = GitHub(os.environ["GITHUB_TOKEN"])
    jobs = list(github.pages(
        f"repos/{repository}/actions/runs/{run['id']}/attempts/{run['run_attempt']}/jobs", "jobs"))
    artifacts = list(github.pages(f"repos/{repository}/actions/runs/{run['id']}/artifacts", "artifacts"))
    body = render(run, jobs, artifacts, github, repository)
    prs = github.pages(f"repos/{repository}/commits/{run['head_sha']}/pulls", None)
    for pr in prs:
        # Fetch current PR state: the commit association response may be stale.
        current = github.request(f"repos/{repository}/pulls/{pr['number']}")
        if match_pr(current, run, repository):
            update_comment(github, repository, current["number"], body, run)


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as error:
        print(f"GitHub API failed: {error.code} {error.reason}", file=sys.stderr)
        raise SystemExit(1) from None
