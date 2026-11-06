# Workshop registration: our own examples (the spec publishes none). Status codes are the team's
# choices (workflow/specs/workshop-registration.md). Unique emails, so it can run repeatedly in production.
# Run: hurl --test --variable base_url=http://localhost:8080 examples/workshop-registration.hurl

# Setup: an accepted workshop (Acceptance: two reviewers, then ACCEPTED)
POST {{base_url}}/speakers
{ "name": "Workshop Host", "email": "host-{{newUuid}}@example.com" }
HTTP 201
[Captures]
host_id: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Hands-on workshop", "format": "WORKSHOP", "level": "BEGINNER", "speakerId": {{host_id}} }
HTTP 201
[Captures]
workshop_id: jsonpath "$.id"

PUT {{base_url}}/talks/{{workshop_id}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{workshop_id}}/ratings/2
{ "score": 5 }
HTTP 200

PUT {{base_url}}/talks/{{workshop_id}}/status
{ "status": "ACCEPTED" }
HTTP 200

# R1: register with a name and an email
POST {{base_url}}/workshops/{{workshop_id}}/registrations
{ "name": "Ada Lovelace", "email": "Ada-{{newUuid}}@Example.com" }
HTTP 201
[Captures]
ada_email: jsonpath "$.email"
ada_uuid: jsonpath "$.email" regex "^Ada-(.*)@Example\\.com$"
first_id: jsonpath "$.id"
[Asserts]
jsonpath "$.id" isInteger
jsonpath "$.workshopId" == {{workshop_id}}
jsonpath "$.name" == "Ada Lovelace"

# R5, D5: the same email again, and in another letter case, is a duplicate
POST {{base_url}}/workshops/{{workshop_id}}/registrations
{ "name": "Ada again", "email": "{{ada_email}}" }
HTTP 409

POST {{base_url}}/workshops/{{workshop_id}}/registrations
{ "name": "ADA", "email": "ADA-{{ada_uuid}}@EXAMPLE.COM" }
HTTP 409

# R1, D1: name and email are required, and the email must be one
POST {{base_url}}/workshops/{{workshop_id}}/registrations
{ "email": "nameless-{{newUuid}}@example.com" }
HTTP 400

POST {{base_url}}/workshops/{{workshop_id}}/registrations
{ "name": "No Email" }
HTTP 400

POST {{base_url}}/workshops/{{workshop_id}}/registrations
{ "name": "  ", "email": "blank-{{newUuid}}@example.com" }
HTTP 400

POST {{base_url}}/workshops/{{workshop_id}}/registrations
{ "name": "Bad Email", "email": "not-an-email" }
HTTP 400

# D1: the email's domain needs a dot
POST {{base_url}}/workshops/{{workshop_id}}/registrations
{ "name": "No Dot", "email": "a@b" }
HTTP 400

POST {{base_url}}/workshops/{{workshop_id}}/registrations
{ "name": null, "email": "null-name-{{newUuid}}@example.com" }
HTTP 400

POST {{base_url}}/workshops/{{workshop_id}}/registrations
{ "name": "Null Email", "email": null }
HTTP 400

POST {{base_url}}/workshops/{{workshop_id}}/registrations
{ "name": "Blank Email", "email": "  " }
HTTP 400

# D9: a wrong JSON type is a 400, not turned into a string
POST {{base_url}}/workshops/{{workshop_id}}/registrations
{ "name": 123, "email": "number-{{newUuid}}@example.com" }
HTTP 400

# A body that isn't JSON, and no body
POST {{base_url}}/workshops/{{workshop_id}}/registrations
Content-Type: application/json
```
not json
```
HTTP 400

POST {{base_url}}/workshops/{{workshop_id}}/registrations
Content-Type: application/json
HTTP 400

# A path id that isn't a number
POST {{base_url}}/workshops/abc/registrations
{ "name": "Path", "email": "path-{{newUuid}}@example.com" }
HTTP 400

GET {{base_url}}/workshops/abc/registrations
HTTP 400

# R6, D7: the list, in registration order, email as typed
GET {{base_url}}/workshops/{{workshop_id}}/registrations
HTTP 200
[Asserts]
jsonpath "$" count == 1
jsonpath "$[0].id" == {{first_id}}
jsonpath "$[0].email" == "{{ada_email}}"
jsonpath "$[0].workshopId" == {{workshop_id}}

# R4, D4: 20 seats in all, so 19 more fit and the 21st is refused

POST {{base_url}}/workshops/{{workshop_id}}/registrations
{ "name": "Attendee 2", "email": "attendee2-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{workshop_id}}/registrations
{ "name": "Attendee 3", "email": "attendee3-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{workshop_id}}/registrations
{ "name": "Attendee 4", "email": "attendee4-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{workshop_id}}/registrations
{ "name": "Attendee 5", "email": "attendee5-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{workshop_id}}/registrations
{ "name": "Attendee 6", "email": "attendee6-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{workshop_id}}/registrations
{ "name": "Attendee 7", "email": "attendee7-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{workshop_id}}/registrations
{ "name": "Attendee 8", "email": "attendee8-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{workshop_id}}/registrations
{ "name": "Attendee 9", "email": "attendee9-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{workshop_id}}/registrations
{ "name": "Attendee 10", "email": "attendee10-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{workshop_id}}/registrations
{ "name": "Attendee 11", "email": "attendee11-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{workshop_id}}/registrations
{ "name": "Attendee 12", "email": "attendee12-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{workshop_id}}/registrations
{ "name": "Attendee 13", "email": "attendee13-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{workshop_id}}/registrations
{ "name": "Attendee 14", "email": "attendee14-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{workshop_id}}/registrations
{ "name": "Attendee 15", "email": "attendee15-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{workshop_id}}/registrations
{ "name": "Attendee 16", "email": "attendee16-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{workshop_id}}/registrations
{ "name": "Attendee 17", "email": "attendee17-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{workshop_id}}/registrations
{ "name": "Attendee 18", "email": "attendee18-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{workshop_id}}/registrations
{ "name": "Attendee 19", "email": "attendee19-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{workshop_id}}/registrations
{ "name": "Attendee 20", "email": "attendee20-{{newUuid}}@example.com" }
HTTP 201

POST {{base_url}}/workshops/{{workshop_id}}/registrations
{ "name": "Attendee 21", "email": "attendee21-{{newUuid}}@example.com" }
HTTP 409

GET {{base_url}}/workshops/{{workshop_id}}/registrations
HTTP 200
[Asserts]
jsonpath "$" count == 20
jsonpath "$[0].id" == {{first_id}}
jsonpath "$[19].name" == "Attendee 20"

# R5: the same person may register for another workshop (setup: a second accepted workshop)
POST {{base_url}}/talks
{ "title": "Second workshop", "format": "WORKSHOP", "level": "ADVANCED", "speakerId": {{host_id}} }
HTTP 201
[Captures]
second_id: jsonpath "$.id"

PUT {{base_url}}/talks/{{second_id}}/ratings/1
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{second_id}}/ratings/2
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{second_id}}/status
{ "status": "ACCEPTED" }
HTTP 200

POST {{base_url}}/workshops/{{second_id}}/registrations
{ "name": "Ada Lovelace", "email": "{{ada_email}}" }
HTTP 201

# R3: withdrawn after registration: the list stays, new registrations are refused (D3)
POST {{base_url}}/talks/{{second_id}}/withdraw
{ "speakerId": {{host_id}} }
HTTP 200

POST {{base_url}}/workshops/{{second_id}}/registrations
{ "name": "Too Late", "email": "late-{{newUuid}}@example.com" }
HTTP 409

GET {{base_url}}/workshops/{{second_id}}/registrations
HTTP 200
[Asserts]
jsonpath "$" count == 1

# R2, D3, D6: a workshop that isn't accepted takes no registrations; its list is readable
POST {{base_url}}/talks
{ "title": "Not decided yet", "format": "WORKSHOP", "level": "BEGINNER", "speakerId": {{host_id}} }
HTTP 201
[Captures]
submitted_id: jsonpath "$.id"

POST {{base_url}}/workshops/{{submitted_id}}/registrations
{ "name": "Early", "email": "early-{{newUuid}}@example.com" }
HTTP 409

GET {{base_url}}/workshops/{{submitted_id}}/registrations
HTTP 200
[Asserts]
jsonpath "$" count == 0

# R2, D2: a talk that isn't a workshop, or no talk at all: 404
POST {{base_url}}/speakers
{ "name": "Presenter", "email": "presenter-{{newUuid}}@example.com" }
HTTP 201
[Captures]
presenter_id: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "A talk", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{presenter_id}} }
HTTP 201
[Captures]
presentation_id: jsonpath "$.id"

# D2: an accepted talk that isn't a workshop takes no registrations: 409 (harness check-2, lessons.md L8)
PUT {{base_url}}/talks/{{presentation_id}}/ratings/1
{ "score": 3 }
HTTP 200

PUT {{base_url}}/talks/{{presentation_id}}/ratings/2
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{presentation_id}}/status
{ "status": "ACCEPTED" }
HTTP 200

POST {{base_url}}/workshops/{{presentation_id}}/registrations
{ "name": "Lost", "email": "lost-{{newUuid}}@example.com" }
HTTP 409

GET {{base_url}}/workshops/{{presentation_id}}/registrations
HTTP 404

POST {{base_url}}/workshops/999999999/registrations
{ "name": "Lost", "email": "lost-{{newUuid}}@example.com" }
HTTP 404

GET {{base_url}}/workshops/999999999/registrations
HTTP 404
