---
name: drive
description: Autopilot - run one step of the feature workflow unattended (fix FAULTY checks, address PR review, verify merged features, spec and build new ones), stopping only where a person must act (PR review, claim). Run repeatedly with /loop /drive.
---

# /drive

One iteration of the workflow, done without asking. Built for `/loop /drive`: each run picks the
most valuable thing, does it fully, reports, and ends. The person's only jobs are reviewing PRs
(including confirming decisions), merging and claiming; never do those for them.

## Each iteration

1. `workflow/bin/next`, then take the **first** of these that applies, do it, and stop:

   1. **A claimed feature is FAULTY** (it's costing points): `/retro <slug>` with the check's
      transcript. The fix PR's title starts with `URGENT`.
   2. **SPEC CHANGED** for a feature we built or are building: update the team spec and tests on a
      branch, and `/retro` if what we built is now wrong.
   3. **An open PR of ours has new review comments or failing CI** (`gh pr list --author @me`,
      `gh pr view <n> --comments`, `gh pr checks <n>`): address them on that branch. A person
      overriding a decision or finding a bug is an error that got past `/build`: `/retro` it in
      the same branch. Reply on the PR saying what changed.
   4. **A feature is merged but not claimed**: `/verify <slug>`. If green, notify the person to claim.
   5. **A feature is claimed but its row in the `REVIEW.md` claim log (section 3) doesn't say
      `claimed`**: `/verify <slug>` (it reads harness feedback), the short post-claim `/retro`, and
      update the row in the same PR.
   6. **A new feature nobody is on**: `/spec <slug>`, then `/build <slug>`, ending with a PR.
   7. Nothing: report "idle" with the `bin/next` table.

2. **Report** in a few lines: what you did, links to PRs, what's waiting on a person (PRs to
   review, decisions to confirm, features to claim). If the PushNotification tool is available, send
   one when a person must act (a PR is ready, a claim is ready, a check is FAULTY).

## Pacing (for /loop)

- Nothing waiting and nothing to build: wait ~20 minutes before the next run.
- Waiting on CI or a deploy: ~4 minutes.
- Never poll the harness more than once a minute (rate limit).

## Guard rails

- Run the loop in its own git worktree or clone (`git worktree add ../team-9-drive origin/main`),
  never in a checkout a person is working in: it switches branches.
- One feature at a time per loop. Before starting one, check `origin/feat/<slug>` doesn't exist.
- Never push to `main`, merge, or claim. Never print the harness token.
- If the same step fails twice in a row, stop the loop and tell the person what's stuck, rather
  than retrying forever.
