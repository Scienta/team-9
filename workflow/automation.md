# Released-spec watcher

## What it does

`watch_specs.py` polls the authenticated harness `/specs` index and downloads each
published `spec.md`. The default polling interval is 5 seconds after each discovery
cycle completes (HTTP requests remain spaced at least 1.1 seconds apart). It tracks SHA-256 content hashes, so a newly released feature
or a changed request creates a task exactly once per observed content version.
Unreleased features are not guessed or probed. The roadmap currently requires a
browser login; the spec index is accessible with the team token.

Each dispatched task runs a **fresh Pi CLI agent session** with its own context,
**in its own git worktree**: `.workflow-state/worktrees/<slug>-<hash12>` on branch
`spec/<slug>-<hash12>`, created from the committed `main` branch. The
agent's working directory is that worktree, so it never sees or touches uncommitted
edits in the main checkout, and humans and other agents can keep working there.
Commit anything a spec agent should build on before it is dispatched. Tasks are still
dispatched one at a time. If the worktree can't be created, the task is marked
`failed` and no agent is launched; it never falls back to the main checkout. The
loop continues polling while an agent works.

Interactive agents use the same naming and rules: see `.claude/rules/worktrees.md`. Pi doesn't
load `.claude/rules/`, so the pi worker (`.claude/pi-agents/worker.md`, linked from `.pi/agents`)
repeats them. Run pi from the `team-9/` root so it loads `AGENTS.md` and finds these agents.

The automation is local only: no agents in CI. No feature claims, commits, pushes,
or deploys are requested. Humans review the result, decide whether to integrate,
deploy, and claim features themselves. These restrictions are prompt instructions,
**not an OS sandbox**: agents have shell access. Run under an appropriately
restricted account/environment if hard enforcement is required.

## Requirements

- Python 3 on macOS/Linux (uses `fcntl` and process groups).
- `pi` on PATH and an already authenticated model provider.
- `.fagdag/harness-token` containing the team token (gitignored, chmod 600).
- Podman/Docker running for the application integration tests.

## Prepare before the game

From the `team-9/` root:

```sh
python3 -m unittest discover -s workflow -p 'test_*.py' -v
# Record current specs WITHOUT asking agents to implement them.
python3 workflow/watch_specs.py --baseline
```

Baseline only marks previously unseen versions. It never silently discards
existing pending work. Skip baselining if you want the current base specs queued
for implementation too. Changes to a baselined request still create new tasks.

## Start and stop

```sh
# Discovery/queue only; no model usage:
python3 workflow/watch_specs.py

# Continuously discover and dispatch tasks, one at a time:
python3 workflow/watch_specs.py --run

# Optional limits. Workers default to anthropic/claude-opus-5-5, the same model as the
# .claude/pi-agents worker and reviewer; --model overrides it:
python3 workflow/watch_specs.py --run --interval 5 --timeout 1800
```

Ctrl-C stops the watcher and terminates its active agent process group. A single
instance lock prevents duplicate watchers on the same repo. To keep it running
outside this terminal, use a dedicated terminal/tmux session, or:

```sh
mkdir -p .workflow-state
nohup python3 -u workflow/watch_specs.py --run > .workflow-state/watcher.log 2>&1 &
echo $! > .workflow-state/watcher.pid
# Stop gracefully:
kill -TERM "$(< .workflow-state/watcher.pid)"
```

Do not start a second watcher to inspect it; read the state/logs instead.

## State and review

Everything generated is in gitignored `.workflow-state/`:

- `state.json`: slug, content hash, timestamps, snapshot path, status, log path, and
  the task's `worktree`, `branch` and `base` commit.
- `worktrees/`: one git worktree per dispatched spec version.
- `specs/*.md`: immutable per-content snapshots provided to agents.
- `*.log`: per-task Pi output; treat these as potentially sensitive local logs.

Statuses:

- `baseline`: version acknowledged without implementation.
- `pending`: waiting for the single worker.
- `running`: agent active.
- `needs-review`: agent exited zero; **not proof the feature is correct**.
- `failed`, `timed-out`, `interrupted`: automatic dispatch is blocked until a
  human checks partial changes. Discovery continues. Failed tasks are not retried
  automatically, preventing unbounded model spend and repeated partial edits.

After a failure, stop the loop, inspect the diff and logs, then manually change
that task's status to `pending` to retry or `skipped` to unblock without retrying.
Never edit state while the watcher is running. After `needs-review`, inspect the
agent's evidence and run the tests yourself before deployment/claiming. Other
pending features may proceed without waiting for human acceptance. Each one is in
its own worktree, so review each spec's diff separately:

```sh
cd .workflow-state/worktrees/<slug>-<hash12>
git status && git diff            # the agent's uncommitted work
./mvnw test
git add -A && git commit -m "feat: <slug>"
cd - && git merge spec/<slug>-<hash12>   # from the main checkout, then deploy by pushing main
git worktree remove .workflow-state/worktrees/<slug>-<hash12>
```

Setting a failed task back to `pending` reuses its existing worktree and branch,
so the retry continues from the partial changes. Remove the worktree and the branch
first if you want a clean start.

The watcher bounds each HTTP request to 20 seconds, spaces requests at least 1.1
seconds apart, honors numeric `Retry-After` on 429 (60-second fallback for other
formats), and retries discovery on the next poll after failures. Authentication
errors are reported without credentials. State is saved atomically; a hard crash
may leave a child alive, so check for orphaned Pi processes before restarting.
The watcher is not a hard model-cost limit: set provider spending limits as well.

## Agent handoff

The agent prompt requires AGENTS.md, a written interpretation of unspecified edge
cases, implementation, regression/concurrency tests, diff review, and evidence in
the final response. It forbids modifying watcher/state/credentials or spawning
further workers. Extensions, skills, prompt templates, and MCP are disabled for
these sessions to avoid accidentally importing autonomous workflows. Repository
AGENTS.md/CLAUDE.md context remains available.

## Known discovery limits

Discovery follows only same-origin `/specs/<slug>/spec.md` links in `/specs`.
Features that appear and disappear entirely between polls cannot be discovered.
Changes to the index markup/API require updating the parser. Feedback failures
are not automatically turned into tasks yet: this avoids repeatedly dispatching
opaque or stale check failures as implementation requests.
