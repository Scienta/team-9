---
name: build
description: Implement a feature from its team spec on branch feat/<slug> - tests first, code, local gates, three parallel reviewer subagents, fixes, lessons, then a PR for a person. Use after /spec.
---

# /build <slug>

Precondition: you're on `feat/<slug>` and `workflow/specs/<slug>.md` exists. Read it, plus
`workflow/lessons.md` and `workflow/checklists/review.md`, before writing anything.

## 1. Tests first

- A JUnit integration test class per feature, e.g. `CoSpeakersTest`, on the pattern of `TalksTest`
  (real app on a random port, `TestDatabase.fresh`). One test per rule and per decision at least;
  a concurrency test for every limit or state rule that can race.
- `examples/<slug>.hurl`: a request per rule and per error status, each with a comment naming the
  rule or decision (`# R2:` / `# D3:`). Unique emails (`{{newUuid}}`) so it can run repeatedly
  against production. It must leave production data valid.
- Run them and see them fail for the right reason (missing endpoint or wrong value, not a compile
  error elsewhere).

## 2. Code

Keep it in the feature's own files where possible (controller, DAO). Schema changes go in
`src/main/resources/schema.sql`, additive and idempotent. Use row locks or conditional writes for
anything that can race.

## 3. Local gates

All must pass, and you must have seen them pass:
- `./mvnw -q package` (all tests, not just yours).
- App running locally (`docker compose up -d`, `java -jar target/cfp.jar &`), then
  `HURL=workflow/bin/hurl examples/run.sh` (every Hurl file, not just yours). Stop the app after.

## 4. Three reviewers, in parallel

Commit, then launch the three reviewer subagents **in one message** so they run concurrently, each
given the slug: `review-spec`, `review-edge`, `review-regress` (`.claude/agents/`).

For each finding: decide whether it's real (reproduce it with a test where you can). Fix the real
ones test-first, and say in the PR which you rejected and why. Rerun the local gates.

**Every real finding is an error a reviewer caught: run `/retro` for it** (one `/retro` can cover
several findings with the same root cause). Its harness change goes in this same branch.

## 5. Pull request

Push and open a PR with `gh pr create`, titled `feat(<slug>): <feature name>`. The body:
- **Decisions to confirm:** every `UNCONFIRMED` decision, one line each, product-owner ones first.
  The person confirms by editing the team spec (CI fails until none are left).
- **Rules → tests:** a table of each rule and decision and the test(s) that prove it.
- **Review findings:** each reviewer's findings and what was done (fixed with test X, or rejected
  because Y), and the lessons added.
- **Local evidence:** the test and Hurl summary lines you saw.

Update the team spec's `Status:` to `in-review`. Tell the user the PR URL and the decisions to confirm.
