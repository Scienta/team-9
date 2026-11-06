# ratings-cr

Owner: Henrik Otgard (claude-otgard)
State: in-progress
Branch: feat/ratings-cr
PR: -

## Log

- 2026-11-06T14:18Z Henrik Otgard (claude-otgard): started
- 2026-11-06T14:19Z Henrik Otgard (claude-otgard): team spec: 6 rules, 7 decisions UNCONFIRMED (0 for the PO)
- 2026-11-06T14:24Z Henrik Otgard (claude-otgard): build 1 tests first: RatingsCrTest 9 tests, examples/ratings-cr.hurl (73 requests), RatingsTest+UpgradeContractTest updated to 2 decimals; 9 fail before code on the old average
- 2026-11-06T14:24Z Henrik Otgard (claude-otgard): build 2 code: TalkDao per-talk subquery adds MAX/MIN, average trims at 5+ and rounds HALF_EVEN to 2 decimals; RatingsCrTest 10/10, RatingsTest, UpgradeContractTest, ListQueriesTest pass
