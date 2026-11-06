# Released-spec watcher

`watch_specs.py` tells you the moment the harness releases a new feature request, or changes one,
so nobody has to keep running `/next` by hand. It only discovers and notifies: it never starts
agents, never touches git and never writes `specs/`. Acting on a release is the normal flow
([README](README.md)): `/next`, then `/spec` and `/build`, or a `/loop /drive` that picks it up.

- Every harness call goes through [`bin/harness`](bin/harness) (token handling, `429` back-off),
  and polls are at least 60 seconds apart (`.claude/rules/safety.md`). It refuses a shorter `--interval`.
- It tracks a SHA-256 per spec version, so each new or changed version is reported exactly once,
  as `NEW <slug>` or `CHANGED <slug>` with a terminal bell. Snapshots go to the gitignored
  `.workflow-state/specs/`, state to `.workflow-state/state.json`.
- An index that has no spec links (a login or error page) is an error, not "no specs".

```sh
python3 -m unittest discover -s workflow -p 'test_*.py' -v
python3 workflow/watch_specs.py --baseline   # record what's out now, silently
python3 workflow/watch_specs.py              # watch (Ctrl-C to stop); --once for a single poll
```

One watcher per checkout (a lock enforces it). To leave it running, use its own terminal or tmux
pane. When it reports something, check the shared board first (`workflow/bin/work show`): another
person's agent may already have taken the feature.
