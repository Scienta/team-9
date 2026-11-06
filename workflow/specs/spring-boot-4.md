# Upgrade to Spring Boot 4.x (`spring-boot-4`)

Feature request: [`specs/spring-boot-4.md`](../../specs/spring-boot-4.md) on the harness, version 1 (V: 3).
Status: building

## Rules

- **R1** The service runs on Spring Boot 4.x (4.1.2, the latest GA when built). Tests:
  `UpgradeTest#infoReportsTheRunningVersions`, `examples/spring-boot-4.hurl` "R1".
- **R2** It runs on a Java version Boot 4 supports: Java 25 (LTS), in the Docker image and in CI.
  Tests: `UpgradeTest#infoReportsTheRunningVersions`, `examples/spring-boot-4.hurl` "R2".
- **R3** `GET /actuator/info` reports `versions.springBoot`, `versions.springFramework` and
  `versions.java` read at runtime (`SpringBootVersion`, `SpringVersion`, `java.version`), never
  hard-coded. Test: `UpgradeTest#infoReportsTheRunningVersions` compares against the classes on the classpath.
- **R4** Nothing else changes for users: every base spec (speakers, talks, ratings) and drip feature
  (acceptance, co-speakers) answers the same statuses and bodies as on 2.7, formats included. Tests:
  the whole existing JUnit suite, every `examples/*.hurl`, and a differential run of a request corpus
  against the 2.7 jar and the 4.x jar (`workflow/bin/diff-upgrade`, see the PR).
- **R5** Trailing slashes keep working (`/speakers/`, `/speakers/{id}/`, and every other path, as 2.7
  matched them). Spring 6+ turned that off by default. Tests: `UpgradeTest#trailingSlashes`, hurl "R5".
- **R6** Exact formats survive the Jackson 3 / JDK 25 change: `submittedAt`
  (`yyyy-MM-dd'T'HH:mm:ss.SSS+00:00`), `submittedOn`, `ratedAtDisplay` (`M/d/yy, h:mm AM` with an
  ordinary space), `averageRating` as a number, the CSV's Norwegian date. Tests: `UpgradeTest#formats`, hurl "R6".
- **R7** The deploy contract is unchanged: port 8080, `GET /health`, `GIT_SHA`, `PG*` variables. Test: `HealthTest`.

## API

No new or changed endpoints. `GET /actuator/info`: 200 `{"versions":{"java":…,"springBoot":"4.…","springFramework":"7.…"}}`.

## Decisions

- **D1** Which Java? → 25 (LTS), as the request's example shows; Boot 4.1 supports 17–25. `UNCONFIRMED`
- **D2** Jackson 3 (Boot 4's default) or stay on Jackson 2? → Jackson 3 with
  `spring.jackson.use-jackson2-defaults=true`, so its changed defaults (sorted properties, failing on
  trailing tokens, nulls for primitives…) don't change our formats. Proven by the differential run. `UNCONFIRMED`
- **D3** Trailing slash: restore for every path or only `/speakers`? → every path, because 2.7 matched
  them everywhere and "nothing else changes". `UNCONFIRMED`
- **D4** CI's Java version lives in `deploy.yml`, which a feature branch may not edit
  (`.claude/rules/safety.md`). → A separate PR makes `deploy.yml` read `.java-version`; this PR
  changes `.java-version` to 25. That PR must merge first. `UNCONFIRMED`
- **D5** Error bodies keep Boot's shape `timestamp, status, error, message, path` (+ `errors` on
  validation failures). Where Boot 4 words a framework message differently (e.g. 404 for an unknown
  path), we accept the new wording: no spec defines it. Listed in the PR from the differential run. `UNCONFIRMED`

## Concurrency and data

No schema change. Same pool (5), same transactions and row locks; tests for them run unchanged.

## Out of scope

Refactoring the legacy code, Jakarta-only cleanups beyond what compiles, new endpoints.
