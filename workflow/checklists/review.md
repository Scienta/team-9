# Review checklist

Used by the three reviewer subagents (`.claude/agents/review-*.md`) and by the person reviewing the
PR. `/retro` adds items when a review let something through: say where it came from (`lessons.md#L<n>`).

## Spec conformance (`review-spec`)

- [ ] Every rule in the team spec has code that implements it and a test that would fail without it.
- [ ] Every DECISION is implemented as written, and tested.
- [ ] Every endpoint in the request exists with the exact path, method and JSON field names.
- [ ] Every status code in the team spec's API table is produced, and none other.
- [ ] `examples/<slug>.hurl` has a request per rule and per error status (`workflow/bin/check-hurl-statuses`
      checks the statuses; check the rules by reading, and every distinct 400 case, lessons.md L5).

## Edge cases and concurrency (`review-edge`)

- [ ] Every item in `spec-questions.md` was considered for this feature.
- [ ] Bad input never gives a 500 (null, blank, wrong type, a fractional number for an `integer` field, unknown enum, non-numeric path id).
- [ ] A rejected request changes nothing in the database.
- [ ] Limits and state transitions hold under concurrent requests: row lock (`SELECT … FOR UPDATE`)
      or conditional write, inside one transaction. No advisory locks, no session `SET`.
- [ ] No more than 5 DB connections needed per request path; no connection held during slow work.
- [ ] A list endpoint runs a fixed number of SQL statements however many rows it returns (no query per
      row): add it to `ListQueriesTest`, which counts them (`lessons.md#L11`).
- [ ] A read that joins tables doesn't drop rows through an inner join on a column without a foreign
      key, and a Hurl file that takes a shared production resource clears it first (`lessons.md#L14`).
- [ ] A framework, server or JDK upgrade: input that never reaches our code still answers as before:
      malformed %-escapes in the query, more than 1000 parameters, an invalid-UTF-8 path, Unicode
      whitespace in validated fields (all in `workflow/bin/diff-upgrade`) (`lessons.md#L7`).

## Regressions and contract (`review-regress`)

- [ ] Every response that shows the changed resource shows the new field or state.
- [ ] The base features still behave per their specs (`specs/speakers.md`, `talks.md`, `ratings.md`)
      and earlier drip features per their team specs; their Hurl files still pass.
- [ ] Schema changes are additive and work on the existing production data (it survives deploys).
- [ ] `/health`, port 8080, the `Dockerfile` and `GIT_SHA` are untouched or still per the contract.
- [ ] No secrets, tokens or `.fagdag/harness-token` contents in the diff.
- [ ] The PR's commits (`git log origin/main..HEAD`), not just its diff, are all this change: no
      commits carried in from someone's local `main` or another branch (`lessons.md#L4`).
- [ ] A framework, JDK or library upgrade: `workflow/bin/diff-upgrade <old> <new>` against both builds on
      empty databases shows no status difference, and every other difference is fixed or listed as a
      DECISION. Renamed config keys are silently ignored on Spring Boot 4, so run once with
      `spring-boot-properties-migrator` too, and check `workflow/bin/verify`'s `ERROR_LINE` still matches
      the new log format (`lessons.md#L7`).
