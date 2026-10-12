# ADR-001: Ignite Module Architecture

**Status:** Accepted
**Date:** 2026-03-24

## Context

Ignition's core platform (tags, alarms, databases, OPC-UA, scripting, identity providers) is robust, affordable, and feature-rich. However, its UI modules (Perspective, Vision) are expensive and painful to develop with. Modern web frameworks (React, Astro, Vue, etc.) enable rapid, beautiful UI development.

Ignite is an Ignition 8.3+ module that:
1. Serves static web UIs pulled from GitHub repositories
2. Exposes Ignition's data layer via REST and WebSocket/SSE APIs
3. Supports multiple independent sites per gateway
4. Uses Ignition's built-in authentication and identity providers

## Decision

### Module Scope: API Gateway + Static Site Host

The module has two responsibilities:

**A. Data API Layer** — REST endpoints and WebSocket/SSE connections exposing:
- Tag reads/writes, subscriptions (real-time via WebSocket/SSE)
- Tag history queries
- Database connections, named queries, arbitrary SQL
- Alarm status and history
- OPC-UA browsing and reads
- Project resource access
- System info / gateway status

**B. Static Site Host** — Pulls and serves static sites from Git repos:
- Configurable list of sites via Gateway Config page
- Each site: repo URL, branch, PAT (optional, for private repos), poll interval, mount path
- Periodic polling for repo changes (configurable interval per site)
- Serves built static assets from the gateway (e.g., `https://<gateway>/ignite/<site-name>/...`)
- Environment variables configurable per site (injected at serve time or via API)
- Support for any frontend framework that produces static output (React, Astro, Vue, Svelte, etc.)

### SDK Version: 8.3+

- Uses Ignition SDK 8.3 with React/TS/Webpack toolchain
- The module's own Gateway Config pages can leverage the built-in web build
- Served sites are framework-agnostic (just static files from git repos)

### Authentication

- Leverages Ignition's existing Identity Providers (IdP) and user/role system
- API endpoints authenticate against Ignition's session/auth mechanisms
- No custom auth layer

### Real-Time Data: WebSocket/SSE

- Tag subscriptions and alarm events delivered via WebSocket or SSE (not polling)
- REST for request/response queries (tag history, DB queries, etc.)
- Avoids tech debt of a polling-first approach

### Multi-Site Architecture

- Gateway Config page allows defining N sites
- Each site is independently configured (repo, branch, PAT, poll interval, mount path, env vars)
- Sites are isolated from each other
- Different teams/areas can have different UIs on the same gateway

### External Hosting Support

- Sites can also run externally (any hosting platform)
- External UIs connect to the module's API endpoints via configured URL + env vars
- CORS configuration per site to allow cross-origin access
- API auth tokens for external consumers

## Architecture Overview

```
+------------------------------------------+
|           Ignition Gateway               |
|                                          |
|  +------------------------------------+  |
|  |         Ignite Module              |  |
|  |                                    |  |
|  |  +-------------+  +------------+  |  |
|  |  | Site Manager |  | Data API   |  |  |
|  |  |              |  |            |  |  |
|  |  | - Git pull   |  | - REST     |  |  |
|  |  | - Serve      |  | - WS/SSE  |  |  |
|  |  | - Env vars   |  | - Auth     |  |  |
|  |  | - Multi-site |  | - CORS     |  |  |
|  |  +-------------+  +------------+  |  |
|  |         |                |         |  |
|  +---------|----------------|--------+   |
|            |                |            |
|  +---------v-+  +-----------v--------+   |
|  | Static    |  | GatewayContext      |  |
|  | Files     |  | (Tags, DB, Alarms, |  |
|  | (on disk) |  |  OPC, IdP, etc.)   |  |
|  +-----------+  +--------------------+   |
+------------------------------------------+

External UI (optional):
  Browser -> https://my-app.onrender.com
                  |
                  +---> https://<gateway>/api/ignite/...
```

## Module Structure (Gradle subprojects)

```
ignite/
  common/        — Shared models, constants (scope: GD)
  gateway/       — GatewayHook, API endpoints, Site Manager, WebSocket (scope: G)
  designer/      — DesignerHook, minimal (scope: D)
  web/           — Gateway config page frontend (8.3 TS/React build)
```

## Site Configuration Model

Each site record in the Gateway Config:

| Field | Type | Description |
|-------|------|-------------|
| name | String | Unique site identifier |
| mountPath | String | URL path segment (e.g., `/ignite/plant-hmi/`) |
| repoUrl | String | Git repository URL |
| branch | String | Branch to track (default: `main`) |
| pat | String (encrypted) | Personal Access Token for private repos |
| pollIntervalMinutes | Integer | How often to check for changes (default: 5) |
| envVars | String (JSON) | Key-value env vars injected into the site |
| corsOrigins | String | Allowed CORS origins (for external hosting mode) |
| enabled | Boolean | Enable/disable site |
| apiKey | String (encrypted) | Per-site API key for external consumers |
| servePath | String | Subdirectory to serve (e.g., `dist/`, `build/`) |

## Data API Endpoints (initial scope)

### REST (`/api/ignite/...`)
- `GET /tags/read?paths=...` — Read tag values
- `POST /tags/write` — Write tag values
- `GET /tags/history?paths=...&start=...&end=...` — Tag history
- `GET /tags/browse?path=...` — Browse tag tree
- `GET /alarms/status` — Current alarm states
- `GET /alarms/history?start=...&end=...` — Alarm journal
- `GET /db/connections` — List database connections
- `POST /db/query` — Execute named query or SQL
- `GET /system/info` — Gateway status and info

### WebSocket/SSE (`/api/ignite/ws` or `/api/ignite/events`)
- Subscribe to tag value changes
- Subscribe to alarm events
- Subscribe to tag browse changes

## Consequences

### Positive
- Any web developer can build Ignition UIs with modern tools
- No Perspective/Vision license needed for custom UIs
- Rapid iteration — push to GitHub, site auto-updates
- Multi-site enables different UIs for different roles/areas
- External hosting option for cloud/hybrid architectures
- Real-time data from day one (no polling tech debt)

### Negative
- No drag-and-drop designer (developers write code)
- Must maintain API compatibility as Ignition SDK evolves
- Git polling adds slight delay vs instant deploy (acceptable tradeoff for SCADA environments)
- WebSocket/SSE adds complexity vs simple REST polling

### Risks
- Ignition's internal APIs for tags/alarms are not all public — may need to use GatewayContext carefully
- Auth integration with IdP needs thorough testing across different IdP types
- Static site serving from within a Java gateway module — need to handle MIME types, caching, SPA routing correctly

## Resolved Questions

1. **Build commands** — v1 requires pre-built static assets in the repo. No build commands on the gateway. Use CI/CD (GitHub Actions) to build and commit artifacts.
2. **API auth** — Per-site API keys for external consumers, plus Ignition session auth for hosted sites.
3. **Git transport** — HTTPS + PAT only for v1. No SSH support needed.
