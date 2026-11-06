# Safety

- `.fagdag/harness-token` is a secret. Never print it, echo it, put it on a command line, commit it
  or paste it into a PR or comment. Use `workflow/bin/harness <path>` for every harness call: it
  reads the token itself and handles the rate limit.
- Don't touch the committed files in `.fagdag/`, the repo secrets or variables, or anything outside
  this team's service (AGENTS.md "Hands off").
- Don't edit `.github/workflows/deploy.yml` from a feature branch. A broken deploy loses ticks;
  changes to it are their own PR, with the reason.
- Schema changes must be additive and safe on the production data, which survives deploys:
  `CREATE TABLE IF NOT EXISTS`, `ALTER TABLE … ADD COLUMN IF NOT EXISTS` with a default. Never drop
  or rewrite existing columns.
- The harness API is rate-limited (bursts of 5, then 1/s). Don't poll it faster than once a minute
  in a loop.
