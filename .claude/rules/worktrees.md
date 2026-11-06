# One spec, one worktree

Every agent working on a spec (a new feature request, or a changed version of one) works in its
own git worktree on its own branch, never in the main checkout. The main checkout is for humans
integrating reviewed work; two agents editing it at once mix unrelated, unreviewed changes into
the same files and the same deploy.

This file is the single source for the convention. `workflow/watch_specs.py` implements it, and
the pi worker in `.claude/pi-agents/worker.md` repeats it because pi doesn't load `.claude/rules/`.

## Naming (same as the watcher)

- `<slug>`: the feature slug, e.g. `co-speakers`.
- `<hash12>`: the first 12 characters of the spec version's SHA-256. The saved snapshot is
  `.workflow-state/specs/<slug>-<sha256>.md`, so take it from the file name.
- Worktree `.workflow-state/worktrees/<slug>-<hash12>`, branch `spec/<slug>-<hash12>`, created from
  the committed `main`.

## Steps

- Check first whether the watcher already has a worktree for this version: the task's `worktree`
  field in `.workflow-state/state.json` (read it, never edit it). If it exists (a watcher task or
  a retry), `cd` into it and reuse it. Don't recreate it.
- Otherwise create it from the committed `main`:

  ```bash
  slug=<feature-slug>
  spec=$(ls .workflow-state/specs/"$slug"-*.md | tail -1)   # pick the version you're implementing
  sha=$(basename "$spec" .md); sha=${sha#"$slug"-}
  name="$slug-${sha:0:12}"
  git worktree add -b "spec/$name" ".workflow-state/worktrees/$name" main
  cd ".workflow-state/worktrees/$name"
  ```

- Do all reading, editing, building and testing inside that worktree. Don't edit the main checkout
  or another spec's worktree, and don't copy uncommitted files over from them. Creating your own
  worktree and editing files inside it is the only write allowed under `.workflow-state/`.
- Specs saved by the watcher are at `.workflow-state/specs/` in the main checkout. Read them from
  there; don't change them.
- `.fagdag/harness-token` is gitignored, so it isn't in the worktree. Don't copy it there.
- The worktree only has what is committed on `main`. Humans: commit anything a spec should build
  on (code, `.claude/`, `workflow/`) before dispatching it.
- Leave the changes uncommitted in the worktree for human review. Agents don't commit, merge,
  push or remove worktrees. A human commits on the `spec/<slug>-<hash12>` branch, merges it into
  `main`, and removes the worktree with `git worktree remove`.
