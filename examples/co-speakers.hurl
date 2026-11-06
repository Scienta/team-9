# Co-speakers: our own examples (the spec publishes none). Status codes are the team's choices
# (workflow/co-speakers.md); the repeated DELETE is the sequence the harness's co-speakers/check-1 sends.
# Run: hurl --test --variable base_url=http://localhost:8080 examples/co-speakers.hurl

POST {{base_url}}/speakers
{ "name": "Main Speaker", "email": "main-{{newUuid}}@example.com" }
HTTP 201
[Captures]
main_id: jsonpath "$.id"

POST {{base_url}}/speakers
{ "name": "First Co", "email": "first-{{newUuid}}@example.com" }
HTTP 201
[Captures]
first_id: jsonpath "$.id"

POST {{base_url}}/speakers
{ "name": "Second Co", "email": "second-{{newUuid}}@example.com" }
HTTP 201
[Captures]
second_id: jsonpath "$.id"

POST {{base_url}}/speakers
{ "name": "Third Co", "email": "third-{{newUuid}}@example.com" }
HTTP 201
[Captures]
third_id: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Together", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{main_id}} }
HTTP 201
[Captures]
talk_id: jsonpath "$.id"
[Asserts]
jsonpath "$.coSpeakerIds" count == 0

# Add two co-speakers, in order
PUT {{base_url}}/talks/{{talk_id}}/co-speakers/{{first_id}}
HTTP 200
[Asserts]
jsonpath "$.coSpeakerIds" count == 1
jsonpath "$.coSpeakerIds[0]" == {{first_id}}

PUT {{base_url}}/talks/{{talk_id}}/co-speakers/{{second_id}}
HTTP 200
[Asserts]
jsonpath "$.coSpeakerIds" count == 2
jsonpath "$.coSpeakerIds[0]" == {{first_id}}
jsonpath "$.coSpeakerIds[1]" == {{second_id}}

# Adding an existing co-speaker changes nothing
PUT {{base_url}}/talks/{{talk_id}}/co-speakers/{{first_id}}
HTTP 200
[Asserts]
jsonpath "$.coSpeakerIds" count == 2
jsonpath "$.coSpeakerIds[0]" == {{first_id}}

# At most two
PUT {{base_url}}/talks/{{talk_id}}/co-speakers/{{third_id}}
HTTP 409

# The main speaker can't be a co-speaker
PUT {{base_url}}/talks/{{talk_id}}/co-speakers/{{main_id}}
HTTP 400

# Shown on the talk; the co-speaker's own list shows only their own talks
GET {{base_url}}/talks/{{talk_id}}
HTTP 200
[Asserts]
jsonpath "$.coSpeakerIds" count == 2

GET {{base_url}}/speakers/{{main_id}}/talks
HTTP 200
[Asserts]
jsonpath "$[0].coSpeakerIds" count == 2

GET {{base_url}}/speakers/{{second_id}}/talks
HTTP 200
[Asserts]
jsonpath "$" count == 0

# Remove, then remove again: the second is a 404 and changes nothing (lessons.md L3)
DELETE {{base_url}}/talks/{{talk_id}}/co-speakers/{{second_id}}
HTTP 200
[Asserts]
jsonpath "$.coSpeakerIds" count == 1
jsonpath "$.coSpeakerIds[0]" == {{first_id}}

DELETE {{base_url}}/talks/{{talk_id}}/co-speakers/{{second_id}}
HTTP 404

# Removing the main speaker, or someone never added: 404
DELETE {{base_url}}/talks/{{talk_id}}/co-speakers/{{main_id}}
HTTP 404

DELETE {{base_url}}/talks/{{talk_id}}/co-speakers/{{third_id}}
HTTP 404

GET {{base_url}}/talks/{{talk_id}}
HTTP 200
[Asserts]
jsonpath "$.coSpeakerIds" count == 1
jsonpath "$.speakerId" == {{main_id}}

# Unknown talk or speaker
PUT {{base_url}}/talks/999999999/co-speakers/{{first_id}}
HTTP 404

PUT {{base_url}}/talks/{{talk_id}}/co-speakers/999999999
HTTP 404
