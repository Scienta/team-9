# workflow/

How team 9 takes a drip feature from "released on the harness" to "claimed with evidence", and how
that process improves itself from its own mistakes. Agent configuration lives in `.claude/`; this
folder has the process, templates, checklists, lessons and scripts.

## The flow

```
 harness releases a spec
          │
   /next  ─┤  fetch every spec, list features and their state, suggest one
          │
   /spec  ─┤  feature request → team spec (workflow/specs/<slug>.md): rules, endpoints, status codes,
          │  edge cases. Every gap is a DECISION with a suggested default, marked UNCONFIRMED.
          │
   /build ─┤  branch feat/<slug>, tests first (JUnit + examples/<slug>.hurl), then code,
          │  ./mvnw package, Hurl against a local app,
          │  3 reviewer subagents in parallel → fix findings → each finding becomes a lesson,
          │  PR with the UNCONFIRMED decisions listed for the reviewer
          │
 PERSON  ─┤  reviews the PR, confirms or overrides every decision
          │  (CI blocks the merge while any decision is UNCONFIRMED or a test fails)
          │  merges → deploy
          │
  /verify ─┤  /health has the merged SHA, every Hurl file green against the live URL, clean logs
          │  → evidence block + claim-log row in REVIEW.md
          │
 PERSON  ─┤  claims on the harness (agents never claim)
          │
  /retro  ─┘  after every claim and after every error: lesson + a change to the harness itself
```

`/drive` runs one step of that flow on its own (`/loop /drive` keeps it going). It stops only where
a person is needed: the PR review and the claim.

| Step | Skill | Script | Writes |
|---|---|---|---|
| Find work | [`/next`](../.claude/skills/next/SKILL.md) | [`bin/next`](bin/next) | `specs/<slug>.md` |
| Team spec | [`/spec`](../.claude/skills/spec/SKILL.md) | | `workflow/specs/<slug>.md` |
| Build | [`/build`](../.claude/skills/build/SKILL.md) | | code, tests, `examples/<slug>.hurl`, PR |
| Review | [3 reviewers](../.claude/agents/) + a person | [`bin/check-decisions`](bin/check-decisions) (CI) | PR comments |
| Evidence | [`/verify`](../.claude/skills/verify/SKILL.md) | [`bin/verify`](bin/verify) | claim log in `REVIEW.md` |
| Learn | [`/retro`](../.claude/skills/retro/SKILL.md) | | [`lessons.md`](lessons.md), checklists, rules, skills |
| Autopilot | [`/drive`](../.claude/skills/drive/SKILL.md) | | all of the above |

## Who decides what

- **Agents** write specs, tests, code and evidence; open PRs; never merge, never claim.
- **A person** confirms or overrides each DECISION in the PR (or asks the organisers' product owner
  about a business rule), merges, and claims.
- When the feature request is silent, the agent picks the default from the
  [decision conventions](checklists/spec-questions.md#house-conventions) so every feature answers
  the same way.

## How it learns

Something that got further than it should have is an error. Each one gets a `/retro`:

| Error | Phase that should have caught it |
|---|---|
| A reviewer subagent finds a bug | `/build` (tests didn't cover it) |
| A person's PR review finds a bug or overrides a decision | `/spec` or `/build` |
| CI fails on a PR | `/build` (didn't run the gate locally) |
| `/verify` fails after deploy | `/build` or the reviewers |
| A harness check goes FAULTY, or a base check regresses | every phase before it |
| A deploy fails or the service goes down | `/build` |

`/retro` writes an entry in [`lessons.md`](lessons.md) and makes **one concrete change** to the
harness so the same mistake can't recur silently: a new question in
[`checklists/spec-questions.md`](checklists/spec-questions.md), a review item in
[`checklists/review.md`](checklists/review.md), a rule in `.claude/rules/`, a test pattern, or a
step in a skill. The change rides in the same PR as the fix, so a person reviews both together.
`/spec` and `/build` read `lessons.md` and both checklists at the start of every feature.

## Files

- [`specs/`](specs/): our team spec per feature (the feature request lives in `/specs/<slug>.md`).
- [`templates/team-spec.md`](templates/team-spec.md): the team spec's shape.
- [`checklists/spec-questions.md`](checklists/spec-questions.md): gaps to look for in every request, and our conventions for answering them.
- [`checklists/review.md`](checklists/review.md): what the reviewers (agents and people) check.
- [`lessons.md`](lessons.md): the learning log.
- [`bin/`](bin/): `harness` (API client), `next`, `verify`, `check-decisions`, `hurl` (Docker fallback).
