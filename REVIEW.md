# REVIEW

> **Showcase brief (17:00, 4 minutes, hard stop).** You screen-share this repo from the shared
> presenter laptop, in a random order drawn at 17:00. No slides, and no harness screens.
>
> 1. **Our workflow in one minute:** section 1, pointing at the files in `workflow/` and `.claude/`.
> 2. **One story:** a bug our review caught before the harness did, or one the harness caught that
>    we missed, and why. Use the claim log.
> 3. **One artifact we'd reuse**, opened on screen.
>
> Keep this file as a running log from t = 0, so 17:00–17:05 is for tidying, not writing. To be
> eligible to win, every heading below needs some text. The comments under each heading are prompts
> and don't count; replace them with your own words.

## 1. Workflow

Preparation: inspected the app, fetched the three published base requests, and ran the existing
suite with Podman. [`workflow/watch_specs.py`](workflow/watch_specs.py) ([how](workflow/automation.md))
announces each released or changed spec. Every agent, Claude Code or pi, on anyone's machine,
records each step on the shared `work` branch ([`bin/work`](workflow/bin/work),
[rule](.claude/rules/work-branch.md)): taking a feature there is the lock, so two people's agents
never build the same drip. Humans review, merge and claim. No automatic claims.

The whole process is in [`workflow/README.md`](workflow/README.md). In one line per step:

1. **`/next`** ([skill](.claude/skills/next/SKILL.md), [script](workflow/bin/next)) fetches every
   spec from the harness, lists each feature's state from GitHub branches and PRs, and suggests one.
2. **`/spec`** ([skill](.claude/skills/spec/SKILL.md)) turns the feature request into our team spec
   ([template](workflow/templates/team-spec.md)), walking a [checklist of gaps](workflow/checklists/spec-questions.md).
   Every gap becomes a DECISION with a default, marked `UNCONFIRMED`.
3. **`/build`** ([skill](.claude/skills/build/SKILL.md)) writes tests first (JUnit and Hurl), then the
   code, runs every gate, fans out **three reviewer subagents** ([spec](.claude/agents/review-spec.md),
   [edge cases](.claude/agents/review-edge.md), [regressions](.claude/agents/review-regress.md)),
   fixes what they find and opens a PR.
4. **A person** reviews the PR and confirms or overrides every decision. [CI](.github/workflows/pr.yml)
   blocks the merge until tests pass and [no decision is unconfirmed](workflow/bin/check-decisions).
5. **`/verify`** ([skill](.claude/skills/verify/SKILL.md), [script](workflow/bin/verify)) proves the
   deployed SHA is the merged one, runs every Hurl file against production and checks the logs.
6. **A person claims**, and the evidence goes in the claim log below.
7. **`/retro`** ([skill](.claude/skills/retro/SKILL.md)) after every error and claim: a
   [lesson](workflow/lessons.md) plus a change to the harness itself, in the same PR as the fix.

**`/loop /drive`** ([skill](.claude/skills/drive/SKILL.md)) runs all of this unattended, stopping
only for PR review and the claim.

## 2. How we reviewed agent output

Four layers, each against something written down:

- **Tests the agent wrote first**, from the numbered rules and decisions in the team spec, plus the
  Hurl file that `/verify` later runs against production.
- **Three reviewer subagents in parallel**, each with a different angle: spec conformance, edge
  cases and concurrency, regressions and the deploy contract. They read the feature request, our
  team spec, [the review checklist](workflow/checklists/review.md) and [the lessons](workflow/lessons.md).
- **A person** reviews every PR and confirms every decision. CI enforces both tests and decisions.
- **Evidence from production** (`bin/verify`) before anyone claims, then the harness's own checks.

Every catch by a later layer becomes a lesson and a harness change ([`/retro`](.claude/skills/retro/SKILL.md)).

Pi agents go through the same gates: the same skills and rules, plus pi versions of the three
reviewers. Our own scripts are tested too, including a check that each test fails on known-bad
input (lessons.md L2). `test_work.py` runs four agents racing for the same feature (exactly one
wins) and four writing at once (no line lost) against a throwaway remote.

What the reviewer agents caught before the harness could (each is a lesson and a checklist item):
a scheduling no-op that an earlier check rejected (L10); a dropped row from an inner join, and a
Hurl file that could leave production places booked, in programme-export (L14); a floating-point
mean that turned a 1.55 tie into 1.5 in ratings-cr (L16); and an untested new lock, plus a test for
it that also passed with the lock removed, in submission-cap-cr (L17).

## 3. Claim log

A person claims on the harness; agents only gather the evidence. Status from harness feedback at
tick 218 (score 1997.9: 2219.97 earned, 222.07 bled).

| Feature | Claimed (tick) | Evidence before or after the claim | Checks at tick 218 |
|---|---|---|---|
| speakers, talks, ratings | 0 (base) | already claimed at the start | all OK |
| co-speakers | 46 | check-1 FAULTY 46-60; PR #4 deployed (a3aa8fb), `bin/verify` 5 Hurl files/55 requests, OK from tick 61 | all OK |
| acceptance | 65 | check-4 FAULTY now and then from tick 68 (`speakerId: 1.5`); fixed by PR #10 (234af5b) | all OK |
| workshop-registration | 106 | check-2 FAULTY 107-133; PRs #13 and #16 deployed (e209c4e), `bin/verify` 7 files/132 requests, OK from tick 134 | **check-4 FAULTY** (see section 4) |
| spring-boot-4 | 113 | PR #11 (9df15ac) live: `/actuator/info` Java 25.0.5, Boot 4.1.2; check-1/2 OK at tick 114 | all OK |
| scheduling | 139 | check-1 FAULTY from 140 (L12), again from 148 (L13); fixed by PRs #18 and #22 | all OK now; check-6 was FAULTY at ticks 206 and 212 |
| programme-export | 159 | PR #19 (de6e506) | all OK |
| ratings-cr | 207 | PR #25 (45973ea) | all OK |
| submission-cap-cr | 214 | PR #26 (8d7d5d4) | **check-4 FAULTY** at tick 218 |

## 4. What the harness caught that we didn't

**co-speakers/check-1, FAULTY from the claim at tick 46 until the fix deployed.** The harness
removed a co-speaker, then sent the same `DELETE` again; we answered 200 (a no-op), and the
harness rejected it. Our decision had copied the request's "a repeated add changes nothing" over
to removal. Nobody confirmed that decision before the claim, and there was no Hurl file, so no
evidence was checked. The fix answers 404, `examples/co-speakers.hurl` replays the harness's
sequence, and the spec checklist now asks about a repeated remove on its own
([lessons.md](workflow/lessons.md) L3).

**workshop-registration/check-2, FAULTY from tick 107 (right after the claim).** The harness
registered for an accepted lightning talk; we answered 404 because our decision D2 treated "not a
workshop" as "no such workshop". The harness wants 409, the same as a workshop that isn't accepted:
both conditions are one rule in the request. Three reviewer agents and a person's review had passed
D2. The house convention now separates "nothing with that id" (404) from "exists but not eligible"
(409) ([lessons.md](workflow/lessons.md) L9).

**scheduling/check-1, FAULTY from the claim at tick 140.** The harness put an accepted talk in
`/programme/R4/S1`, a room that doesn't exist; we answered 400 because our decision D1 called an
unknown room in the path "an unknown enum value", which the house convention made 400. The harness
wants 404: a path that names nothing. Three reviewer agents and a person confirmed D1. The house
convention now says an enum value in the path is 404, one in the body is 400; the fix answers 404
on every verb and the Hurl file replays the request ([lessons.md](workflow/lessons.md) L12).

**scheduling/check-1 again, from tick 148, once the 404 fix was live.** The check got further:
unknown rooms and slots passed, then `PUT /programme/R3/S1 {"talkId": 2065989499}`, a talk that
doesn't exist, got 404 from us. Our D3 had used the house convention's exception for a body id
that is "the subject of the action". The harness wants 400, as for `POST /talks` with an unknown
`speakerId`. The exception is gone from the convention ([lessons.md](workflow/lessons.md) L13).

**workshop-registration/check-4 and scheduling/check-6: races under load, from tick 176.** The
harness sends many requests at once. Production stored 21 registrations for a 20-seat workshop
(ticks 176-213), and placed one speaker twice in the same slot (ticks 206, 212). Every request
locks the row first (`SELECT … FOR UPDATE`), and our tests pass, but after waiting for the lock,
production counts from a stale snapshot (Henrik's diagnosis on the board). We set `READ COMMITTED`
per session, and in production that doesn't reach the transaction through the platform's connection
pooler. Local Postgres has no pooler, so our local tests didn't show it. The fix sets the isolation inside each
transaction (on `fix/isolation-in-transaction`, not merged yet). AGENTS.md warned about session
`SET` leaking through the pool, but no checklist asked what that does to isolation.

**submission-cap-cr/check-4, FAULTY at tick 218, four ticks after the claim.** In a burst of
requests for one speaker at the cap, `PUT /talks/9656/co-speakers/9572` answered 200 while the
requests either side of it answered 409 "already presents 3 talks". It looks like the same stale
read; that isn't confirmed yet.

## 5. Reusable artifacts

- [`workflow/bin/work`](workflow/bin/work): a race-safe shared agent board on an orphan git branch
  (compare-and-swap pushes, no checkout), tested with concurrent writers in
  [`workflow/test_work.py`](workflow/test_work.py).

- [`workflow/checklists/spec-questions.md`](workflow/checklists/spec-questions.md): the gaps every
  feature request leaves, and house conventions for answering them.
- [`workflow/bin/check-decisions`](workflow/bin/check-decisions): agents build on defaults, but a
  CI gate stops anything reaching main until a person has confirmed each one.
- [`.claude/skills/retro/SKILL.md`](.claude/skills/retro/SKILL.md): every error ends in a change to
  the process, not just a fix.
- The three reviewer agents in [`.claude/agents/`](.claude/agents/).

## 6. One thing we'd do differently

Test against production's database setup, connection pooler included, from the start. The
checks we lost after tick 170 were races that passed locally, where Postgres has no pooler
(section 4). We added a test gate before deploy early (PR #2), but L15 showed it still checks each
PR against an old main: two green PRs merged into a main that didn't compile. We'd also turn on
"require branches to be up to date" on `main` from t = 0.
