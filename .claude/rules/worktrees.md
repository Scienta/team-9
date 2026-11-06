# One feature, one worktree

An agent working on a feature works in its own git worktree on `feat/<slug>`, never by switching
branches in a checkout a person is using. Two agents (or an agent and a person) in the same checkout
mix unrelated, unreviewed changes into the same files, and a `git switch` pulls the floor out from
under whoever else is there.

- Take the feature on the board first (`workflow/bin/work start <slug>`, see `work-branch.md`).
  Then, where `/spec` says `git switch -c feat/<slug> origin/main`, do this instead:

  ```bash
  git fetch origin
  git worktree add -b "feat/$slug" "../team-9-$slug" origin/main
  cd "../team-9-$slug" && git push -u origin "feat/$slug"
  ```

  If `../team-9-<slug>` already exists (a retry or a handover), `cd` into it and reuse it.
- `/drive` runs in its own worktree `../team-9-drive` (see the skill) and makes per-feature
  worktrees from there the same way.
- Do all reading, editing, building and testing inside the feature's worktree. Don't edit the main
  checkout or another feature's worktree, and don't copy uncommitted files over from them.
- `.fagdag/harness-token` is gitignored, so it isn't in the worktree. Don't copy it:
  `workflow/bin/harness` finds it in the main checkout.
- Commit and push on `feat/<slug>` as the skills say. Never merge, never push to `main`. A person
  removes the worktree (`git worktree remove`) after the PR is merged.
