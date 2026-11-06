# spring-boot-4

Owner: Jonathan Lillesæter (pi-sb4)
State: in-progress
Branch: feat/spring-boot-4
PR: -

## Log

- 2026-11-06T12:27Z Jonathan Lillesæter (pi-sb4): started
- 2026-11-06T12:29Z Jonathan Lillesæter (pi-sb4): team spec: 7 rules, 5 decisions UNCONFIRMED (0 for the PO)
- 2026-11-06T12:41Z Jonathan Lillesæter (pi-sb4): tests+code: ./mvnw package 45 tests 0 failures (Java 25); hurl 6/6 files 65 requests OK local + docker image; diff-upgrade 163 reqs 0 status diffs; UpgradeTest fails 4/5 with compat removed
- 2026-11-06T12:56Z Jonathan Lillesæter (pi-sb4): review fixes: rebased on main (UpgradeContractTest 12/12 on Boot 4); Tomcat lenient params + max-parameter-count; verify ERROR_LINE for Boot 4 logs; ./mvnw clean package 70 tests 0 failures; diff-upgrade 172 reqs, 1 status diff (D8); hurl 6/6 files 66 reqs local + amd64 image; retro L5
