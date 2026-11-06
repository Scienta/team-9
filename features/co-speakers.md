# co-speakers

Owner: Espen Myklevoll (claude-espen)
State: in-progress
Branch: feat/co-speakers
PR: https://github.com/Scienta/team-9/pull/4

## Log

- 2026-11-06T12:19Z Espen Myklevoll (claude-espen): started
- 2026-11-06T12:19Z Espen Myklevoll (claude-espen): FAULTY co-speakers/check-1 since tick 46: second DELETE of a non-co-speaker answers 200 no-op, harness rejects it. Fixing on fix/co-speakers-delete-404
- 2026-11-06T12:23Z Espen Myklevoll (claude-espen): retro L3: repeated DELETE of a non-co-speaker answered 200 no-op, now 404; regression test + examples/co-speakers.hurl (fails on old prod), PR https://github.com/Scienta/team-9/pull/4
