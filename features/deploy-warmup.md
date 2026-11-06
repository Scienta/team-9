# deploy-warmup

Owner: Jonathan Lillesæter (pi-deploy-warmup)
State: in-review
Branch: feat/deploy-warmup
PR: https://github.com/Scienta/team-9/pull/14

## Log

- 2026-11-06T13:15Z Jonathan Lillesæter (pi-deploy-warmup): re-start (entry missing from board): cold JVM after each deploy -> first-tick timeouts; warm-up before port opens; rebasing on Boot 4 main
- 2026-11-06T13:25Z Jonathan Lillesæter (pi-deploy-warmup): state -> in-review: PR https://github.com/Scienta/team-9/pull/14; local 0.25cpu first-tick POST /speakers p50/max 2.8/3.1s -> 0.8/1.3s; 85 tests 0 failures; WarmUpTest mutations fail as expected
- 2026-11-06T13:36Z Jonathan Lillesæter (pi-deploy-warmup): deployed in 019ac3b (PR #14's own run was cancelled by the next merge): prod 'warm-up done ... 0 failures, 12991 ms' before Tomcat started; first tick on new instance POST /speakers 201 p50 295/max 985 ms vs 310/4994 on previous cold deploy (tick 123 timeouts); ticks 126-128 all speaker/talk checks OK; one deploy observed so far
