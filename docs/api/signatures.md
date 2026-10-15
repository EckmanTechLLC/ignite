# Electronic signatures

Optional on `POST /tags/write`, `POST /alarms/acknowledge` and `POST /script/library`; mandatory on sites with `requireSignature` true. Design and rationale: [ADR-006](../decisions/adr-006-electronic-signatures.md).

## Request fields

| Field | Meaning |
|-------|---------|
| `reason` | Optional text, at most 200 characters. |
| `signature.username` | Must equal the authenticated user (Bearer session or web session). |
| `signature.password` | That user's password, verified against the login user source. |
| `signature.meaning` | `APPROVED`, `REVIEWED`, `CHANGED`, `ACKNOWLEDGED`, `OVERRIDE` or `OTHER`. |

`signature` needs all three members. Each request carries its own signature; nothing is reusable.

## Examples

Tag write (object form; a bare `[{path, value}]` array still works, unsigned):

    POST /data/ignite/tags/write
    Authorization: Bearer <token>
    X-Ignite-Site: <site>

    {"writes":[{"path":"[default]Area/Setpoint","value":42}],
     "reason":"batch 17 changeover",
     "signature":{"username":"<user>","password":"<password>","meaning":"CHANGED"}}

Alarm acknowledge:

    {"ids":["<event-uuid>"],"reason":"checked on floor",
     "signature":{"username":"<user>","password":"<password>","meaning":"ACKNOWLEDGED"}}

Library script: add `reason` and `signature` beside `project`, `path`, `args`, `kwargs`.

## Audit

Action names are unchanged. The action value is the existing value, then ` | signed: <MEANING>` if signed, then ` | reason: <text>` if given:

- `Tag Write`: `<old> -> <new>` (`?` when the old value cannot be read), e.g. `20.0 -> 42 | signed: CHANGED | reason: batch 17 changeover`
- `Alarm Acknowledge`: `acknowledged 1 of 1 | signed: ACKNOWLEDGED`
- `Script Library`: `executed | signed: APPROVED`

Values are cut to 255 characters, ending in an ellipsis.

## Errors

| Status | Body | When |
|--------|------|------|
| 400 | `{"error":"..."}` | Malformed `signature` (missing member, unknown meaning) or `reason` over 200 characters |
| 403 | `{"error":"signature required"}` | Site has `requireSignature` and the request carries no valid signature; nothing was changed |
| 403 | `{"error":"signature actor mismatch"}` | `signature.username` is not the authenticated user, or the caller is an API key or anonymous |
| 403 | `{"error":"signature invalid"}` | Wrong user or password (generic, no user enumeration) |
| 429 | `{"error":"Too many failed attempts; try again later"}` + `Retry-After` | Lockout, shared with `/auth/login` |

Unsigned requests on a site without `requireSignature` behave as before.

## Site configuration

`requireSignature` (boolean, default false) is accepted by the site create and update routes and returned in the site JSON; the config UI has a checkbox.
