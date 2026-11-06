---
name: next
description: Fetch every feature spec from the harness, list each feature's state (new, in progress, in review, merged, claimed, FAULTY) and suggest what to do next. Use at the start of a session, when asked "what's next", or when a new drip feature may have been released.
---

# /next

1. Run `workflow/bin/next`. It fetches new and changed specs into `specs/`, reads branch and PR
   state from GitHub and who has taken what from the shared `work` board, and prints a table and
   a suggestion. Also run `workflow/bin/work show` and mention anything `blocked` (a person is needed).
2. Show the user the table and the suggestion as printed. Add, in a sentence or two:
   - For a **new** feature: what it asks for (read `specs/<slug>.md`).
   - For **SPEC CHANGED**: what changed (`git diff specs/<slug>.md`) and whether something we built
     or claimed is affected.
   - For **FAULTY**: which checks, from `workflow/bin/harness /api/teams/$(cat .fagdag/team)/feedback`.
3. Don't start the suggested step unless the user asks (or you're running under `/drive`).
