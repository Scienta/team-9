# Upgrade to Spring Boot 4.x (`spring-boot-4`)

Feature request: [`specs/spring-boot-4.md`](../../specs/spring-boot-4.md) on the harness, version 1 (V: 3).
Status: in-review

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
  against the 2.7 jar and the 4.x jar (`workflow/bin/diff-upgrade`: 172 requests, the only status
  difference is D8; the remaining body differences are the framework wordings in D5).
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
  changes `.java-version` to 25. That PR (#5) must merge first; this branch is then rebased so
  `.java-version` says 25 (both add the file). `UNCONFIRMED`
- **D5** Framework-generated error wording that no spec defines changes with Boot 4 and we accept it
  (same status, same body shape `timestamp, status, error, message, path`): JSON parse errors no longer
  quote Jackson internals, type mismatches start `Method parameter 'id': …`, 405/415/missing-param
  messages are reworded, an unknown path says `No static resource nope.` instead of `No message available`,
  `errors[]` on a 2-field validation failure may come in another order, and `Allow`/`Vary` headers on
  405/404 differ slightly, key order inside an `errors[].arguments[]` entry differs, a 415 for an
  unparsable Content-Type and a 406 gain an `Accept` header, `TRACE` gets a JSON 405 body, a path with
  invalid UTF-8 (`/talks/%C0%AE`) gets Tomcat's HTML 400 instead of a JSON 400, and Jackson 3's read
  limits (nesting 500, numbers ≤ 1000 digits) turn absurd bodies into a JSON parse 400 rather than our
  own 400. Our own messages (`speaker not found`, …) are unchanged. `UNCONFIRMED`
- **D6** Spring 6+ stopped translating Postgres SQLSTATEs by vendor code (55P03 became
  `UncategorizedSQLException`). → Keep 5.3's translation with `src/main/resources/sql-error-codes.xml`
  (the PostgreSQL entry of Spring's own file), so `DuplicateKeyException`/`CannotAcquireLockException`
  handling and `CoSpeakersTest` stay as they were. `UNCONFIRMED`
- **D7** Small 2.7 → 4 differences restored by config, because they're cheap and visible to clients:
  `charset=UTF-8` on error responses (`CompatibilityConfig`), emoji written as `\uD83C\uDFA4` escapes,
  `/actuator/health` without the new probe groups, renamed `spring.web.error.*`/`spring.servlet.encoding.*`/
  `spring.jackson.json.read.*` keys (the old ones are silently ignored on Boot 4; found by running once
  with `spring-boot-properties-migrator`, which reports nothing left now). `UNCONFIRMED`
- **D8** Hibernate Validator 9's `@NotBlank` also rejects a name that is only Unicode whitespace
  (`"\u2003"`, `"\u3000"`): 201 on 2.7, 400 now. → Accept it: the speakers spec asks for "a real name",
  so this is closer to the spec, and `\u00a0`/tab/newline behave as before. The only status change
  found (`diff-upgrade` lists it). `UNCONFIRMED`
- **D9** Tomcat 11 throws on a query/form parameter it can't decode (`?sort=%zz`) and accepts only 1000
  parameters; Tomcat 9 dropped the bad parameter and accepted 10000. → Restore both
  (`CompatibilityConfig#lenientParametersFilter`, `server.tomcat.max-parameter-count=10000`), so these
  stay 200 and don't log an ERROR stack trace. `UNCONFIRMED`

## Concurrency and data

No schema change. Same pool (5), same transactions and row locks; tests for them run unchanged.

## Out of scope

A form-encoded `PUT`/`DELETE` with a malformed body (`score=%zz`) answers 500 before the controller
runs, on 2.7 as on 4 (found by review-edge; not a regression, worth its own fix).
Refactoring the legacy code, Jakarta-only cleanups beyond what compiles, new endpoints.
