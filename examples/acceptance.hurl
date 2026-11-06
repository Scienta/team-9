# Acceptance: our own examples (the spec publishes none). The status codes are the team's choices.
# Run: hurl --test --variable base_url=http://localhost:8080 examples/acceptance.hurl

POST {{base_url}}/speakers
{
  "name": "Barbara Liskov",
  "email": "barbara-{{newUuid}}@example.com"
}
HTTP 201
[Captures]
speaker_id: jsonpath "$.id"

POST {{base_url}}/speakers
{
  "name": "Someone Else",
  "email": "else-{{newUuid}}@example.com"
}
HTTP 201
[Captures]
other_speaker: jsonpath "$.id"

POST {{base_url}}/talks
{
  "title": "Data abstraction",
  "format": "PRESENTATION",
  "level": "ADVANCED",
  "speakerId": {{speaker_id}}
}
HTTP 201
[Captures]
talk: jsonpath "$.id"
[Asserts]
jsonpath "$.status" == "SUBMITTED"

# Accepting needs two different reviewers
PUT {{base_url}}/talks/{{talk}}/status
{ "status": "ACCEPTED" }
HTTP 409

PUT {{base_url}}/talks/{{talk}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{talk}}/ratings/1
{ "score": 5 }
HTTP 200

# One reviewer rating twice is still one reviewer
PUT {{base_url}}/talks/{{talk}}/status
{ "status": "ACCEPTED" }
HTTP 409

PUT {{base_url}}/talks/{{talk}}/ratings/2
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{talk}}/status
{ "status": "ACCEPTED" }
HTTP 200
[Asserts]
jsonpath "$.status" == "ACCEPTED"

# A decision is final
PUT {{base_url}}/talks/{{talk}}/status
{ "status": "REJECTED" }
HTTP 409

GET {{base_url}}/talks/{{talk}}
HTTP 200
[Asserts]
jsonpath "$.status" == "ACCEPTED"

# A speakerId that isn't a whole number is bad input, not "someone else"
POST {{base_url}}/talks/{{talk}}/withdraw
{ "speakerId": 1.5 }
HTTP 400

# ...and it changed nothing
GET {{base_url}}/talks/{{talk}}
HTTP 200
[Asserts]
jsonpath "$.status" == "ACCEPTED"

# Only the speaker withdraws
POST {{base_url}}/talks/{{talk}}/withdraw
{ "speakerId": {{other_speaker}} }
HTTP 403

POST {{base_url}}/talks/{{talk}}/withdraw
{ "speakerId": {{speaker_id}} }
HTTP 200
[Asserts]
jsonpath "$.status" == "WITHDRAWN"

POST {{base_url}}/talks/{{talk}}/withdraw
{ "speakerId": {{speaker_id}} }
HTTP 409

# Waitlisted can be decided later, rejected can't be withdrawn
POST {{base_url}}/talks
{
  "title": "Behavioural subtyping",
  "format": "LIGHTNING_20",
  "level": "BEGINNER",
  "speakerId": {{speaker_id}}
}
HTTP 201
[Captures]
second: jsonpath "$.id"

PUT {{base_url}}/talks/{{second}}/status
{ "status": "WAITLISTED" }
HTTP 200

PUT {{base_url}}/talks/{{second}}/status
{ "status": "REJECTED" }
HTTP 200

POST {{base_url}}/talks/{{second}}/withdraw
{ "speakerId": {{speaker_id}} }
HTTP 409
