# Workshop registration (`workshop-registration`)

Feature request: [`specs/workshop-registration.md`](../../specs/workshop-registration.md), version 2.
Status: building

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
| | 400 | body missing or not JSON; `name` missing, null or blank; `email` missing, null, blank or not an email (D1); non-numeric `{id}` |
| | 404 | no talk with that id, or the talk isn't a `WORKSHOP` (D2) |
| | 409 | the workshop isn't `ACCEPTED` (D3); 20 registrations already (D4); this email (any case) is already registered for it (D5) |
| | 503 | the workshop row stayed locked for 2 s (`Retry-After: 1`, as co-speakers) |
| `GET /workshops/{id}/registrations` | 200 | the list, oldest first; `[]` when none; for any status of the workshop (D6) |
| | 400 | non-numeric `{id}` |
| | 404 | no talk with that id, or the talk isn't a `WORKSHOP` (D2) |

No existing response changes: `Talk` gets no new field.

## Decisions

- **D1** What's a valid email? → The same check as speaker registration (`@NotBlank @Email`), so
  both features accept and reject the same strings; invalid is 400 (house convention). `UNCONFIRMED`
- **D2** A talk that exists but isn't a `WORKSHOP` → 404 on both endpoints: the path names a
  workshop, and there is no workshop with that id (house convention: 404 for a path id that
  doesn't exist). `UNCONFIRMED`
- **D3** A workshop that isn't `ACCEPTED` (`SUBMITTED`, `WAITLISTED`, `REJECTED`, `WITHDRAWN`) →
  409: the request is well-formed and the state forbids it (house convention). `UNCONFIRMED`
- **D4** The 21st registration → 409, nothing stored (house convention: a limit reached). `UNCONFIRMED`
- **D5** Registering an email again for the same workshop, in any letter case → 409, nothing
  stored. The request says "one registration per email", not that a repeat "changes nothing", so
  it's a duplicate, not a no-op (house convention; lessons.md L3 on not assuming no-ops). The
  check runs before the capacity check, so a duplicate on a full workshop is still 409. `UNCONFIRMED`
- **D6** Listing a workshop that isn't accepted → 200 with its registrations (`[]` if none), so
  a withdrawn workshop's list stays readable (R3). `UNCONFIRMED`
- **D7** Emails are compared with `lower(email)`, as speakers are (`ux_speaker_email`); no other
  normalisation (whitespace is already rejected by `@Email`). Stored and returned as typed. `UNCONFIRMED`
- **D8** Registration ids come from one sequence across all workshops; the list is ordered by it,
  which is registration order. `UNCONFIRMED`

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
