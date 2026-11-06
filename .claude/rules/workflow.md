# How we work on features

The process is in `workflow/README.md`; this is what every agent session must follow.

- Work on a feature goes through the skills in order: `/next` → `/spec` → `/build` → (person reviews
  and merges) → `/verify` → (person claims) → `/retro`. `/drive` runs them unattended.
- One feature per worktree and branch, `spec/<slug>-<hash12>`, as `.claude/rules/worktrees.md`
  says (the same convention as the watcher). Push the branch as soon as you start: `bin/next`
  reads origin's branches to see who is on what, so an unpushed branch means two people (or two
  loops) can end up building the same feature.
- There are two ways to run agents on features: the Claude Code skills below (attended, or
  `/loop /drive`), and the watcher with Pi workers (`workflow/automation.md`). Both end in a PR
  through CI; run only one autopilot at a time.
- Never push to `main`, never merge a PR, never claim a feature. Those are a person's. Everything
  reaches `main` through a PR, so the `tests` and `decisions` checks run before it deploys.
- The request on the harness (`specs/<slug>.md`) is the business source of truth. Our team spec
  (`workflow/specs/<slug>.md`) is the source of truth for everything the request leaves open.
  If they disagree, the request wins and the team spec is wrong.
- Before `/spec` or `/build`, read `workflow/lessons.md` and both files in `workflow/checklists/`.
  They are how earlier mistakes are kept from repeating.
- Never say a feature works or is ready to claim without evidence the person can check: the
  `bin/verify` output, test output, or a harness feedback line. "Should work" is not evidence.
- A new check (script, test, CI gate) must be shown to fail on a known-bad input before its "OK" is
  trusted (lessons.md L2).
- Every error a later phase catches gets a `/retro` (see `workflow/README.md#how-it-learns`). Don't
  just fix it: change the harness so it's caught earlier next time.
- Keep a feature's code in its own files where you can (its own controller, DAO, test class and
  Hurl file), so parallel branches don't conflict.
- Write tests first. JUnit integration tests in `src/test/java/no/scienta/cfp/` (they use
  `TestDatabase.fresh`, see `TalksTest`), and `examples/<slug>.hurl` with a request per rule and per
  error status. The Hurl file is what `bin/verify` runs against production.
