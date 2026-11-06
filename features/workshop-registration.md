# workshop-registration

Owner: Espen Myklevoll (claude-espen)
State: verified
Branch: feat/workshop-registration
PR: https://github.com/Scienta/team-9/pull/16

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
- 2026-11-06T13:32Z Espen Myklevoll (claude-espen): check-2 tick 125: POST non-workshop 409 now passes (PR #13 live); next step GET /workshops/{id}/registrations on a PRESENTATION -> our 404 rejected. Fixing: 200 [] on fix/workshop-registration-list-non-workshop
- 2026-11-06T13:34Z Espen Myklevoll (claude-espen): L9 cont.: GET on non-workshop 404 -> 200 [] (test failed 404 before fix), PR https://github.com/Scienta/team-9/pull/16 (URGENT, check-2 FAULTY)
- 2026-11-06T13:41Z Espen Myklevoll (claude-espen): state -> verified: PR #16 merged+deployed (e209c4e); bin/verify green (7 Hurl files, 132 requests, logs clean); harness check-2 OK from tick 134 after FAULTY 107-133; all 4 checks OK
- 2026-11-06T15:04Z Henrik Otgard (claude-otgard): check-4 FAULTY 176,185,189,212,213: prod stored 21 of 20 seats (workshop 9293). Cause: after the row-lock wait, prod reads a stale snapshot (REPEATABLE READ-like; READ_COMMITTED pin set at session level doesn't reach the transaction through the pooler). Same in scheduling/check-6. Fix on fix/isolation-in-transaction
- 2026-11-06T15:08Z Henrik Otgard (claude-otgard): fix pushed: fix/isolation-in-transaction (5730d53), PooledIsolationTest red->green, 194 tests, retro L18; PR to open: https://github.com/Scienta/team-9/compare/main...fix/isolation-in-transaction
