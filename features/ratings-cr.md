# ratings-cr

Owner: Henrik Otgard (claude-otgard)
State: in-review
Branch: feat/ratings-cr
PR: -

## Log

- 2026-11-06T14:18Z Henrik Otgard (claude-otgard): started
- 2026-11-06T14:19Z Henrik Otgard (claude-otgard): team spec: 6 rules, 7 decisions UNCONFIRMED (0 for the PO)
- 2026-11-06T14:24Z Henrik Otgard (claude-otgard): build 1 tests first: RatingsCrTest 9 tests, examples/ratings-cr.hurl (73 requests), RatingsTest+UpgradeContractTest updated to 2 decimals; 9 fail before code on the old average
- 2026-11-06T14:24Z Henrik Otgard (claude-otgard): build 2 code: TalkDao per-talk subquery adds MAX/MIN, average trims at 5+ and rounds HALF_EVEN to 2 decimals; RatingsCrTest 10/10, RatingsTest, UpgradeContractTest, ListQueriesTest pass
- 2026-11-06T14:29Z Henrik Otgard (claude-otgard): build 3 gates (on a scratch merge with fix/request-logging-test-compile, since main 7aa58d4 doesn't compile): mvnw package JDK 25 171 tests 0 fail; 10 Hurl files 297 requests pass with Hurl 7.0.0; pushed 73fa50d
- 2026-11-06T14:30Z Henrik Otgard (claude-otgard): build 4: pushed 73fa50d; three reviewers (spec, edge, regress) running
- 2026-11-06T14:34Z Henrik Otgard (claude-otgard): state -> in-review: branch pushed (04bc6db), PR to open by a person: https://github.com/Scienta/team-9/compare/main...feat/ratings-cr ; 7 decisions to confirm; depends on fix/request-logging-test-compile (main doesn't compile)
- 2026-11-06T14:36Z Henrik Otgard (claude-otgard): decisions: 7 confirmed by Henrik Otgard as written; check-decisions passes; pushed 1b08807
