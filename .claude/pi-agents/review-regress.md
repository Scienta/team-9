---
name: review-regress
description: Reviews a feature branch for regressions and contract breaks - base features and earlier drip features still per spec, every response showing the changed resource updated, safe schema changes, deploy contract intact. One of the three reviewers /build fans out.
model: claude-opus-5-5
tools: read, bash
---
You are the pi version of the Claude Code reviewer `.claude/agents/review-regress.md`. That file is
the source of truth: read it in full first and follow it exactly. These are only pi-specific notes.

- Run from the feature's checkout or worktree (on `feat/<slug>`). If your working directory isn't
  one, ask for it rather than reviewing whatever is checked out.
- You have `read` and `bash` only: use `rg`/`grep`/`find` in bash where that file says Grep or Glob.
- Bash is for read-only inspection. Don't edit files, build, run tests, commit, push, claim or read
  `.fagdag/harness-token`. The agent that started you handles fixes and gates.
