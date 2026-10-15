# ADR-006: Electronic Signatures (21 CFR Part 11)

**Date:** 2026-10-15
**Status:** Accepted
**Deciders:** @etl
**Related:** ADR-004 (tiers, audit, lockout), ADR-005 Amendment A4 (Demo 5, GMP cleanroom)

## Context

A regulated-manufacturing demo needs the module to support the behaviours 21 CFR Part 11 asks of a system that records state changes:

- **Audit trail (§11.10(e)):** a secure, time-stamped record of who changed what, with the old and new value. The audit profile already records actor, host, time, action and target; the action value for a tag write held only the new value.
- **Signature manifestation (§11.50):** a signed record shows the signer's printed name, the date and time, and the meaning of the signature (approval, review, responsibility, ...).
- **Signature/record linking (§11.70):** the signature is tied to its record so it cannot be copied to another one.
- **Two components (§11.200):** user id and password, and the signer must be the authenticated user who is making the change.

## Decision

`POST /tags/write`, `POST /alarms/acknowledge` and `POST /script/library` accept an optional `reason` (at most 200 characters) and `signature` object `{"username","password","meaning"}`; `meaning` is one of `APPROVED`, `REVIEWED`, `CHANGED`, `ACKNOWLEDGED`, `OVERRIDE`, `OTHER` (case-insensitive on input). For `/tags/write`, whose body is a bare array, the object form `{"writes":[...],"signature":...,"reason":...}` is also accepted; the bare array keeps working unsigned. Details and errors are in [docs/api/signatures.md](../api/signatures.md).

1. **Verification.** The credential is checked against the login user source exactly as `POST /auth/login` does (same code path, same lockout counters keyed by username and remote address). Success clears the counter; failure counts toward it; a locked pair gets 429 with `Retry-After`.
2. **Signer = actor.** The signature username must equal the authenticated Bearer (or web-session) user, else 403 `signature actor mismatch`. API-key callers cannot sign (they cannot write either).
3. **Generic failure.** A wrong user or password is 403 `signature invalid`, with no hint which part failed.
4. **Per-site requirement.** New site flag `RequireSignature` (default false). On a flagged site a request without a valid signature is refused with 403 `signature required` before any write, acknowledgement or script runs.
5. **Audit linkage (§11.50/§11.70).** Audit action names are unchanged (`Tag Write`, `Alarm Acknowledge`, `Script Library`). The action value is the existing value, then ` | signed: <MEANING>` when signed, then ` | reason: <text>` when given. For tag writes the existing value becomes `<old> -> <new>` (old read just before the write, `?` if unreadable). The record's actor and timestamp supply name and date/time. Values are cut to 255 characters (ending in an ellipsis) because database audit profiles use a bounded column. The password is never logged or stored.

The logic lives in `web/SignatureUtil.java`: parsing, actor check and formatting are pure and unit tested; `gate()` does the gateway work and is called by the three routes after authorization and scope checks, before the state change.

## Consequences

- Every site may send signatures; only sites with `RequireSignature` refuse unsigned changes. Unsigned writes on other sites behave as before, except the tag-write audit value is now `old -> new`.
- Signature attempts share the login lockout: repeated bad signature passwords lock the user (from that address) out of login too, and the reverse.
- There are no re-usable signature tokens. Each action carries its own credential, so a captured request body is the only replay surface and is bound to one action and the Bearer session.
- The password travels in the request body; deployments must use TLS (already assumed for Bearer tokens).
- Failed signature attempts are not separately audited in this change.
- Signing is not available to API-key callers or anonymous requests.

## Alternatives considered

- **Separate `POST /auth/verify` returning a short-lived signature token, attached to the action.** Rejected: a token is replayable for its lifetime and not bound to a specific record, which weakens §11.70 linkage and §11.200 (the credential should be presented at signing time).
- **Reuse the Bearer session as the signature.** Rejected: the session is a login, not a deliberate act of signing; Part 11 expects the two components at signing time.
- **Sign per tag instead of per request.** Rejected: one request is one operator act; the signature and reason are applied to each audit line it produces.
