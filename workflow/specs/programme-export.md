# Programme export (`programme-export`)

Feature request: [`specs/programme-export.md`](../../specs/programme-export.md), version 1.
Builds on [scheduling](scheduling.md) (table `programme`, `Programme.SLOTS`) and co-speakers.
Status: draft

## Rules

- **R1** `GET /programme` answers a JSON array and `GET /programme.csv` a CSV file, each with exactly
  one row per talk in the `programme` table, and nothing else. Tests: `ProgrammeExportTest#oneRowPerScheduledTalk`,
  `examples/programme-export.hurl` "R1".
- **R2** Rows are ordered by slot start time (`S1` … `S5`, which is start order), then by room
  (`R1`, `R2`, `R3`); the JSON and the CSV have the same order. Tests: `#orderedBySlotThenRoom`, Hurl "R2".
- **R3** Each row has `slot`, `start`, `end` (`HH:mm`, as in scheduling R1), `room`, `talkId`,
  `title`, `format` and `speakers`, with the talk's title, format and speakers as they are at the
  time of the request (not when it was scheduled). JSON field order: as listed.
  Tests: `#rowShowsCurrentTalk`, Hurl "R3".
- **R4** `speakers` holds speaker names: the main speaker first, then the co-speakers in the order
  they were added. JSON: an array of strings. CSV: one field, names joined by `"; "`
  (`Grace Hopper; Ada Lovelace`). Tests: `#speakersMainThenCoInAddedOrder`, Hurl "R4".
- **R5** The CSV is UTF-8, `Content-Type: text/csv; charset=UTF-8`, whatever the `Accept` header
  (also `Accept: application/json`). Tests: `#csvIsTextCsvEvenWhenJsonIsAsked`, Hurl "R5".
- **R6** The CSV's first line is exactly `slot,start,end,room,talkId,title,format,speakers`; lines
  end in `\n` (no `\r`), every line including the last ends in `\n`, and there's no blank line at
  the end. Tests: `#csvHeaderAndLineEndings`, Hurl "R6".
- **R7** A CSV field is quoted only if it contains a comma, a double quote, `\n` or `\r`; inside
  quotes a `"` is doubled (`"Kotlin, ""really"" fast"`). Every other field is written as it is
  (spaces, semicolons and non-ASCII letters included). Tests: `#csvQuotesOnlyWhenNeeded`, Hurl "R7".
- **R8** An empty programme is `[]` in JSON and the header line alone (`…speakers\n`) in CSV.
  Tests: `#emptyProgramme`, Hurl (only when production's programme is empty, see Out of scope).

## API

| Method and path | Status | When |
|---|---|---|
| `GET /programme` | 200 | always; `application/json`, an array of `ProgrammeRow` (D3) |
| `GET /programme.csv` | 200 | always; `text/csv; charset=UTF-8` (R5, D4) |

No 400/404/409: both are reads with no parameters. Query parameters are ignored (D6). Framework
defaults as on every endpoint: 405 for another method (`POST /programme` etc.).

`ProgrammeRow`: `{"slot":"S4","start":"13:30","end":"15:30","room":"R2","talkId":17,"title":"…","format":"WORKSHOP","speakers":["Grace Hopper","Ada Lovelace"]}`.

No existing response changes. `/programme/{room}/{slot}` (scheduling) is untouched.

## Decisions

- **D1** A scheduled talk that's since been withdrawn (or otherwise changed status) → still exported:
  the request says "one row per scheduled talk" and scheduling R8/D11 keeps it on the programme
  until someone takes it out. `UNCONFIRMED (ask the product owner)`
- **D2** `format` is the enum value as the API writes it (`LIGHTNING_10`), in JSON and CSV, not the
  prettified form of `/admin/talks.csv` (`Lightning 10`): the request's schema is the enum.
  `UNCONFIRMED`
- **D3** `GET /programme` answers JSON whatever the `Accept` header (no 406 for `Accept: text/csv`):
  the request defines only one representation per path, and R5 does the same for the CSV.
  `UNCONFIRMED`
- **D4** The CSV's `Content-Type` carries `charset=UTF-8` (`text/csv; charset=UTF-8`): the media
  type is still `text/csv`, and the parameter makes the UTF-8 rule explicit (RFC 4180's registration
  of `text/csv` defines the parameter). Also `Content-Disposition: attachment; filename=programme.csv`, as `/admin/talks.csv`.
  No BOM: "starts with exactly this header line". `UNCONFIRMED`
- **D5** `talkId` in the CSV is the plain integer; `start`/`end` are `HH:mm` and never need quoting.
  A title with a line break is kept as is inside a quoted field (RFC 4180), not flattened.
  `UNCONFIRMED`
- **D6** Unknown query parameters (`?room=R1`) are ignored, 200 with the whole programme: there are
  no filters in the request. `UNCONFIRMED`
- **D7** A talk with no co-speakers has just the main speaker (`["Grace Hopper"]` / `Grace Hopper`).
  Every talk has a main speaker, so `speakers` is never empty. `UNCONFIRMED`
- **D8** Speaker names are the current names (`PUT /speakers/{id}` renames show up at once), per
  "as they are right now". `UNCONFIRMED`

Checklist items with nothing to decide: 1 (no ids), 2 (no input), 3 (no limits), 4-7 (read only,
no state, no owner), 10 (no old rule changes), 11 (reads only: each export is one consistent
`SELECT` snapshot, see below), 12 (times as scheduling R1).

## Concurrency and data

No schema change, no writes, no locks. Each export is two queries whatever the programme's size
(programme ⨝ talk ⨝ speaker; then the co-speakers of those talks ⨝ speaker, in added order), not
one per row (cf. the list-queries fix). Both run in one `REPEATABLE READ` read-only transaction so
a scheduling or co-speaker change landing between them can't give a row its co-speakers from a
moment later. One connection per request.

## Out of scope

Filters, other formats, paging, a BOM. The Hurl file runs against production, where the programme
is shared with the harness: it schedules its own talks into free places, checks they're in the
exports with the right order and quoting, and takes them out again; it doesn't assert the
programme is otherwise empty (R8 is covered by JUnit).
