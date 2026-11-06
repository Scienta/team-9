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

<!-- Who or what reviewed the agents' work (people, review agents, tests, Hurl examples, gates in
the deploy workflow), and against what (the spec, its examples, your own checks). -->

## 3. Claim log

No new claims made during preparation. Harness feedback reports speakers, talks and ratings as
base features already claimed at tick 0. Agents do not claim features.

## 4. What the harness caught that we didn't

No new deployment or claim yet, so no new harness failure evidence. Pre-game feedback was not
used as proof of full conformance. Local inspection found email validation and exact timestamp
formatting gaps; partial fixes are in the working tree, pending regression coverage and review.

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

## 5. Reusable artifacts

- [`workflow/watch_feedback.py`](workflow/watch_feedback.py): watches a scoring/monitoring feed and
  reports only changes, telling a deploy blip from a real failure and spotting when a failing check
  gets further (the fix worked, the next bug shows). Pure decision function, tested on real data.
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

Make tests a pre-deployment gate rather than running them after deployment. The inherited pipeline
can deploy regressions even when tests fail; changing that gate remains a separate follow-up.
