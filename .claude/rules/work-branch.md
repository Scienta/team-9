# The `work` branch: our shared agent board

`work` is an orphan branch on origin that every agent on the team, whoever's machine it runs on,
writes to and reads from. It's how a person's agents know what everyone else's agents are doing.
It **never** merges into `main`, and nothing merges into it.

## Write everything there, and push at once

Every agent action on a drip feature is recorded on `work` when it happens, not at the end. Only
ever write through `workflow/bin/work`: it pushes each entry immediately and is safe when several
agents write at the same moment. Set `WORK_AGENT` to name your session (e.g. `WORK_AGENT=claude-drive`).

| When | Command |
|---|---|
| Session start, and before picking anything | `workflow/bin/work show` |
| **Before** any work on a feature (before the branch, before the team spec) | `workflow/bin/work start <slug>` |
| Team spec written and pushed | `work log <slug> "team spec: N rules, M decisions UNCONFIRMED"` |
| Tests written, gates run, each reviewer's findings | `work log <slug> "<what, with counts>"` |
| PR opened | `work state <slug> in-review "PR <url>"` |
| Waiting on a person (decision, PO question, review comment, stuck twice) | `work state <slug> blocked "<what you need from whom>"` |
| Unblocked, or back to fixing after review/CI | `work state <slug> in-progress "<why>"` |
| PR merged / `bin/verify` green / person claimed | `work state <slug> merged` / `verified "<evidence line>"` / `claimed` |
| FAULTY check, SPEC CHANGED, `/retro` | `work log <slug> "<what happened, lesson id>"` |
| Giving up or handing over | `work state <slug> abandoned "<where it is, what's left>"` |
| A fact others need (PO answer, harness behaviour, platform gotcha) | `work note <topic> "<fact>"` |

- **`start` is the lock.** If it exits 3, someone already has the feature: stop, don't build it in
  parallel, and tell the person who owns it (shown in the output). A feature can be started again
  only once it's `claimed` (e.g. its spec changed) or `abandoned`.
- `bin/next` and `/drive` read the board: a feature taken there isn't "new", even with no branch yet.
- Entries are one line, factual, with evidence where there is some (counts, URLs, check names).
  "Should work" isn't an entry.

## What doesn't go there

- **No code, team specs, lessons or decisions.** Those go on `feat/<slug>` and reach `main` through
  a reviewed PR as before. `work` says what's happening; `main` is what's true.
- **No secrets**: no token, no `Authorization` header, no request bodies with personal data.
  `bin/work` refuses the obvious cases; don't rely on it.

## Never

- Merge `work` into anything or anything into `work`; open a PR from or to it; base a branch on it;
  cherry-pick from it. It shares no history with `main`, so git refuses (`refusing to merge unrelated
  histories`): **never pass `--allow-unrelated-histories`** to get around it.
- Check it out, commit to it by hand, rebase it, force-push it or delete it. `bin/work` doesn't need
  a checkout; the history is the audit log.
- Deploy from it. Only `main` deploys.
