# main
- 2026-11-06T14:27Z Henrik Otgard (claude-otgard): main 7aa58d4 doesn't compile its tests (PR #23 changed RequestLoggingFilter's constructor, PR #7 added a test with the old one): deploy gate blocks every deploy. Fix: branch fix/request-logging-test-compile (4069cb2), 161 tests green on JDK 25; needs a PR + merge by a person
- 2026-11-06T14:33Z Jonathan Lillesæter (pi-perf-cpu): main red since 7aa58d4 (#7 + #23 semantic conflict: RequestLoggingFilterTest no-arg ctor); deploy 42233146344 failed, prod stays 626cd1d; fix PR fix/main-filter-test — merge it before anything else
