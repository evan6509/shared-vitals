import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "release_notes.py"
spec = importlib.util.spec_from_file_location("release_notes", SCRIPT)
release_notes = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release_notes)


class ReleaseNotesTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.old_cwd = os.getcwd()
        os.chdir(self.directory.name)
        self.addCleanup(os.chdir, self.old_cwd)
        self.git("init", "-b", "main")
        self.git("config", "user.name", "Release Test")
        self.git("config", "user.email", "release@example.invalid")
        self.first = self.commit("Create project")
        self.git("tag", "v0.1.1")

    def git(self, *args):
        return subprocess.check_output(["git", *args], text=True, stderr=subprocess.PIPE).strip()

    def commit(self, subject):
        self.git("commit", "--allow-empty", "-m", subject)
        return self.git("rev-parse", "HEAD")

    def test_initial_release_includes_history(self):
        output = release_notes.notes("0.1.1", "owner/shared-vitals", self.first)
        self.assertIn("# What's new in 0.1.1", output)
        self.assertIn("- Create project", output)
        self.assertIn("Initial release.", output)

    def test_release_range_and_target_are_exact(self):
        target = self.commit("Share player hunger")
        self.commit("Later unreleased work")
        output = release_notes.notes("0.1.2", "owner/shared-vitals", target, "v0.1.1")
        self.assertIn("Share player hunger", output)
        self.assertNotIn("Create project", output)
        self.assertNotIn("Later unreleased work", output)
        self.assertIn("compare/v0.1.1...v0.1.2", output)

    def test_merge_lists_feature_commits_once(self):
        self.git("switch", "-c", "codex/development")
        self.commit("Share player health")
        self.git("switch", "main")
        self.git("merge", "--no-ff", "codex/development", "-m", "Merge development")
        output = release_notes.notes("0.1.2", "owner/shared-vitals", "HEAD", "v0.1.1")
        self.assertEqual(output.count("Share player health"), 1)
        self.assertNotIn("Merge development", output)

    def test_unrelated_previous_release_is_rejected(self):
        self.git("checkout", "--orphan", "unrelated")
        unrelated = self.commit("Other history")
        with self.assertRaises(subprocess.CalledProcessError):
            release_notes.notes("0.1.2", "owner/shared-vitals", unrelated, "v0.1.1")

    def test_subject_does_not_inject_links_or_mentions(self):
        target = self.commit("Fix [health](https://example.invalid) for @everyone")
        output = release_notes.notes("0.1.2", "owner/shared-vitals", target, "v0.1.1")
        self.assertIn(r"\[health\]", output)
        self.assertNotIn("@everyone", output)


if __name__ == "__main__":
    unittest.main()
