"""workflow/bin/check-main flags commits on local main that aren't on origin/main (lessons.md L4)."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("bin") / "check-main"


def git(*args, cwd):
    return subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", *args], cwd=cwd,
                          check=True, capture_output=True, text=True).stdout


class CheckMainTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        base = Path(temp.name)
        git("init", "-q", "--bare", "-b", "main", "origin.git", cwd=base)
        self.clone = base / "clone"
        git("clone", "-q", str(base / "origin.git"), "clone", cwd=base)
        git("commit", "-q", "--allow-empty", "-m", "init", cwd=self.clone)
        git("push", "-q", "origin", "main", cwd=self.clone)
        (self.clone / "workflow/bin").mkdir(parents=True)
        shutil.copy(SCRIPT, self.clone / "workflow/bin/check-main")

    def check(self):
        return subprocess.run([str(self.clone / "workflow/bin/check-main")], capture_output=True, text=True)

    def test_in_sync_passes(self):
        self.assertEqual(0, self.check().returncode)

    def test_commit_on_local_main_fails_and_names_it(self):
        git("commit", "-q", "--allow-empty", "-m", "agent work straight on main", cwd=self.clone)
        result = self.check()
        self.assertEqual(1, result.returncode)
        self.assertIn("agent work straight on main", result.stderr)

    def test_commits_on_a_branch_pass(self):
        git("switch", "-q", "-c", "feat/x", cwd=self.clone)
        git("commit", "-q", "--allow-empty", "-m", "branch work", cwd=self.clone)
        self.assertEqual(0, self.check().returncode)

    def test_main_behind_origin_passes(self):
        git("commit", "-q", "--allow-empty", "-m", "someone else's", cwd=self.clone)
        git("push", "-q", "origin", "main", cwd=self.clone)
        git("reset", "-q", "--hard", "HEAD~1", cwd=self.clone)
        self.assertEqual(0, self.check().returncode)


if __name__ == "__main__":
    unittest.main()
