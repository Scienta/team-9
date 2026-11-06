"""Tests for workflow/bin/work against a throwaway bare 'origin' and clones of it."""
from concurrent.futures import ThreadPoolExecutor
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("bin") / "work"


def git(*args, cwd):
    return subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True, text=True).stdout


class WorkBranchTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.base = Path(temp.name)
        self.origin = self.base / "origin.git"
        git("init", "-q", "--bare", "-b", "main", str(self.origin), cwd=self.base)
        seed = self.clone("seed", install=False)
        (seed / "app.txt").write_text("app\n")
        git("add", ".", cwd=seed)
        git("commit", "-q", "-m", "init", cwd=seed)
        git("push", "-q", "origin", "main", cwd=seed)

    def clone(self, name, install=True):
        path = self.base / name
        git("clone", "-q", str(self.origin), str(path), cwd=self.base)
        git("config", "user.name", name, cwd=path)
        git("config", "user.email", f"{name}@test", cwd=path)
        if install:  # untracked, like a fresh script before it's committed
            (path / "workflow/bin").mkdir(parents=True, exist_ok=True)
            shutil.copy(SCRIPT, path / "workflow/bin/work")
        return path

    def work(self, clone, *args, check=True):
        result = subprocess.run([str(clone / "workflow/bin/work"), *args], cwd=clone, capture_output=True,
                                text=True, env={**os.environ, "WORK_AGENT": "test"})
        if check and result.returncode != 0:
            self.fail(f"work {args} failed ({result.returncode}): {result.stderr}")
        return result

    def board_file(self, path):
        return git("show", f"work:{path}", cwd=self.origin)

    def test_first_write_creates_an_orphan_branch(self):
        a = self.clone("anna")
        self.work(a, "start", "co-speakers")
        self.assertIn("Owner: anna (test)", self.board_file("features/co-speakers.md"))
        self.assertIn("never merged", self.board_file("README.md"))
        # No shared history with main, so it can't be merged or PR'd into main by accident.
        merge_base = subprocess.run(["git", "merge-base", "main", "work"], cwd=self.origin, capture_output=True)
        self.assertNotEqual(0, merge_base.returncode)
        self.assertEqual("app.txt\n", git("ls-tree", "--name-only", "main", cwd=self.origin))

    def test_start_is_exclusive_until_released(self):
        a, b = self.clone("anna"), self.clone("bob")
        self.work(a, "start", "acceptance")
        taken = self.work(b, "start", "acceptance", check=False)
        self.assertEqual(3, taken.returncode)
        self.assertIn("already taken: anna", taken.stderr)
        self.work(a, "state", "acceptance", "abandoned", "handing over")
        self.work(b, "start", "acceptance")
        body = self.board_file("features/acceptance.md")
        self.assertIn("Owner: bob (test)", body)
        self.assertIn("restarted after abandoned", body)

    def test_concurrent_starts_have_exactly_one_winner(self):
        clones = [self.clone(f"agent{i}") for i in range(4)]
        with ThreadPoolExecutor(len(clones)) as pool:
            results = list(pool.map(lambda c: self.work(c, "start", "ratings-v2", check=False), clones))
        self.assertEqual([0], [r.returncode for r in results if r.returncode == 0])
        self.assertEqual(3, sum(r.returncode == 3 for r in results))

    def test_concurrent_logs_lose_nothing(self):
        clones = [self.clone(f"agent{i}") for i in range(4)]
        self.work(clones[0], "start", "talks")
        with ThreadPoolExecutor(len(clones)) as pool:
            list(pool.map(lambda ic: self.work(ic[1], "log", "talks", f"line {ic[0]}"), enumerate(clones)))
        body = self.board_file("features/talks.md")
        for i in range(4):
            self.assertIn(f"line {i}", body)

    def test_state_and_pr_fields_and_show(self):
        a = self.clone("anna")
        self.work(a, "start", "talks")
        self.work(a, "state", "talks", "in-review", "PR https://github.com/Scienta/team-9/pull/7")
        body = self.board_file("features/talks.md")
        self.assertIn("State: in-review", body)
        self.assertIn("PR: https://github.com/Scienta/team-9/pull/7", body)
        self.work(a, "note", "product-owner", "co-speakers may be the main speaker: no")
        shown = self.work(self.clone("bob"), "show").stdout
        self.assertRegex(shown, r"talks\s+in-review\s+anna \(test\)")
        self.assertIn("notes/product-owner.md", shown)
        self.assertIn("main speaker: no", self.work(a, "show", "notes/product-owner").stdout)

    def test_rejects_unknown_state_unstarted_feature_and_credentials(self):
        a = self.clone("anna")
        self.assertEqual(3, self.work(a, "log", "talks", "hi", check=False).returncode)
        self.work(a, "start", "talks")
        self.assertNotEqual(0, self.work(a, "state", "talks", "done", check=False).returncode)
        self.assertNotEqual(0, self.work(a, "log", "talks", "curl -H 'Authorization: Bearer x'", check=False).returncode)
        self.assertNotEqual(0, self.work(a, "start", "../evil", check=False).returncode)
        self.assertNotIn("Bearer", self.board_file("features/talks.md"))

    def test_never_touches_the_checkout(self):
        a = self.clone("anna")
        self.work(a, "start", "talks")
        self.assertEqual("main\n", git("rev-parse", "--abbrev-ref", "HEAD", cwd=a))
        self.assertEqual("?? workflow/\n", git("status", "--porcelain", cwd=a))


if __name__ == "__main__":
    unittest.main()
