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
