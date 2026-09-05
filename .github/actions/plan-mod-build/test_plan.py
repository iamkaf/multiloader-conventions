from pathlib import Path
import subprocess
import tempfile
import unittest

from plan import changed_files, plan_builds, plan_teakit, version_key


class BuildPlanTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        for loader in ("fabric", "forge", "neoforge"):
            self.write(f"{loader}/build.gradle.kts", "")
        self.version("1.21.11", "fabric,forge,neoforge", 21)
        self.version("26.2", "fabric,neoforge", 25)

    def write(self, path, contents):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(contents)

    def version(self, version, loaders, java):
        self.write(f"versions/{version}/gradle.properties", f"project.enabled-loaders={loaders}\nproject.java={java}\n")

    def test_shared_source_builds_every_node_with_one_common_check_per_version(self):
        jobs = plan_builds(self.root, ["common/src/main/java/Example.java"])
        self.assertEqual(5, len(jobs))
        for version in ("1.21.11", "26.2"):
            selected = [j for j in jobs if j["version"] == version]
            self.assertEqual(1, sum(j["common"] == "compileJava" for j in selected))

    def test_version_overlays_select_only_their_version(self):
        for path in ("versions/26.2/gradle.properties", "fabric/versions/26.2/src/main/java/Example.java"):
            with self.subTest(path=path):
                jobs = plan_builds(self.root, [path])
                self.assertEqual(["26.2-fabric", "26.2-neoforge"], [j["name"] for j in jobs])

    def test_docs_only_and_empty_diffs_need_no_runners(self):
        for changes in ([], ["README.md", ".github/ISSUE_TEMPLATE/bug.yml"], ["versions/26.2/README.md"]):
            self.assertEqual([], plan_builds(self.root, changes))

    def test_dispatch_and_deleted_versions_validate_remaining_matrix(self):
        for changes in (None, ["versions/1.20.1/gradle.properties"]):
            self.assertEqual(5, len(plan_builds(self.root, changes)))

    def test_horizontal_jobs_build_each_loader_once_and_keep_common_tests(self):
        jobs = plan_builds(self.root, None, common_task="test", horizontal_jars=True)
        self.assertEqual(2, len(jobs))
        self.assertEqual("fabric,forge,neoforge", jobs[0]["loaders"])
        self.assertEqual(3, len(jobs[0]["artifacts"].splitlines()))
        self.assertTrue(all(j["merge"] and j["common"] == "test" for j in jobs))
        self.assertTrue(all(j["java"] == "25" for j in jobs))

    def test_single_loader_version_does_not_request_a_merged_jar(self):
        self.version("26.3-pre-1", "fabric", 25)
        jobs = plan_builds(self.root, ["versions/26.3-pre-1/gradle.properties"], horizontal_jars=True)
        self.assertEqual(1, len(jobs))
        self.assertFalse(jobs[0]["merge"])

    def test_build_java_overrides_runtime_java(self):
        path = self.root / "versions/1.21.11/gradle.properties"
        path.write_text(path.read_text() + "project.build-java=25\n")
        self.assertTrue(all(j["java"] == "25" for j in plan_builds(self.root, None)))

    def test_build_java_meets_settings_plugin_floor_without_changing_runtime(self):
        path = "versions/1.16.5/gradle.properties"
        properties = "project.enabled-loaders=fabric\nproject.java=8\nproject.build-java=17\n"
        self.write(path, properties)
        jobs = plan_builds(self.root, [path])
        self.assertEqual("21", jobs[0]["java"])
        self.assertEqual(properties, (self.root / path).read_text())

    def test_invalid_metadata_fails_instead_of_dropping_a_check(self):
        cases = [("project.enabled-loaders=fabric,unknown\nproject.java=25\n"),
                 ("project.enabled-loaders=fabric\n"),
                 ("project.enabled-loaders=\nproject.java=25\n")]
        for properties in cases:
            with self.subTest(properties=properties):
                self.write("versions/26.2/gradle.properties", properties)
                with self.assertRaises(ValueError):
                    plan_builds(self.root, None)

    def test_missing_loader_build_file_fails(self):
        (self.root / "forge/build.gradle.kts").unlink()
        with self.assertRaises(ValueError):
            plan_builds(self.root, None)

    def test_common_check_can_be_disabled_without_losing_loader_builds(self):
        jobs = plan_builds(self.root, None, common_task="none")
        self.assertEqual(5, len(jobs))
        self.assertTrue(all(j["common"] == "none" for j in jobs))
        with self.assertRaises(ValueError):
            plan_builds(self.root, None, common_task="unknown")

    def test_git_diff_keeps_both_sides_of_a_version_rename(self):
        def git(*args):
            return subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True, text=True).stdout.strip()
        git("init", "-b", "main")
        git("add", ".")
        git("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", "Create fixture")
        before = git("rev-parse", "HEAD")
        git("update-ref", "refs/remotes/origin/main", before)
        git("mv", "versions/26.2", "versions/26.3")
        git("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", "Rename version")
        for changes in (changed_files(self.root, "push", before_sha=before), changed_files(self.root, "pull_request", base_ref="main")):
            self.assertIn("versions/26.2/gradle.properties", changes)
            self.assertIn("versions/26.3/gradle.properties", changes)
        self.assertIsNone(changed_files(self.root, "workflow_dispatch"))

    def runtime_fixture(self):
        self.write("test/teakit/backpack.test.ts", "")
        self.write("teakitw", "")

    def test_runtime_uses_latest_numeric_version(self):
        self.runtime_fixture()
        self.version("26.10", "fabric", 25)
        jobs = plan_teakit(self.root, None, "wrapper")
        self.assertEqual(["26.10-fabric"], [job["name"] for job in jobs])

    def test_runtime_skips_docs_and_older_version_changes(self):
        self.runtime_fixture()
        for paths in (["README.md"], ["versions/1.21.11/gradle.properties"], []):
            self.assertEqual([], plan_teakit(self.root, paths, "wrapper"))
        for paths in (["test/teakit/backpack.test.ts"], ["fabric/src/main/java/Mod.java"], ["versions/26.2/gradle.properties"]):
            self.assertEqual(["26.2-fabric"], [job["name"] for job in plan_teakit(self.root, paths, "wrapper")])

    def test_runtime_loader_pilot_retains_all_requested_loaders(self):
        self.runtime_fixture()
        self.version("26.2", "fabric,forge,neoforge", 25)
        jobs = plan_teakit(self.root, None, "gradle", "fabric,forge,neoforge")
        self.assertEqual(["26.2-fabric", "26.2-forge", "26.2-neoforge"], [job["name"] for job in jobs])

    def test_runtime_missing_suite_or_wrapper_fails(self):
        self.assertEqual([], plan_teakit(self.root, None))
        with self.assertRaises(ValueError):
            plan_teakit(self.root, None, "gradle")
        self.write("test/teakit/backpack.test.ts", "")
        with self.assertRaises(ValueError):
            plan_teakit(self.root, None, "wrapper")

    def test_runtime_invalid_or_unavailable_loaders_fail(self):
        self.runtime_fixture()
        for loaders in ("unknown", "fabric,fabric", "", "forge"):
            with self.subTest(loaders=loaders), self.assertRaises(ValueError):
                plan_teakit(self.root, None, "gradle", loaders)

    def test_version_ordering_distinguishes_prereleases(self):
        versions = ["26.3", "26.2", "26.3-rc-2", "26.3-pre-10", "26.3-pre-2"]
        self.assertEqual(["26.2", "26.3-pre-2", "26.3-pre-10", "26.3-rc-2", "26.3"], sorted(versions, key=version_key))


if __name__ == "__main__":
    unittest.main()
