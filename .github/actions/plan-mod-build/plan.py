"""Plan CI jobs from version metadata without configuring Minecraft in Gradle."""

import json
import os
from pathlib import Path
import re
import subprocess


LOADERS = ("fabric", "forge", "neoforge")
VERSION_PATH = re.compile(r"^(?:(?:common|fabric|forge|neoforge)/)?versions/([^/]+)/")


def changed_files(root, event, base_ref="", before_sha=""):
    if event == "pull_request":
        if not base_ref:
            raise ValueError("Missing pull request base branch")
        revision = f"refs/remotes/origin/{base_ref}...HEAD"
    elif event == "push" and before_sha and set(before_sha) != {"0"}:
        if not re.fullmatch(r"[0-9a-f]{40}", before_sha):
            raise ValueError("Invalid previous commit")
        revision = f"{before_sha}..HEAD"
    else:
        return None
    result = subprocess.run(
        ["git", "diff", "--name-only", "--no-renames", "-z", revision, "--"],
        cwd=root, check=True, capture_output=True, text=True,
    )
    return [path for path in result.stdout.split("\0") if path]


def selected_versions(versions, changes):
    if changes is None:
        return sorted(versions)
    selected = set()
    for path in changes:
        version_path = VERSION_PATH.match(path)
        if version_path:
            version = version_path.group(1)
            # A removed version can change the shared project graph.
            if version not in versions:
                return sorted(versions)
            selected.add(version)
        elif not (
            path.endswith(".md")
            or Path(path).name.startswith("LICENSE")
            or path in (".gitignore", ".gitattributes", ".editorconfig")
            or path.startswith(".github/ISSUE_TEMPLATE/")
        ):
            return sorted(versions)
    return sorted(selected)


def read_properties(path):
    properties = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith(("#", "!")) and "=" in line:
            key, value = line.split("=", 1)
            properties[key.strip()] = value.strip()
    return properties


def plan_builds(root, changes, common_task="compileJava", horizontal_jars=False):
    if common_task not in ("compileJava", "test", "none"):
        raise ValueError("common-task must be compileJava, test, or none")
    version_files = {p.parent.name: p for p in (root / "versions").glob("*/gradle.properties")}
    if not version_files:
        raise ValueError("No versions/*/gradle.properties found")

    jobs = []
    for version in selected_versions(version_files, changes):
        if not re.fullmatch(r"[0-9][A-Za-z0-9._-]*", version):
            raise ValueError(f"Invalid version directory: {version}")
        properties = read_properties(version_files[version])
        enabled = {v.strip() for v in properties.get("project.enabled-loaders", "").split(",") if v.strip()}
        if not enabled or enabled - set(LOADERS):
            raise ValueError(f"Invalid project.enabled-loaders for {version}")
        loaders = [loader for loader in LOADERS if loader in enabled]
        for loader in loaders:
            if not any((root / loader / name).is_file() for name in ("build.gradle.kts", "build.gradle")):
                raise ValueError(f"Missing build file for {version}-{loader}")
        java = properties.get("project.build-java") or properties.get("project.java", "")
        if not re.fullmatch(r"[0-9]+", java):
            raise ValueError(f"Missing or invalid build Java for {version}")

        # Merging consumes every loader jar, so build and validate them in one job.
        groups = [loaders] if horizontal_jars else [[loader] for loader in loaders]
        for index, group in enumerate(groups):
            merge = horizontal_jars and len(group) > 1
            jobs.append({
                "name": version if horizontal_jars else f"{version}-{group[0]}",
                "version": version,
                "loaders": ",".join(group),
                "java": str(max(25, int(java))) if merge else java,
                "common": common_task if index == 0 else "none",
                "merge": merge,
                "artifacts": "\n".join(f"{loader}/versions/{version}/build/libs/*.jar" for loader in group),
            })
    return jobs


def main():
    root = Path.cwd()
    changes = changed_files(root, os.environ["EVENT_NAME"], os.environ.get("BASE_REF", ""), os.environ.get("BEFORE_SHA", ""))
    horizontal = os.environ.get("HORIZONTAL_JARS", "false")
    if horizontal not in ("true", "false"):
        raise ValueError("horizontal-jars must be true or false")
    jobs = plan_builds(root, changes, os.environ.get("COMMON_TASK", "compileJava"), horizontal == "true")
    with Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
        output.write("matrix=" + json.dumps(jobs, separators=(",", ":")) + "\n")
    print("Selected builds: " + ", ".join(job["name"] for job in jobs) if jobs else "No build inputs changed")


if __name__ == "__main__":
    main()
