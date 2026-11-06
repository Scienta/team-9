# Building and testing fast

`./mvnw package` is cached and runs the tests in parallel, so iterate with it freely.

- **Build cache** (`.mvn/extensions.xml`, `.mvn/maven-build-cache-config.xml`): when the pom, `src/`
  and the plugin config match an earlier green build, Maven restores `target/` (classes, `cfp.jar`)
  from `~/.m2/build-cache` in ~2 s and doesn't compile or test again. Every worktree shares the
  cache, so a tree another worktree already built is a hit. A build that skipped tests or ran a
  subset (`-Dtest=…`) is never reused for one that has to run them all, and a failed build is never
  cached.
- **Parallel tests**: `.mvn/maven.config` sets `-DforkCount=3`, so test classes run in 3 JVMs (each
  with its own Postgres container). A full run takes ~35 s instead of ~52 s; `HealthTest` (~21 s,
  the deploy contract's 5 s timeouts) is the floor.
- **While iterating**, run only the class you're working on: `./mvnw -q test -Dtest=TalksTest`
  (or `-Dtest='TalksTest#name*'`). Then run the full `./mvnw -q package` before you commit; it's a
  gate in `/build`.
- A cache hit prints `Found cached build, restoring … from cache` and runs no tests. That counts as
  the gate passing: the same inputs passed before. If you suspect the cache (e.g. you changed
  something outside `src/` and the pom, like the JDK or Docker), force a real run with
  `-Dmaven.build.cache.skipCache=true`, or turn it off with `-Dmaven.build.cache.enabled=false`.
- Tests that depend on timing or a shared database must stay safe to run in a separate JVM next to
  other classes: use `TestDatabase.fresh` (one database per class) and don't rely on class order.
  If one can't, say so in the PR and use `-DforkCount=1` to confirm.
