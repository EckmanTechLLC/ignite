# ADR-003: Auth, Audit & Script Execution

**Date:** 2026-03-26
**Status:** Accepted
**Deciders:** @etl

## Context

Ignite's API routes currently have duplicated auth logic (`isAuthorized()` copy-pasted across 5 files), no role-based access control, no audit trail, and no script execution capability. These gaps prevent Ignite from being a production-ready Perspective replacement:

1. **Auth** — Any valid session or API key gets full access to everything. Operators and administrators have identical permissions. The UI has no concept of roles — every user sees every page.
2. **Audit** — Tag writes, alarm acknowledgments, and future script executions leave no trail. Ignition has a built-in audit system that every serious deployment uses.
3. **Scripts** — The only way to interact with Ignition is through the fixed REST endpoints (tags, alarms, DB). There's no way to call project library functions or system.* functions, which is how real Ignition applications handle business logic.

Additionally, the Refrigeration page is currently a grid of data cards — functional but not visually compelling. A P&ID-style SVG layout would better demonstrate that Ignite can deliver the same visual process diagrams that Perspective provides.

## Decision

### 1. Auth Refactor — Shared Utility + Role-Based Access

**Extract `AuthUtil`** (`com.ignite.gateway.web.AuthUtil`):
- Replaces duplicated `isAuthorized()` and `errorJson()` across TagRoutes, DbRoutes, AlarmRoutes, SystemRoutes, SubscriptionRoutes
- Single source of truth for authentication logic

**Role-based access via Ignition security levels:**
- Use Ignition's existing Identity Provider and security level system — no custom role tables
- New endpoint: `GET /auth/session` — returns current user info, roles/security levels, and permissions
- Route handlers check the user's Ignition security levels before allowing operations
- Three permission tiers mapped to Ignition security levels:
  - **Read** (default for authenticated users) — browse tags, read values, view alarms, view system info
  - **Write** (requires configured security level, e.g., "Operator") — write tags, acknowledge alarms, execute scripts
  - **Admin** (requires configured security level, e.g., "Administrator") — site configuration, system endpoints
- API keys remain full-access (no role restrictions) — they are for machine-to-machine integration where the site owner controls what the key is used for
- Per-site scoping handled by Ignition's IdP configuration (each project can have its own IdP)

**New SiteRecord fields:**
- `WriteSecurityLevel` (StringField) — Ignition security level required for write operations (default: empty = any authenticated user)
- `AdminSecurityLevel` (StringField) — Ignition security level required for admin operations (default: empty = any authenticated user)
- `AuditProfileName` (StringField) — Name of the Ignition audit profile to log to (default: empty = no auditing)

**Frontend role enforcement:**
- `GET /auth/session` called on app load, result stored in context
- Route guards prevent navigation to restricted pages (e.g., operator cannot access System page)
- Write controls (HoaToggle, EditableValue) disabled/hidden for read-only users

### 2. Audit Integration

**Use Ignition's built-in audit system** — `GatewayContext.getAuditManager().getProfile(name)`:
- Audit profile name configured per site (new `AuditProfileName` field on SiteRecord)
- If no profile configured, auditing is silently skipped (not an error)

**Audited operations** (write actions only — reads are not audited):
- Tag writes (`POST /tags/write`) — actor, tag paths, values
- Alarm acknowledgment (`POST /alarms/acknowledge`) — actor, alarm IDs
- Script execution (`POST /script/library`, `POST /script/system`) — actor, function name, project
- Site configuration changes (create/update/delete) — actor, site name, changed fields

**Audit record format:**
- `action`: Operation type (e.g., "Tag Write", "Script Execute", "Alarm Acknowledge")
- `actionTarget`: Target resource (tag path, script name, alarm ID)
- `actionValue`: New value or result summary
- `actor`: Username from session or "api-key:<site-name>" for API key auth
- `originatingSystem`: "Ignite"
- `originatingContext`: `ApplicationScope.GATEWAY`

### 3. Script Execution

**Two endpoints:**

**`POST /script/library`** — Execute a project library function:
```json
{
  "project": "MyProject",
  "path": "demo.helloWorld",
  "args": [1, "two", true],
  "kwargs": {"optional": "value"}
}
```
- Uses `GatewayContext.getProjectManager().getProjectScriptManager(projectName)`
- `ScriptManager.createLocalsMap()` provides project scope with system.* pre-imported
- Builds Python code: `result = demo.helloWorld(args[0], args[1], optional=kwargs['optional'])`
- Executes via `ScriptManager.runCode()`
- No whitelist — if the function exists in the project library, it can be called
- The project library author controls what logic is exposed

**`POST /script/system`** — Execute a system.* function directly:
```json
{
  "function": "system.tag.readBlocking",
  "args": [["[default]Path/To/Tag"]],
  "project": "MyProject"
}
```
- Same ScriptManager execution pattern
- No whitelist restrictions (unlike the AI module) — Ignite is a controlled API gateway, not an AI with agency
- Timeout protection (configurable, default 30s) via ExecutorService
- Any system.* function is callable — the UI developer decides what to call

**Return value serialization** (3-tier fallback, from an earlier internal reference module):
1. `TypeUtilities.pyToGson()` — handles primitives, lists, dicts
2. Sequence extraction — iterate `__len__`/`__getitem__` for list-like objects
3. String fallback — `toString()` with type annotation

**ScriptExecutor** (`com.ignite.gateway.ScriptExecutor`):
- Shared service created in GatewayHook, passed to ScriptRoutes
- ExecutorService with timeout for each execution
- Shutdown cleanly in GatewayHook.shutdown()

### 4. Refrigeration P&ID Visual (Demo Enhancement)

Replace the current Refrigeration page card layout with an SVG-based P&ID (Piping and Instrumentation Diagram):
- **SVG diagram** showing the refrigeration system layout: 2 compressors, 2 cooling towers, accumulator, receiver, piping, valves
- **Live data overlays** — tag values rendered as text labels positioned on the SVG, updated in real-time via SSE
- **Color-coded status** — equipment fills/strokes change color based on running state, alarm state, or value ranges
- **Clickable equipment** — clicking a compressor/tower opens a detail panel (slide-out or modal) with the full metrics currently shown in cards
- **Responsive** — SVG scales with viewBox, detail panels adapt to screen size
- Keep existing card data as the detail view — the P&ID is the overview, cards are the drill-down

## Task Breakdown

| Task | Scope | Dependencies |
|------|-------|-------------|
| task-20-auth-util | Extract AuthUtil + errorJson, update 5 route files, add SiteRecord fields (WriteSecurityLevel, AdminSecurityLevel, AuditProfileName), update config UI | None |
| task-21-session-endpoint | GET /auth/session endpoint (user info + security levels), role checking in AuthUtil | task-20 |
| task-22-role-enforcement | Add role checks to write endpoints (tags/write, alarms/acknowledge), admin checks to site config | task-21 |
| task-23-audit-logging | AuditUtil class, wire into tag writes, alarm acks, site config changes | task-20 |
| task-24-script-executor | ScriptExecutor service, POST /script/library and /script/system endpoints | task-20 |
| task-25-demo-auth-roles | Frontend: GET /auth/session on load, route guards, disable write controls for read-only users | task-21 |
| task-26-demo-refrigeration-pid | SVG P&ID layout, live data overlays, clickable equipment detail panels | None (demo only) |
| task-27-audit-scripts-demo | Demo: add script execution example page or integrate library call into existing page | task-24 |

## Consequences

**Positive:**
- Production-ready auth — leverages Ignition's proven IdP and security system
- Full audit trail through Ignition's standard audit viewer — no custom log tables
- Script execution unlocks the full power of Ignition's system.* API and project libraries for web UIs
- P&ID visual demonstrates that Ignite can deliver the same process graphics as Perspective
- Auth deduplication eliminates 5 copies of identical code

**Negative:**
- Role enforcement adds a small overhead to every API request (security level lookup)
- Script execution is powerful — misconfigured project libraries could expose sensitive operations (but this is the same risk Perspective has)
- P&ID SVG requires careful manual layout work — not auto-generated from tag structure

**Risks:**
- Ignition 8.3 SDK may not expose security level lookup from `RequestContext.getActor()` string alone — may need to look up the user via `UserSourceManager`. Need to verify during task-21.
- `ScriptManager.runCode()` threading behavior needs validation — concurrent API calls executing scripts simultaneously
