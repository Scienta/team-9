import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import urllib.error

module_spec = importlib.util.spec_from_file_location("watch_specs", Path(__file__).with_name("watch_specs.py"))
watch = importlib.util.module_from_spec(module_spec)
module_spec.loader.exec_module(watch)


REAL_POPEN = watch.subprocess.Popen


def fake_agent(process):
    """Popen replacement: intercept only the pi agent, let git run for real."""
    def popen(command, *args, **kwargs):
        if command[0] == "git":
            return REAL_POPEN(command, *args, **kwargs)
        return process
    return Mock(side_effect=popen)


class FakeClient:
    text = "# Speakers\nVersion: 1\n"

    def get(self, path):
        if path == "/specs":
            return '<a href="/specs/speakers/spec.md">Spec</a><a href="https://evil.test/specs/evil/spec.md">Ignore</a>'
        if path == "/specs/speakers/spec.md":
            return self.text
        raise AssertionError(path)


class WatchSpecsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.state = {"tasks": {}}
        self.client = FakeClient()

    def discover(self, baseline=False):
        watch.discover(self.client, self.directory, self.state, baseline)

    def test_baseline_is_not_dispatched(self):
        self.discover(baseline=True)
        self.discover()
        self.assertEqual(1, len(self.state["tasks"]))
        self.assertEqual("baseline", next(iter(self.state["tasks"].values()))["status"])

    def test_new_version_is_queued_once(self):
        self.discover(baseline=True)
        self.client.text += "New requirement\n"
        self.discover()
        self.discover()
        self.assertEqual(["baseline", "pending"], [t["status"] for t in self.state["tasks"].values()])
        task = list(self.state["tasks"].values())[-1]
        self.assertEqual(self.client.text, Path(task["spec"]).read_text())

    def test_empty_or_login_index_is_rejected(self):
        class Login:
            def get(self, path):
                return '<html>Please log in</html>'
        with self.assertRaises(ValueError):
            watch.discover(Login(), self.directory, self.state)
        self.assertEqual({}, self.state["tasks"])

    def test_html_in_place_of_spec_is_rejected(self):
        self.client.text = '<html>Sign in</html>'
        with self.assertRaises(ValueError):
            self.discover()
        self.assertEqual({}, self.state["tasks"])

    def test_state_survives_restart(self):
        self.discover()
        path = self.directory / "state.json"
        watch.save(path, self.state)
        restored = watch.json.loads(path.read_text())
        watch.discover(self.client, self.directory, restored)
        self.assertEqual(self.state, restored)
        self.assertFalse(path.with_suffix(".tmp").exists())

    def test_rate_limit_honors_retry_after(self):
        client = watch.Client.__new__(watch.Client)
        client.base = "https://example.test"
        client.token = "test-secret"
        client.next_request = 0
        opener = Mock()
        opener.open.side_effect = urllib.error.HTTPError(
            "https://example.test/specs", 429, "Too many requests", {"Retry-After": "120"}, None)
        with patch.object(watch.urllib.request, "build_opener", return_value=opener), \
                patch.object(watch.time, "sleep"), patch.object(watch.time, "monotonic", return_value=10):
            with self.assertRaises(urllib.error.HTTPError):
                client.get("/specs")
        self.assertEqual(130, client.next_request)

    def init_repo(self):
        run = lambda *a: watch.subprocess.run(["git", *a], cwd=self.directory, check=True, capture_output=True)
        run("init", "-q", "-b", "main")
        (self.directory / ".gitignore").write_text(".workflow-state/\n")
        (self.directory / "app.txt").write_text("committed\n")
        run("add", ".")
        run("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "init")
        (self.directory / "app.txt").write_text("uncommitted main-tree edit\n")

    def test_worker_is_dispatched_and_handed_off_for_review(self):
        self.init_repo()
        handlers = {}
        sleeps = []
        process = Mock(pid=1234, returncode=0)
        process.poll.return_value = 0

        def sleep(seconds):
            sleeps.append(seconds)
            if len(sleeps) == 2:
                handlers[watch.signal.SIGTERM](None, None)

        with patch.object(watch, "ROOT", self.directory), \
                patch.object(watch, "Client", return_value=self.client), \
                patch.object(watch.signal, "signal", side_effect=lambda s, h: handlers.update({s: h})), \
                patch.object(watch.time, "sleep", side_effect=sleep), \
                patch.object(watch.subprocess, "Popen", fake_agent(process)) as launch, \
                patch("sys.argv", ["watch_specs.py", "--run"]):
            self.assertEqual(0, watch.main())
        calls = [c for c in launch.call_args_list if c.args[0][0] == "pi"]
        self.assertEqual(1, len(calls))
        launch.call_args = calls[0]
        self.assertIn("--no-session", launch.call_args.args[0])
        command = launch.call_args.args[0]
        self.assertEqual("anthropic/claude-opus-5-5", command[command.index("--model") + 1])
        self.assertTrue(launch.call_args.kwargs["start_new_session"])
        state = watch.json.loads((self.directory / ".workflow-state/state.json").read_text())
        task = next(iter(state["tasks"].values()))
        self.assertEqual("needs-review", task["status"])
        # The agent runs in its own worktree on its own branch, from the committed main.
        worktree = Path(task["worktree"])
        self.assertEqual(worktree, launch.call_args.kwargs["cwd"])
        self.assertEqual(self.directory / ".workflow-state/worktrees" / ("speakers-" + task["sha256"][:12]), worktree)
        self.assertTrue(task["branch"].startswith("spec/speakers-"))
        self.assertEqual("committed\n", (worktree / "app.txt").read_text())
        self.assertEqual("uncommitted main-tree edit\n", (self.directory / "app.txt").read_text())
        self.assertIn(str(worktree), launch.call_args.args[0][-1])
        self.assertEqual(task["branch"], watch.git("rev-parse", "--abbrev-ref", "HEAD", cwd=worktree))

    def test_retry_reuses_existing_worktree(self):
        self.init_repo()
        self.discover()
        key, task = next(iter(self.state["tasks"].items()))
        with patch.object(watch, "ROOT", self.directory):
            first = watch.worktree_for(task, key)
            (first / "partial.txt").write_text("agent work\n")
            second = watch.worktree_for(task, key)
        self.assertEqual(first, second)
        self.assertTrue((second / "partial.txt").exists())

    def test_worktree_setup_failure_blocks_without_launching(self):
        # Not a git repository: worktree creation fails, so no agent may run in the main tree.
        handlers = {}
        with patch.object(watch, "ROOT", self.directory), \
                patch.object(watch, "Client", return_value=self.client), \
                patch.object(watch.signal, "signal", side_effect=lambda s, h: handlers.update({s: h})), \
                patch.object(watch.time, "sleep", side_effect=lambda s: handlers[watch.signal.SIGTERM](None, None)), \
                patch.object(watch.subprocess, "Popen", fake_agent(Mock())) as launch, \
                patch("sys.argv", ["watch_specs.py", "--run"]):
            self.assertEqual(0, watch.main())
        self.assertFalse([c for c in launch.call_args_list if c.args[0][0] == "pi"])
        state = watch.json.loads((self.directory / ".workflow-state/state.json").read_text())
        self.assertEqual("failed", next(iter(state["tasks"].values()))["status"])

    def test_failed_task_blocks_dispatch(self):
        directory = self.directory / ".workflow-state"
        directory.mkdir()
        watch.save(directory / "state.json", {"tasks": {"old": {"status": "failed"}}})
        handlers = {}
        with patch.object(watch, "ROOT", self.directory), \
                patch.object(watch, "Client", return_value=self.client), \
                patch.object(watch.signal, "signal", side_effect=lambda s, h: handlers.update({s: h})), \
                patch.object(watch.time, "sleep", side_effect=lambda s: handlers[watch.signal.SIGTERM](None, None)), \
                patch.object(watch.subprocess, "Popen") as launch, \
                patch("sys.argv", ["watch_specs.py", "--run"]):
            self.assertEqual(0, watch.main())
        launch.assert_not_called()

    def test_agent_prompt_has_human_gates(self):
        self.discover()
        task = next(iter(self.state["tasks"].values()))
        prompt = watch.prompt(task)
        self.assertIn("Never claim features, push, deploy, commit", prompt)
        self.assertIn("./mvnw test", prompt)
        self.assertNotIn("Bearer", prompt)
        self.assertIn("dedicated git worktree", prompt)


if __name__ == "__main__":
    unittest.main()
