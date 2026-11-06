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
.workflow-state/feedback/. It never starts agents, never touches git except through
workflow/bin/work, and never sees the token: harness calls go through workflow/bin/harness.

  python3 workflow/watch_feedback.py             run until Ctrl-C (start it once per team, see
                                                 workflow/README.md#feedback-watcher)
  python3 workflow/watch_feedback.py --once      one poll; exit 1 if a check is REAL or the service is down
  python3 workflow/watch_feedback.py --no-board --no-notify   local output only
"""
import argparse
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
    for entry in reversed(timeline):
        if entry.get("status") == "OK":
            break
        streak, since = streak + 1, entry.get("tick")
        failure = entry.get("failure") or {}
        if where is None and failure.get("method"):
            where = f"{failure['method']} {failure.get('path')}"
    return streak, since, where


def no_response(check):
    """True if the latest faulty run got no answer at all from us: the signature of a restart."""
    requests = (check.get("transcript") or {}).get("requests") or []
    return any(r.get("status") is None for r in requests)


def last_request(check):
    """The last request of the latest faulty run, with our answer, e.g. 'GET /workshops/4205/registrations -> 404'."""
    requests = (check.get("transcript") or {}).get("requests") or []
    if not requests:
        return None
    r = requests[-1]
    return f"{r.get('method')} {r.get('path')} -> {r.get('status')}"


def notes_of(check):
    notes = check.get("notes") or []
    return f" Harness notes: {'; '.join(map(str, notes))}." if notes else ""


def observe(state, feedback, timeline_for, now, last_deploy):
    """Compare the new feedback with what we saw last time. Updates state in place and returns events,
    each (kind, slug or None, check name "<slug>/<check>" or None, message).

    timeline_for(name) returns the harness timeline of check "<slug>/<check>". last_deploy is the epoch
    time a new version was last seen on /health, or None.
    """
    events = []
    tick = feedback.get("tick")
    checks = state.setdefault("checks", {})
    claimed = state.setdefault("claimed", [])
    confirmed = state.setdefault("confirmed", [])
    first_run = "tick" not in state

    down = bool((feedback.get("down") or {}).get("current"))
    if down != state.get("down", False):
        reason = (feedback.get("down") or {}).get("reason")
        events.append(("DOWN", None, None, f"harness says the service is DOWN at tick {tick}: {reason}") if down
                      else ("UP", None, None, f"service is back up at tick {tick}"))
    state["down"] = down

    for feature in feedback.get("features", []):
        slug = feature["slug"]
        if not feature.get("claimed"):
            continue
        if slug not in claimed:
            claimed.append(slug)
            if not first_run:
                events.append(("CLAIMED", slug, None, f"{slug} claimed at tick {feature.get('claimedAtTick')}, "
                                                      f"{len(feature.get('checks', []))} checks"))
        all_ok = True
        for check in feature.get("checks", []):
            name = f"{slug}/{check['name']}"
            seen = checks.setdefault(name, {"status": "OK", "alerted": False})
            if check["status"] == "OK":
                if seen["status"] != "OK":
                    if seen.get("alerted"):
                        events.append(("RECOVERED", slug, name, f"{name} OK again at tick {tick} "
                                                                f"(FAULTY since tick {seen.get('since')})"))
                    else:
                        events.append(("blip-over", slug, name, f"{name} OK again at tick {tick}"))
                checks[name] = {"status": "OK", "alerted": False}
                continue
            all_ok = False
            streak, since, where = trailing_faulty(timeline_for(name))
            streak = max(streak, 1)
            detail = last_request(check) or where or "?"
            recent_deploy = last_deploy is not None and now - last_deploy < DEPLOY_WINDOW
            blip = streak == 1 and (no_response(check) or recent_deploy)
            previous_where = seen.get("where")
            seen.update(status="FAULTY", since=seen.get("since") if seen.get("alerted") else (since or tick),
                        streak=streak, transcript=check.get("transcript"))
            if blip:
                if seen.get("reported_blip") != tick:
                    why = "no response" if no_response(check) else "right after a deploy"
                    events.append(("blip", slug, name, f"{name} FAULTY at tick {tick} ({why}): {detail}"))
                    seen["reported_blip"] = tick
                continue
            if not seen.get("alerted"):
                events.append(("REAL", slug, name, f"{name} FAULTY since tick {seen['since']} ({streak} tick(s)), "
                                                   f"fails at {detail}.{notes_of(check)} Run /retro {slug}"))
                seen["alerted"] = True
            elif where and previous_where and where != previous_where and not no_response(check):
                events.append(("MOVED", slug, name, f"{name} still FAULTY but now fails at {detail} "
                                                    f"(was {previous_where}): the last fix got it further.{notes_of(check)}"))
            if where and not no_response(check):
                seen["where"] = where
        if all_ok and slug not in confirmed and feature.get("checks"):
            confirmed.append(slug)
            if not first_run:
                events.append(("HARNESS-OK", slug, None, f"{slug}: all {len(feature['checks'])} checks OK at tick {tick}"))
    state["tick"] = tick
    return events


def live_version(url):
    try:
        with urllib.request.urlopen(url + "/health", timeout=10) as response:
            return json.loads(response.read().decode()).get("version")
    except Exception:
        return None


def board(slug, message):
    env = dict(os.environ, WORK_AGENT=os.environ.get("WORK_AGENT", "feedback-watcher"))
    work = str(ROOT / "workflow" / "bin" / "work")
    subprocess.run([work, "note", "feedback", message], env=env, capture_output=True)
    if slug:  # also on the feature's own log, if it's on the board (base features may not be)
        subprocess.run([work, "log", slug, "feedback: " + message], env=env, capture_output=True)


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

    directory = ROOT / ".workflow-state"
    directory.mkdir(mode=0o700, exist_ok=True)
    lock = (directory / "feedback.lock").open("w")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        parser.exit(1, "Another feedback watcher is already running in this checkout.\n")
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
                    if state.get("version"):
                        state["deployed_at"] = time.time()
                        say(f"DEPLOY: {url} now runs {version[:7]}")
                    state["version"] = version
                feedback = json.loads(client.get(f"/api/teams/{team}/feedback"))
                tick = feedback.get("tick")
                if tick != state.get("tick"):
                    events = observe(state, feedback,
                                     lambda name: json.loads(client.get(f"/api/teams/{team}/feedback/timeline?check={name}")),
                                     time.time(), state.get("deployed_at"))
                    for kind, slug, name, message in events:
                        say(f"tick {tick} {kind}: {message}")
                        if kind in ("REAL", "MOVED") and name:
                            path = transcripts / f"{name.replace('/', '-')}-tick{tick}.json"
                            path.write_text(json.dumps(state["checks"][name].get("transcript"), indent=2))
                            say(f"         transcript: {path.relative_to(ROOT)}")
                        if kind in SHARED:
                            if not args.no_board:
                                board(slug, message)
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
