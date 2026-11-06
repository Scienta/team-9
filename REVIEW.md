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

<!-- The steps you actually ran, from spec to claim. Link to the files in workflow/ and .claude/. -->

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

<!-- Who or what reviewed the agents' work (people, review agents, tests, Hurl examples, gates in
the deploy workflow), and against what (the spec, its examples, your own checks). -->

## 3. Claim log

<!-- A running entry per feature or change request, written when you claim or decide to skip it:
claimed or skipped, at which tick, and the evidence you held before claiming. For example:

| Feature | Claimed or skipped | Tick | Evidence before claiming |
|---|---|---|---|
| Speakers | claimed | 3 | examples.hurl green against our URL, plus our own edge-case tests for R3 and R7 |
-->

## 4. What the harness caught that we didn't

<!-- Failures the harness found after you claimed (or after a deploy), and why your own review missed them. -->

## 5. Reusable artifacts

<!-- What's worth taking to client work: agents, skills, prompts, spec templates, gates. Give the paths. -->

- [`workflow/checklists/spec-questions.md`](workflow/checklists/spec-questions.md): the gaps every
  feature request leaves, and house conventions for answering them.
- [`workflow/bin/check-decisions`](workflow/bin/check-decisions): agents build on defaults, but a
  CI gate stops anything reaching main until a person has confirmed each one.
- [`.claude/skills/retro/SKILL.md`](.claude/skills/retro/SKILL.md): every error ends in a change to
  the process, not just a fix.
- The three reviewer agents in [`.claude/agents/`](.claude/agents/).

## 6. One thing we'd do differently

<!-- One thing, and why. -->
