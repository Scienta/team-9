# Ratings change request: a request per rule and decision (team spec: workflow/specs/ratings-cr.md).
# Run: hurl --test --variable base_url=http://localhost:8080 ratings-cr.hurl
# Production-safe: it only rates talks it creates itself.

POST {{base_url}}/speakers
{ "name": "Rated a", "email": "a-{{newUuid}}@example.com" }
HTTP 201
[Captures]
a: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Talk under_five", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{a}} }
HTTP 201
[Captures]
under_five: jsonpath "$.id"

PUT {{base_url}}/talks/{{under_five}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{under_five}}/ratings/2
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{under_five}}/ratings/3
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{under_five}}/ratings/4
{ "score": 5 }
HTTP 200

# R1, R3: fewer than 5 ratings, every score counts: 4, 4, 4, 5 = 4.25, now 2 decimals
GET {{base_url}}/talks/{{under_five}}
HTTP 200
[Asserts]
jsonpath "$.averageRating" == 4.25
jsonpath "$.ratingCount" == 4

POST {{base_url}}/talks
{ "title": "Talk thirds", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{a}} }
HTTP 201
[Captures]
thirds: jsonpath "$.id"

PUT {{base_url}}/talks/{{thirds}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{thirds}}/ratings/2
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{thirds}}/ratings/3
{ "score": 5 }
HTTP 200

# R3: 13 / 3 = 4.333... rounds to 4.33
GET {{base_url}}/talks/{{thirds}}
HTTP 200
[Asserts]
jsonpath "$.averageRating" == 4.33
jsonpath "$.ratingCount" == 3

POST {{base_url}}/talks
{ "title": "Talk unrated", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{a}} }
HTTP 201
[Captures]
unrated: jsonpath "$.id"

# R6: no ratings, no average
GET {{base_url}}/talks/{{unrated}}
HTTP 200
[Asserts]
jsonpath "$.averageRating" == null
jsonpath "$.ratingCount" == 0

POST {{base_url}}/speakers
{ "name": "Rated b", "email": "b-{{newUuid}}@example.com" }
HTTP 201
[Captures]
b: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Talk five", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{b}} }
HTTP 201
[Captures]
five: jsonpath "$.id"

PUT {{base_url}}/talks/{{five}}/ratings/1
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{five}}/ratings/2
{ "score": 2 }
HTTP 200

PUT {{base_url}}/talks/{{five}}/ratings/3
{ "score": 2 }
HTTP 200

PUT {{base_url}}/talks/{{five}}/ratings/4
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{five}}/ratings/5
{ "score": 5 }
HTTP 200

# R2, R4: 5 ratings leave out one highest and one lowest: (2 + 2 + 4) / 3 (2.8 untrimmed); every reviewer still counted
GET {{base_url}}/talks/{{five}}
HTTP 200
[Asserts]
jsonpath "$.averageRating" == 2.67
jsonpath "$.ratingCount" == 5

POST {{base_url}}/talks
{ "title": "Talk tied", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{b}} }
HTTP 201
[Captures]
tied: jsonpath "$.id"

PUT {{base_url}}/talks/{{tied}}/ratings/1
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{tied}}/ratings/2
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{tied}}/ratings/3
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{tied}}/ratings/4
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{tied}}/ratings/5
{ "score": 1 }
HTTP 200

# R2: exactly one of each, even when several gave the same top or bottom score: (5 + 5 + 1) / 3
GET {{base_url}}/talks/{{tied}}
HTTP 200
[Asserts]
jsonpath "$.averageRating" == 3.67
jsonpath "$.ratingCount" == 5

# D6: reviewer 5 replaces their 5 with a 1; the scores are 1, 2, 2, 4, 1, so the 4 and one 1 are left out
PUT {{base_url}}/talks/{{five}}/ratings/5
{ "score": 1 }
HTTP 200

# D6: (1 + 2 + 2) / 3
GET {{base_url}}/talks/{{five}}
HTTP 200
[Asserts]
jsonpath "$.averageRating" == 1.67
jsonpath "$.ratingCount" == 5

POST {{base_url}}/speakers
{ "name": "Rated c", "email": "c-{{newUuid}}@example.com" }
HTTP 201
[Captures]
c: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Talk eighth_low", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{c}} }
HTTP 201
[Captures]
eighth_low: jsonpath "$.id"

PUT {{base_url}}/talks/{{eighth_low}}/ratings/1
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{eighth_low}}/ratings/2
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{eighth_low}}/ratings/3
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{eighth_low}}/ratings/4
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{eighth_low}}/ratings/5
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{eighth_low}}/ratings/6
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{eighth_low}}/ratings/7
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{eighth_low}}/ratings/8
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{eighth_low}}/ratings/9
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{eighth_low}}/ratings/10
{ "score": 4 }
HTTP 200

# R3, D4: 10 ratings, the 1 and the 5 left out, the other 8 sum to 25: 3.125 rounds to the even 3.12
GET {{base_url}}/talks/{{eighth_low}}
HTTP 200
[Asserts]
jsonpath "$.averageRating" == 3.12
jsonpath "$.ratingCount" == 10

POST {{base_url}}/talks
{ "title": "Talk eighth_high", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{c}} }
HTTP 201
[Captures]
eighth_high: jsonpath "$.id"

PUT {{base_url}}/talks/{{eighth_high}}/ratings/1
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{eighth_high}}/ratings/2
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{eighth_high}}/ratings/3
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{eighth_high}}/ratings/4
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{eighth_high}}/ratings/5
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{eighth_high}}/ratings/6
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{eighth_high}}/ratings/7
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{eighth_high}}/ratings/8
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{eighth_high}}/ratings/9
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{eighth_high}}/ratings/10
{ "score": 4 }
HTTP 200

# R3, D4: the other 8 sum to 27: 3.375 rounds to the even 3.38
GET {{base_url}}/talks/{{eighth_high}}
HTTP 200
[Asserts]
jsonpath "$.averageRating" == 3.38
jsonpath "$.ratingCount" == 10

POST {{base_url}}/talks
{ "title": "Talk fortieths", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{c}} }
HTTP 201
[Captures]
fortieths: jsonpath "$.id"

PUT {{base_url}}/talks/{{fortieths}}/ratings/1
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/2
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/3
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/4
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/5
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/6
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/7
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/8
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/9
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/10
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/11
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/12
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/13
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/14
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/15
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/16
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/17
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/18
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/19
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/20
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/21
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/22
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/23
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/24
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/25
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/26
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/27
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/28
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/29
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/30
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/31
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/32
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/33
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/34
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/35
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/36
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/37
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/38
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/39
{ "score": 2 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/40
{ "score": 2 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/41
{ "score": 2 }
HTTP 200

PUT {{base_url}}/talks/{{fortieths}}/ratings/42
{ "score": 2 }
HTTP 200

# D4: 42 ratings, a 1 and a 2 left out, the other 40 sum to 43: exactly 1.075 -> 1.08 (a floating-point mean gives 1.07)
GET {{base_url}}/talks/{{fortieths}}
HTTP 200
[Asserts]
jsonpath "$.averageRating" == 1.08
jsonpath "$.ratingCount" == 42

POST {{base_url}}/speakers
{ "name": "Rated e", "email": "e-{{newUuid}}@example.com" }
HTTP 201
[Captures]
e: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Talk eighths", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{e}} }
HTTP 201
[Captures]
eighths: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Talk seventeenths", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{e}} }
HTTP 201
[Captures]
seventeenths: jsonpath "$.id"

PUT {{base_url}}/talks/{{eighths}}/ratings/1
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{eighths}}/ratings/2
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{eighths}}/ratings/3
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{eighths}}/ratings/4
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{eighths}}/ratings/5
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{eighths}}/ratings/6
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{eighths}}/ratings/7
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{eighths}}/ratings/8
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{eighths}}/ratings/9
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{eighths}}/ratings/10
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{seventeenths}}/ratings/1
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{seventeenths}}/ratings/2
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{seventeenths}}/ratings/3
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{seventeenths}}/ratings/4
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{seventeenths}}/ratings/5
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{seventeenths}}/ratings/6
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{seventeenths}}/ratings/7
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{seventeenths}}/ratings/8
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{seventeenths}}/ratings/9
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{seventeenths}}/ratings/10
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{seventeenths}}/ratings/11
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{seventeenths}}/ratings/12
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{seventeenths}}/ratings/13
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{seventeenths}}/ratings/14
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{seventeenths}}/ratings/15
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{seventeenths}}/ratings/16
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{seventeenths}}/ratings/17
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{seventeenths}}/ratings/18
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{seventeenths}}/ratings/19
{ "score": 5 }
HTTP 200

# D5: 25/8 = 3.125 and 53/17 = 3.1176... both show 3.12, so they tie and 19 reviewers beat 10;
# sorting on the exact values would put 3.125 first
GET {{base_url}}/talks
[Query]
sort: rating
speakerId: {{e}}
HTTP 200
[Asserts]
jsonpath "$" count == 2
jsonpath "$[0].id" == {{seventeenths}}
jsonpath "$[0].averageRating" == 3.12
jsonpath "$[0].ratingCount" == 19
jsonpath "$[1].id" == {{eighths}}
jsonpath "$[1].averageRating" == 3.12
jsonpath "$[1].ratingCount" == 10

POST {{base_url}}/speakers
{ "name": "Rated d", "email": "d-{{newUuid}}@example.com" }
HTTP 201
[Captures]
d: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Talk trimmed_to_five", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{d}} }
HTTP 201
[Captures]
trimmed_to_five: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Talk five_of_four", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{d}} }
HTTP 201
[Captures]
five_of_four: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Talk four_and_a_half", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{d}} }
HTTP 201
[Captures]
four_and_a_half: jsonpath "$.id"

PUT {{base_url}}/talks/{{trimmed_to_five}}/ratings/1
{ "score": 1 }
HTTP 200

PUT {{base_url}}/talks/{{trimmed_to_five}}/ratings/2
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{trimmed_to_five}}/ratings/3
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{trimmed_to_five}}/ratings/4
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{trimmed_to_five}}/ratings/5
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{five_of_four}}/ratings/1
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{five_of_four}}/ratings/2
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{five_of_four}}/ratings/3
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{five_of_four}}/ratings/4
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{four_and_a_half}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{four_and_a_half}}/ratings/2
{ "score": 5 }
HTTP 200

# R5, D5: ranked by the trimmed average. 1,5,5,5,5 is 4.2 untrimmed but 5.0 trimmed, and ties
# 5,5,5,5 (5.0) on the average, then wins on more reviewers; 4,5 (4.5) comes last
GET {{base_url}}/talks
[Query]
sort: rating
speakerId: {{d}}
HTTP 200
[Asserts]
jsonpath "$" count == 3
jsonpath "$[0].id" == {{trimmed_to_five}}
jsonpath "$[0].averageRating" == 5.0
jsonpath "$[0].ratingCount" == 5
jsonpath "$[1].id" == {{five_of_four}}
jsonpath "$[1].averageRating" == 5.0
jsonpath "$[1].ratingCount" == 4
jsonpath "$[2].id" == {{four_and_a_half}}
jsonpath "$[2].averageRating" == 4.5

# D7: the rating endpoint's answer and validation are unchanged
PUT {{base_url}}/talks/{{four_and_a_half}}/ratings/3
{ "score": 6 }
HTTP 400
