# ADR-002: Showcase Demo — Dairy Plant Dashboard

**Date:** 2026-03-25
**Status:** Superseded by ADR-005 (2026-10-12)
**Deciders:** @etl

## Context

Ignite's proof-of-concept demo (tasks 08/11) served its purpose for development testing but is a minimal tag browser/trending tool. To demonstrate Ignite's value proposition — that it can replace Perspective with any modern web framework — we need a production-quality showcase demo built around a realistic facility.

The Ignition gateway runs the built-in dairy plant simulator with:
- **Refrigeration**: 2 compressors (17 tags each), 2 cooling towers, ambient sensors, valves, levels
- **Overview/Production**: 8 motors, 3 sensors, 2 palletizers, pallet wrapper, 3 air units, production counters
- **Controls**: Pause, Reset, Repeat, Base Rate

Target audience: Ignition community (operators, integrators, executives). The demo must look professional and showcase that any web technology can access all Ignition resources through Ignite.

## Decision

Build a multi-page React dashboard application themed as a dairy plant control interface.

### Tech Stack

- **React 18 + Vite** — Same base as POC, proven to work with Ignite
- **React Router v6** — Client-side routing for multi-page SPA (works with Ignite's SPA fallback)
- **Tailwind CSS v3** — Utility-first CSS for rapid, consistent, professional styling
- **Chart.js 4** — Already proven in task-11 for historical trending
- **Lucide React** — Lightweight icon library (MIT, tree-shakeable)

No server-side rendering needed — Ignite serves pre-built static assets.

### Pages

#### 1. Dashboard (/)
Plant overview at a glance.
- Live KPI cards: ambient temp, ambient humidity, case count, compressor count active
- Equipment status grid: motors (8), palletizers (2), air units (3), pallet wrapper — color-coded by HOA state (Off/Hand/Auto)
- Active alarm banner with count (links to Alarms page)
- All values via SSE — no polling

#### 2. Refrigeration (/refrigeration)
Compressor and cooling infrastructure detail.
- Compressor 1 & 2 panels: pressures (suction, discharge, oil), temperatures (suction, discharge, oil sep), amps, voltage, % loaded, tonnage (current vs ideal), HOA
- Cooling Tower 1 & 2: fan speed, pH, temp, makeup, water pumps
- System-level: accumulator level, receiver level, discharge pressure/temp, valve positions
- Mini trend charts for key values (inline sparklines or small Chart.js)

#### 3. Production (/production)
Packaging line and motor status.
- Motor status table: 8 motors with amps, HOA state, status indicator
- Palletizer 1 & 2: HOA, pattern, type
- Pallet wrapper: HOA, pattern
- Sensor readings: 3 zones, temp + humidity each
- Case count with trend chart
- Production counters: CurrentOrder, CurrentRun, RunControl

#### 4. Trending (/trending)
Multi-tag historical explorer — the "power user" page.
- Tag picker (searchable tree browser)
- Select multiple tags, overlay on single chart
- Time range selector: 1h, 6h, 24h, 7d, custom
- Auto-refresh toggle (re-query on interval)
- Point count / resolution selector
- Export hint (copy data, etc.)

#### 5. Alarms (/alarms)
Alarm management.
- Active alarms table: path, display value, priority, state, timestamp
- Acknowledge button per alarm (requires new module endpoint)
- Alarm history tab: filterable by time range, priority
- Auto-refresh via SSE alarm events
- Alarm count badge in sidebar nav (always visible)

#### 6. System (/system)
Gateway administration view.
- Gateway info card: name, uptime, JVM, OS
- Module list table with version info
- Tag browser (improved from POC — full tree navigation)
- Database connections list
- Demonstrates admin-level Ignite API access

### Layout

- **Sidebar navigation**: collapsible, icons + labels, active page highlight, alarm badge
- **Header bar**: Ignite branding, SSE connection status indicator, dark themed
- **Content area**: scrollable, responsive grid
- **Responsive**: full layout >= 1024px, collapsed sidebar on smaller screens

### Module Changes Required

One new endpoint:
- `POST /alarms/acknowledge` — Acknowledge one or more alarms by UUID/display path

No other module changes needed — all existing APIs (tags, alarms, SSE, system, DB) are sufficient.

### Prerequisites (manual Ignition configuration)

Before the demo is functional:
1. Configure alarm thresholds on ~5 simulator tags (compressor discharge temp high, ambient temp high/low, accumulator level, etc.)
2. Set up a database connection (internal SQLite or external) so the System page can list it

### Deployment

Same workflow as current demo:
1. `cd demo && npm run build`
2. Push `dist/` to git repo
3. Trigger sync from Ignite config page
4. Served at `/data/ignite/s/<mount>/`

## Alternatives Considered

- **Astro**: Good for content-heavy sites with islands of interactivity. This is a full SPA with constant live data — React SPA is a better fit.
- **Next.js**: SSR adds complexity with no benefit — Ignite serves static files, no Node.js on the gateway.
- **Vue/Svelte**: Equally valid but React is already proven in the project and more familiar to the Ignition community (Perspective uses React).
- **No CSS framework**: Would take significantly longer to achieve professional polish. Tailwind is widely adopted and tree-shakes to tiny bundles.

## Consequences

- The existing `demo/` directory will be replaced with the new showcase app
- Tailwind CSS and React Router are new dependencies (approved for this task)
- Lucide React is a new dependency (approved for this task)
- One new module endpoint (alarm acknowledge) is required
- Alarm thresholds and DB connection must be configured manually in Ignition Designer before demo is fully functional
