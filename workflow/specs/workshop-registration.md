# Workshop registration (`workshop-registration`)

Feature request: [`specs/workshop-registration.md`](../../specs/workshop-registration.md), version 2.
Status: in-review

## Rules

- **R1** An attendee registers for a workshop with a name and an email; both are required.
  `POST /workshops/{id}/registrations` answers 201 with `{id, workshopId, name, email}`.
  Tests: `WorkshopRegistrationTest#registersAndListsInOrder`, `examples/workshop-registration.hurl` "R1".
- **R2** Only a talk with format `WORKSHOP` whose status is `ACCEPTED` takes registrations.
  Tests: `#onlyAcceptedWorkshopsTakeRegistrations`, Hurl "R2".
- **R3** If a workshop is withdrawn after people registered, they stay on its list (and it takes no
  new registrations, by R2). Tests: `#withdrawnWorkshopKeepsItsList`, Hurl "R3".
- **R4** A workshop has at most 20 registrations, also when many people register at the same
  moment. Tests: `#twentySeatsAndNoMore`, `#rushNeverExceedsTwentySeats`, Hurl "R4".
- **R5** One registration per email per workshop; emails are the same whatever the letter case
  (`ada@example.com` = `ADA@Example.com`), also under concurrent requests. The same person may
  register for several workshops. Tests: `#oneRegistrationPerEmailIgnoringCase`,
  `#samePersonCanRegisterForSeveralWorkshops`, `#simultaneousDuplicatesRegisterOnce`, Hurl "R5".
- **R6** `GET /workshops/{id}/registrations` lists the registrations in the order they were made,
  each `email` as the attendee typed it. Tests: `#registersAndListsInOrder`, Hurl "R6".

## API

| Method and path | Status | When |
|---|---|---|
| `POST /workshops/{id}/registrations` | 201 | registered; body is the `Registration` |
| | 400 | body missing or not JSON; `name` missing, null, blank, not a JSON string (D9) or containing a NUL character; `email` missing, null, blank, not a JSON string (D9), not an email or without a dot in its domain (D1); non-numeric `{id}`. Checked before the workshop is looked up (D10). |
| | 404 | no talk with that id (D2) |
| | 409 | the talk isn't a `WORKSHOP` (D2); the workshop isn't `ACCEPTED` (D3); 20 registrations already (D4); this email (any case) is already registered for it (D5) |
| | 503 | the workshop row stayed locked for 2 s (`Retry-After: 1`, as co-speakers). Test: `#lockedWorkshopAnswers503AndChangesNothing` |
| `GET /workshops/{id}/registrations` | 200 | the list, oldest first; `[]` when none; for any status of the workshop (D6), and `[]` for a talk that isn't a `WORKSHOP` (D2) |
| | 400 | non-numeric `{id}` |
| | 404 | no talk with that id (D2) |

Framework defaults, as on every other endpoint: 415 for a `Content-Type` that isn't JSON, 405 for
any other method on the path.

No existing response changes: `Talk` gets no new field.

## Decisions

- **D1** What's a valid email? → Speaker registration's check (`@NotBlank @Email`) **plus a dot in
  the domain**, so `a@b` and `a@localhost` are 400 (`mail.example.co.uk` is fine). Overridden in
  review: the first answer was "exactly as speakers", which accepts `a@b`. `CONFIRMED by Espen Myklevoll`
- **D2** A talk that exists but isn't a `WORKSHOP` → **409 on POST** (the talk exists, but only
  workshops take registrations, the same rule as D3) and **200 `[]` on GET** (it exists and has no
  registrations). 404 only when no talk has that id. First answer was 404 on both; the harness's
  workshop-registration/check-2 rejected the POST 404 at tick 107, then, once that was fixed, the
  GET 404 at tick 125 (lessons.md L9). `CONFIRMED by Espen Myklevoll` (as 404; changed after the FAULTY check)
- **D3** A workshop that isn't `ACCEPTED` (`SUBMITTED`, `WAITLISTED`, `REJECTED`, `WITHDRAWN`) →
  409: the request is well-formed and the state forbids it (house convention). `CONFIRMED by Espen Myklevoll`
- **D4** The 21st registration → 409, nothing stored (house convention: a limit reached). `CONFIRMED by Espen Myklevoll`
- **D5** Registering an email again for the same workshop, in any letter case → 409, nothing
  stored. The request says "one registration per email", not that a repeat "changes nothing", so
  it's a duplicate, not a no-op (house convention; lessons.md L3 on not assuming no-ops). The
  check runs before the capacity check, so a duplicate on a full workshop is still 409. `CONFIRMED by Espen Myklevoll`
- **D6** Listing a workshop that isn't accepted → 200 with its registrations (`[]` if none), so
  a withdrawn workshop's list stays readable (R3). `CONFIRMED by Espen Myklevoll`
- **D7** Emails are compared with `lower(email)`, as speakers are (`ux_speaker_email`); no other
  normalisation (whitespace is already rejected by `@Email`). Stored and returned as typed. `CONFIRMED by Espen Myklevoll`
- **D8** Registration ids come from one sequence across all workshops; the list is ordered by it,
  which is registration order. `CONFIRMED by Espen Myklevoll`
- **D9** `name` or `email` given as a number, boolean, array or object → 400 (house convention:
  wrong type). Jackson would otherwise turn `123` into `"123"` and store it. Stricter than speaker
  registration, which still coerces. `CONFIRMED by Espen Myklevoll`
- **D10** A bad body on an unknown or non-workshop id → 400, not 404: the body is validated before
  the workshop is looked up, as Spring does for every endpoint. `CONFIRMED by Espen Myklevoll`
- **D11** Who may register → anyone; the request has no owner or caller identity. `CONFIRMED by Espen Myklevoll`

## Concurrency and data

- New table `workshop_registration (id SERIAL, talk_id → talk, name, email, registered_at)` with a
  unique index on `(talk_id, lower(email))`. Additive and idempotent in `schema.sql`.
- A registration locks the talk row (`TalkDao.lock`: `SET LOCAL lock_timeout = '2s'`, then
  `SELECT … FOR UPDATE`) in a `READ_COMMITTED` transaction, then checks status, duplicate and count,
  then inserts. Registrations for one workshop are serialised, so the count check can't race; a
  decision or withdrawal on the same talk waits for it too. The unique index is the backstop for
  duplicates (a violation answers 409). Different workshops don't block each other.
- The pool has 5 connections; each registration holds its lock for a few ms.

## Out of scope

Cancelling a registration, changing the 20-seat capacity, and showing registrations on `Talk`.

Known limits, the same as speaker registration: no length limit on `name`; a name of only
non-breaking spaces passes `@NotBlank`; letter case beyond ASCII (`ÅSE` vs `åse`) follows the
database's locale for `lower()`.
