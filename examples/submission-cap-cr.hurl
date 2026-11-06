# Submission cap change request: our own examples (the request publishes none). Rules and decisions:
# workflow/specs/submission-cap-cr.md. Every speaker is new, so it can run repeatedly against production.
# Run: hurl --test --variable base_url=http://localhost:8080 examples/submission-cap-cr.hurl

POST {{base_url}}/speakers
{ "name": "Capped Speaker", "email": "cap-s-{{newUuid}}@example.com" }
HTTP 201
[Captures]
s: jsonpath "$.id"

POST {{base_url}}/speakers
{ "name": "Main One", "email": "cap-m1-{{newUuid}}@example.com" }
HTTP 201
[Captures]
m1: jsonpath "$.id"

POST {{base_url}}/speakers
{ "name": "Main Two", "email": "cap-m2-{{newUuid}}@example.com" }
HTTP 201
[Captures]
m2: jsonpath "$.id"

POST {{base_url}}/speakers
{ "name": "Main Three", "email": "cap-m3-{{newUuid}}@example.com" }
HTTP 201
[Captures]
m3: jsonpath "$.id"

POST {{base_url}}/speakers
{ "name": "Main Four", "email": "cap-m4-{{newUuid}}@example.com" }
HTTP 201
[Captures]
m4: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "One", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{m1}} }
HTTP 201
[Captures]
t1: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Two", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{m2}} }
HTTP 201
[Captures]
t2: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Three", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{m3}} }
HTTP 201
[Captures]
t3: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Four", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{m4}} }
HTTP 201
[Captures]
t4: jsonpath "$.id"

# s: 2 own talks and 1 co-speakership, so a load of 3
POST {{base_url}}/talks
{ "title": "Own one", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{s}} }
HTTP 201
[Captures]
o1: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Own two", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{s}} }
HTTP 201
[Captures]
o2: jsonpath "$.id"

PUT {{base_url}}/talks/{{t1}}/co-speakers/{{s}}
HTTP 200

# R1: co-speaking counts, so a third own talk is refused (2 own: it would be a 201 without R1)
POST {{base_url}}/talks
{ "title": "Own three", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{s}} }
HTTP 409
[Asserts]
body contains "already presents 3 talks"

# R4: nothing was created
GET {{base_url}}/speakers/{{s}}/talks
HTTP 200
[Asserts]
jsonpath "$" count == 2

# R5: at the cap, can't be added as co-speaker to a talk in the running, and nothing changes
PUT {{base_url}}/talks/{{t2}}/co-speakers/{{s}}
HTTP 409
[Asserts]
body contains "already presents 3 talks"

GET {{base_url}}/talks/{{t2}}
HTTP 200
[Asserts]
jsonpath "$.coSpeakerIds" count == 0

# R3: taking s off t1 frees the place at once
DELETE {{base_url}}/talks/{{t1}}/co-speakers/{{s}}
HTTP 200

PUT {{base_url}}/talks/{{t2}}/co-speakers/{{s}}
HTTP 200
[Asserts]
jsonpath "$.coSpeakerIds[0]" == {{s}}

# s: o1, o2, t2. R2: a withdrawn own talk frees a place at once
POST {{base_url}}/talks/{{o1}}/withdraw
{ "speakerId": {{s}} }
HTTP 200

POST {{base_url}}/talks
{ "title": "Own three", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{s}} }
HTTP 201
[Captures]
o3: jsonpath "$.id"

# s: o2, t2, o3. R2: a rejected own talk frees a place at once
PUT {{base_url}}/talks/{{o2}}/status
{ "status": "REJECTED" }
HTTP 200

PUT {{base_url}}/talks/{{t3}}/co-speakers/{{s}}
HTTP 200

# s: t2, o3, t3. R2: the main speaker withdrawing t2 frees the co-speaker's place too
POST {{base_url}}/talks/{{t2}}/withdraw
{ "speakerId": {{m2}} }
HTTP 200

POST {{base_url}}/talks
{ "title": "Own four", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{s}} }
HTTP 201

# s: o3, t3, o4, at the cap again
POST {{base_url}}/talks
{ "title": "Own five", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{s}} }
HTTP 409

# R6: at the cap, s can still join a rejected talk...
PUT {{base_url}}/talks/{{t1}}/status
{ "status": "REJECTED" }
HTTP 200

PUT {{base_url}}/talks/{{t1}}/co-speakers/{{s}}
HTTP 200
[Asserts]
jsonpath "$.coSpeakerIds[0]" == {{s}}

# ...and a withdrawn one
POST {{base_url}}/talks/{{t4}}/withdraw
{ "speakerId": {{m4}} }
HTTP 200

PUT {{base_url}}/talks/{{t4}}/co-speakers/{{s}}
HTTP 200
[Asserts]
jsonpath "$.coSpeakerIds[0]" == {{s}}

# D2: at the cap, adding s again to t3 (already a co-speaker) is a no-op 200
PUT {{base_url}}/talks/{{t3}}/co-speakers/{{s}}
HTTP 200
[Asserts]
jsonpath "$.coSpeakerIds" count == 1
jsonpath "$.coSpeakerIds[0]" == {{s}}

# R9: s's own lists show only the talks where s is the main speaker (o1, o2, o3, o4)
GET {{base_url}}/speakers/{{s}}/talks
HTTP 200
[Asserts]
jsonpath "$" count == 4

GET {{base_url}}/talks?speakerId={{s}}
HTTP 200
[Asserts]
jsonpath "$" count == 4

# D4: a talk with 2 co-speakers and a speaker at the cap: the talk's limit is checked first
PUT {{base_url}}/talks/{{t3}}/co-speakers/{{m4}}
HTTP 200

PUT {{base_url}}/talks/{{o3}}/co-speakers/{{m1}}
HTTP 200

PUT {{base_url}}/talks/{{o3}}/co-speakers/{{m2}}
HTTP 200

POST {{base_url}}/speakers
{ "name": "Full Speaker", "email": "cap-f-{{newUuid}}@example.com" }
HTTP 201
[Captures]
f: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "F one", "format": "LIGHTNING_10", "level": "BEGINNER", "speakerId": {{f}} }
HTTP 201
[Captures]
f1: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "F two", "format": "LIGHTNING_10", "level": "BEGINNER", "speakerId": {{f}} }
HTTP 201

POST {{base_url}}/talks
{ "title": "F three", "format": "LIGHTNING_10", "level": "BEGINNER", "speakerId": {{f}} }
HTTP 201

PUT {{base_url}}/talks/{{o3}}/co-speakers/{{f}}
HTTP 409
[Asserts]
body contains "at most 2 co-speakers"

# D5: only the added speaker's cap counts: f's talk takes a co-speaker though f is at the cap
PUT {{base_url}}/talks/{{f1}}/co-speakers/{{m3}}
HTTP 200
[Asserts]
jsonpath "$.coSpeakerIds[0]" == {{m3}}

# D9: as before. Main speaker as co-speaker, unknown talk or speaker, bad body
PUT {{base_url}}/talks/{{o3}}/co-speakers/{{s}}
HTTP 400

PUT {{base_url}}/talks/999999999/co-speakers/{{s}}
HTTP 404

PUT {{base_url}}/talks/{{t3}}/co-speakers/999999999
HTTP 404

POST {{base_url}}/talks
{ "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{s}} }
HTTP 400

POST {{base_url}}/talks
{ "title": "Nobody", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": 999999999 }
HTTP 400
