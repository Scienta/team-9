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
- 2026-11-06T14:43Z Gjermund Karlsen Lie (claude-gjermund): review-regress: no findings (mvnw package green, other Hurl files' loads <= 3, 3 test edits justified, schema additive)
- 2026-11-06T14:46Z Gjermund Karlsen Lie (claude-gjermund): review-spec: 0 bugs, 4 gaps/nits all fixed; review-edge: 0 bugs, 6 gaps/nits: 3 fixed, 2 rejected (index build, message wording), 1 pre-existing base bug (POST /talks speakerId 1.5 -> speaker 1, title 123 -> "123") left for a separate fix
- 2026-11-06T14:46Z Gjermund Karlsen Lie (claude-gjermund): retro L14: no CR/new-lock questions in /spec; spec-questions 15-16 + review.md checks; mvnw package 177 tests 0 failures; pushed 24e7855
