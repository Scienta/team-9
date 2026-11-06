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

## L3: a repeated DELETE answered 200, and co-speakers/check-1 was FAULTY from the claim on (tick 46-51)

- **What happened:** co-speakers was claimed at tick 46 and check-1 was FAULTY on every tick after.
  Its transcript: add two co-speakers, read them back, `DELETE /talks/{id}/co-speakers/{s}` (200),
  then the same DELETE again, which we answered 200 with the talk unchanged. The harness rejected
  that second DELETE. Every other request in the check passed.
- **Root cause:** the request says a repeated *add* "changes nothing" and is silent on removal. Our
  decision (`workflow/co-speakers.md`) carried the add's no-op over to removal, and our house
  convention ("repeating an operation is a no-op 200") made that the default. The decision was
  listed for human review but nobody confirmed it before the claim, and there was no Hurl file, so
  `bin/verify` could not have run.
- **Should have been caught by:** `/spec` (the decision) and the claim (no evidence held).
- **Harness change:** `workflow/checklists/spec-questions.md`: items 4 and 5 now ask about a repeated
  remove separately, and the house convention is now "removing something that isn't there is a 404".
  `examples/co-speakers.hurl` replays the harness's sequence; it failed against the old production
  code at the repeated DELETE before the fix (got 200, expected 404).


## L4: an agent's workflow change reached main and production without a PR (13:00-13:21)

- **What happened:** an agent (pi, Jonathan's session) did the agent-workflow work (`3bd0d31`, then
  the `work` board and the reconciled skills in `8864a91`) by editing and committing straight on
  `main` in the shared `team-9/` checkout, while another session committed co-speakers in the same
  checkout. That local `main` then reached `origin/main` twice without review: pushed with
  `392f624`, and merged by `f643e12` (with a WIP acceptance commit). PR #2 was also cut from it, so
  its 1-file diff sat on top of ~40 unreviewed files. Both deployed. Espen's PR #3 was then closed
  as "superseded" by a commit nobody had reviewed.
- **Root cause:** the agent's rule (`worktrees.md`) only talked about "an agent working on a
  feature", and the agent treated workflow/config work as exempt. Nothing flagged a local `main`
  with commits that weren't on origin, and nothing stopped anyone pushing or merging it.
- **Should have been caught by:** the agent before its first edit; failing that, any later session
  in that checkout (`bin/next`), or the PR review of #2 (its commit list, not just its diff).
- **Harness change:** `workflow/bin/check-main` (run by `bin/next`, tested in
  `workflow/test_check_main.py`) fails when local `main` has commits that aren't on origin/main;
  `/drive` stops on it. `.claude/rules/worktrees.md` now covers every change, including workflow
  and agent config, and forbids committing on `main`. `checklists/review.md` checks the PR's commit
  list. Still needed from a person: branch protection on `main` (required PR and checks).
