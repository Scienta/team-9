# Ratings change request: trimmed average, 2 decimals (`ratings-cr`)

Feature request: [`specs/ratings-cr.md`](../../specs/ratings-cr.md), version 1. Changes
[`specs/ratings.md`](../../specs/ratings.md); everything else about ratings stays as it is.
Status: in-review

## Rules

- **R1** With fewer than 5 ratings, a talk's `averageRating` is the mean of every score, as before.
  Tests: `RatingsCrTest#fewerThanFiveRatingsCountEveryScore`, `examples/ratings-cr.hurl` "R1".
- **R2** With 5 or more ratings, the average leaves out exactly one highest and exactly one lowest
  score, also when several reviewers gave the same top or bottom score; the mean is over the other
  `n - 2`. Tests: `#fiveOrMoreRatingsDropOneHighestAndOneLowest`, `#tiedExtremesDropOnlyOneEach`, Hurl "R2".
- **R3** `averageRating` is rounded to 2 decimals with ties to the even neighbour (banker's
  rounding): `3.125` → `3.12`, `3.375` → `3.38`. This applies at any number of ratings, so
  `4, 4, 4, 5` (4.25) now shows `4.25`, no longer `4.3`. Tests: `#roundsToTwoDecimalsHalfEven`, Hurl "R3".
- **R4** `ratingCount` still counts every reviewer, including the two left out of the average.
  Tests: `#fiveOrMoreRatingsDropOneHighestAndOneLowest`, Hurl "R4".
- **R5** `GET /talks?sort=rating` ranks by the new, rounded average; ties and unrated talks as
  before (more reviewers first, then oldest first; unrated last). Tests: `#rankingUsesTheTrimmedAverage`, Hurl "R5".
- **R6** A talk with no ratings still has no average (`null`) and `ratingCount` 0.
  Tests: `#unratedHasNoAverageAndNumbersHaveNoTrailingZeros`, Hurl "R6".

## API

No new endpoints and no new status codes. Every endpoint that shows a `Talk` shows the new
`averageRating`: `POST /talks`, `GET /talks/{id}`, `GET /talks`, `GET /talks?sort=rating`,
`GET /speakers/{id}/talks`, and the `Talk` returned by co-speakers, acceptance and withdraw.

| Method and path | Status | When |
|---|---|---|
| `GET /talks/{id}` | 200 | the talk with the new `averageRating` (R1-R4, R6) |
| `GET /talks` | 200 | with `sort=rating`, ranked by the new average (R5) |
| `PUT /talks/{id}/ratings/{reviewerId}` | 200 | unchanged: the `Rating`; validation and statuses as the ratings spec |

Admin reports, not in any spec: `/admin/talks.csv`'s rating column shows the new average with a
decimal comma (`4,25`), and `/admin/stats` (D2).

## Decisions

- **D1** How a 2-decimal average is written in JSON → as a JSON number with no trailing zeros:
  `4.25`, `4.5`, `4.0`, never `4.50`. Rounding to 2 decimals fixes the value, and JSON numbers
  compare by value; this is how `averageRating` is written today. `CONFIRMED by Henrik Otgard`
- **D2** `/admin/stats`'s `averageRating` (the mean of the talks' averages, an admin report outside
  the specs) → the mean of the new per-talk averages, still rounded to 1 decimal half up as today:
  "everything else stays as it is", and the request only changes the per-talk average. `CONFIRMED by Henrik Otgard`
- **D3** The CSV export's rating column → the new per-talk average, decimal comma, no trailing zeros
  (`4,25`, `4,5`), as today's column follows `averageRating`. `CONFIRMED by Henrik Otgard`
- **D4** The rounding is done on the exact fraction (sum / count with integer sum and count), not on
  a floating-point mean, so a true tie like 25/8 = 3.125 always rounds to the even neighbour and
  10/3 = 3.333… to 3.33. A tie at the third decimal needs a divisor with only 2s and 5s beyond 2 and 4
  (8, 16, 40, …), so it can only happen with 10, 18, 42, … ratings (after the trim); 43/40 = 1.075 is
  the case a floating-point mean gets wrong (1.07). `/admin/stats` sums the talk averages exactly
  too. `CONFIRMED by Henrik Otgard`
- **D5** "Ties" in the ranking are talks whose **rounded** averages are equal (4.333… and 4.33 tie),
  then the talk with more reviewers (`ratingCount`, every reviewer) first, then the oldest: the
  ratings spec's tie-breaks, applied to the new average. `CONFIRMED by Henrik Otgard`
- **D6** Replacing a rating (the same reviewer again) can move which score is highest or lowest; the
  average is always computed from the current scores, so the trimmed pair follows. `CONFIRMED by Henrik Otgard`
- **D7** No other ratings behaviour changes: the 1-5 score, positive reviewer ids, one rating per
  reviewer, `ratedAtDisplay`, the acceptance rule of 2 different reviewers, and every status code.
  `CONFIRMED by Henrik Otgard`

Checklist items with nothing to decide: unknown ids, malformed input, idempotency, undo, state,
who may do it (no change to any endpoint's input); joins (no new join: the count, sum, highest and
lowest come from the same per-talk subquery as today).

## Concurrency and data

- No schema change. `TalkDao`'s per-talk subquery adds `MAX(score)` and `MIN(score)` next to the
  `COUNT` and `SUM` it has; all four come from one statement, so they agree with each other.
- Ratings already insert-or-replace in one statement (ratings spec); a talk read concurrently with a
  rating sees it before or after, never half.
- `/admin/stats` already reads one REPEATABLE READ snapshot.

## Out of scope

Changing the 1-5 scale, weighting reviewers, or showing which scores were left out.
