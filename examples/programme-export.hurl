# Programme export: a request per rule and decision (team spec: workflow/specs/programme-export.md).
# Run: hurl --test --variable base_url=http://localhost:8080 programme-export.hurl
# Production shares the programme with the harness: this uses R1/S4, R1/S5 and R2/S5 (scheduling.hurl
# uses none of them), finds its own rows by talk id, and takes everything out again at the end.
# If the harness holds one of those places at that moment, the PUT answers 409: rerun.

# Setup: Grace (main speaker), Ada and Alan (co-speakers, Ada created first), Solo and Line
POST {{base_url}}/speakers
{ "name": "Grace Export", "email": "grace-{{newUuid}}@example.com" }
HTTP 201
[Captures]
grace: jsonpath "$.id"

POST {{base_url}}/speakers
{ "name": "Ada \"Countess\" Export", "email": "ada-{{newUuid}}@example.com" }
HTTP 201
[Captures]
ada: jsonpath "$.id"

POST {{base_url}}/speakers
{ "name": "Alan Export", "email": "alan-{{newUuid}}@example.com" }
HTTP 201
[Captures]
alan: jsonpath "$.id"

POST {{base_url}}/speakers
{ "name": "Solo Export", "email": "solo-{{newUuid}}@example.com" }
HTTP 201
[Captures]
solo: jsonpath "$.id"

POST {{base_url}}/speakers
{ "name": "Line Export", "email": "line-{{newUuid}}@example.com" }
HTTP 201
[Captures]
line: jsonpath "$.id"

# Talk 1: comma and quotes in the title, two co-speakers
POST {{base_url}}/talks
{ "title": "Kotlin, \"really\" fast", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{grace}} }
HTTP 201
[Captures]
t1: jsonpath "$.id"

PUT {{base_url}}/talks/{{t1}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{t1}}/ratings/2
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{t1}}/status
{ "status": "ACCEPTED" }
HTTP 200

# Talk 2: spaces and semicolons only, so no quoting
POST {{base_url}}/talks
{ "title": "Spaces; semicolons 'and' Bjørn", "format": "LIGHTNING_10", "level": "BEGINNER", "speakerId": {{solo}} }
HTTP 201
[Captures]
t2: jsonpath "$.id"

PUT {{base_url}}/talks/{{t2}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{t2}}/ratings/2
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{t2}}/status
{ "status": "ACCEPTED" }
HTTP 200

# Talk 3: a line break in the title
POST {{base_url}}/talks
{ "title": "Two\nlines", "format": "LIGHTNING_20", "level": "BEGINNER", "speakerId": {{line}} }
HTTP 201
[Captures]
t3: jsonpath "$.id"

PUT {{base_url}}/talks/{{t3}}/ratings/1
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{t3}}/ratings/2
{ "score": 4 }
HTTP 200

PUT {{base_url}}/talks/{{t3}}/status
{ "status": "ACCEPTED" }
HTTP 200

# Scheduled in a scrambled order: programme order is t3 (R1/S4), t1 (R1/S5), t2 (R2/S5)
PUT {{base_url}}/programme/R2/S5
{ "talkId": {{t2}} }
HTTP 200

PUT {{base_url}}/programme/R1/S5
{ "talkId": {{t1}} }
HTTP 200

PUT {{base_url}}/programme/R1/S4
{ "talkId": {{t3}} }
HTTP 200

# Changes after scheduling show up (R3, D8): co-speakers Alan then Ada (not id order, R4),
# Grace renamed, and talk 2 withdrawn but still on the programme (D1)
PUT {{base_url}}/talks/{{t1}}/co-speakers/{{alan}}
HTTP 200

PUT {{base_url}}/talks/{{t1}}/co-speakers/{{ada}}
HTTP 200

PUT {{base_url}}/speakers/{{grace}}
{ "name": "Grace B. Export", "email": "grace-{{newUuid}}@example.com" }
HTTP 200

POST {{base_url}}/talks/{{t2}}/withdraw
{ "speakerId": {{solo}} }
HTTP 200

# R1, R3, R4, D2, D7: one row per scheduled talk with every field, current speakers main first
GET {{base_url}}/programme
HTTP 200
[Asserts]
header "Content-Type" startsWith "application/json"
jsonpath "$" isCollection
jsonpath "$[?(@.talkId == {{t1}})]" count == 1
jsonpath "$[?(@.talkId == {{t1}})].slot" nth 0 == "S5"
jsonpath "$[?(@.talkId == {{t1}})].start" nth 0 == "15:45"
jsonpath "$[?(@.talkId == {{t1}})].end" nth 0 == "16:45"
jsonpath "$[?(@.talkId == {{t1}})].room" nth 0 == "R1"
jsonpath "$[?(@.talkId == {{t1}})].title" nth 0 == "Kotlin, \"really\" fast"
jsonpath "$[?(@.talkId == {{t1}})].format" nth 0 == "PRESENTATION"
jsonpath "$[?(@.talkId == {{t1}})].speakers[0]" nth 0 == "Grace B. Export"
jsonpath "$[?(@.talkId == {{t1}})].speakers[1]" nth 0 == "Alan Export"
jsonpath "$[?(@.talkId == {{t1}})].speakers[2]" nth 0 == "Ada \"Countess\" Export"
jsonpath "$[?(@.talkId == {{t1}})].speakers" nth 0 count == 3
jsonpath "$[?(@.talkId == {{t2}})].speakers" nth 0 count == 1
jsonpath "$[?(@.talkId == {{t2}})].format" nth 0 == "LIGHTNING_10"
jsonpath "$[?(@.talkId == {{t3}})].start" nth 0 == "13:30"
jsonpath "$[?(@.talkId == {{t3}})].end" nth 0 == "15:30"
jsonpath "$[?(@.talkId == {{t3}})].title" nth 0 == "Two\nlines"
# R2: slot then room, so t3 (S4) before t1 (R1/S5) before t2 (R2/S5); field order as in the request
body matches "(?s)\\{\"slot\":\"S4\",\"start\":\"13:30\",\"end\":\"15:30\",\"room\":\"R1\",\"talkId\":{{t3}},\"title\":.*\"talkId\":{{t1}},.*\"talkId\":{{t2}},"

# R5, R6, R7, R4: CSV, header, quoting only when needed, "; " between speakers, \n line endings
GET {{base_url}}/programme.csv
HTTP 200
[Asserts]
header "Content-Type" matches "^text/csv; ?charset=UTF-8$"
body startsWith "slot,start,end,room,talkId,title,format,speakers\n"
body endsWith "\n"
body not endsWith "\n\n"
body not contains "\r"
body contains "\nS4,13:30,15:30,R1,{{t3}},\"Two\nlines\",LIGHTNING_20,Line Export\n"
body contains "\nS5,15:45,16:45,R1,{{t1}},\"Kotlin, \"\"really\"\" fast\",PRESENTATION,\"Grace B. Export; Alan Export; Ada \"\"Countess\"\" Export\"\n"
body contains "\nS5,15:45,16:45,R2,{{t2}},Spaces; semicolons 'and' Bjørn,LIGHTNING_10,Solo Export\n"
# R2: same order as the JSON
body matches "(?s)\nS4,13:30,15:30,R1,{{t3}},.*\nS5,15:45,16:45,R1,{{t1}},.*\nS5,15:45,16:45,R2,{{t2}},"

# R5: still text/csv when the client asks for JSON
GET {{base_url}}/programme.csv
Accept: application/json
HTTP 200
[Asserts]
header "Content-Type" matches "^text/csv; ?charset=UTF-8$"
body startsWith "slot,start,end,room,talkId,title,format,speakers\n"

# D3: /programme is JSON even when CSV is asked for
GET {{base_url}}/programme
Accept: text/csv
HTTP 200
[Asserts]
header "Content-Type" startsWith "application/json"
jsonpath "$[?(@.talkId == {{t1}})]" count == 1

# D6: query parameters are ignored, the whole programme comes back
GET {{base_url}}/programme?room=R3&slot=S1
HTTP 200
[Asserts]
jsonpath "$[?(@.talkId == {{t1}})].room" nth 0 == "R1"

# R1: a talk taken out is gone from both
DELETE {{base_url}}/programme/R2/S5
HTTP 204

GET {{base_url}}/programme
HTTP 200
[Asserts]
jsonpath "$[?(@.talkId == {{t2}})]" count == 0

GET {{base_url}}/programme.csv
HTTP 200
[Asserts]
body not contains ",{{t2}},"

# Clean up: leave production's programme as we found it
DELETE {{base_url}}/programme/R1/S5
HTTP 204

DELETE {{base_url}}/programme/R1/S4
HTTP 204
