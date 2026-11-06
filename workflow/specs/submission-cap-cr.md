# Submission cap change request (`submission-cap-cr`)

Feature request: [`specs/submission-cap-cr.md`](../../specs/submission-cap-cr.md), version 1 (V: 2).
Changes: [Talk submissions](../../specs/talks.md) (the 3-talk limit) and
[Co-speakers](../../specs/co-speakers.md) (replaces "co-speaking doesn't count").
Status: in-review

## Rules

A speaker's **load** is the number of talks they present that are **in the running**: talks whose
status is `SUBMITTED`, `WAITLISTED` or `ACCEPTED`, where they are the main speaker (`talk.speaker_id`)
or a co-speaker (`talk_co_speaker`). `REJECTED` and `WITHDRAWN` talks are out of the running.

- **R1** A speaker's load counts the talks they co-speak on as well as their own: a speaker who is main
  speaker on 2 talks and co-speaker on 1 (all in the running) can't submit another talk (409).
  Tests: `SubmissionCapTest#coSpeakingCountsTowardsTheCap`, Hurl "R1".
- **R2** A talk that is `REJECTED` or `WITHDRAWN` doesn't count, for the main speaker or its
  co-speakers: after one of a speaker's 3 talks is withdrawn (or rejected), they can submit again at
  once, and a co-speaker on it can be added to another talk at once.
  Tests: `#withdrawnAndRejectedTalksDontCount`, Hurl "R2".
- **R3** Removing a speaker as co-speaker (`DELETE /talks/{id}/co-speakers/{speakerId}`) frees their
  place at once. Tests: `#removingACoSpeakerFreesTheirPlace`, Hurl "R3".
- **R4** A speaker whose load is 3 or more can't submit a new talk: `POST /talks` answers 409 and
  creates nothing. Tests: `#atTheCapSubmitIs409`, Hurl "R4".
- **R5** A speaker whose load is 3 or more can't be added as co-speaker to a talk in the running:
  `PUT /talks/{id}/co-speakers/{speakerId}` answers 409 and changes nothing.
  Tests: `#atTheCapAddingAsCoSpeakerIs409`, Hurl "R5".
- **R6** A speaker at or over the cap can still be added as co-speaker to a `REJECTED` or `WITHDRAWN`
  talk (it doesn't count). The 2-co-speaker limit per talk and the other co-speaker rules still apply.
  Tests: `#atTheCapCanJoinAnOutOfTheRunningTalk`, Hurl "R6".
- **R7** The cap holds under concurrent requests: any mix of simultaneous submissions by a speaker and
  co-speaker additions of that speaker (to different talks) never takes their load above 3.
  Tests: `#concurrentSubmitsAndInvitationsNeverExceedTheCap` (several rounds, L5).
- **R8** Nothing that exists is removed or changed: a speaker already over the cap (load > 3, e.g. from
  co-speaking under the old rule) keeps every talk and co-speakership, and gets nothing new (R4, R5)
  until their load is below 3. Tests: `#overTheCapKeepsEverythingButGetsNothingNew`.
- **R9** `GET /speakers/{id}/talks` (and `GET /talks?speakerId=`) still list only talks where the
  speaker is main speaker. Tests: `#speakerTalkListStillMainOnly`, Hurl "R9".

## API

No new endpoints, no new fields. Changed statuses:

| Method and path | Status | When |
|---|---|---|
| `POST /talks` | 201 | valid, and the speaker's load is below 3 (R1, R2) |
| | 400 | as before (missing/invalid title, format, level, `speakerId`; unknown `speakerId`) |
| | 409 | the speaker's load is 3 or more (R4, R8): nothing created |
| `PUT /talks/{id}/co-speakers/{speakerId}` | 200 | added; or already a co-speaker (no-op, D2); or the talk is out of the running (R6) |
| | 400 | `speakerId` is the talk's main speaker (as before); non-numeric path id (as before) |
| | 404 | talk or speaker doesn't exist (as before) |
| | 409 | the talk already has 2 co-speakers (as before); **new:** the talk is in the running and the speaker's load is 3 or more (R5) |
| | 503 | talk or speaker row lock not obtained within 2 s (as before, `Retry-After: 1`) |
| `DELETE /talks/{id}/co-speakers/{speakerId}` | unchanged | now also frees the speaker's place (R3) |
| `POST /talks/{id}/withdraw`, `PUT /talks/{id}/status` | unchanged | `WITHDRAWN`/`REJECTED` now frees a place for the main speaker and every co-speaker (R2) |

No response body changes. `/admin/stats` (`speakersWithTalks`) is about main speakers and doesn't change.

## Decisions

- **D1** Which statuses count? → `SUBMITTED`, `WAITLISTED`, `ACCEPTED` count; `REJECTED` and `WITHDRAWN`
  don't, because the request names exactly those two as "out of the running", and a waitlisted talk can
  still be accepted. `CONFIRMED by Gjermund Karlsen Lie`
- **D2** `PUT` of a speaker who is already a co-speaker on that talk, while their load is 3 or more →
  no-op 200 with the talk, because the house convention makes a repeated add a no-op, the talk is
  already in their load (nothing new), and the existing order of checks puts the duplicate check before
  the capacity checks. Order: talk 404 → speaker 404 → main speaker 400 → already a co-speaker: 200
  no-op → talk has 2 co-speakers: 409 → talk in the running and speaker load ≥ 3: 409 → add.
  Nothing can make a repeat fail later: a talk only leaves the running, never re-enters (item 13, L10).
  `CONFIRMED by Gjermund Karlsen Lie`
- **D3** Status for "at the cap" on both endpoints → 409, the house convention for a limit reached and
  what `POST /talks` already answers. Message: `speaker <id> already presents 3 talks` (submit's old
  message "a speaker can submit at most 3 talks" is replaced; the status is what's checked). `CONFIRMED by Gjermund Karlsen Lie`
- **D4** When both the talk's 2-co-speaker limit and the speaker's cap apply → the talk's limit is
  checked first; both are 409, only the message differs. `CONFIRMED by Gjermund Karlsen Lie`
- **D5** Whose cap does adding a co-speaker check? → only the added speaker's. The talk is already in
  the main speaker's and the other co-speakers' load, so nothing changes for them. `CONFIRMED by Gjermund Karlsen Lie`
- **D6** Can a talk re-enter the running and push someone over? → No: `REJECTED` and `WITHDRAWN` are
  final (acceptance spec), and every other transition stays inside the running. So decisions and
  withdrawals never check the cap, and an accept doesn't re-check it. `CONFIRMED by Gjermund Karlsen Lie`
- **D7** Is the load stored or computed? → computed on each check from `talk` and `talk_co_speaker`
  (no counter column), so existing production data (withdrawn talks, old co-speakerships) is counted
  correctly from the first deploy with no migration, and R8 needs no special case. `CONFIRMED by Gjermund Karlsen Lie`
- **D8** Lock timeout on `POST /talks` → unchanged: no `lock_timeout` on submit (it waits as today),
  while `PUT co-speakers` keeps its 2 s / 503. A 503 on a base-feature submit under a burst risks the
  talks checks; a submit holds the speaker lock for milliseconds. `CONFIRMED by Gjermund Karlsen Lie`
- **D9** Malformed input, unknown ids → unchanged from the talks and co-speakers decisions (400 in the
  body, 404 in the path). Nothing new is parsed. `CONFIRMED by Gjermund Karlsen Lie`

No business rule is ambiguous enough to need the product owner.

## Concurrency and data

What races: two submissions by the same speaker; a submission and a co-speaker addition of that
speaker; two co-speaker additions of the same speaker to different talks. (Additions to the *same*
talk are already serialized by the talk row lock.) Withdraw, reject and co-speaker removal only lower a
load, so they can't break the cap.

- **One lock per speaker:** both paths take `SELECT id FROM speaker WHERE id = ? FOR UPDATE` on the
  speaker whose load is checked, then count, then write, in one transaction. Submit already locks the
  speaker row; `addCoSpeaker` adds it, after the talk lock (order: talk → speaker). Submit takes no
  talk lock (its talk is new), and no path takes a speaker lock before a talk lock, so there is no
  lock-order cycle.
- **READ COMMITTED, pinned** on `submitTalk` as on `addCoSpeaker`: the count must see what committed
  while this transaction waited for the speaker lock. Under REPEATABLE READ (production's default may
  be stricter: the ratings upsert got `40001` there) the snapshot predates the wait and two queued
  submits both see room. `submitTalk` is plain `@Transactional` today, so this is also a fix to the
  base limit. Test: a REPEATABLE READ database default, as `CoSpeakersRepeatableReadTest` does.
- The count is a conditional write: one statement for submit (`INSERT … WHERE load < 3`), and count
  then insert under the speaker lock for co-speakers.
- **Schema (additive):** `CREATE INDEX IF NOT EXISTS ix_talk_co_speaker_speaker ON talk_co_speaker
  (speaker_id)`; the primary key `(talk_id, speaker_id)` can't serve a lookup by speaker.

## Out of scope

- Any change to what the talk lists, `/admin/stats` or the CSV show.
- Removing anything from speakers already over the cap.
