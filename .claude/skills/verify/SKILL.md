---
name: verify
description: Gather evidence that a merged feature works on the deployed service (health SHA, every Hurl file against production, clean logs), record it in REVIEW.md's claim log, and report whether a person can claim. Also checks harness feedback for already-claimed features. Use after a feature's PR is merged and deployed, or after a claim.
---

# /verify <slug>

1. Run `workflow/bin/verify <slug>`. It fails if `/health` doesn't report `origin/main`'s SHA yet;
   if the deploy is still running (`gh run list --workflow deploy --limit 1`), wait for it and rerun.
2. **If it fails:** that's an error after merge. Don't suggest a claim. Find the cause (Hurl output,
   logs via `.github/fagdag/logs.sh --tail 200`), then `/retro <slug>`, which fixes it on a new
   branch and opens a PR. Record it: `workflow/bin/work log <slug> "verify FAILED: <cause>"`.
3. **If it passes and the feature isn't claimed:** add a row to the claim log in `REVIEW.md`
   section 3 (feature, `ready to claim`, the current tick from `workflow/bin/next`, the evidence
   lines), commit it on a branch `claimlog/<slug>` and open a PR. Tell the person: "Evidence is green;
   claim `<slug>` on the harness and update the row's tick." Paste the evidence block, and
   `workflow/bin/work state <slug> verified "<the summary evidence line>"`. After the person has
   claimed: `work state <slug> claimed`.
4. **If the feature is already claimed:** read the feedback
   (`workflow/bin/harness /api/teams/$(cat .fagdag/team)/feedback`). For any check that isn't OK,
   read its timeline (`.../feedback/timeline?check=<slug>/<check>`) and transcript, and run
   `/retro <slug>` with it. If all are OK, say so with the feedback line as evidence. Either way,
   `workflow/bin/work log <slug> "feedback: <the line>"`.

Never claim, and never say "ready to claim" without a passing `bin/verify` run in this session.
