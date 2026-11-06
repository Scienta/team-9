---
name: spec
description: Turn a harness feature request (specs/<slug>.md) into our team spec (workflow/specs/<slug>.md) with numbered rules, a full status-code table and every gap answered as an UNCONFIRMED decision. Use when starting a feature, before /build.
---

# /spec <slug>

The feature request gives business rules and the API surface, not status codes, edge cases or
error handling. This step writes those down so the build and the reviewers have one thing to check against.

1. **Claim the work, in its own worktree** (`.claude/rules/worktrees.md`). Run `workflow/bin/next`
   first: if the feature is in progress or in review (a branch on origin, or a watcher task), stop,
   someone else has it. Otherwise:
   ```bash
   slug=<slug>; git fetch -q origin
   name="$slug-$(sha256sum specs/$slug.md | cut -c1-12)"
   git worktree add -b "spec/$name" ".workflow-state/worktrees/$name" origin/main
   cd ".workflow-state/worktrees/$name" && git push -u origin "spec/$name"
   ```
   Pushing right away is how `bin/next` shows the team someone is on it. Do every later step,
   including `/build`, inside this worktree.
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
4. **Commit and push** the team spec on the `spec/<slug>-<hash12>` branch: `docs(<slug>): Team spec`.
5. **Tell the user** the rules count and list the decisions in one line each, the product-owner
   ones first. Then go on to `/build <slug>` if running under `/drive`; otherwise suggest it.
