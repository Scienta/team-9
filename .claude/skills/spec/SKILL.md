---
name: spec
description: Turn a harness feature request (specs/<slug>.md) into our team spec (workflow/specs/<slug>.md) with numbered rules, a full status-code table and every gap answered as an UNCONFIRMED decision. Use when starting a feature, before /build.
---

# /spec <slug>

The feature request gives business rules and the API surface, not status codes, edge cases or
error handling. This step writes those down so the build and the reviewers have one thing to check against.

1. **Take the work.** `workflow/bin/work start <slug>` first: it's the team's lock, on the shared
   `work` branch (`.claude/rules/work-branch.md`). If it exits 3, or `origin/feat/<slug>` already
   exists, stop: someone else has it. Then make the branch in its own worktree and push it right
   away (`.claude/rules/worktrees.md`):
   `git fetch origin && git worktree add -b feat/<slug> ../team-9-<slug> origin/main`, then
   `cd ../team-9-<slug> && git push -u origin feat/<slug>`.
2. **Read**, in full: `specs/<slug>.md`, every spec it links to in `specs/`, earlier team specs in
   `workflow/specs/`, `workflow/lessons.md`, `workflow/checklists/spec-questions.md`, and the code
   the feature touches (start from the controllers and `ConferenceService`).
3. **Write `workflow/specs/<slug>.md`** from `workflow/templates/team-spec.md`:
   - **Rules:** every rule in the request, numbered R1…, each one testable sentence.
   - **API:** every endpoint, every status it can answer and when, and every existing response
     that changes.
   - **Decisions:** walk every item in `spec-questions.md`. Each gap the request leaves open
     becomes a decision D1… with an answer, the reason, and `UNCONFIRMED`. Prefer the house
     conventions in that file and say so. Where the request is genuinely ambiguous about a business
     rule (not just a status code), mark the decision `UNCONFIRMED (ask the product owner)`.
   - **Concurrency and data:** what can race, and how it's prevented.
4. **Commit and push** the team spec on the branch: `docs(<slug>): Team spec`. Then
   `workflow/bin/work log <slug> "team spec: <n> rules, <m> decisions UNCONFIRMED (<k> for the PO)"`.
   If a business rule needs the product owner, also `work state <slug> blocked "<question>"` so
   a person sees it, and keep building on the default.
5. **Tell the user** the rules count and list the decisions in one line each, the product-owner
   ones first. Then go on to `/build <slug>` if running under `/drive`; otherwise suggest it.
