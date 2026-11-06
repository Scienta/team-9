# Team 9 (pi)

Claude Code loads `.claude/rules/*.md` automatically; pi doesn't, so: at the start of every session,
read every file in `.claude/rules/` and follow them as if they were part of this prompt. They hold
the team workflow (`workflow.md`), safety rules (`safety.md`), one worktree per feature
(`worktrees.md`) and the shared `work` board every agent writes to (`work-branch.md`).

- The skills `/next`, `/spec`, `/build`, `/verify`, `/retro`, `/drive` come from `.claude/skills/`
  (pi: `/skill:<name>`). Where a skill says to launch the Claude Code subagents `review-spec`,
  `review-edge`, `review-regress`, use the pi subagents of the same name (`.pi/agents`, project scope).
- Name yourself on the board: prefix `workflow/bin/work` with `WORK_AGENT=pi-<what you're doing>`.
