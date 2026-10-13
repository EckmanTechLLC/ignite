# ADR-005: Showcase Program — Multi-Demo Platform

**Date:** 2026-10-12
**Status:** Accepted (2026-10-12)
**Deciders:** @etl
**Supersedes:** ADR-002 (Dairy Plant Dashboard) once the first two demos are deployed

## Context

ADR-002's dairy-plant dashboard proved Ignite works but is not impressive, and it is coupled to the Quick Start simulator that no longer exists on the lab gateway. ADR-004 is now verified end-to-end on Ignition 8.3.10 (task-42). The next goal is a showcase that makes the value proposition obvious in thirty seconds: **any web technology, served by the gateway, with live access to every Ignition resource through Ignite** — tags, alarms, history, named queries, scripts, audit, and tiered auth.

Operator direction (2026-10-12): four themes over time — (1) Space / launch operations, (2) Data center infrastructure, (3) Microgrid, (4) Brewery — starting with 1 and 2. Each is a **single stunning screen**, not a multi-page app. Different front-end stacks across demos, including 3D and a non-React framework. No shortcuts. Live data comes from a gateway-side simulation the demo can steer. One public monorepo. The operator creates the historian provider; everything else is agent-built.

Lab facts the design must respect: `demo` is an empty on-disk 8.3 project (`data/projects/demo/`); no historian provider and no database connection exist; `Ignite-Users` has three test accounts (no roles / Operator / Operator+Administrator); the `demo` site record (id 1) serves the dairy demo; SSE delivers per-tag change events with a 30-minute connection cap and a heartbeat; `SiteRecord` has `branch` and `servePath`.

## Decision

### 1. One monorepo, source and deploy separated

`https://github.com/EckmanTechLLC/ignite-showcase` (public, MIT):

```
shared/client/     @ignite/client — framework-agnostic TypeScript library (see §5)
shared/theme/      CSS custom-property tokens, fonts (bundled), motion rules
launch/            Demo 1 — React 18 + Vite + React Three Fiber (+ drei) + ECharts
datacenter/        Demo 2 — Vue 3 + Vite + animated SVG + ECharts
sim/               Gateway side: tag model JSON, timer scripts, project library, scenarios
docs/              Per-demo README, ASSETS.md (licences), screenshots
```

npm workspaces; `pnpm` is not introduced (keep npm, already used). `main` holds source only. A `deploy` branch holds **built output only** (`launch/`, `datacenter/`, each the contents of a `dist/`), produced by `npm run deploy` which builds every demo and commits to `deploy` via a worktree. Site records on the gateway point at the same repo with `branch=deploy` and `servePath=launch` / `servePath=datacenter`. No `dist/` is ever committed to `main`. CI is out of scope for now; the script is the contract CI will later run.

### 2. Scope: one screen per demo

Each demo is a single full-viewport scene with overlays (slide-in panels, HUD strips). No router. The scene must read as a real control room at a glance and reward exploration. Breadth comes from the overlays: trends (history), alarms (status + acknowledge), operations (scripts, audit), identity (login tiers). Every Ignite route is exercised at least once per demo, listed in each demo's README as a feature matrix.

### 3. Demo 1 — Launch Operations (`launch/`, React + R3F)

- **Scene:** a 3D launch pad and vehicle, procedural low-poly geometry built in code (no third-party model files unless CC0, recorded in `ASSETS.md`), night lighting, pad floodlights, vapour/venting particles bound to live tags. Camera presets (pad, tank farm, flame trench, orbit).
- **Systems (tags under `[default]Launch/`):** LOX and CH4 loading (tank levels %, flow, temperatures, pressures, chilldown state), GSE power buses, deluge system, pad weather (wind, lightning distance), vehicle health (tank pressures, battery, avionics), countdown clock and sequencer state.
- **Sequencer:** T-minus countdown with built-in holds (T-40 min, T-10 min, T-40 s) and a go/no-go poll; implemented in the gateway sim; commands (`resume`, `hold`, `abort`, `recycle`) are project-library functions called through `POST /script/library` by an operator-tier user. Anonymous viewers see everything, control is disabled.
- **Telemetry:** streaming strip charts (ECharts, 1 Hz) for 6–8 channels, back-filled from `/tags/history` on open so a chart is never empty.
- **Alarms:** alarm overlay from `/alarms/status`, acknowledge requires operator tier, abort cascade (LOX leak scenario → pressure alarm → auto-hold → abort) shows a realistic storm and recovery.
- **Operations overlay (admin tier):** scenario launcher (`nominal`, `hold-at-T-40s`, `lox-leak-abort`, `weather-scrub`), audit ticker from `/audit/logs`, named-query panel if a datasource exists (degrades gracefully if not).

### 4. Demo 2 — Data Center Infrastructure (`datacenter/`, Vue 3)

- **Scene:** top-down animated SVG of one data hall: 6 rows × 10 racks with a live heat map, hot/cold aisle containment, two CRAH loops, and the power chain Utility → Switchgear → UPS A/B → PDU A/B → rack with animated flow proportional to load. Hover any element for live detail; click for its trend.
- **Systems (tags under `[default]DC/`):** per-rack kW, inlet/outlet temperature, PDU load, UPS state (online / battery / bypass, runtime remaining), chiller and CRAH supply/return temps, humidity, live PUE and IT load, capacity headroom.
- **Scenarios:** `utility-loss` (UPS to battery, runtime countdown, generator start), `crah-failure` (thermal rise, cascading rack alarms), `rack-migration` (load moves across the hall) — all via project-library scripts at operator tier.
- **Overlays:** alarm board with acknowledge, 24-hour PUE / load trend from history, failover test and scenario controls at operator tier, audit view at admin tier.
- **Why Vue:** proves `@ignite/client` and the server contract are framework-neutral; Vue's reactivity suits 1 Hz updates across hundreds of SVG elements.

### 5. `@ignite/client` (shared, TypeScript, zero runtime deps)

- Env bootstrap from `window.__IGNITE_ENV__` (`IGNITE_GATEWAY_URL`, `IGNITE_SITE`, `IGNITE_API_KEY`).
- Credential policy exactly as ADR-004: `X-Ignite-Site` on every call, `X-Ignite-Key` header, Bearer token when logged in; on SSE the key/token/site travel as query params.
- SSE client with reconnect/backoff, the 30-minute reconnect handled transparently, and **frame coalescing**: changes are buffered and delivered once per animation frame as a batch (fixes ADR-002's one-render-per-event problem).
- REST wrappers: tags (read/write/browse/history), alarms (status/history/acknowledge), scripts (library/system/projects), db (named), audit, auth (login/session/logout) with 401 → `unauthorized` event.
- Vitest unit tests against a mocked fetch/EventSource. Published only as a workspace package.

### 6. Gateway-side simulation (`sim/`)

- **Tag model** as JSON (`sim/tags/launch.json`, `sim/tags/dc.json`) in `system.tag.configure` format: memory tags with engineering units, alarm definitions (setpoint / out-of-range with priorities), and history enabled on trended tags. Loaded through Ignite's own `POST /script/system` → `system.tag.configure` as an operator-tier test user (dogfooding, as in task-42).
- **Historian:** operator creates an Internal Historian provider named `IgniteHistorian` once (Config → Tags → History). Trended tags reference it; the demos query it only through `/tags/history`.
- **Timer scripts:** two gateway timer scripts in project `demo`, 1 000 ms, fixed-rate, one per demo, each advancing a small physics-ish model (first-order lags, noise, scenario state machine) and writing its tags in one `system.tag.writeBlocking` call. State lives in tags (`[default]Launch/_Sim_/...`), not in script globals, so the Designer and the demos see it and a gateway restart is harmless.
- **Project library:** `launch.py` / `dc.py` modules (scenario triggers, sequencer commands, derived KPIs) callable via `/script/library`; function names validated by ADR-004's path regex.
- **Authoring:** 8.3 projects are on-disk resources. Before writing any resource by hand, the agent obtains a reference: the operator creates one trivial timer script and one library script in the Designer; the agent copies that exact on-disk format and project `resource.json` conventions. Nothing is guessed about the format.

### 7. Visual and engineering standards

- Dark "control room" theme from shared tokens; one accent per demo. 1080p to 4K responsive; 60 fps scene with SSE coalescing; no runtime calls to any host other than the gateway (fonts and assets bundled). Lighthouse performance ≥ 90 on the built page.
- Every asset licence recorded in `docs/ASSETS.md`; procedural geometry preferred over models.
- No secrets in the repo: `__IGNITE_ENV__` injection is the only configuration source; `.env.example` placeholders only.
- Auth is role-generic in the UI ("Viewer / Operator / Director"); the lab maps these to its three test accounts.

### 8. Deployment on the lab

New site records `launch` and `datacenter` (repo `ignite-showcase`, `branch=deploy`, `servePath` per demo, `WriteSecurityLevel=Operator`, `AdminSecurityLevel=Administrator`, audit profile = the lab's profile). The `demo` dairy site stays until both new demos pass their browser checklist, then it is deleted with ADR-002 marked Superseded.

## Consequences

- **Positive:** two visibly different stacks on one server contract; the shared client fixes the SSE render problem for every future demo; the sim is reusable for demos 3 and 4; a `deploy` branch keeps source history clean.
- **Negative:** three toolchains (React/R3F, Vue, Jython) and a monorepo to maintain; 3D raises the bar for performance testing; `deploy` must be rebuilt by hand until CI exists.
- **Risks:** 8.3 on-disk project format unknown until the Designer sample exists (mitigated by §6 Authoring); R3F + 1 Hz tag binding needs care (bind via refs, not React state); Internal Historian query behaviour through `system.tag.queryTagHistory` on 8.3 unverified (first task checks it).
- Backlog items from the 2026-10-09 review that these demos may surface: SSE connection cap and shutdown handling, ETag ignoring injected env. They are fixed only if a demo task hits them, each as its own task.

## Alternatives considered

- Reskin the dairy dashboard — rejected: structure is the problem, not the colours.
- One repo per demo — rejected: duplicated client/auth/theme code; monorepo with workspaces is the standard answer.
- Programmable Device Simulator — rejected: no scripted scenarios, less story control.
- External data generator via the API — rejected: another process to run; the gateway can simulate itself.
- Perspective for comparison — out of scope; Ignite's pitch does not require it.

## Task breakdown (each ≤ 5 deliverables; sequential unless noted)

| Task | Scope | Depends on |
|---|---|---|
| task-49-showcase-scaffold | Create `ignite-showcase` repo, workspaces, `shared/theme`, `shared/client` with tests, `npm run deploy` worktree script, root README | — |
| task-50-sim-reference | Operator creates `IgniteHistorian` + one Designer timer script + one library script; agent captures on-disk formats, verifies `/tags/history` against the internal historian with one trended tag | — (operator step first) |
| task-51-sites-skeletons | Site records `launch` + `datacenter` on the lab (branch `deploy`), early end-to-end check of `@ignite/client` with the skeletons | 49 |
| task-52-sim-launch | `sim/tags/launch.json`, launch timer script, `launch.py` library, scenarios; loaded on the lab; verified via Ignite routes | 50 |
| task-53-sim-datacenter | Same for `DC/` | 50 (parallel with 52) |
| task-54-launch-scene | R3F pad/vehicle scene, camera presets, live bindings, HUD countdown | 49, 52 |
| task-55-launch-overlays | Telemetry charts, alarms, sequencer controls, ops/audit, login tiers | 54 |
| task-56-dc-scene | Vue SVG hall, heat map, power chain and cooling animation, hover/click | 49, 53 (parallel with 54) |
| task-57-dc-overlays | Alarm board, PUE/load trends, scenario and failover controls, audit | 56 |
| task-58-deploy-showcase | `deploy` branch, site records, smoke matrix subset, browser checklist for both demos; retire dairy site; ADR-002 → Superseded | 55, 57 |
| task-59-polish-perf | Lighthouse, 4K, SSE coalescing measurements, screenshots in `docs/` | 58 |

Demos 3 (Microgrid) and 4 (Brewery) will be amendments to this ADR once 1 and 2 ship.

## Amendment A1 — Demo 3: Campus Microgrid (2026-10-13, Accepted)

**Why now:** demos 1 and 2 shipped (tasks 49–64); the sim pattern, `@ignite/client`, deploy branch and site model are proven. Demo 3 adds the third framework and a history-heavy story (forecast vs actual, cost), which the first two only touched.

### Stack
`microgrid/` — **Svelte 5 + Vite + TypeScript**, 2D animated **single-line diagram** (SVG) with ECharts (lazy) for trends. Third framework on the same `@ignite/client`; Svelte's compiled reactivity suits dozens of animated conductors. Shared theme, own accent.

### One screen
A campus single-line diagram as the hero: utility intertie → main switchboard → feeders to **PV arrays** (4 arrays, 6 strings each, 2 inverters per array), **BESS** (2 containers), **genset**, **EV charging** (10 chargers), **buildings** (3 loads incl. HVAC). Conductors animate with direction and magnitude of power flow; the sun arc and cloud layer at the top drive irradiance; a tariff strip shows the time-of-use band; a cost/CO₂ ticker runs live. Overlays as before: Trends (T), Alarms (A), Dispatch controls (C), Identity/Audit (L), Help (?). Click any element → its trend.

### Simulation (`sim/microgrid/`, tags under `[default]MG/`)
- **Time:** compressed day — 24 simulated hours in **12 real minutes** by default (`_Sim_/SpeedFactor` = 120), adjustable; the sim clock (`MG/Time/HourOfDay`, `DayIndex`, `Season`) drives the sun model (elevation → clear-sky irradiance), a cloud field (random walk + fronts), ambient temperature, building load profiles and the tariff schedule.
- **PV:** per-string DC kW from irradiance × area × temp-derated efficiency; inverter AC kW with clipping at rated; per-inverter state (`RUN|CURTAILED|FAULT|OFF`); curtailment setpoints; array-level `Health`.
- **BESS:** 2 × 500 kWh / 250 kW; SOC, kW (±), cell temp, round-trip losses, `Mode` (`PEAK_SHAVE|SELF_CONSUME|BACKUP|IDLE|ISLAND`), throughput counter (degradation hint), limits by temp.
- **Grid:** import/export kW, V, Hz, `Available`, `Islanded`, time-of-use tariff ($/kWh by band), demand charge window, daily import kWh and cost, CO₂ intensity curve → tCO₂ avoided.
- **Genset:** 400 kW diesel, `State`, fuel, start sequence (10 s), only in island mode.
- **EV:** 10 chargers × 22 kW; sessions arrive with a daily pattern (rush hour 07–09 and 17–19); per-charger kW, SOC of the connected car, `Smart` curtailment on grid stress.
- **Loads:** 3 buildings with weekday profiles + HVAC term from ambient; critical-load flag on one.
- **KPIs:** net load, PV fraction, self-sufficiency %, peak import today vs baseline, $ today vs baseline, CO₂ avoided — the baseline is "no PV/BESS" computed in the same tick.
- **Scenarios (`mg.*` library):** `grid_outage` (island → BESS carries critical load → genset start → PV curtails to balance → resync on `grid_restore`), `cloud_front_ev_rush` (PV collapses during the evening EV ramp; dispatch shifts; tariff cost spikes), `peak_shave_day` (BESS dispatches against the forecast; compare with baseline), `inverter_fault` (array 2 inverter B fault, string losses, alarm cascade, `clear_fault`). Plus `set_speed`, `set_hour` (jump), `set_mode`, `set_cloudiness`, `status`.
- **Alarms:** inverter fault, string underperformance (vs siblings), BESS temp high / SOC low in backup, grid lost, islanded, frequency/voltage out of band, charger fault, demand threshold exceeded.
- **History:** KPIs and feeder kW at 1 s on change; strings at 10 s with deadband; the forecast curve is written as a tag (`MG/Forecast/PVkW`, `LoadkW`) so "forecast vs actual" is a plain two-series history chart.

### Deployment
Site `microgrid` (servePath `microgrid`, `TagPathPatterns` = `[default]MG/**`, same tiers/audit); Home → Launch card appears automatically.

### Tasks
| Task | Scope | Depends on |
|---|---|---|
| task-66-sim-microgrid | `sim/microgrid` tag model (~400 tags), timer + `mg` library, scenarios; loaded and proven on the lab | 50, 52 pattern |
| task-67-mg-scene | Svelte app: single-line diagram, sun/cloud strip, flow animation, tariff/cost ticker, HUD, `?perf`, help | 49, 66 |
| task-68-mg-overlays | Trends (forecast vs actual, click-to-trend), alarms + ack, dispatch controls, identity/audit, feature matrix | 67 |
| task-69-mg-ship | site record + scope, smoke matrix, screenshots, perf doc, runbook update, README | 65, 68 |
