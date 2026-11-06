# Lessons

The learning log. `/retro` appends an entry for every error (see `workflow/README.md#how-it-learns`)
and every claim. `/spec` and `/build` read this file at the start of every feature.

Each entry: what happened, the root cause, the phase that should have caught it, and the harness
change made so it can't recur silently (with the file changed). Number them `L1`, `L2`, ...

## L1: the team token file held a pasted curl fragment (tick 6)

- **What happened:** `.fagdag/harness-token` held `Authorization: Bearer <token>" "/api/teams/...`
  instead of the token, so every harness call answered 401.
- **Root cause:** the token was copied from the feedback page's example command, not the token field.
- **Should have been caught by:** setup.
- **Harness change:** `workflow/bin/harness` refuses a token file containing `Bearer`,
  `Authorization`, a quote or a slash, and says what to paste.

## L2: the log check counted validation warnings as errors, then silently matched nothing (tick 22)

- **What happened:** the first `bin/verify` flagged every `WARN … MethodArgumentNotValidException`
  (expected 400s) as an error. The first fix parsed the log lines as JSON, but they start with a
  timestamp, so `jq` failed on every line and the check reported OK whatever the logs held.
- **Root cause:** a check that can't fail was trusted without proving it can see a positive.
- **Should have been caught by:** writing the check.
- **Harness change:** rule in `.claude/rules/workflow.md`: a new check must be shown to detect a
  known-bad input before its OK is trusted.

## L3: each deploy failed every claimed feature's checks for a tick (tick 39 and 42)

- **What happened:** two merges three minutes apart (PR #1, then Co-speakers) each restarted the
  container. Startup takes ~37 s (`Started CfpApplication in 36.964 seconds`), and harness checks
  that landed then got no response (`status: null` on `POST /speakers`). Six checks across
  speakers, talks and ratings went FAULTY; 9 points bled. They recovered on the next tick.
- **Root cause:** every push to `main` deploys with downtime, and nothing in the workflow treated a
  merge as costly.
- **Should have been caught by:** the workflow design (merging).
- **Harness change:** `workflow/README.md` and `.claude/skills/drive/SKILL.md`: merge ready PRs
  together; `/drive` tells the person when several are ready. Follow-up worth a PR of its own:
  cut startup time (the app loads `spring-boot-starter-data-jpa` without using JPA).

## L4: two people built two automations for the same job without knowing (tick 30-42)

- **What happened:** the Claude Code skills (`/next` … `/drive`) and the Pi watcher
  (`watch_specs.py`) were built in parallel with different branch names (`feat/<slug>`,
  `spec/<slug>-<hash12>`, and a hand-made `feature/spec-2-acceptans`), team-spec locations and
  merge paths. `bin/next` couldn't see the watcher's or the hand-made branches, so it called
  co-speakers and acceptance "new" while both were taken or done.
- **Root cause:** no single place said what a feature's branch and spec are called.
- **Should have been caught by:** setup.
- **Harness change:** one convention, in `.claude/rules/worktrees.md`, used by both automations;
  `bin/next` recognises every branch form, the watcher's state and team specs on `main`, and lists
  branches it can't attribute so a person sees them.
