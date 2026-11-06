# spring-boot-4

Owner: Jonathan Lillesæter (pi-sb4)
State: in-review
Branch: feat/spring-boot-4
PR: https://github.com/Scienta/team-9/pull/11

## Log

- 2026-11-06T12:27Z Jonathan Lillesæter (pi-sb4): started
- 2026-11-06T12:29Z Jonathan Lillesæter (pi-sb4): team spec: 7 rules, 5 decisions UNCONFIRMED (0 for the PO)
- 2026-11-06T12:41Z Jonathan Lillesæter (pi-sb4): tests+code: ./mvnw package 45 tests 0 failures (Java 25); hurl 6/6 files 65 requests OK local + docker image; diff-upgrade 163 reqs 0 status diffs; UpgradeTest fails 4/5 with compat removed
- 2026-11-06T12:56Z Jonathan Lillesæter (pi-sb4): review fixes: rebased on main (UpgradeContractTest 12/12 on Boot 4); Tomcat lenient params + max-parameter-count; verify ERROR_LINE for Boot 4 logs; ./mvnw clean package 70 tests 0 failures; diff-upgrade 172 reqs, 1 status diff (D8); hurl 6/6 files 66 reqs local + amd64 image; retro L5
- 2026-11-06T12:57Z Jonathan Lillesæter (pi-sb4): state -> in-review: PR https://github.com/Scienta/team-9/pull/11, 9 decisions to confirm; needs PR #5 (ci java-version-file) merged first
- 2026-11-06T12:57Z Jonathan Lillesæter (pi-sb4): retro L5: upgrade corpus didn't reach server/validator layers or log format; diff-upgrade corpus + verify ERROR_LINE + review.md, PR #11
- 2026-11-06T13:00Z Jonathan Lillesæter (pi-sb4): CI fix: PR #5 (deploy.yml/pr.yml read .java-version) folded into PR #11 at Jonathan's request, #5 closed; tests ran on JDK 11 before
- 2026-11-06T13:04Z Jonathan Lillesæter (pi-sb4): decisions D1-D9 confirmed by Jonathan; cross-checked team-7 upgrade: their submittedAt Z / submittedOn zone bug doesn't apply (TZ=Pacific/Kiritimati check), test added; 70 tests green
- 2026-11-06T13:06Z Jonathan Lillesæter (pi-sb4): PR #11 CI all green (tests on JDK 25, decisions, not-work); ready for a person to merge
