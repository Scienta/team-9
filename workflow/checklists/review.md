# Review checklist

Used by the three reviewer subagents (`.claude/agents/review-*.md`) and by the person reviewing the
PR. `/retro` adds items when a review let something through: say where it came from (`lessons.md#L<n>`).

## Spec conformance (`review-spec`)

- [ ] Every rule in the team spec has code that implements it and a test that would fail without it.
- [ ] Every DECISION is implemented as written, and tested.
- [ ] Every endpoint in the request exists with the exact path, method and JSON field names.
- [ ] Every status code in the team spec's API table is produced, and none other.
- [ ] `examples/<slug>.hurl` has a request per rule and per error status.

## Edge cases and concurrency (`review-edge`)

- [ ] Every item in `spec-questions.md` was considered for this feature.
- [ ] Bad input never gives a 500 (null, blank, wrong type, unknown enum, non-numeric path id).
- [ ] A rejected request changes nothing in the database.
- [ ] Limits and state transitions hold under concurrent requests: row lock (`SELECT … FOR UPDATE`)
      or conditional write, inside one transaction. No advisory locks, no session `SET`.
- [ ] No more than 5 DB connections needed per request path; no connection held during slow work.

## Regressions and contract (`review-regress`)

- [ ] Every response that shows the changed resource shows the new field or state.
- [ ] The base features still behave per their specs (`specs/speakers.md`, `talks.md`, `ratings.md`)
      and earlier drip features per their team specs; their Hurl files still pass.
- [ ] Schema changes are additive and work on the existing production data (it survives deploys).
- [ ] `/health`, port 8080, the `Dockerfile` and `GIT_SHA` are untouched or still per the contract.
- [ ] No secrets, tokens or `.fagdag/harness-token` contents in the diff.
