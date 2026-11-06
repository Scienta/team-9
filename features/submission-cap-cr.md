# submission-cap-cr

Owner: Gjermund Karlsen Lie (claude-gjermund)
State: in-progress
Branch: feat/submission-cap-cr
PR: -

## Log

- 2026-11-06T14:32Z Gjermund Karlsen Lie (claude-gjermund): restarted after abandoned
- 2026-11-06T14:32Z Gjermund Karlsen Lie (claude-gjermund): taking over from Espen; /build from team spec 3516b1b
- 2026-11-06T14:37Z Gjermund Karlsen Lie (claude-gjermund): tests first: SubmissionCapTest 14 + SubmissionCapRepeatableReadTest 1, 9 fail on current code for the right reason (co-speaking not counted, withdrawn counted, PUT 200 at cap, 7>3 under race); examples/submission-cap-cr.hurl 44 requests. Note: main doesn't compile (#7+#23), building with PR #24's test fix applied locally, not committed
- 2026-11-06T14:41Z Gjermund Karlsen Lie (claude-gjermund): code + gates: mvnw package 176 tests 0 failures (with #24's test fix applied locally); check-hurl-statuses OK (200 201 400 404 409); Hurl run BLOCKED: ghcr.io hurl image needs sandbox approval; pushed 3570b26
