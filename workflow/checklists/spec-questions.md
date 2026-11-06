# Spec questions

`/spec` walks every item for every feature request and turns each one that applies into a rule or a
DECISION in the team spec. `/retro` adds items here when a gap slipped through: say where it came
from (`lessons.md#L<n>`).

## Gaps to look for

1. **Unknown ids.** Each id in the path or body that doesn't exist: which status? (talk, speaker, ...)
2. **Malformed input.** Missing body, missing field, `null`, wrong type (a fractional number such as `1.5` for an `integer` field, lessons.md L8), empty or blank string,
   wrong case for an enum, extra fields, non-numeric path id. Two that pass Spring's defaults
   silently (lessons.md L5): a **number or boolean where a string is expected** (Jackson turns `123`
   into `"123"`: use `StrictStringDeserializer` on the field) and a **NUL character** in a string
   (Postgres refuses it at the insert, a 500: reject it with `@Pattern(regexp = "[^\\x00]*")`).
3. **Limits.** Every "at most N" / "at least N": what happens at N, at N+1, and does it hold under
   concurrent requests?
4. **Idempotency.** Doing the same thing twice: a no-op returning 200, or an error? Answer it
   separately for each verb: the request saying a repeated add "changes nothing" says nothing about
   a repeated remove (lessons.md L3).
5. **Undo.** Removing something that isn't there, including removing it a second time: by default
   404 (see the conventions), not a no-op 200 (lessons.md L3).
6. **State.** Every state and every transition: which are allowed, which are final, and what does
   a forbidden one answer? Draw the table.
7. **Who may do it.** Anyone, or only an owner (a `speakerId` in the body)? What if it's the wrong one?
8. **Ordering.** Every list: in what order? Ties?
9. **Where it shows.** Every existing response that must now show the new field or state
   (`POST /talks`, `GET /talks/{id}`, `GET /speakers/{id}/talks`, lists, reports, CSV).
10. **Effects on old rules.** Does it change a counted limit (the 3-talk limit), a filter, a
    sort, the ratings average?
11. **Concurrency.** Two people doing conflicting things at once: what stops an illegal state?
    (Row locks or conditional writes: advisory locks are not available.)
12. **Formats.** Exact string formats for any new timestamps or numbers.
13. **Order of checks vs. no-ops.** If a decision makes a repeat a no-op (200, nothing changes),
    say where it sits in the check order, and check every later state change against it: can the
    resource change after the first call (withdrawn, new members) so a later check rejects the
    repeat? Write the answer into both decisions (lessons.md L10).

## House conventions

Our default answers, so every feature behaves the same way. A DECISION that follows one says so.

- **400** for a malformed request: missing, null, blank, wrong type, unknown enum value (case-sensitive).
- **Emails** need a dot in the domain on top of `@Email` (`a@b` is 400), and compare with `lower()`.
  Set by a person in review of workshop-registration D1; speaker registration predates it (lessons.md L6).
- **404** for an id in the **path** that doesn't exist. Only then: if a resource with that id
  exists but is the wrong kind for the action (a talk that isn't a workshop), that's **409**, the
  same as the wrong state; and a **read** of it is **200** with what's there (an empty list), not
  404. Answer the read and the write separately (lessons.md L9).
- **400** for an id in the **body** that doesn't exist (as `POST /talks` does with `speakerId`),
  unless the request makes it the subject of the action. Write down which applies.
- **409** for a well-formed request the current state forbids: a limit reached, an illegal state
  transition, a duplicate.
- **403** only if the request explicitly has an owner and the caller is someone else; otherwise 409.
- Errors use the app's existing shape (`ResponseStatusException` with a short message).
- Repeating an **add** the request calls a no-op is a **no-op 200** returning the current resource.
- **Removing** something that isn't there (never added, already removed, or the wrong kind of member)
  is a **404**, unless the request says otherwise. The harness checks this: a 200 no-op failed
  co-speakers/check-1 (lessons.md L3).
- A forbidden operation **changes nothing**.
- Lists are oldest first (insertion order) unless the request says otherwise.
