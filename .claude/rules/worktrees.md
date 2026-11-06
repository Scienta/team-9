# One feature, one worktree

An agent changing anything in this repo (a feature, a fix, a retro, or workflow and agent
config, which is **not** exempt) works in its own git worktree on its own branch (`feat/<slug>`,
`fix/<slug>-<short>`, `chore/<topic>`), never by editing or switching branches in a checkout a
person or another session is using. **Never commit on `main`, never push `main`, never merge a local
`main` into anything** (lessons.md L4): everything reaches `main` through a PR. If
`workflow/bin/check-main` fails, stop and tell the person. Two agents (or an agent and a person) in the same checkout
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
