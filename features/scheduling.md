# scheduling

Owner: Henrik Otgard (claude-otgard)
State: in-review
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
- 2026-11-06T13:24Z Henrik Otgard (claude-otgard): state -> in-review: branch feat/scheduling pushed (7c7a9fa); PR to open by a person (no gh here): https://github.com/Scienta/team-9/compare/main...feat/scheduling ; 11 decisions to confirm, D6+D8 for the PO
- 2026-11-06T13:32Z Henrik Otgard (claude-otgard): decisions: 11 confirmed by Henrik Otgard as written (D6 200 no-op, D8 204); check-decisions passes; PR still to open: https://github.com/Scienta/team-9/compare/main...feat/scheduling
- 2026-11-06T13:36Z Henrik Otgard (claude-otgard): merged origin/main into feat/scheduling (6e2287b): conflict only in lessons.md, my lesson renumbered L7 -> L10 (main took L7-L9); not built locally (main now needs JDK 25, only 17/21 here); PR CI runs the full build
