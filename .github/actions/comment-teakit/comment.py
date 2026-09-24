"""Post a compact TeaKit report from a completed, untrusted pull request build."""

import io
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from datetime import datetime

MARKER = "<!-- teakit-ci-results -->"
MAX_REPORT_BYTES = 1_000_000
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
        result = summary["runs"][0]["result"]
        passed, failed = result["passed"], result["failed"]
        if any(type(count) is not int or count < 0 for count in (passed, failed)):
            return None
        skipped = sum(test.get("status") == "skipped" for test in result.get("tests", []))
        return passed, failed, skipped
    except (OSError, ValueError, KeyError, TypeError, AttributeError, IndexError, zipfile.BadZipFile):
        return None


def markdown_text(value):
    return str(value).replace("|", "\\|").replace("\n", " ").replace("\r", " ").replace("[", "\\[").replace("]", "\\]")


def match_pr(pr, run, repository):
    return (pr.get("state") == "open"
            and pr.get("head", {}).get("sha") == run.get("head_sha")
            and pr.get("head", {}).get("repo", {}).get("full_name") == run.get("head_repository", {}).get("full_name")
            and pr.get("base", {}).get("repo", {}).get("full_name") == repository)


def render(run, jobs, artifacts, github, repository):
    rows = []
    for job in jobs:
        match = TEAKIT_JOB.search(job["name"])
        if not match:
            continue
        node = match.group(1)
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
        result = f"{summary[0]} passed, {summary[1]} failed, {summary[2]} skipped" if summary else "Report unavailable"
        status = job.get("conclusion") or "unknown"
        job_url = f"https://github.com/{repository}/actions/runs/{run['id']}/job/{job['id']}"
        rows.append(f"| [{markdown_text(node)}]({job_url}) | {markdown_text(status)} | {result} |")
    url = f"https://github.com/{repository}/actions/runs/{run['id']}/attempts/{run['run_attempt']}"
    header = f"{MARKER}\n### TeaKit results\n\n[Build run]({url}) · attempt {run['run_attempt']}\n\n"
    if not rows:
        return header + "No TeaKit checks ran for this commit."
    return header + "| Node | Check | Tests |\n| --- | --- | --- |\n" + "\n".join(rows)


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
