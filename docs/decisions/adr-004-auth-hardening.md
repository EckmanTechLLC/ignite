# ADR-004: Authorization Hardening

**Date:** 2026-10-09
**Status:** Accepted
**Deciders:** @etl
**Supersedes:** ADR-003 §1 ("API keys remain full-access"); ADR-001 §Authentication ("No custom auth layer")

## Context

A full codebase review (2026-10-09) found that the authorization model built in ADR-003 and extended in task-28 contains three decisions that, combined, collapse all access control to "can you reach the gateway":

1. **The per-site API key is public and grants admin.** It is injected into every served `index.html` (`StaticFileRoutes.buildEnvJson`) and returned by the unauthenticated `GET /sites/:name/env`, while `AuthUtil.canWrite`/`canAdmin` return `true` for any valid key. Site names are enumerable via the unauthenticated `GET /sites`. Chain: list sites → fetch env → admin on every route.
2. **Tier checks are client-optional.** Site context comes from the caller-chosen `X-Ignite-Site` header; when it is omitted, `siteCtx == null` and `canAdmin(null)` returns `true` for any authenticated user. `/sites/:id` update/delete never consult the *target* record's own admin level.
3. **Two routes were never gated at all.** `/script/library`, `/script/system` (arbitrary Jython via string concatenation of the caller-supplied path) and `/db/query type=sql` (raw SQL incl. DML/DDL, unaudited) check `isAuthorized` only.

Secondary findings in the same area: audit profile is resolved from the caller-chosen site (omit header → no audit); `/db/query`, login/logout, sync/test are never audited; login has no rate limiting, authenticates against a caller-chosen user source via Jython in an arbitrary project, and session-user roles are read from the first user source containing that username; `servePath` is not confined to the site directory; API keys are compared with `String.equals`; disabled sites' keys still validate; API key accepted in query string on every route.

The custom Bearer login added in task-28 (`POST /auth/login`, `SessionStore`) reversed ADR-001's "no custom auth layer" without an ADR. This ADR ratifies it as a fallback.

## Decision

### 1. API key = read tier, always

- `AuthUtil.canWrite` and `canAdmin` no longer short-circuit on API key. An API key satisfies `isAuthorized` only.
- Write and admin operations require a user identity (Ignition web session cookie or Bearer session) holding the configured security level.
- The key remains injected into `index.html` and served by `/sites/:name/env` — it is a *public read credential* by design, equivalent to loading the site. Document it as such in the config UI.
- Key matching: constant-time (`MessageDigest.isEqual`), and only for sites with `Enabled == true`.
- `apiKey` query parameter accepted **only** on `GET /sse` (EventSource cannot set headers). All other routes: `X-Ignite-Key` header only. Demo `api.js` moves the key to the header.
- `GET /sse` additionally accepts `token=<bearer>` as a query parameter so Bearer-authenticated users can subscribe. Document the access-log exposure; tokens are already short-lived (8h).
- Web-session identity is taken from `WebUiSession`/`WebAuthUser`, never from `RequestContext.getActor()`, which is `"unknown"` for anonymous requests on 8.3 (task-46).

### 2. Gateway settings record (new)

A singleton `IgniteSettingsRecord` (PersistentRecord, one row) holding gateway-wide values that today are either hard-coded or absent:

| Field | Type | Purpose |
|---|---|---|
| `AdminSecurityLevel` | String | Required for gateway-wide admin ops (site CRUD, `/system/*`, `/audit/logs`). **Blank = deny all for API-key and Bearer callers** (not "allow all"); an Ignition web session with config-write permission (CSRF token required on unsafe methods, task-45) is always a gateway admin, so the config UI can bootstrap the setting. |
| `DefaultAuditProfile` | String | Fallback audit profile when the resolved site has none or no site resolves. |
| `LoginAuthProfile` | String | The only user source `POST /auth/login` authenticates against. Default `Ignite-Users` on first run. |
| `LoginMaxFailures` | Int | Failures per (username, remote IP) before lockout. Default 5. |
| `LoginLockoutSeconds` | Int | Lockout duration. Default 300. |

Exposed in the config UI as a "Gateway Settings" section above the site list. Registered via `getSchemaUpdater().updatePersistentRecords` alongside `SiteRecord`.

### 3. Deny-by-default site resolution

`AuthUtil.getSiteForRequest` is unchanged in *how* it resolves, but callers change in *what they do with null*:

| Route family | Site source | If no site resolves |
|---|---|---|
| `/tags/*`, `/alarms/*`, `/db/*`, `/script/*`, `/sse` | API key match, else `X-Ignite-Site` | **403** `site required` |
| `/sites` (list, create), `/system/*`, `/audit/logs` | n/a — gateway-wide | Gated by `IgniteSettingsRecord.AdminSecurityLevel` |
| `/sites/:id` (get, update, delete, sync, test, status) | **The target record** (by `:id`) | n/a |

Per-site `WriteSecurityLevel`/`AdminSecurityLevel` semantics are unchanged (blank = any authenticated user) — but blank now only ever applies to the site the record actually belongs to, never to a site the caller named.

`GET /sites` becomes gateway-admin-gated; `GET /sites/:id` and `GET /sites/:id/status` are gated by the target record's admin level like the other `/sites/:id/*` routes. `GET /sites/:name/env` stays open (see §1).

### 4. Script execution — write tier + strict validation

- Both `/script/library` and `/script/system` require `canWrite` against the resolved site (never satisfiable by API key per §1). This matches ADR-003's original intent.
- `functionPath` / `functionName` validated against `^[A-Za-z_]\w*(\.[A-Za-z_]\w*)+$` → 400 otherwise. The executor resolves the callable by attribute walk from `locals` (`getattr` chain) rather than string-concatenating the path into Python source.
- `project` required and validated against `ProjectManager.getNames()` → 400 otherwise. No more `projects.get(0)` anywhere (`AuthRoutes`, `AuditRoutes`).
- Audit records emitted for **every** call — success, failure and timeout — with the function path and outcome.
- Script executor pool bounded (`newFixedThreadPool(4)`), 503 when saturated.

### 5. Raw SQL removed — named queries only

- `POST /db/query` accepts `type=named` only; `type=sql` → 400 `raw SQL is not supported`.
- Named queries that are not flagged read-only in the project require `canWrite`; read-only named queries require `isAuthorized`.
- Every named-query execution is audited (project, query path, parameter names — not values).
- `GET /db/connections` stays read tier.

### 6. Login model: Ignition session preferred, Bearer fallback (ratifies task-28)

- **Preferred:** Ignition web session cookie (`req.getActor()`). Roles resolved from the gateway's actual authentication profile for that session. If the 8.3 SDK does not expose which profile authenticated the session, fall back to `LoginAuthProfile` only — never iterate all profiles.
- **Fallback:** `POST /auth/login` authenticates **natively** via `context.getUserSourceManager().getProfile(settings.LoginAuthProfile).authenticate(new BasicAuthChallenge(username, password))` — no Jython, no project dependency. `authProfile` is no longer accepted from the request body.
- Lockout: in-memory counter keyed `(username, remoteAddr)`; after `LoginMaxFailures` failures return 429 for `LoginLockoutSeconds`. Successful login clears the counter.
- All failures return a generic 401 `Invalid username or password` — no distinction between unknown user, bad password, or missing profile (missing profile is logged at ERROR server-side).
- Login success, login failure (with username, not password) and logout are audited to `DefaultAuditProfile`.
- `SessionStore` cleanup runs on a scheduled task (every 10 min), not only on login. Roles remain frozen for the session lifetime (accepted).

### 7. Audit coverage

- Profile resolution order: site's `AuditProfileName` → `IgniteSettingsRecord.DefaultAuditProfile` → skip (logged once at WARN per route at startup if both blank).
- Newly audited: named queries, login/logout, `/sites/:id/sync`, `/sites/:id/test`, `/audit/logs` queries (already), script failures/timeouts.
- Site update records the names of changed fields in `actionValue` (not values) — so key rotation and security-level changes are visible.

### 8. Static serving confinement

- `resolveServePath` canonicalizes and requires the result to start with `siteDir.getCanonicalPath() + File.separator` (or equal it). `SiteConfigRoutes` rejects `servePath` containing `..` at create/update with 400.
- The `cleaned.endsWith("/")` early-return in `resolveFile` moves *after* the containment check.

### 9. Error hygiene

- Route handlers catch `JsonParseException`, `IllegalStateException` (Gson), `NumberFormatException`, `DateTimeParseException` → 400 with a generic message.
- `SException` from record save (unique/mandatory) → 409 / 400.
- 500 responses carry `{"error":"internal error"}` only; detail is logged server-side with a correlation id included in the response.
- No Jython tracebacks, project lists, or JDBC messages in response bodies.

## Out of scope (separate tasks, no ADR needed)

Robustness issues from the same review that are not design decisions: git subprocess timeout/destroy and PAT-in-`.git/config`, per-site sync lock, `SiteRecord` `IRecordListener`, SSE connection cap + shutdown tracking + drop-oldest backpressure, `java.sql.Date` serialization crash, HTML ETag including injected env, alarm-ack user, `freeModule` in Gradle, dead code removal, CORS header name mismatch, demo contract fixes.

## Task Breakdown

| Task | Scope | Depends on |
|---|---|---|
| task-32-settings-record | `IgniteSettingsRecord` + schema registration + config UI "Gateway Settings" section + `SettingsUtil.get(context)` accessor | — |
| task-33-authutil-tiers | API key → read-only; constant-time compare; `Enabled` check; header-only key except `/sse`; `token` param on `/sse`; deny-by-default `canWrite`/`canAdmin` signatures taking a resolved `SiteRecord` (not a nullable level string); gateway-wide `canAdminGateway` | task-32 |
| task-34-route-gating | Apply §3 table to every route file: target-record resolution for `/sites/:id/*`, gateway admin on `/sites` list/create + `/system` + `/audit`, 403 on unresolved site for site-scoped routes; `/sites*` GET auth | task-33 |
| task-35-script-hardening | §4: write tier, regex validation, getattr-walk execution, project validation, bounded pool, audit all outcomes | task-33 |
| task-36-db-named-only | §5: remove raw SQL path, read-only detection, write tier, audit | task-33 |
| task-37-login-native | §6: `UserSourceProfile.authenticate`, fixed profile from settings, lockout, generic 401, login/logout audit, scheduled `SessionStore` cleanup; session-user role resolution via single profile | task-32 |
| task-38-audit-defaults | §7: profile fallback chain, sync/test audit, changed-field names on site update | task-32 |
| task-39-serve-path-confinement | §8 | — |
| task-40-error-hygiene | §9 across all route files | task-34 |
| task-41-demo-auth-contract | `api.js`: key in `X-Ignite-Key` header, `token` on SSE, 401 → clear token + re-show login; remove `authProfile` from `LoginForm`; `Audit.jsx` reads `result`; `getAuditLogs` URL fix | task-33, task-37 |
| task-44-web-session-bootstrap | `AuthUtil.isGatewayConfigWriter` (`SESSION_WRITE`) short-circuits `canAdminGateway`; web-session roles/security levels unioned into `getUserSecurityLevels` | task-33 |
| task-45-config-ui-csrf | `isGatewayConfigWriter`: permission-only check for GET/HEAD/OPTIONS, permission + CSRF (`X-CSRF-Token`) for unsafe methods; panel fetches token from `/data/app/session` | task-44 |
| task-46-anonymous-actor | `AuthUtil.webSessionUser` (`WebUiSession` -> `WebAuthUser`) is the only web identity source; all `req.getActor()` uses removed; `getActorIdentity` order Bearer, web session, api-key, `anonymous` | task-45 |
| task-65-site-tag-scope (A1) | `SiteRecord.TagPathPatterns` + `TagScope` matcher (unit-tested) enforced on tags read/write/browse/history, `/sse`, alarms status/history/acknowledge; config UI "Tag scope" textarea + "scoped" badge; lab sites scoped `[default]Launch/**` / `[default]DC/**` | task-33, task-34 |

Each task writes its summary to `docs/sessions/`.

## Consequences

**Positive**
- The public API key can no longer escalate; every write/admin action is attributable to a named user.
- No code path accepts an attacker-chosen security scope (site name, auth profile, project).
- Script and SQL surfaces shrink to what ADR-003 originally intended, with input validation that makes injection structurally impossible rather than filtered.
- Login no longer depends on Jython or on a project existing.
- Audit coverage becomes complete for mutations and attributable even when the caller omits site context.

**Negative**
- **Write-tier users can call any `system.*` function** (including `system.user.*`, `system.util.execute`, `system.db.runUpdateQuery`) — effectively gateway-scripting privilege. This is the accepted trade-off of the write-tier decision; sites that need finer control should keep `WriteSecurityLevel` tight or wrap operations in project library functions and restrict `/script/system` by policy. Revisit if a per-site function allowlist becomes necessary.
- Machine-to-machine integrations that previously wrote via API key must now log in and hold a Bearer token.
- The demo's current zero-login write experience ends: operators must log in to toggle HOA/edit setpoints (the login form already exists).
- Raw SQL removal drops the System page's ad-hoc query capability; named queries must be authored in the Designer.
- New schema (`IgniteSettingsRecord`) and a config-UI section.
- Gateway-wide `AdminSecurityLevel` blank = deny applies to API-key and Bearer callers. (2026-10-12, task-44) An Ignition web session with config-write permission (CSRF token required on unsafe methods, task-45) is always a gateway admin, and its roles/security levels count as security levels, so the config UI can populate the setting on a fresh install.

**Risks**
- Whether 8.3 exposes the authenticating user-source profile for a web session actor needs verification in task-37; the fallback (single `LoginAuthProfile`) is specified.
- `BasicAuthChallenge` lives in `ignition-common` (not cached locally at review time); signature to be confirmed in task-37.
- Named-query read-only detection depends on `NamedQueryManager` exposing the query's type; if not, treat all named queries as write tier.

## Amendment A1 — per-site tag scope (2026-10-13, Accepted)

**Finding (task-58 smoke matrix):** a site's API key — and a user resolved to a site via `X-Ignite-Site` — can read, browse, historise and subscribe to **any** tag on the gateway (the `launch` key reads `[default]DC/*`). ADR-004 bound keys to an access *tier* and a *site record*, never to a tag namespace. On a single-tenant lab this is harmless; for any gateway hosting several sites it is a data-exposure gap.

**Decision:** add `SiteRecord.TagPathPatterns` (String, optional; one pattern per line or comma-separated) and enforce it on every tag-addressed route for the resolved site:

| Pattern | Meaning |
|---|---|
| `[default]Launch/**` | the folder and everything below it (recursive) |
| `[default]Launch/Clock/*` | direct children only |
| `[default]Launch/Clock/Phase` | one tag |
| blank field | **unrestricted** (today's behaviour; keeps every existing site working) |

Rules:
- Resolution first (ADR-004 §3), then scope: the site that authorised the call (key → its site; user → `X-Ignite-Site`/`site`) supplies the patterns. Gateway-admin routes are unaffected.
- Enforced on `GET /tags/read`, `/tags/browse` (results filtered; a browse of a folder outside scope → 403), `/tags/history`, `POST /tags/write`, `/sse` (explicit paths and folder patterns — the expansion is filtered, the `subscribed` count reflects what survived), `/alarms/status` and `/alarms/history` (rows filtered by alarm source path), `POST /alarms/acknowledge` (each event's source must be in scope). Provider names compare case-insensitively; paths exactly.
- Out of scope by nature and documented as such: `/script/*` (runs gateway-wide Jython under the write tier — a site that must not run scripts should set `WriteSecurityLevel` to a role its users lack) and `/db/query` (named queries are project resources, not tags).
- Violations return 403 `{"error":"tag path not permitted"}`; one DEBUG log line with site and path.
- Config UI: a "Tag scope" textarea on the site form with the three example rows as placeholder text; Home → Launch cards unaffected.
- Lab: `launch` → `[default]Launch/**`; `datacenter` → `[default]DC/**` (both demos then prove the boundary: the launch key's `/tags/read` of a `DC` path → 403).

**Consequences:** one more field on an existing record (schema update via `updatePersistentRecords`, additive); a glob matcher (~40 lines, unit-tested) used by six routes; `demo/`-era clients that read arbitrary tags keep working as long as the field stays blank. Task: `task-65-site-tag-scope`.
