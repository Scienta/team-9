#!/usr/bin/env python3
"""Watch the harness for released or changed feature requests and say so. Stdlib only.

Discovery and notification only: it never starts agents and never touches git. When it reports a
NEW or CHANGED spec, a person (or the /drive loop) runs /next, which fetches it into specs/ and
takes it through /spec and /build (workflow/README.md). Every harness call goes through
workflow/bin/harness, which owns the token and the rate limit (.claude/rules/safety.md), and polls
are at least 60 s apart.
"""
import argparse
import fcntl
import hashlib
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import signal
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
MIN_INTERVAL = 60  # .claude/rules/safety.md: never poll the harness more than once a minute.


class SpecLinks(HTMLParser):
    def __init__(self):
        super().__init__()
        self.slugs = set()

    def handle_starttag(self, tag, attrs):
        if tag == "a":
            match = re.fullmatch(r"/specs/([a-z0-9][a-z0-9-]*)/spec\.md", dict(attrs).get("href", ""))
            if match:
                self.slugs.add(match[1])


class Client:
    """GETs harness paths through workflow/bin/harness (token handling, 429 back-off)."""

    def __init__(self, root):
        self.script = root / "workflow" / "bin" / "harness"

    def get(self, path):
        result = subprocess.run([str(self.script), path], capture_output=True, text=True, timeout=120)
        if result.returncode != 0:
            # bin/harness never prints the token; keep only its first line.
            raise RuntimeError((result.stderr.strip().splitlines() or ["harness call failed"])[0])
        return result.stdout


def save(path, state):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(state, indent=2) + "\n")
    temporary.replace(path)


def discover(client, directory, state, baseline=False):
    """Record every spec version not seen before. Returns [(slug, "new"|"changed", sha12)]."""
    parser = SpecLinks()
    parser.feed(client.get("/specs"))
    if not parser.slugs:
        raise ValueError("No spec links found; refusing to treat a login/error page as an empty index")
    found = []
    for slug in sorted(parser.slugs):
        text = client.get(f"/specs/{slug}/spec.md")
        if not text.lstrip().startswith("# "):
            raise ValueError(f"Unexpected spec content for {slug}")
        digest = hashlib.sha256(text.encode()).hexdigest()
        key = slug + "-" + digest
        if key in state["tasks"]:
            continue
        seen_before = any(t["slug"] == slug for t in state["tasks"].values())
        snapshot = directory / "specs" / (key + ".md")
        snapshot.parent.mkdir(exist_ok=True)
        snapshot.write_text(text)
        state["tasks"][key] = {
            "slug": slug, "sha256": digest, "status": "baseline" if baseline else "reported",
            "spec": str(snapshot), "discovered": time.time(),
        }
        if baseline:
            print(f"{slug}: baseline ({digest[:12]})", flush=True)
        else:
            kind = "changed" if seen_before else "new"
            found.append((slug, kind, digest[:12]))
            print(f"\a{kind.upper()} {slug} ({digest[:12]}): run /next", flush=True)
    return found


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--once", action="store_true", help="Poll once and exit")
    parser.add_argument("--baseline", action="store_true", help="Record current versions silently; implies --once")
    parser.add_argument("--interval", type=float, default=MIN_INTERVAL, help=f"Seconds between polls (>= {MIN_INTERVAL})")
    args = parser.parse_args()
    if args.interval < MIN_INTERVAL:
        parser.error(f"interval must be >= {MIN_INTERVAL} (harness rate limit, .claude/rules/safety.md)")
    directory = ROOT / ".workflow-state"
    directory.mkdir(mode=0o700, exist_ok=True)
    lock = (directory / "lock").open("w")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        parser.exit(1, "Another watcher is already running.\n")
    state_path = directory / "state.json"
    state = json.loads(state_path.read_text()) if state_path.exists() else {"tasks": {}}
    client = Client(ROOT)
    stopping = False

    def request_stop(signum, frame):
        nonlocal stopping
        stopping = True
    signal.signal(signal.SIGTERM, request_stop)
    signal.signal(signal.SIGINT, request_stop)
    try:
        while not stopping:
            try:
                discover(client, directory, state, args.baseline)
            except Exception as error:
                print(f"Discovery failed ({type(error).__name__}: {error}); retrying next poll", flush=True)
                if args.once or args.baseline:
                    return 1
            finally:
                save(state_path, state)
            if args.once or args.baseline:
                return 0
            deadline = time.monotonic() + args.interval
            while not stopping and time.monotonic() < deadline:
                time.sleep(1)
    finally:
        save(state_path, state)
        lock.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
