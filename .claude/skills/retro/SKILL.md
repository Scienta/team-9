---
name: retro
description: Learn from an error - a reviewer or person catching a bug, CI or /verify failing, a FAULTY harness check, a failed deploy - or from a claim. Fixes the bug if there is one, writes a lesson, and changes the harness (checklists, rules, skills, scripts) so it's caught earlier next time, all in one PR. Use whenever a later phase catches something an earlier one should have.
---

# /retro <slug> [what happened]

The harness gets better only through this step. Every error that got further than it should have
ends in a concrete change to how we work, not just a fix.

1. **Establish what happened, with evidence.** For a FAULTY check: its timeline and transcript
   (`workflow/bin/harness "/api/teams/$(cat .fagdag/team)/feedback/timeline?check=<slug>/<check>"`),
   the logs around it (`.github/fagdag/logs.sh --since <time>`). For a review finding: the finding.
   For CI: the failing job's log (`gh run view --log-failed`).
2. **Find the root cause and the phase that should have caught it** (table in
   `workflow/README.md#how-it-learns`). Ask "why did that phase miss it?" until the answer is
   something in the harness: a question `/spec` didn't ask, a test pattern `/build` didn't use, a
   check a reviewer didn't make, a gate that doesn't exist.
3. **Fix the bug, if there is one**, test-first: a regression test (JUnit and a Hurl request) that
   fails on the current code, then the fix. If this isn't already on a feature branch, use a new
   branch `fix/<slug>-<short>` from `origin/main`.
4. **Change the harness.** At least one of:
   - a gap to look for in `workflow/checklists/spec-questions.md`
   - a check in `workflow/checklists/review.md` (and the matching `.claude/agents/review-*.md` if
     the agent wouldn't otherwise look for it)
   - a rule in `.claude/rules/`
   - a step in a skill in `.claude/skills/`
   - a script or gate in `workflow/bin/` or CI
   Prefer the earliest phase that could have caught it, and a check that runs automatically over a
   sentence an agent must remember. Don't add a vague item ("be careful with X"): make it a question
   or a check with a yes/no answer.
5. **Write the lesson** at the end of `workflow/lessons.md`, numbered after the last one: what
   happened, root cause, phase that should have caught it, the harness change (with file paths).
   Reference the lesson id from the checklist item you added.
6. **One PR** with the fix, the regression test, the lesson and the harness change:
   `fix(<slug>): <what>` with a body that starts with the lesson. For a FAULTY check say clearly at
   the top that it's costing points, so a person reviews it first.
7. If a harness check was FAULTY, add the failure and its root cause to `REVIEW.md` section 4.

After a claim with nothing wrong, still run a short retro: anything that slowed us down or that a
person had to correct? If so, a lesson and a change; if not, nothing to commit.
