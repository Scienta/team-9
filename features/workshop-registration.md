# workshop-registration

Owner: Espen Myklevoll (claude-espen)
State: in-review
Branch: feat/workshop-registration
PR: https://github.com/Scienta/team-9/pull/13

## Log

- 2026-11-06T12:39Z Espen Myklevoll (claude-espen): started
- 2026-11-06T12:40Z Espen Myklevoll (claude-espen): team spec: 6 rules, 8 decisions UNCONFIRMED (0 for the PO)
- 2026-11-06T12:42Z Espen Myklevoll (claude-espen): tests first: WorkshopRegistrationTest 10 tests (2 concurrency), all 10 fail with 404 (no endpoint); examples/workshop-registration.hurl 51 requests
- 2026-11-06T12:43Z Espen Myklevoll (claude-espen): code: WorkshopController/WorkshopService/RegistrationDao/RegistrationForm + schema; WorkshopRegistrationTest 10/10 pass
- 2026-11-06T12:44Z Espen Myklevoll (claude-espen): local gates: mvnw package exit 0; Hurl 6 files/106 requests pass locally; launching 3 reviewers
- 2026-11-06T12:55Z Espen Myklevoll (claude-espen): reviewers: spec 0 bugs/5 gaps+nits, edge 1 bug (NUL name 500)+2 gaps, regress 0 regressions/2 nits; fixed 8 incl. coerced types (D9); retro L5: check-hurl-statuses in /build + CI
- 2026-11-06T12:55Z Espen Myklevoll (claude-espen): state -> in-review: PR https://github.com/Scienta/team-9/pull/9, 11 decisions to confirm
- 2026-11-06T13:02Z Espen Myklevoll (claude-espen): decisions: 10 confirmed by Espen, D1 overridden (email domain needs a dot; test failed 201 before fix); retro L6; pushed
- 2026-11-06T13:14Z Espen Myklevoll (claude-espen): FAULTY workshop-registration/check-2 tick 107: POST /workshops/{id}/registrations on an ACCEPTED LIGHTNING_10 talk -> our 404 (D2), harness rejects; withdrawn workshop 409 passed in the same run. Fixing on fix/workshop-registration-non-workshop
- 2026-11-06T13:20Z Espen Myklevoll (claude-espen): retro L8: non-workshop talk 404 -> 409 (test failed 404 before fix), PR https://github.com/Scienta/team-9/pull/13 (URGENT, check-2 FAULTY since tick 107)
