# Scheduling (`scheduling`)

Feature request: [`specs/scheduling.md`](../../specs/scheduling.md), version 1.
Status: in-review

## Rules

- **R1** The venue is fixed: rooms `R1`, `R2`, `R3` and slots `S1` 09:00-10:00, `S2` 10:15-11:15,
  `S3` 11:30-12:30, `S4` 13:30-15:30, `S5` 15:45-16:45, the same in every room. Every `Entry` shows
  its room, slot, and `start`/`end` as `HH:mm` (24-hour, leading zero: `09:00`).
  Tests: `SchedulingTest#entryShowsRoomSlotAndTimes`, `examples/scheduling.hurl` "R1".
- **R2** `PUT /programme/{room}/{slot}` with `{talkId}` puts the talk there and answers 200 with
  the `Entry`; `GET` answers the `Entry` in it; `DELETE` takes the talk out. A talk that has been
  taken out can be put somewhere else. Tests: `#putGetDeleteAndPutElsewhere`, Hurl "R2".
- **R3** Only an `ACCEPTED` talk goes on the programme. Tests: `#onlyAcceptedTalks`, Hurl "R3".
- **R4** A `WORKSHOP` goes only in the long slot `S4`; every other format fits any slot,
  including `S4`. Tests: `#workshopsOnlyInTheLongSlot`, Hurl "R4".
- **R5** One talk per room and slot. Tests: `#oneTalkPerRoomAndSlot`, Hurl "R5".
- **R6** A talk is on the programme at most once; to move it, take it out first.
  Tests: `#aTalkIsOnTheProgrammeOnce`, Hurl "R6".
- **R7** Within one slot, the main speakers and co-speakers of all its talks are different people:
  a talk can't go in a slot where any of its speakers (main or co) already speaks.
  Tests: `#nobodySpeaksInTwoRoomsAtOnce`, Hurl "R7".
- **R8** The rules are checked when a talk is put on the programme. Later changes to the talk
  (withdrawal, co-speakers) leave the programme as it is. Tests: `#laterChangesLeaveTheProgramme`, Hurl "R8".
- **R9** Concurrent edits never leave the programme breaking R5, R6 or R7.
  Tests: `#rushForOnePlaceHasOneWinner`, `#rushOfOneTalkIntoManyPlacesHasOneWinner`,
  `#rushOfSharedSpeakersIntoOneSlotHasOneWinner`.

## API

| Method and path | Status | When |
|---|---|---|
| `PUT /programme/{room}/{slot}` | 200 | the talk is there; body is the `Entry`. Also when this exact talk is already in this exact place (D6) |
| | 400 | `{room}` or `{slot}` isn't one of the values, case-sensitive (D1); body missing or not JSON; `talkId` missing, null, not a whole number (string, decimal, boolean) or < 1 (D2) |
| | 404 | no talk with that `talkId` (D3) |
| | 409 | the talk isn't `ACCEPTED` (D4); a workshop outside `S4` (D5); another talk is in this place (R5); the talk is already somewhere else (R6, D6); one of its speakers already speaks in this slot (R7) |
| | 503 | the slot stayed locked for 2 s (`Retry-After: 1`, as co-speakers). Nothing changes. |
| `GET /programme/{room}/{slot}` | 200 | the `Entry` in this place |
| | 400 | bad `{room}` or `{slot}` (D1) |
| | 404 | nothing in this place (D7) |
| `DELETE /programme/{room}/{slot}` | 204 | the talk was taken out; no body (D8) |
| | 400 | bad `{room}` or `{slot}` (D1) |
| | 404 | nothing in this place, also when it was just taken out (D8, lessons.md L3) |

Framework defaults, as on every other endpoint: 415 for a `Content-Type` that isn't JSON, 405 for
any other method on the path.

`Entry`: `{"room":"R2","slot":"S4","start":"13:30","end":"15:30","talkId":17}`, in that order.

No existing response changes: `Talk` gets no new field.

## Decisions

- **D1** A room or slot that isn't one of the listed values (`R4`, `r1`, `S0`, `S6`) → 400 on every
  verb: the request defines them as enums, so it's malformed input (house convention: unknown enum
  value, case-sensitive). `UNCONFIRMED`
- **D2** `talkId` must be a JSON whole number ≥ 1. A string (`"5"`), decimal (`5.5` or `5.0`),
  boolean, null or missing `talkId` → 400 (house convention: wrong type; lessons.md L5 on Jackson
  coercion). Extra fields are ignored. `UNCONFIRMED`
- **D3** `talkId` that doesn't exist → 404, not 400: the talk is the subject of the action (the
  house convention's exception for body ids). `UNCONFIRMED`
- **D4** A talk that isn't `ACCEPTED` (`SUBMITTED`, `WAITLISTED`, `REJECTED`, `WITHDRAWN`) → 409
  (house convention: the state forbids it). `UNCONFIRMED`
- **D5** A workshop in any slot but `S4` → 409: the request is well-formed, the rule forbids it.
  `UNCONFIRMED`
- **D6** Putting the same talk in the same place again → 200 with the `Entry`, nothing changes:
  `PUT` is idempotent and the talk is still on the programme only once. This holds even if the
  talk changed since it was put there (withdrawn, or a co-speaker who now clashes, R8): the repeat
  changes nothing, so no rule is checked again. The same talk in a different place → 409 (R6: take
  it out first). `UNCONFIRMED (ask the product owner)`
- **D7** `GET` on an empty place → 404: there is no `Entry` to show (house convention: 404 for a
  path that names nothing). `UNCONFIRMED`
- **D8** `DELETE` answers 204 with no body: the request gives `DELETE` no response schema, unlike
  `PUT` and `GET`. An empty place, including a second `DELETE`, → 404 (house convention, lessons.md
  L3). `UNCONFIRMED (ask the product owner)`
- **D9** Which checks run first when several fail → path (400), body (400), talk exists (404),
  then the D6 repeat (200), status (409), fit (409), place free (409), talk not elsewhere (409),
  speakers (409). Every failure changes nothing. Spring answers 415 for a non-JSON `Content-Type`
  before any of these. `UNCONFIRMED`
- **D10** Who may change the programme → anyone; the request has no caller identity. `UNCONFIRMED`
- **D11** Withdrawing a scheduled talk, or changing its co-speakers, doesn't touch the programme
  (R8). A withdrawn talk stays in its place until someone deletes it. `UNCONFIRMED`

## Concurrency and data

- New tables, additive and idempotent in `schema.sql`:
  - `programme_slot (slot PRIMARY KEY)` seeded with `S1`-`S5` (`ON CONFLICT DO NOTHING`): one row
    per slot to lock. Room and slot values and times live in code (`Programme`), not the database.
  - `programme (room, slot → programme_slot, talk_id → talk, PRIMARY KEY (room, slot))` with a
    unique index on `talk_id`.
- A `PUT` runs in one `READ_COMMITTED` transaction: `SET LOCAL lock_timeout = '2s'`, then
  `SELECT … FROM programme_slot WHERE slot = ? FOR UPDATE`, then the checks, then the insert. All
  `PUT`s into one slot run one at a time, so R5 and R7 (both within a slot) can't race. R6 spans
  slots: the unique index on `talk_id` is what holds it (a violation answers 409), and the primary
  key backs up R5.
- `DELETE` is one statement (`DELETE … RETURNING`): removing never breaks a rule, so it takes no lock.
- The talk row isn't locked: by R8 a change to the talk racing with a `PUT` may land on either side.
- The pool has 5 connections; each `PUT` holds its slot lock for a few ms.

## Out of scope

Listing the whole programme, moving a talk in one request, changing rooms, slots or times.

The Hurl file runs against production, where the 15 places are shared with the harness: it takes
out everything it puts in, and a place the harness happens to be using at that moment can make it
fail with 409.
