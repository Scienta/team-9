#!/usr/bin/env python3
"""Watch the harness feedback continuously and say when a claimed feature breaks, moves or recovers.

Claimed features are scored on every tick, so a FAULTY check costs points until someone notices.
Today that took 15 ticks for co-speakers and 27 for workshop-registration (workflow/lessons.md).
This polls the team's feedback once a minute (the harness rate limit) and reports changes only:

  REAL       a check is FAULTY and it isn't a deploy blip: on 2+ ticks in a row, or on 1 tick with
             real answers from us and no deploy in the last few minutes. Run /retro <slug>.
  MOVED      a check that is still FAULTY now fails at a different request: the last fix worked
             and the check got further. Read the new transcript (lessons.md L9).
  RECOVERED  a check reported REAL is OK again, with the tick range it cost.
  blip       FAULTY on one tick with no answer from us (status null) or right after a deploy: the
             container was restarting. Logged only; it becomes REAL if it's still FAULTY next tick.
  CLAIMED    a feature was claimed. HARNESS-OK once all its checks have been seen OK after that.
  DOWN / UP  the harness says the service is down, or back.
  DEPLOY     /health reports a new version.

REAL, MOVED, RECOVERED, CLAIMED, HARNESS-OK, DOWN and UP go to the `work` board (notes/feedback.md,
and the feature's own log if it's on the board) and to a desktop notification. Every event, blips
included, is appended to .workflow-state/feedback.log; each REAL or MOVED transcript is saved in
.workflow-state/feedback/. State and lock are in the main checkout's .workflow-state, shared by its
worktrees, so it runs once per clone. It never starts agents, never touches git except through
workflow/bin/work, and never sees the token: harness calls go through workflow/bin/harness.

  python3 workflow/watch_feedback.py             run until Ctrl-C (start it once per team, see
                                                 workflow/README.md#feedback-watcher)
  python3 workflow/watch_feedback.py --once      one poll; exit 1 if a check is REAL or the service is
                                                 down, 2 if the poll itself failed
  python3 workflow/watch_feedback.py --no-board --no-notify   local output only
"""
import argparse
import copy
import fcntl
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time
import urllib.request

sys.path.insert(0, str(Path(__file__).resolve().parent))
from watch_specs import Client, MIN_INTERVAL, ROOT, save  # noqa: E402  (same harness client and rate limit)

DEPLOY_WINDOW = 180  # seconds after a new version appears on /health in which a 1-tick failure is a blip
SHARED = {"REAL", "MOVED", "RECOVERED", "CLAIMED", "HARNESS-OK", "DOWN", "UP"}  # board + notification


def trailing_faulty(timeline):
    """How many of the latest ticks in a check's timeline are FAULTY, the first of them, and the latest
    failing request as "METHOD /templated/path" (None if the timeline has none)."""
    streak, since, where = 0, None, None
    for entry in reversed(timeline or []):
        if not isinstance(entry, dict) or entry.get("status") == "OK":
            break
        streak, since = streak + 1, entry.get("tick")
        failure = entry.get("failure") or {}
        if where is None and failure.get("method"):
            where = f"{failure['method']} {failure.get('path')}"
    return streak, since, where


def requests_of(check):
    return [r for r in ((check.get("transcript") or {}).get("requests") or []) if isinstance(r, dict)]


def no_response(check):
    """True if the latest faulty run got no answer at all from us: the signature of a restart."""
    return any(r.get("status") is None for r in requests_of(check))


def last_request(check):
    """The last request of the latest faulty run, with our answer, e.g. 'GET /workshops/4205/registrations -> 404'."""
    requests = requests_of(check)
    if not requests:
        return None
    r = requests[-1]
    return f"{r.get('method')} {r.get('path')} -> {r.get('status')}"


def notes_of(check):
    notes = check.get("notes") or []
    return f" Harness notes: {'; '.join(map(str, notes))}." if notes else ""


def one_line(message):
    """bin/work refuses multi-line messages, and harness notes may contain newlines."""
    return " ".join(str(message).split())


def poll(state, feedback, timeline_for, now, last_deploy):
    """observe() on a copy of the state, kept only if it completes: a poll that fails halfway (a timeline
    call that raises, Ctrl-C) changes nothing, so the next poll decides the same events again."""
    draft = copy.deepcopy(state)
    events = observe(draft, feedback, timeline_for, now, last_deploy)
    state.clear()
    state.update(draft)
    return events


def observe(state, feedback, timeline_for, now, last_deploy):
    """Compare the new feedback with what we saw last time. Updates state in place and returns events,
    each (kind, slug or None, check name "<slug>/<check>" or None, one-line message).

    timeline_for(name) returns the harness timeline of check "<slug>/<check>". last_deploy is the epoch
    time a new version was last seen on /health, or None. Use poll(), which makes this all-or-nothing.
    """
    events = []

    def event(kind, slug, name, message):
        events.append((kind, slug, name, one_line(message)))

    tick = feedback.get("tick")
    previous = state.get("checks") or {}
    checks = {}
    claimed = state.setdefault("claimed", [])
    confirmed = state.setdefault("confirmed", [])
    first_run = "tick" not in state

    down = bool((feedback.get("down") or {}).get("current"))
    if down != state.get("down", False):
        reason = (feedback.get("down") or {}).get("reason")
        if down:
            event("DOWN", None, None, f"harness says the service is DOWN at tick {tick}: {reason}")
        else:
            event("UP", None, None, f"service is back up at tick {tick}")
    state["down"] = down

    for feature in feedback.get("features") or []:
        slug = feature.get("slug")
        if not slug or not feature.get("claimed"):
            continue
        feature_checks = [c for c in (feature.get("checks") or []) if isinstance(c, dict) and c.get("name")]
        claimed_at = feature.get("claimedAtTick")
        if slug not in claimed:
            claimed.append(slug)
            if not first_run:
                event("CLAIMED", slug, None, f"{slug} claimed at tick {claimed_at}, {len(feature_checks)} checks")
        all_ok = bool(feature_checks)
        for check in feature_checks:
            name = f"{slug}/{check['name']}"
            seen = dict(previous.get(name) or {"status": "OK", "alerted": False})
            checks[name] = seen
            status = check.get("status")
            if status not in ("OK", "FAULTY"):  # not run yet, or a status we don't know: no news
                all_ok = False
                continue

            def ended(by_tick):
                if seen.get("alerted"):
                    event("RECOVERED", slug, name, f"{name} OK again at tick {by_tick} (FAULTY since tick {seen.get('since')})")
                else:
                    event("blip-over", slug, name, f"{name} OK again at tick {by_tick}")

            if status == "OK":
                if seen.get("status") == "FAULTY":
                    ended(tick)
                checks[name] = {"status": "OK", "alerted": False}
                continue

            all_ok = False
            streak, since, where = trailing_faulty(timeline_for(name))
            if seen.get("status") == "FAULTY" and since is not None and seen.get("since") is not None \
                    and since > seen["since"]:
                # The timeline's current run of FAULTY ticks started after ours did, so there was an OK
                # tick in between that no poll saw: that incident is over, and this is a new one.
                ended(since - 1)
                seen = {"status": "OK", "alerted": False}
                checks[name] = seen
            continuing = seen.get("status") == "FAULTY"
            if continuing:  # don't rely on the timeline already holding this tick
                streak = max(streak, seen.get("streak", 1) + 1)
                since = seen["since"] if seen.get("since") is not None else since  # a new incident was split off above
                where = where or seen.get("where")
            streak = max(streak, 1)
            since = since if since is not None else tick
            if claimed_at is not None and since is not None and since < claimed_at:
                since = claimed_at  # points are only lost from the claim on
            detail = last_request(check) or where or "?"
            recent_deploy = last_deploy is not None and now - last_deploy < DEPLOY_WINDOW
            blip = streak == 1 and (no_response(check) or recent_deploy)
            previous_where = seen.get("where")
            seen.update(status="FAULTY", since=since, streak=streak, transcript=check.get("transcript"))
            if blip:
                if seen.get("reported_blip") != tick:
                    why = "no response" if no_response(check) else "right after a deploy"
                    event("blip", slug, name, f"{name} FAULTY at tick {tick} ({why}): {detail}")
                    seen["reported_blip"] = tick
                continue
            if not seen.get("alerted"):
                event("REAL", slug, name, f"{name} FAULTY since tick {since} ({streak} tick(s)), "
                                          f"fails at {detail}.{notes_of(check)} Run /retro {slug}")
                seen["alerted"] = True
            elif where and previous_where and where != previous_where and not no_response(check):
                event("MOVED", slug, name, f"{name} still FAULTY, now at a different request: {detail} "
                                           f"(was {previous_where}). Read the new transcript.{notes_of(check)}")
            if where and not no_response(check):
                seen["where"] = where
        if all_ok and slug not in confirmed:
            confirmed.append(slug)
            if not first_run:
                event("HARNESS-OK", slug, None, f"{slug}: all {len(feature_checks)} checks OK at tick {tick}")
    state["checks"] = checks  # checks no longer in the feedback are forgotten
    state["tick"] = tick
    return events


def live_version(url):
    try:
        with urllib.request.urlopen(url + "/health", timeout=10) as response:
            return json.loads(response.read().decode()).get("version")
    except Exception:
        return None


def state_dir():
    """.workflow-state in the main checkout, shared by every worktree of it, so the lock and the state
    are once per clone: a run in a feature worktree doesn't re-announce what the main one already did."""
    try:
        common = subprocess.run(["git", "rev-parse", "--git-common-dir"], cwd=ROOT, capture_output=True,
                                text=True, check=True, timeout=10).stdout.strip()
        return (ROOT / common).resolve().parent / ".workflow-state"
    except Exception:
        return ROOT / ".workflow-state"


def board(slug, message, say):
    """Write to the work board; never block the loop for long, and say when it didn't work."""
    env = dict(os.environ, WORK_AGENT=os.environ.get("WORK_AGENT", "feedback-watcher"))
    work = str(ROOT / "workflow" / "bin" / "work")
    commands = [([work, "note", "feedback", message], "note")]
    if slug:  # also on the feature's own log, if it's on the board (base features may not be: exit 3)
        commands.append(([work, "log", slug, "feedback: " + message], f"log {slug}"))
    for command, what in commands:
        try:
            result = subprocess.run(command, env=env, capture_output=True, text=True, timeout=60)
        except subprocess.TimeoutExpired:
            say(f"         board {what}: timed out after 60 s")
            continue
        if result.returncode not in (0, 3) or (result.returncode == 3 and what == "note"):
            first = (result.stderr.strip().splitlines() or ["?"])[0]
            say(f"         board {what} failed ({result.returncode}): {first}")


def notify(kind, message):
    if shutil.which("notify-send"):
        urgency = "critical" if kind in ("REAL", "DOWN") else "normal"
        subprocess.run(["notify-send", "-u", urgency, f"team-9 harness: {kind}", message], capture_output=True)
    elif shutil.which("osascript"):
        subprocess.run(["osascript", "-e", f"display notification {json.dumps(message)} with title "
                        f"{json.dumps('team-9 harness: ' + kind)}"], capture_output=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--once", action="store_true", help="Poll once and exit (1 if a check is REAL or the service is down)")
    parser.add_argument("--interval", type=float, default=MIN_INTERVAL, help=f"Seconds between polls (>= {MIN_INTERVAL})")
    parser.add_argument("--no-board", action="store_true", help="Don't write to the work board")
    parser.add_argument("--no-notify", action="store_true", help="No desktop notifications")
    args = parser.parse_args()
    if args.interval < MIN_INTERVAL:
        parser.error(f"interval must be >= {MIN_INTERVAL} (harness rate limit, .claude/rules/safety.md)")

    directory = state_dir()
    directory.mkdir(mode=0o700, exist_ok=True)
    lock = (directory / "feedback.lock").open("w")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        parser.exit(1, f"Another feedback watcher is already running for this clone ({directory}).\n")
    state_path = directory / "feedback.json"
    state = json.loads(state_path.read_text()) if state_path.exists() else {}
    transcripts = directory / "feedback"
    transcripts.mkdir(mode=0o700, exist_ok=True)
    log = (directory / "feedback.log").open("a")
    team = (ROOT / ".fagdag" / "team").read_text().strip()
    url = f"https://fagsem-h2026-{team}.scienta.cloud"
    client = Client(ROOT)
    stopping = False

    def request_stop(signum, frame):
        nonlocal stopping
        stopping = True
    signal.signal(signal.SIGTERM, request_stop)
    signal.signal(signal.SIGINT, request_stop)

    def say(line):
        line = f"{time.strftime('%H:%M:%S')} {line}"
        print(line, flush=True)
        log.write(line + "\n")
        log.flush()

    try:
        while not stopping:
            try:
                version = live_version(url)
                if version and version != state.get("version"):
                    # A deploy we saw happen (the old version was live within the last few polls), so
                    # 1-tick failures right now are restart blips. After a restart of this watcher, an
                    # old version change says nothing about what's happening now.
                    if state.get("version") and time.time() - state.get("version_seen_at", 0) < 3 * args.interval:
                        state["deployed_at"] = time.time()
                    if state.get("version"):
                        say(f"DEPLOY: {url} now runs {version[:7]}")
                    state["version"] = version
                if version:
                    state["version_seen_at"] = time.time()
                feedback = json.loads(client.get(f"/api/teams/{team}/feedback"))
                tick = feedback.get("tick")
                if tick != state.get("tick"):
                    events = poll(state, feedback,
                                     lambda name: json.loads(client.get(f"/api/teams/{team}/feedback/timeline?check={name}")),
                                     time.time(), state.get("deployed_at"))
                    for kind, slug, name, message in events:
                        say(f"tick {tick} {kind}: {message}")
                        if kind in ("REAL", "MOVED") and name:
                            path = transcripts / f"{name.replace('/', '-')}-tick{tick}.json"
                            path.write_text(json.dumps(state["checks"][name].get("transcript"), indent=2))
                            say(f"         transcript: {path}")
                        if kind in SHARED:
                            if not args.no_board:
                                board(slug, message, say)
                            if not args.no_notify:
                                notify(kind, message)
                if args.once:
                    bad = sorted(n for n, c in state.get("checks", {}).items() if c["status"] != "OK")
                    print(f"tick {tick}: " + (f"FAULTY: {' '.join(bad)}" if bad else
                          f"all checks OK on {len(state.get('claimed', []))} claimed feature(s)")
                          + (", SERVICE DOWN" if state.get("down") else ""), flush=True)
            except Exception as error:
                say(f"Poll failed ({type(error).__name__}: {error}); retrying next poll")
                if args.once:
                    return 2
            finally:
                save(state_path, state)
            if args.once:
                serious = state.get("down") or any(c.get("alerted") for c in state.get("checks", {}).values())
                return 1 if serious else 0
            deadline = time.monotonic() + args.interval
            while not stopping and time.monotonic() < deadline:
                time.sleep(1)
    finally:
        save(state_path, state)
        log.close()
        lock.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
