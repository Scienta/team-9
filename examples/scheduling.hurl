# Scheduling: a request per rule and per error status (team spec: workflow/specs/scheduling.md).
# Run: hurl --test --variable base_url=http://localhost:8080 scheduling.hurl
# Production shares the 15 places with the harness: everything put in here is taken out again.

# Setup: speakers Ada and Grace, and accepted talks (two reviewers each, then ACCEPTED)
POST {{base_url}}/speakers
{ "name": "Ada Scheduled", "email": "ada-{{newUuid}}@example.com" }
HTTP 201
[Captures]
ada: jsonpath "$.id"

POST {{base_url}}/speakers
{ "name": "Grace Scheduled", "email": "grace-{{newUuid}}@example.com" }
HTTP 201
[Captures]
grace: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Ada's talk", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{ada}} }
HTTP 201
[Captures]
ada_talk: jsonpath "$.id"

PUT {{base_url}}/talks/{{ada_talk}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{ada_talk}}/ratings/2
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{ada_talk}}/status
{ "status": "ACCEPTED" }
HTTP 200

POST {{base_url}}/talks
{ "title": "Grace's workshop", "format": "WORKSHOP", "level": "ADVANCED", "speakerId": {{grace}} }
HTTP 201
[Captures]
workshop: jsonpath "$.id"

PUT {{base_url}}/talks/{{workshop}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{workshop}}/ratings/2
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{workshop}}/status
{ "status": "ACCEPTED" }
HTTP 200

# Grace's second talk, with Ada as co-speaker
POST {{base_url}}/talks
{ "title": "Grace and Ada", "format": "LIGHTNING_20", "level": "INTERMEDIATE", "speakerId": {{grace}} }
HTTP 201
[Captures]
pair_talk: jsonpath "$.id"

PUT {{base_url}}/talks/{{pair_talk}}/co-speakers/{{ada}}
HTTP 200

PUT {{base_url}}/talks/{{pair_talk}}/ratings/1
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{pair_talk}}/ratings/2
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{pair_talk}}/status
{ "status": "ACCEPTED" }
HTTP 200

# Alan's accepted talk: shares no speaker with anyone, so only R5 can reject it
POST {{base_url}}/speakers
{ "name": "Alan Scheduled", "email": "alan-{{newUuid}}@example.com" }
HTTP 201
[Captures]
alan: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Alan's talk", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{alan}} }
HTTP 201
[Captures]
alan_talk: jsonpath "$.id"

PUT {{base_url}}/talks/{{alan_talk}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{alan_talk}}/ratings/2
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{alan_talk}}/status
{ "status": "ACCEPTED" }
HTTP 200

# A talk that isn't accepted
POST {{base_url}}/talks
{ "title": "Still submitted", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{ada}} }
HTTP 201
[Captures]
submitted_talk: jsonpath "$.id"

# R1, R2: put a talk in a room and slot; the Entry shows the times as HH:mm
PUT {{base_url}}/programme/R3/S1
{ "talkId": {{ada_talk}} }
HTTP 200
[Asserts]
jsonpath "$.room" == "R3"
jsonpath "$.slot" == "S1"
jsonpath "$.start" == "09:00"
jsonpath "$.end" == "10:00"
jsonpath "$.talkId" == {{ada_talk}}

# R2: look it up
GET {{base_url}}/programme/R3/S1
HTTP 200
[Asserts]
jsonpath "$.talkId" == {{ada_talk}}
jsonpath "$.start" == "09:00"

# D6: the same talk in the same place again changes nothing
PUT {{base_url}}/programme/R3/S1
{ "talkId": {{ada_talk}} }
HTTP 200
[Asserts]
jsonpath "$.talkId" == {{ada_talk}}

# R6: the talk is already on the programme, so not somewhere else too
PUT {{base_url}}/programme/R3/S2
{ "talkId": {{ada_talk}} }
HTTP 409

# R5: one talk per room and slot
PUT {{base_url}}/programme/R3/S1
{ "talkId": {{alan_talk}} }
HTTP 409

# R7: Ada co-speaks on the pair talk and already speaks in S1
PUT {{base_url}}/programme/R2/S1
{ "talkId": {{pair_talk}} }
HTTP 409

# R3, D4: only accepted talks
PUT {{base_url}}/programme/R3/S3
{ "talkId": {{submitted_talk}} }
HTTP 409

# R4, D5: a workshop needs the long slot S4
PUT {{base_url}}/programme/R3/S3
{ "talkId": {{workshop}} }
HTTP 409

# R4: the workshop goes in S4, 13:30-15:30
PUT {{base_url}}/programme/R3/S4
{ "talkId": {{workshop}} }
HTTP 200
[Asserts]
jsonpath "$.start" == "13:30"
jsonpath "$.end" == "15:30"

# R7: a different slot is fine for the same people
PUT {{base_url}}/programme/R3/S5
{ "talkId": {{pair_talk}} }
HTTP 200
[Asserts]
jsonpath "$.start" == "15:45"
jsonpath "$.end" == "16:45"

# D1: a room or slot that isn't one of the listed values (case-sensitive) doesn't exist: 404.
# The PUT to R4 is the request scheduling/check-1 failed on (we answered 400; lessons.md L12).
PUT {{base_url}}/programme/R4/S1
{ "talkId": {{ada_talk}} }
HTTP 404

GET {{base_url}}/programme/R1/S6
HTTP 404

DELETE {{base_url}}/programme/r1/S1
HTTP 404

# D2: talkId must be a whole number
PUT {{base_url}}/programme/R3/S2
{ "talkId": "{{ada_talk}}" }
HTTP 400

PUT {{base_url}}/programme/R3/S2
{ "talkId": 1.5 }
HTTP 400

PUT {{base_url}}/programme/R3/S2
{ "talkId": 1.0 }
HTTP 400

PUT {{base_url}}/programme/R3/S2
{ "talkId": null }
HTTP 400

PUT {{base_url}}/programme/R3/S2
{ "talkId": true }
HTTP 400

PUT {{base_url}}/programme/R3/S2
{ "talkId": 0 }
HTTP 400

PUT {{base_url}}/programme/R3/S2
{ "talkId": -1 }
HTTP 400

PUT {{base_url}}/programme/R3/S2
{ }
HTTP 400

PUT {{base_url}}/programme/R3/S2
Content-Type: application/json
HTTP 400

PUT {{base_url}}/programme/R3/S2
Content-Type: application/json
`not json`
HTTP 400

# D3: an unknown talk
PUT {{base_url}}/programme/R3/S2
{ "talkId": 2147483647 }
HTTP 404

# R2, D8: take the talks out again (204, no body); an empty place is 404
DELETE {{base_url}}/programme/R3/S1
HTTP 204

GET {{base_url}}/programme/R3/S1
HTTP 404

DELETE {{base_url}}/programme/R3/S1
HTTP 404

# R2: a talk that was taken out can go somewhere else
PUT {{base_url}}/programme/R3/S2
{ "talkId": {{ada_talk}} }
HTTP 200
[Asserts]
jsonpath "$.start" == "10:15"
jsonpath "$.end" == "11:15"

# R8: withdrawing a scheduled talk leaves the programme as it is
POST {{base_url}}/talks/{{ada_talk}}/withdraw
{ "speakerId": {{ada}} }
HTTP 200

GET {{base_url}}/programme/R3/S2
HTTP 200
[Asserts]
jsonpath "$.talkId" == {{ada_talk}}

# D6: putting it in the same place again still changes nothing, though it's withdrawn now
PUT {{base_url}}/programme/R3/S2
{ "talkId": {{ada_talk}} }
HTTP 200
[Asserts]
jsonpath "$.talkId" == {{ada_talk}}

# Clean up: leave production's programme as we found it
DELETE {{base_url}}/programme/R3/S2
HTTP 204

DELETE {{base_url}}/programme/R3/S4
HTTP 204

DELETE {{base_url}}/programme/R3/S5
HTTP 204
