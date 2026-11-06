# Lessons

The learning log. `/retro` appends an entry for every error (see `workflow/README.md#how-it-learns`)
and every claim. `/spec` and `/build` read this file at the start of every feature.

Each entry: what happened, the root cause, the phase that should have caught it, and the harness
change made so it can't recur silently (with the file changed). Number them `L1`, `L2`, ...

## L1: the team token file held a pasted curl fragment (tick 6)

- **What happened:** `.fagdag/harness-token` held `Authorization: Bearer <token>" "/api/teams/...`
  instead of the token, so every harness call answered 401.
- **Root cause:** the token was copied from the feedback page's example command, not the token field.
- **Should have been caught by:** setup.
- **Harness change:** `workflow/bin/harness` refuses a token file containing `Bearer`,
  `Authorization`, a quote or a slash, and says what to paste.

## L2: the log check counted validation warnings as errors, then silently matched nothing (tick 22)

- **What happened:** the first `bin/verify` flagged every `WARN … MethodArgumentNotValidException`
  (expected 400s) as an error. The first fix parsed the log lines as JSON, but they start with a
  timestamp, so `jq` failed on every line and the check reported OK whatever the logs held.
- **Root cause:** a check that can't fail was trusted without proving it can see a positive.
- **Should have been caught by:** writing the check.
- **Harness change:** rule in `.claude/rules/workflow.md`: a new check must be shown to detect a
  known-bad input before its OK is trusted.

## L3: a repeated DELETE answered 200, and co-speakers/check-1 was FAULTY from the claim on (tick 46-51)

- **What happened:** co-speakers was claimed at tick 46 and check-1 was FAULTY on every tick after.
  Its transcript: add two co-speakers, read them back, `DELETE /talks/{id}/co-speakers/{s}` (200),
  then the same DELETE again, which we answered 200 with the talk unchanged. The harness rejected
  that second DELETE. Every other request in the check passed.
- **Root cause:** the request says a repeated *add* "changes nothing" and is silent on removal. Our
  decision (`workflow/co-speakers.md`) carried the add's no-op over to removal, and our house
  convention ("repeating an operation is a no-op 200") made that the default. The decision was
  listed for human review but nobody confirmed it before the claim, and there was no Hurl file, so
  `bin/verify` could not have run.
- **Should have been caught by:** `/spec` (the decision) and the claim (no evidence held).
- **Harness change:** `workflow/checklists/spec-questions.md`: items 4 and 5 now ask about a repeated
  remove separately, and the house convention is now "removing something that isn't there is a 404".
  `examples/co-speakers.hurl` replays the harness's sequence; it failed against the old production
  code at the repeated DELETE before the fix (got 200, expected 404).


## L4: an agent's workflow change reached main and production without a PR (13:00-13:21)

- **What happened:** an agent (pi, Jonathan's session) did the agent-workflow work (`3bd0d31`, then
  the `work` board and the reconciled skills in `8864a91`) by editing and committing straight on
  `main` in the shared `team-9/` checkout, while another session committed co-speakers in the same
  checkout. That local `main` then reached `origin/main` twice without review: pushed with
  `392f624`, and merged by `f643e12` (with a WIP acceptance commit). PR #2 was also cut from it, so
  its 1-file diff sat on top of ~40 unreviewed files. Both deployed. Espen's PR #3 was then closed
  as "superseded" by a commit nobody had reviewed.
- **Root cause:** the agent's rule (`worktrees.md`) only talked about "an agent working on a
  feature", and the agent treated workflow/config work as exempt. Nothing flagged a local `main`
  with commits that weren't on origin, and nothing stopped anyone pushing or merging it.
- **Should have been caught by:** the agent before its first edit; failing that, any later session
  in that checkout (`bin/next`), or the PR review of #2 (its commit list, not just its diff).
- **Harness change:** `workflow/bin/check-main` (run by `bin/next`, tested in
  `workflow/test_check_main.py`) fails when local `main` has commits that aren't on origin/main;
  `/drive` stops on it. `.claude/rules/worktrees.md` now covers every change, including workflow
  and agent config, and forbids committing on `main`. `checklists/review.md` checks the PR's commit
  list. Still needed from a person: branch protection on `main` (required PR and checks).

## L5: three reviewers caught a 500, a coerced type and untested statuses in workshop-registration (tick 75)

- **What happened:** the first build of workshop-registration passed every local gate. The three
  reviewer agents then found: a `name` with a NUL character answered **500** (Postgres refuses zero
  bytes at the insert); `"name": 123` was stored as `"123"` with a 201 instead of a 400; the 503
  lock-timeout status had no test; and the Hurl file, which `bin/verify` runs before a claim,
  covered 4 of the API table's 400 cases. A deliberate break also showed the rush test caught a
  missing lock in only 2 runs of 3.
- **Root cause:** the spec checklist's "malformed input" item named "wrong type" but not the two
  cases Spring's defaults let through, so neither the spec nor the tests asked; and "a Hurl request
  per error status" was a sentence to remember, not a check.
- **Should have been caught by:** `/spec` (the inputs) and `/build` step 3 (the coverage).
- **Harness change:** `workflow/checklists/spec-questions.md` item 2 names both cases and their fix
  (`StrictStringDeserializer`, `@Pattern`); `workflow/bin/check-hurl-statuses` fails when a status in
  the team spec's API table has no Hurl request (shown failing on a Hurl file without its 404s), run
  by `/build` step 3 and by CI's `decisions` job; `workflow/checklists/review.md` points at it.
  Concurrency tests that guard a lock now run several rounds (`rushNeverExceedsTwentySeats`).

## L6: a person overrode the email decision in workshop-registration review (tick 90)

- **What happened:** D1 answered "validate emails exactly as speaker registration does". In review
  Espen overrode it: an attendee email needs a dot in its domain, so `a@b` is 400. The other ten
  decisions were confirmed as written.
- **Root cause:** `/spec` copied the nearest existing behaviour without asking whether that
  behaviour was what we wanted; `@Email` alone accepts `a@b`.
- **Should have been caught by:** `/spec` (the decision).
- **Harness change:** a house convention for emails in `workflow/checklists/spec-questions.md`, so
  the next feature with an email defaults to the stricter rule.

## L7: the upgrade's differential corpus only exercised our code, not the server and validator under it (spring-boot-4, review)

- **What happened:** `workflow/bin/diff-upgrade` (163 requests, 2.7 vs 4.1.2) showed no status
  difference, but review-edge found three: `GET /talks?sort=%zz` and >1000 query parameters went
  200 → 400 with an ERROR stack trace (Tomcat 11 throws where Tomcat 9 dropped the parameter, and its
  limit fell from 10000 to 1000), and a name of only Unicode whitespace went 201 → 400 (Hibernate
  Validator 9). review-regress found that `bin/verify`'s log check matched only Boot 2.7's
  `yyyy-MM-dd HH:mm:ss` timestamps, so on Boot 4 an ERROR line without a stack trace reported OK
  (the L2 failure mode again), and that the branch was 4 commits behind main, whose new
  `UpgradeContractTest` had never run against Boot 4.
- **Root cause:** the corpus was written from our controllers' point of view (fields, ids, enums);
  an upgrade also replaces the layers *before* the controller (servlet container parameter parsing,
  URL decoding, bean validation) and the tooling that reads its output (log format). None of the
  corpus requests reached those.
- **Should have been caught by:** `/build` (the corpus and the local gates).
- **Harness change:** `workflow/bin/diff-upgrade` now has malformed %-escapes, 1001 parameters, an
  invalid-UTF-8 path, a form body and Unicode-whitespace names; `workflow/bin/verify` matches both
  log formats (`ERROR_LINE`, shown to match Boot 2.7 and Boot 4 ERROR lines and not WARN/INFO);
  `workflow/checklists/review.md` adds an item for server-level input and log format on upgrades;
  `.claude/agents/review-*.md` now say Spring Boot 4 / Java 25.

## L8: withdraw took `speakerId: 1.5` as speaker 1, and acceptance/check-4 failed now and then (ticks 68-81)

- **What happened:** acceptance/check-4 was FAULTY on and off from tick 68. Its transcript at tick 81:
  `POST /talks/{id}/withdraw` with `{"speakerId":1.5}` answered 403 "only the speaker can withdraw a
  talk"; the harness expected a 400. It only fails when the check's random body is fractional.
- **Root cause:** `ConferenceService.withdraw` accepted any `Number` and called `.longValue()`, so 1.5
  became speaker 1. The spec types `speakerId` as `integer`. Our JUnit and Hurl files tried only a
  missing id and another valid speaker.
- **Should have been caught by:** `/build` (tests for "wrong type" stopped at strings and absent fields)
  and `review-edge`.
- **Harness change:** `workflow/checklists/review.md` and `spec-questions.md` now name a fractional number
  for an `integer` field. `AcceptanceTest` and `examples/acceptance.hurl` cover it; the JUnit test
  failed on the old code with the harness's 403 before the fix.

## L9: an accepted talk that isn't a workshop got 404, and workshop-registration/check-2 went FAULTY (tick 107)

- **What happened:** right after the claim, check-2 accepted a WORKSHOP, registered, withdrew it
  (409 on the next registration: passed), then accepted a LIGHTNING_10 talk and registered for it.
  We answered 404 "workshop not found" (decision D2), and the harness rejected it.
- **Root cause:** D2 applied "404 for a path id that doesn't exist" to an id that does exist but is
  the wrong kind. The request puts both conditions in one rule ("only workshops ... that the
  committee has accepted take registrations"), and the harness answers both the same way: 409. A
  person confirmed D2 in review, so review didn't catch it either.
- **Should have been caught by:** `/spec` (the decision), with a house convention that separated
  "no such thing" from "not eligible".
- **Harness change:** `workflow/checklists/spec-questions.md`: 404 only when nothing with that id
  exists; an existing resource of the wrong kind is 409. Test and Hurl request for an accepted
  presentation and lightning talk. `REVIEW.md` section 4.

## L10: reviewers caught a no-op rejected by an earlier check, and thin Hurl coverage, in scheduling (tick ~105)

- **What happened:** the first build of scheduling passed every local gate. All three reviewers
  then found that a talk put on the programme, withdrawn, and put in the same place again answered
  409, although D6 said a repeat is a 200 no-op and R8 says later changes leave the programme
  alone: D9's check order ran the `ACCEPTED` check before the repeat. review-spec also found the
  Hurl file covered 4 of D2's 9 kinds of bad `talkId` (all were in JUnit), and that its R5 request
  also broke R7, so it would pass with the R5 check removed.
- **Root cause:** the spec wrote the no-op (D6) and the check order (D9) as separate decisions and
  never asked whether a resource can change between the first call and the repeat. The Hurl file
  was written per status, which `check-hurl-statuses` accepts, not per case; L5 named this in the
  review checklist, which is too late to write the requests.
- **Should have been caught by:** `/spec` (the order) and `/build` step 1 (the Hurl file).
- **Harness change:** `workflow/checklists/spec-questions.md` item 13 (order of checks vs. no-ops);
  `.claude/skills/build/SKILL.md` step 1: one Hurl request per case a decision lists, and a rule's
  request must not break another rule too. `workflow/bin/check-hurl-statuses` now says which
  statuses it exempts instead of listing them as covered. Fixed with a test-first regression in
  `SchedulingTest#laterChangesLeaveTheProgramme` (failed with 409 before the fix) and Hurl "D6".
