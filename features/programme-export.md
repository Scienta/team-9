# programme-export

Owner: Jonathan Lillesæter (pi-programme-export)
State: in-review
Branch: feat/programme-export
PR: https://github.com/Scienta/team-9/pull/19

## Log

- 2026-11-06T13:41Z Jonathan Lillesæter (pi-programme-export): started
- 2026-11-06T13:42Z Jonathan Lillesæter (pi-programme-export): team spec: 8 rules, 8 decisions UNCONFIRMED (1 for the PO: D1 withdrawn-but-scheduled talks still exported); depends on scheduling PR #15 (unmerged), will build on top of origin/feat/scheduling
- 2026-11-06T13:47Z Jonathan Lillesæter (pi-programme-export): tests first: ProgrammeExportTest 10 tests all failed 404 before code; then 10/10 pass; 3 mutations (order, ';' quoting, co-speaker order) each caught
- 2026-11-06T13:47Z Jonathan Lillesæter (pi-programme-export): gates: mvnw package 109 tests 0 failures; local hurl 9/9 files 221 reqs pass; check-hurl-statuses OK (200); built on origin/feat/scheduling (PR #15 unmerged) — commit 5f4a6f4
- 2026-11-06T13:52Z Jonathan Lillesæter (pi-programme-export): reviewers: spec 1 gap+4 nits, edge 2 gaps+2 nits, regress 2 gaps+1 nit; fixed 5 (LEFT JOIN D9, statement-count test, Hurl pre-clean, Content-Disposition test, spec test names), D5 CR-in-title now for PO; merged origin/main (scheduling #15 now merged)
- 2026-11-06T13:52Z Jonathan Lillesæter (pi-programme-export): gates after fixes: mvnw package 116 tests 0 failures; local hurl 9/9 files 224 reqs pass (programme-export.hurl twice in a row); check-hurl-statuses OK
- 2026-11-06T13:52Z Jonathan Lillesæter (pi-programme-export): retro L12: inner join without FK drops rows, Hurl cleanup unreachable on failure, stale base missed L11; harness: spec-questions #14, build skill steps 1+3, review.md
- 2026-11-06T13:53Z Jonathan Lillesæter (pi-programme-export): state -> in-review: PR https://github.com/Scienta/team-9/pull/19, 9 decisions to confirm (D1, D5 for the PO)
- 2026-11-06T13:56Z Jonathan Lillesæter (pi-programme-export): D1-D9 CONFIRMED by Jonathan Lillesæter; compared with team 7 (green): same behaviour except our Content-Disposition (D4) and JSON-for-any-Accept (D3); added 2 tests from their suite (re-added co-speaker last, trailing slash), 118 tests 0 failures
- 2026-11-06T14:00Z Jonathan Lillesæter (pi-programme-export): merged origin/main (#18): lessons.md conflict, both L12s; kept main's L12, ours now L13 (refs in spec-questions, review.md, build skill updated); 118 tests 0 failures, check-decisions OK; pushed
