# <Feature name> (`<slug>`)

Feature request: [`specs/<slug>.md`](../../specs/<slug>.md), version <V>.
Status: draft | building | in-review | merged | claimed

## Rules

Every business rule from the request, numbered, one testable sentence each. Cite the request's
wording when it's exact. Each rule names the tests that prove it.

- **R1** <rule>. Tests: `<JUnit class#method>`, `examples/<slug>.hurl` "R1 ...".
- **R2** ...

## API

Every endpoint, with every status code it can answer and why.

| Method and path | Status | When |
|---|---|---|
| `PUT /...` | 200 | ... |
| | 400 | ... |
| | 404 | ... |
| | 409 | ... |

Every existing endpoint whose response changes (for example every place a Talk is shown):

- `GET /talks/{id}`, `GET /speakers/{id}/talks`, `POST /talks`, ...: <what changes>

## Decisions

Everything the request leaves open, answered. One line each, with the reasoning. A decision stays
`UNCONFIRMED` until a person confirms it; CI blocks the merge while any is left.

- **D1** <question> → <answer>, because <reason>. `UNCONFIRMED`
- **D2** ...

When a person confirms: replace `UNCONFIRMED` with `CONFIRMED by <name>`. When they override:
change the answer, mark it `CONFIRMED by <name>`, and the agent updates code and tests to match.

## Concurrency and data

What can race, and how it's prevented (row lock, conditional write, unique constraint). Schema changes.

## Out of scope

What we deliberately don't do.
