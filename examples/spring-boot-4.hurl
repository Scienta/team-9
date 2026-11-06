# Upgrade to Spring Boot 4 (team spec: workflow/specs/spring-boot-4.md).
# Run: hurl --test --variable base_url=http://localhost:8080 spring-boot-4.hurl
# Every other examples/*.hurl must also still pass (R4: nothing else changes).

# R1, R2, R3: the running versions
GET {{base_url}}/actuator/info
HTTP 200
[Asserts]
jsonpath "$.versions.springBoot" startsWith "4."
jsonpath "$.versions.springFramework" startsWith "7."
jsonpath "$.versions.java" matches /^25(\.|$)/

# R5: a trailing slash on POST /speakers/
POST {{base_url}}/speakers/
{
  "name": "Trailing Slash",
  "email": "slash-{{newUuid}}@example.com"
}
HTTP 201
[Captures]
speaker_id: jsonpath "$.id"

# R5: and on GET /speakers/{id}/
GET {{base_url}}/speakers/{{speaker_id}}/
HTTP 200
[Asserts]
jsonpath "$.name" == "Trailing Slash"

# R4: JSON with comments, as the legacy clients send it (spring.jackson key renamed in Boot 4)
POST {{base_url}}/talks/
Content-Type: application/json
```
{
  "title": "Upgrading legacy Spring", /* from the 2019 mobile client */
  "format": "PRESENTATION",
  "level": "ADVANCED",
  "speakerId": {{speaker_id}}
}
```
HTTP 201
[Captures]
talk_id: jsonpath "$.id"
[Asserts]
# R6: the legacy formats
jsonpath "$.submittedAt" matches /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}\+00:00$/
jsonpath "$.submittedOn" matches /^\d{4}-\d{2}-\d{2}$/
jsonpath "$.durationMinutes" == 45
jsonpath "$.averageRating" == null
jsonpath "$.ratingCount" == 0

# R5: trailing slash on the talk lists
GET {{base_url}}/speakers/{{speaker_id}}/talks/
HTTP 200
[Asserts]
jsonpath "$" count == 1

# R6: ratedAtDisplay with an ordinary space before AM/PM
PUT {{base_url}}/talks/{{talk_id}}/ratings/1
{
  "score": 4
}
HTTP 200
[Asserts]
jsonpath "$.ratedAtDisplay" matches /^\d{1,2}\/\d{1,2}\/\d{2}, \d{1,2}:\d{2} [AP]M$/

PUT {{base_url}}/talks/{{talk_id}}/ratings/2
{
  "score": 5
}
HTTP 200

GET {{base_url}}/talks/{{talk_id}}
HTTP 200
[Asserts]
jsonpath "$.averageRating" == 4.5
jsonpath "$.ratingCount" == 2

# D5, D7: errors keep Boot's shape, our message, and the charset
GET {{base_url}}/speakers/0
HTTP 404
[Asserts]
header "Content-Type" == "application/json;charset=UTF-8"
jsonpath "$.status" == 404
jsonpath "$.error" == "Not Found"
jsonpath "$.message" == "speaker not found"
jsonpath "$.path" == "/speakers/0"

# R4: a query parameter that can't be decoded is ignored, as on 2.7 (Tomcat 11 alone answers 400)
GET {{base_url}}/talks?speakerId={{speaker_id}}&x=%zz
HTTP 200
[Asserts]
jsonpath "$" count == 1

# R7: the deploy contract's health
GET {{base_url}}/health
HTTP 200
[Asserts]
jsonpath "$.status" == "ok"
