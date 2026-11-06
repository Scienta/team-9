# Mutation testing (PIT)

Passing tests only prove the code runs; PIT proves the tests would notice if it were wrong. It
changes the compiled code one small thing at a time (a `<` becomes `<=`, a call is removed, a
return becomes `null`) and reruns the tests that cover that line. A mutant no test fails on is a
behaviour nobody checks.

It's an optional local development tool: not a CI job and not a required gate. Don't run it
unasked as part of `/build`; a full run takes minutes.

## When it's useful

- You're writing tests for existing code and want to see whether they actually pin it down.
- You changed a tricky class and want to check its tests, narrowed to that class (below).

## How

Run from the worktree. Podman provides Docker on this team's machines:

```bash
export DOCKER_HOST=unix://$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}')
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/run/podman/podman.sock
./mvnw -B -Pmutation -Dmaven.build.cache.enabled=false test-compile \
  org.pitest:pitest-maven:mutationCoverage -Dpit.targetClasses=no.scienta.cfp.WorkshopService
```

- Narrow it with `-Dpit.targetClasses` (globs allowed); without it every class is mutated, which
  takes many minutes. PIT has no free incremental mode any more, so every run starts over.
  `-Dpit.mutationThreshold=N` fails the run below N % killed.
- Safe to run in several worktrees at once: each PIT minion gets its own Testcontainers Postgres
  on a random port.
- Results: the `>> Generated N mutations Killed M` line in the output, details in
  `target/pit-reports/index.html` and `mutations.xml` (status `SURVIVED` or `NO_COVERAGE`).
  `target/` is gitignored; never commit reports.
- List what's left: `grep -E 'status=.(SURVIVED|NO_COVERAGE)' target/pit-reports/mutations.xml`.

## Killing a surviving mutant

- **Write the test from the spec, not from the code.** Ask what behaviour the mutant breaks and
  which rule in `specs/` or the team spec says it matters. Never assert a value just because the
  code returns it today: if the code looks wrong against the spec, that's a bug to fix, not to pin.
- **Test at the level where the behaviour is visible.** Rules over HTTP (the `TalksTest` pattern);
  validation branches and orderings in `ConferenceServiceTest` with mocks; DAO answers the API hides
  (unique index, 404 fallbacks) in `DaoTest`; classes whose only output is a log line with
  `LogCapture`.
- **Boundaries:** test both sides (500 and 501 characters, score 0, 1, 5 and 6, status 399 and 400).
- **Time zones:** run the test with a non-UTC default zone (see `UtilsTest.withDefaultZone`), or a
  missing `setTimeZone(UTC)` passes on the UTC CI runners.
- **Mockito** returns an empty `Map`/`List` for unstubbed calls, not `null`: stub "not found" explicitly.
- **A mutant no input can kill** (an equivalent mutant) usually means the code says something
  twice. Rewrite it so it behaves identically without the redundant branch (see
  `Utils.isValidEmail`) and say so in the PR. Don't exclude classes or mutators to make the number
  go up.
- **A mutant that reveals dead code** is often a bug (see `RequestLoggingFilter`: its truncation
  marker could never appear). Fix it test-first: show the test failing on the old code.

## Report

If you ran it, you can paste the `>> Generated … Killed …` line in the PR. It's information, not a
requirement.
