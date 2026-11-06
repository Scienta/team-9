# programme-export

Owner: Jonathan Lillesæter (pi-programme-export)
State: in-progress
Branch: feat/programme-export
PR: -

## Log

- 2026-11-06T13:41Z Jonathan Lillesæter (pi-programme-export): started
- 2026-11-06T13:42Z Jonathan Lillesæter (pi-programme-export): team spec: 8 rules, 8 decisions UNCONFIRMED (1 for the PO: D1 withdrawn-but-scheduled talks still exported); depends on scheduling PR #15 (unmerged), will build on top of origin/feat/scheduling
- 2026-11-06T13:47Z Jonathan Lillesæter (pi-programme-export): tests first: ProgrammeExportTest 10 tests all failed 404 before code; then 10/10 pass; 3 mutations (order, ';' quoting, co-speaker order) each caught
- 2026-11-06T13:47Z Jonathan Lillesæter (pi-programme-export): gates: mvnw package 109 tests 0 failures; local hurl 9/9 files 221 reqs pass; check-hurl-statuses OK (200); built on origin/feat/scheduling (PR #15 unmerged) — commit 5f4a6f4
