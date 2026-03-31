# Ignite

**API Gateway + Static Site Host for Ignition 8.3+**

Ignite is an Ignition module that lets you build modern web UIs (React, Vue, Astro, etc.) that talk directly to your Ignition gateway — reading/writing tags, querying databases, subscribing to real-time data, executing scripts, and more. All through a clean REST + SSE API, with Ignition-native authentication and audit logging.

## Why Ignite?

- **Use any frontend framework** — React, Vue, Svelte, Astro, plain HTML/JS
- **No Perspective/Vision license required** — leverage Ignition's core platform
- **Git-powered deployment** — push to a repo, Ignite pulls and serves it
- **Full Ignition integration** — tags, history, alarms, databases, scripts, audit logs
- **Real-time data** — Server-Sent Events for live tag values and alarm notifications
- **Built-in security** — Ignition IdP, role-based access, API keys, audit trails

## Requirements

- Ignition **8.3+**
- Gateway network access to your git repositories (HTTPS)

## Installation

1. Download `Ignite.unsigned.modl` from this repository
2. In the Ignition Gateway web UI, go to **Config → System → Modules**
3. Click **Install or Upgrade a Module** and upload the `.modl` file
4. Accept the unsigned module warning
5. The module appears under **Config → Connections → Ignite → Sites**

## Quick Start

### 1. Create a Site

In the Gateway config UI (**Connections → Ignite → Sites**), click **Add Site**:

| Field | Example | Description |
|-------|---------|-------------|
| Name | `dashboard` | Human-readable identifier |
| Mount Path | `dashboard` | URL path segment |
| Repository URL | `https://github.com/yourorg/yourapp` | HTTPS git repo URL |
| Branch | `main` | Git branch to track |
| PAT | `github_pat_...` | Personal access token (for private repos) |
| Poll Interval | `5` | Minutes between sync checks |
| API Key | `my-secret-key` | Per-site API key for external consumers |
| Serve Path | `/` | Subdirectory in repo to serve (e.g., `dist`) |

### 2. Access Your Site

Once synced, your site is available at:

```
https://<gateway>:8088/data/ignite/s/<mount>/
```

For example: `https://gateway.local:8088/data/ignite/s/dashboard/`

### 3. Call the API

From your frontend JavaScript:

```js
// Read tag values
const response = await fetch('/data/ignite/tags/read?paths=[default]Temperature,[default]Pressure', {
  headers: { 'X-Ignite-Key': 'my-secret-key' }
});
const data = await response.json();
// { tags: [{ path: "[default]Temperature", value: 72.5, quality: "Good", timestamp: "..." }] }
```

---

## Authentication

Ignite supports three authentication methods. Every API request is checked in this order:

### 1. Ignition Session

If the user is logged into the Ignition gateway, their session is automatically recognized. No extra headers needed.

### 2. Bearer Token (Login/Logout)

For standalone web apps that need a login form:

```http
POST /data/ignite/auth/login
Content-Type: application/json

{
  "username": "operator1",
  "password": "secret",
  "authProfile": "default"
}
```

Response:
```json
{
  "token": "550e8400-e29b-41d4-a716-446655440000",
  "user": "operator1",
  "roles": ["Operator"],
  "expiresIn": 28800
}
```

Include the token in subsequent requests:
```http
Authorization: Bearer 550e8400-e29b-41d4-a716-446655440000
```

Sessions expire after 8 hours. To log out:
```http
POST /data/ignite/auth/logout
Authorization: Bearer <token>
```

### 3. API Key

For machine-to-machine or simple integrations. Set the API key on your site configuration, then pass it via header or query parameter:

```http
X-Ignite-Key: my-secret-key
```
or
```
GET /data/ignite/tags/read?paths=[default]Tag1&apiKey=my-secret-key
```

> **Note:** API keys have full access (read + write + admin) to all endpoints.

### Check Current Session

```http
GET /data/ignite/auth/session?site=dashboard
```

Response:
```json
{
  "authenticated": true,
  "user": "admin",
  "authMethod": "bearer",
  "securityLevels": ["Administrator"],
  "permissions": { "canWrite": true, "canAdmin": true }
}
```

---

## Security Model

Ignite uses a 3-tier permission model based on Ignition security levels:

| Tier | Allows | Configured Via |
|------|--------|----------------|
| **Read** | Browse tags, read values, view alarms, query databases | Any authenticated user |
| **Write** | Write tag values, acknowledge alarms | Site's `writeSecurityLevel` field |
| **Admin** | Site CRUD, system info, audit logs, trigger sync | Site's `adminSecurityLevel` field |

Security levels are matched against the user's roles from their Ignition user source. If a security level field is left empty, any authenticated user qualifies.

---

## API Reference

All endpoints are under `/data/ignite/`. Replace `<gateway>` with your gateway's host and port.

### Tags

#### Read Tags
```http
GET /tags/read?paths=[default]Tag1,[default]Tag2
```

Response:
```json
{
  "tags": [
    { "path": "[default]Tag1", "value": 42.5, "quality": "Good", "timestamp": "2026-03-30T12:00:00Z" },
    { "path": "[default]Tag2", "value": true, "quality": "Good", "timestamp": "2026-03-30T12:00:00Z" }
  ]
}
```

#### Write Tags
```http
POST /tags/write
Content-Type: application/json

[
  { "path": "[default]Tag1", "value": 100 },
  { "path": "[default]Tag2", "value": "text" }
]
```

Response:
```json
{
  "results": [
    { "path": "[default]Tag1", "quality": "Good" },
    { "path": "[default]Tag2", "quality": "Good" }
  ]
}
```

**Auth:** Requires write permission.

#### Browse Tags
```http
GET /tags/browse?path=[default]
```

Response:
```json
{
  "path": "[default]",
  "children": [
    { "name": "Temperature", "path": "[default]Temperature", "hasChildren": false, "type": "LEAF", "dataType": "Double" },
    { "name": "Folder", "path": "[default]Folder", "hasChildren": true, "type": "FOLDER" }
  ],
  "count": 2
}
```

#### Tag History
```http
GET /tags/history?paths=[default]Tag1,[default]Tag2&start=2026-03-29T00:00:00Z&end=2026-03-30T00:00:00Z&returnSize=500
```

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `paths` | Yes | — | Comma-separated tag paths |
| `start` | No | 1 hour ago | ISO 8601 start time |
| `end` | No | Now | ISO 8601 end time |
| `returnSize` | No | 100 | Max rows (capped at 1000) |

Response:
```json
{
  "columns": ["t_stamp", "[default]Tag1", "[default]Tag2"],
  "rows": [
    ["2026-03-29T00:00:00Z", 42.5, 18.2],
    ["2026-03-29T00:05:00Z", 43.0, 18.1]
  ],
  "rowCount": 2
}
```

---

### Database

#### List Connections
```http
GET /db/connections
```

Response:
```json
{
  "connections": [
    { "name": "MyDB", "description": "Production", "status": "VALID", "vendor": "MYSQL" }
  ],
  "count": 1
}
```

#### Execute Query

**SQL query:**
```http
POST /db/query
Content-Type: application/json

{
  "connection": "MyDB",
  "query": "SELECT id, name FROM products WHERE active = ?",
  "type": "sql",
  "params": { "1": true }
}
```

**Named query:**
```http
POST /db/query
Content-Type: application/json

{
  "query": "MyProject/Reports/GetActiveProducts",
  "type": "named",
  "params": { "active": true }
}
```

Response:
```json
{
  "columns": [
    { "name": "id", "type": "BIGINT" },
    { "name": "name", "type": "VARCHAR" }
  ],
  "rows": [[1, "Widget"], [2, "Gadget"]],
  "rowCount": 2,
  "truncated": false
}
```

> **Note:** Results are capped at 1,000 rows. The `truncated` flag indicates if more rows were available.

---

### Alarms

#### Active Alarms
```http
GET /alarms/status?path=[default]
```

Response:
```json
{
  "alarms": [
    {
      "id": "550e8400-...",
      "source": "prov:default:/Tag",
      "displayPath": "[default]/Tag",
      "name": "HighTemp",
      "priority": "High",
      "state": "ActiveUnacked",
      "isAcked": false,
      "isCleared": false,
      "activeTime": "2026-03-30T12:00:00Z"
    }
  ],
  "count": 1
}
```

#### Alarm History
```http
GET /alarms/history?start=2026-03-29T00:00:00Z&end=2026-03-30T00:00:00Z&state=ActiveUnacked&journal=MyJournal
```

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `start` | No | 24h ago | ISO 8601 start time |
| `end` | No | Now | ISO 8601 end time |
| `path` | No | — | Alarm source path prefix filter |
| `state` | No | — | ClearUnacked, ClearAcked, ActiveUnacked, ActiveAcked |
| `journal` | No | — | Alarm journal profile name |

#### Acknowledge Alarms
```http
POST /alarms/acknowledge
Content-Type: application/json

{ "ids": ["550e8400-...", "660f9500-..."] }
```

Response:
```json
{ "acknowledged": 2 }
```

**Auth:** Requires write permission.

---

### Real-Time Data (SSE)

Open a Server-Sent Events connection for live tag values and alarm notifications:

```js
const url = '/data/ignite/sse?paths=[default]Tag1,[default]Tag2&alarms=true';
const source = new EventSource(url);

source.onmessage = (event) => {
  const data = JSON.parse(event.data);

  if (data.type === 'tagChange') {
    console.log(`${data.path} = ${data.value} (${data.quality})`);
  }

  if (data.type === 'alarm') {
    console.log(`Alarm: ${data.name} — ${data.state} (${data.priority})`);
  }
};
```

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `paths` | No | — | Comma-separated tag paths to subscribe |
| `alarms` | No | `false` | Set to `true` to receive alarm events |
| `path` | No | — | Alarm source path prefix filter |
| `apiKey` | No | — | API key (alternative to header) |

**Tag change event:**
```json
{ "type": "tagChange", "path": "[default]Tag1", "value": 42.5, "quality": "Good", "timestamp": "..." }
```

**Alarm event:**
```json
{ "type": "alarm", "source": "prov:default:/Tag", "name": "HighTemp", "state": "Active", "priority": "High" }
```

**Connection details:**
- Heartbeat sent every 30 seconds (keeps proxies alive)
- Max connection lifetime: 30 minutes (reconnect after)
- Queue capacity: 500 events per connection

---

### Scripts

Execute Ignition project library functions and `system.*` functions.

#### Library Function
```http
POST /script/library
Content-Type: application/json

{
  "project": "MyProject",
  "path": "reports.generateDaily",
  "args": ["2026-03-30"],
  "kwargs": { "format": "pdf" }
}
```

Response:
```json
{ "result": "Report generated successfully" }
```

#### System Function
```http
POST /script/system
Content-Type: application/json

{
  "project": "MyProject",
  "function": "system.tag.readBlocking",
  "args": [["[default]Tag1"]],
  "kwargs": {}
}
```

#### List Projects
```http
GET /script/projects
```

Response:
```json
{ "projects": ["MyProject", "AnotherProject"] }
```

> **Note:** Scripts have a 30-second timeout. Results are serialized via a 3-tier fallback: native JSON conversion → sequence extraction → string with type annotation.

---

### Audit Logs

Query Ignition's audit log (works with any audit profile backend — internal DB, SQL Server, etc.).

```http
GET /audit/logs?auditProfile=Ignite&startDate=1711900800000&endDate=1711987200000&actor=admin
```

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `auditProfile` | Yes | — | Ignition audit profile name |
| `startDate` | No | — | Epoch millis or ISO 8601 |
| `endDate` | No | — | Epoch millis or ISO 8601 |
| `actor` | No | — | Filter by actor (substring) |
| `action` | No | — | Filter by action (substring) |
| `target` | No | — | Filter by target (substring) |

Response:
```json
{
  "result": [
    {
      "action": "Tag Write",
      "actionTarget": "[default]Tag1",
      "actionValue": "100",
      "actor": "admin",
      "actorHost": "192.168.1.10",
      "originatingSystem": "Ignite",
      "timestamp": 1711900800000
    }
  ]
}
```

**Auth:** Requires admin permission.

---

### System

#### Gateway Info
```http
GET /system/info
```

Response:
```json
{
  "gatewayName": "MyGateway",
  "uptimeMs": 864000000,
  "jvmVersion": "11.0.15",
  "os": "Linux 5.10.0",
  "timezone": "America/Chicago",
  "modules": [
    { "id": "com.ignite", "name": "Ignite", "version": "1.0.0" }
  ]
}
```

**Auth:** Requires admin permission.

---

### Site Management

Sites are configured in the Gateway config UI, but you can also manage them via the API:

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/sites` | Public | List all sites with sync status |
| `POST` | `/sites` | Admin | Create a new site |
| `GET` | `/sites/:id` | Public | Get one site |
| `PUT` | `/sites/:id` | Admin | Update a site |
| `DELETE` | `/sites/:id` | Admin | Delete a site |
| `GET` | `/sites/:id/status` | Public | Get sync status |
| `POST` | `/sites/:id/sync` | Admin | Trigger immediate sync |
| `POST` | `/sites/:id/test` | Admin | Test git connectivity |

---

## Static File Serving

Ignite serves your frontend assets from git-synced repositories.

### URL Structure
```
/data/ignite/s/<mount>/           → index.html
/data/ignite/s/<mount>/assets/    → static assets (JS, CSS, images)
```

### Features

- **SPA Routing** — Requests without file extensions serve `index.html` (React Router, Vue Router, etc. work out of the box)
- **Environment Injection** — `window.__IGNITE_ENV__` is injected into every `index.html` with your site's configured environment variables
- **Caching** — Hashed filenames (`app.a1b2c3.js`) get `max-age=31536000, immutable`. HTML gets `no-cache`
- **ETag Support** — 304 Not Modified responses for unchanged assets
- **CORS** — Configured per-site via the `corsOrigins` field

### Environment Variables

Access your site's env vars in JavaScript:

```js
// Injected automatically into index.html as window.__IGNITE_ENV__
const gatewayUrl = window.__IGNITE_ENV__?.IGNITE_GATEWAY_URL;
const apiKey = window.__IGNITE_ENV__?.IGNITE_API_KEY;
```

Or fetch them explicitly:
```http
GET /data/ignite/sites/<name>/env
```

---

## Audit Logging

Ignite writes audit records to Ignition's built-in audit system. Configure the `auditProfileName` field on your site to enable logging.

**Audited actions:**
- Tag writes (path + new value)
- Alarm acknowledgments
- Site create/update/delete
- Script execution
- Audit log queries

Audit records include the actor (username or `api-key:<name>`), client IP, timestamp, and action details. View them in Ignition's audit log viewer or via the `/audit/logs` endpoint.

---

## Git Sync

Ignite clones and polls your git repositories over HTTPS.

- **Protocol:** HTTPS only (no SSH)
- **Authentication:** Personal Access Token (PAT) injected into the clone URL
- **Polling:** Configurable per-site (default 5 minutes)
- **Pre-built assets only:** Ignite serves files as-is from the repo. Run your build (`npm run build`, etc.) before pushing
- **Branch tracking:** Each site tracks a specific branch

### Recommended Workflow

1. Develop locally with your framework's dev server
2. Build production assets (`npm run build`)
3. Commit the built output (e.g., `dist/`) to a deploy branch
4. Ignite polls and serves the latest build

---

## Demo

A working demo app (React + Vite + Tailwind) is available at [EckmanTechLLC/ignite-demo](https://github.com/EckmanTechLLC/ignite-demo). It demonstrates:

- Live tag values via SSE
- Tag browsing and history charts
- Alarm monitoring and acknowledgment
- Database queries
- Script execution console
- SCADA-style Refrigeration P&ID with animated flow
- Login/logout with role-based UI
- Audit log viewer

---

## License

MIT

---

Built by [Eckman Tech LLC](https://eckman-tech.com)
