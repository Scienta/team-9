# scheduling

Owner: Henrik Otgard (claude-otgard)
State: blocked
Branch: feat/scheduling
PR: -

## Log

- 2026-11-06T13:09Z Henrik Otgard (claude-otgard): started
- 2026-11-06T13:11Z Henrik Otgard (claude-otgard): team spec: 9 rules, 11 decisions UNCONFIRMED (2 for the PO: D6 repeat PUT same place = 200, D8 DELETE = 204)
- 2026-11-06T13:11Z Henrik Otgard (claude-otgard): state -> blocked: PO: D6 is a repeat PUT of the same talk to the same place 200 or 409? D8 DELETE 204 or 200 with Entry? Building on 200/204 meanwhile
- 2026-11-06T13:13Z Henrik Otgard (claude-otgard): build 1 tests first: SchedulingTest 13 tests (3 rush tests x3 rounds), examples/scheduling.hurl; all 13 fail before code (relation programme does not exist)
- 2026-11-06T13:15Z Henrik Otgard (claude-otgard): build 2 code: Programme/ProgrammeDao/ProgrammeService/ProgrammeController + schema; SchedulingTest 13/13; slot lock shown guarded (without it shared-speaker rush got 3 winners)
- 2026-11-06T13:16Z Henrik Otgard (claude-otgard): build 3 gates: mvnw package 89 tests 0 fail 0 skip; 7 Hurl files 158 requests pass; scheduling.hurl repeatable and leaves programme empty; check-hurl-statuses OK
- 2026-11-06T13:16Z Henrik Otgard (claude-otgard): build 4: committed afaa9d1, pushed feat/scheduling; three reviewers (spec, edge, regress) running
- 2026-11-06T13:23Z Henrik Otgard (claude-otgard): build 4 review: spec 4 findings (3 fixed, 1 harness msg fixed), edge 1 bug (same D6) + 2 nits fixed, regress 0 regressions; retro L7; gates rerun: 89 tests, 7 Hurl files 170 requests pass
