# Co-speakers implementation decisions

Requirements: released co-speakers v1 snapshot. These choices fill in unspecified API behavior; they do not change the workflow.

## Edge cases and status codes (before implementation)
- PUT and DELETE return 200 and the full current Talk, including ordered `coSpeakerIds`; all existing Talk-returning routes include this field, with [] for no co-speakers.
- Missing talk: 404, checked before speaker validation. Missing registered speaker: 404 for either operation. Non-numeric/out-of-range path integers: Spring binding returns 400; numeric IDs with no matching resource (including zero/negative) return 404.
- PUT of the main speaker: 400. PUT of a third distinct co-speaker: 409. Duplicate PUT: 200 no-op, even at capacity; it must not reorder entries.
- DELETE of a registered speaker not attached to the talk (including the main speaker): 200 no-op. The main speaker never changes.
- Removing then re-adding appends at the end. No body is required for either mutation; request bodies have no effect.
- Co-speaking does not affect main-speaker filters, own talk lists, submission quotas, or ratings. No restriction based on the co-speaker's submission count.

## Persistence / concurrency
- Add an idempotent schema extension with foreign keys to talk/speaker, unique (talk, speaker), and a sequence-backed addition order; existing talks naturally have an empty list.
- Every co-speaker mutation locks its talk row FOR UPDATE within a transaction before checking membership/capacity. Different talks remain independent. Concurrent additions cannot exceed two; duplicate additions are no-ops; additions/removals serialize. Never use advisory locks.
- Tests: HTTP coverage of ordered values on every Talk route, duplicate/capacity/self/missing/invalid paths, deletion/re-addition, quotas in both directions, and concurrent distinct/duplicate/mixed mutations with persisted-state assertions.

## Hardening (lock timeout, isolation, idempotency)

### Lock timeout: 2 s, then 503 + `Retry-After: 1`
- Each co-speaker mutation runs `SET LOCAL lock_timeout = '2s'` inside its transaction, right before
  `SELECT id FROM talk WHERE id = ? FOR UPDATE`. `SET LOCAL` ends with the transaction (commit or
  rollback); a session `SET` would stay on the pooled connection for whoever borrows it next.
  `TalkDao.lock` refuses to run outside a transaction, where `SET LOCAL` would do nothing.
- Why 2 s: a normal co-speaker transaction holds the lock for a few milliseconds, so 2 s waiting means
  something is stuck. Without a limit, each waiting request holds one of only 5 pooled connections,
  and a single stuck lock could use them all up and stall the whole service. 2 s is far below
  client/platform timeouts and long enough to absorb normal queueing.
- Worst case is ~2 × 2 s, not 2 s: Postgres applies `lock_timeout` to each lock wait separately.
  With several waiters on one row, the first waits on the holder's transaction; the others first
  queue for the row's tuple lock (≤ 2 s), then wait on the holder (≤ 2 s). Found when CI answered a
  queued DELETE with 503 after 4,002 ms; the test bound was 4,000 ms and is now < 5,500 ms. Still
  under the 5 s `/health` limit, though only just, if all 5 pooled connections are stuck waiting.
- Timeout → Postgres SQLSTATE `55P03` (lock_not_available). Spring 5.3 translates it to
  `CannotAcquireLockException`; a test checks this against real Postgres. The service doesn't rely on
  the exception type, though: it looks for SQLSTATE `55P03` in the cause chain and only then throws
  `LockTimeoutException`. Anything else (deadlock `40P01`, serialization failure `40001`, constraint
  violations) is rethrown unchanged.
- `LockTimeoutException` is a `ResponseStatusException(503, "talk is busy, retry shortly")` with
  `Retry-After: 1`. The body therefore uses Spring Boot's normal error shape
  (`timestamp, status, error, message, path`), like every other error in the app. 503 + Retry-After
  means "temporary, retry": nothing was changed (the transaction rolls back), and retrying is safe
  because PUT/DELETE are idempotent. The timeout can also hit a later statement in the same
  transaction (e.g. the FK check on a speaker row locked by a concurrent talk submission). That
  answers 503 in the same way.

### Explicit READ COMMITTED
- `addCoSpeaker` and `removeCoSpeaker` use `@Transactional(isolation = Isolation.READ_COMMITTED)`.
  The capacity check follows lock-then-read: lock the talk row, then read the current members. That's
  only correct if the read sees changes committed while this transaction waited for the lock.
  READ COMMITTED takes a new snapshot per statement, so it does. Under REPEATABLE READ the snapshot is
  taken at the first query (the FOR UPDATE itself, before the wait). Queued additions would then see
  stale membership and go past the 2-member cap. Verified: with the annotation removed and the
  database default set to REPEATABLE READ, 4 queued PUTs gave 4 × 200 instead of 2.
- Spring's `DataSourceTransactionManager` sets the level on the connection and restores the previous
  one afterwards. Tests check every pooled connection afterwards: the database default is kept
  (`read committed` normally, `repeatable read` in the REPEATABLE READ test), and `lock_timeout` is `0`.

### Database-level idempotency
- `TalkDao.addCoSpeaker` uses `INSERT ... ON CONFLICT (talk_id, speaker_id) DO NOTHING`. A duplicate
  does nothing: no error, and the existing row and its `added_order` stay as they were. The service
  already skips duplicates under the lock; this is a second line of defence for other callers.

### Tests (real Postgres via Testcontainers)
- `CoSpeakersTest`:
  - Spring translates 55P03 → `CannotAcquireLockException`.
  - The SQLSTATE check accepts only 55P03.
  - A lock held on a separate JDBC connection makes a PUT and a DELETE both answer 503 +
    `Retry-After: 1` with the standard error body after ~2–4 s (bound < 5.5 s, see above), and
    nothing changes. After rollback,
    the same PUT answers 200.
  - Forced overlap: two PUTs are confirmed waiting via `pg_stat_activity` (`wait_event_type = 'Lock'`).
    After the lock is released, both answer 200, in lock order, with no duplicates.
  - No-leak check: 8 concurrent borrowers on the 5-connection pool, all 5 backends seen.
  - Calling the DAO add twice doesn't change `added_order`.
- `CoSpeakersRepeatableReadTest` (its own database and Spring context): `ALTER DATABASE ... SET
  default_transaction_isolation = 'repeatable read'` runs before the app connects. 4 PUTs queued
  behind a held lock → no 5xx, exactly 2 × 200, 2 rows, and pooled connections still at the default.
- Every one of these tests failed when its change was temporarily reverted:
  - lock_timeout removed → PUT hangs (test times out);
  - `SET` instead of `SET LOCAL` → `2s` leaks;
  - no mapping → 500;
  - no explicit isolation → 4 accepted;
  - no ON CONFLICT → `DuplicateKeyException`.

## Independent review

A visible Pi `reviewer` subagent inspected the implementation against the released request,
including transaction boundaries, capacity/idempotency, ordering, response hydration, and quota
isolation. No blocking defects were found. A separate full `./mvnw -B -q test` run by the parent
completed successfully: 29 tests, 0 failures/errors, 1 existing skip. `git diff --check` passed.

Nonblocking gaps: schema replay is not an actual legacy-database upgrade/restart test;
per-talk membership hydration adds N queries to list operations. No acceptance, deployment,
or claim is implied by this local review.

### Concurrency hardening review (lock timeout, explicit isolation, ON CONFLICT)

Implemented by the project `worker` subagent and reviewed read-only by the project `reviewer`
subagent, both on `claude-opus-5-5`: no blocking findings. The parent's full `./mvnw -B -q test`
run passed: 35 tests, 0 failures/errors, 1 existing skip (CoSpeakersTest 11,
CoSpeakersRepeatableReadTest 1). Lock contention is now forced deterministically: tests hold the
talk row lock on a separate connection and wait on `pg_stat_activity` before releasing it. That
supersedes the earlier gap noting the async tests didn't guarantee overlap.

Remaining low-severity gaps from that review: the pooled-connection check can't detect an
isolation leak when the leaked level equals the database default (only the REPEATABLE READ test
can); the 503 path for a lock timeout on the speaker foreign key, and rethrowing non-55P03 errors
end to end, are covered only by a unit test of the SQLSTATE check; the timing bounds (1.5 s poll,
< 4 s) could be tight on a heavily loaded CI machine; older concurrency tests use `join()` without
a timeout; nothing was tested against the deployed database.

## Review / handoff
- Run ./mvnw test using the available Podman Docker socket. Review only feature changes against the request and preserve pre-existing work.
- No feature claim, commit, push, deployment, credential access, or changes to protected workflow/state files.

### Evidence from the initial working-tree handoff (before the hardening above)
- Final `./mvnw test`: BUILD SUCCESS; 29 tests, 0 failures, 0 errors, 1 skipped. All 6 CoSpeakersTest integration tests passed using real Postgres via Testcontainers/Podman. The existing skip is UtilsTest.nowIsCurrentTime (`flaky on Jenkins`). Reports: `target/surefire-reports/`; command output: `/tmp/team9-co-speakers-test-final.log`.
- Added schema replay test: applying schema.sql twice preserves empty talks and existing ordered memberships.
- `git diff --check` passed. Reviewed the feature diff against each rule: immutable main speaker, registered co-speakers, two-member cap, no duplicate/reorder, deletion/re-addition order, every Talk response, main-only lists and quotas, and serialized concurrent mutations.
- Existing/unrelated changes were preserved. Other edits appeared in the working tree during the run; this handoff concerns only the co-speaker paths/hunks.
- No blocking requirements questions. Human review should confirm the unspecified 400/404/409 and no-op choices above. This evidence is not harness acceptance.
- Risks: the two-member cap is enforced by the transactional service and talk-row lock, not a database constraint (direct SQL bypasses it). Talk list hydration adds one membership query per talk, matching the existing per-talk ratings pattern; large-list performance is not load-tested. Schema replay was tested locally, not against the deployed persistent database. Tests ran on the available Java 23 runtime with the project's Java 11 compilation target.
