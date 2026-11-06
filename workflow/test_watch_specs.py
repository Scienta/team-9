import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

module_spec = importlib.util.spec_from_file_location("watch_specs", Path(__file__).with_name("watch_specs.py"))
watch = importlib.util.module_from_spec(module_spec)
module_spec.loader.exec_module(watch)


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
        return watch.discover(self.client, self.directory, self.state, baseline)

    def test_baseline_is_silent(self):
        self.assertEqual([], self.discover(baseline=True))
        self.assertEqual([], self.discover())
        self.assertEqual(["baseline"], [t["status"] for t in self.state["tasks"].values()])

    def test_new_spec_is_reported_once(self):
        self.assertEqual([("speakers", "new")], [f[:2] for f in self.discover()])
        self.assertEqual([], self.discover())

    def test_changed_spec_is_reported_once(self):
        self.discover(baseline=True)
        self.client.text += "New requirement\n"
        self.assertEqual([("speakers", "changed")], [f[:2] for f in self.discover()])
        self.assertEqual([], self.discover())
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
        self.assertEqual([], watch.discover(self.client, self.directory, restored))
        self.assertEqual(self.state, restored)
        self.assertFalse(path.with_suffix(".tmp").exists())

    def test_client_goes_through_bin_harness(self):
        client = watch.Client(self.directory)
        ok = Mock(returncode=0, stdout="body", stderr="")
        with patch.object(watch.subprocess, "run", return_value=ok) as run:
            self.assertEqual("body", client.get("/specs"))
        self.assertEqual([str(self.directory / "workflow/bin/harness"), "/specs"], run.call_args.args[0])
        failed = Mock(returncode=1, stdout="", stderr="harness: GET /specs answered 401\nbody")
        with patch.object(watch.subprocess, "run", return_value=failed):
            with self.assertRaisesRegex(RuntimeError, "answered 401$"):
                client.get("/specs")

    def test_polling_faster_than_once_a_minute_is_refused(self):
        with patch("sys.argv", ["watch_specs.py", "--interval", "5"]), self.assertRaises(SystemExit):
            watch.main()

    def test_never_starts_agents_or_touches_git(self):
        source = Path(watch.__file__).read_text()
        for forbidden in ('"pi"', "Popen", '"git"', "worktree"):
            self.assertNotIn(forbidden, source)


if __name__ == "__main__":
    unittest.main()
