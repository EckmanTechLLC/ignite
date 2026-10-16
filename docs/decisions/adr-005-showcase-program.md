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
| task-66-sim-microgrid | `sim/microgrid` tag model (~400 tags), timer + `mg` library, scenarios; loaded and proven on the lab (complete) | 50, 52 pattern |
| task-67-mg-scene | Svelte app: single-line diagram, sun/cloud strip, flow animation, tariff/cost ticker, HUD, `?perf`, help (complete) | 49, 66 |
| task-68-mg-overlays | Trends (forecast vs actual, click-to-trend), alarms + ack, dispatch controls, identity/audit, feature matrix (complete) | 67 |
| task-69-mg-ship | site record + scope, smoke matrix, screenshots, perf doc, runbook update, README (complete) | 65, 68 |

## Amendment A2 — Demo 4: Craft Brewery (2026-10-13, Accepted)

**Why now:** demos 1–3 shipped (tasks 49–71). Demo 4 adds the fourth framework, a GPU-drawn 2D scene, and the two stories the first three lack: **batch/recipe manufacturing** (ISA-88 phases, genealogy) and **discrete-line OEE** (packaging downtime, reason codes, rejects).

### Stack
`brewery/` — **SolidJS 1.9 + Vite + TypeScript**, scene drawn with **PixiJS 8** (WebGL, isometric 2D plant with particle effects: steam, boil, CO₂ bubbles, cans on conveyors), overlays as Solid DOM, ECharts (lazy) for trends. Fourth framework on the same `@ignite/client`; fine-grained signals suit hundreds of per-tag updates without a virtual DOM. Shared theme, own accent (copper). New runtime dependencies: `solid-js`, `vite-plugin-solid`, `pixi.js` — accepted cost ≈ 400 kB gz for the renderer; Lighthouse ≥ 90 remains the target but the WebGL bundle is an accepted risk, recorded in `docs/perf.md`.

### One screen
An isometric craft brewery as the hero, left to right: **brewhouse** (mill → mash tun → lauter tun → kettle → whirlpool → wort chiller, with hot/cold liquor tanks), **cellar** (12 fermenters FV01–FV12, 4 bright tanks BBT1–BBT4, yeast brink), **packaging line** (depalletizer → rinser → filler/seamer → pasteurizer → labeler → packer → palletizer, with conveyors), **utilities** (steam boiler, glycol chiller, CO₂, compressed air, CIP skid). Vessels show level, temperature and phase; wort/beer flows animate along pipes; cans move on the line at the real line speed; each vessel carries its **batch tag** (recipe colour). A **batch strip** at the top is a live Gantt: every active batch as a bar across phases (Mash → Lauter → Boil → Whirlpool → Knock-out → Fermentation → Conditioning → Packaging), with the current brew day's schedule. A **line KPI tile** shows OEE (availability × performance × quality) with the current downtime reason. Overlays: Trends (T), Alarms (A), Controls (C), Identity/Audit (L), **Batch (B)** — ISA-88 detail and genealogy for the selected batch, Help (?). Click a vessel → its batch; click the line → OEE detail.

### Simulation (`sim/brewery/`, tags under `[default]BR/`)
- **Time:** `_Sim_/SpeedFactor` default **60** (1 real minute = 1 simulated hour; a 10-hour brew day = 10 real minutes). Fermentation biology is additionally accelerated by `_Sim_/FermentAccel` (default **8**) so a 5-day primary completes in ≈ 15 real minutes — dramatised like the microgrid's electrical clock, documented as such. A batch runs end-to-end in ≈ 30 real minutes; the scheduler keeps 4–6 batches overlapping so the screen is never idle.
- **Recipes (4):** Pilsner, IPA, Stout, Wheat — each with mash profile (temperatures/rests), boil length, hop additions, target OG/FG, ferment temperature and yeast strain, conditioning days, packaging format (can/keg). Recipe parameters live as tags under `BR/Recipes/<name>/*`.
- **Brewhouse (ISA-88 unit state machines):** each unit has `Phase`, `PhaseTimeRemainingMin`, `BatchId`, `Recipe`, `Level%`, `TempC`, `Setpoint`, plus unit-specific process values (mash pH and gravity, lauter run-off turbidity and flow, kettle gravity/evaporation/boil-over risk, whirlpool rest, knock-out flow and wort-out temperature). Steam demand and hot/cold liquor consumption drive the utilities.
- **Cellar:** 12 fermenters with `BatchId`, `Phase` (`EMPTY|FILLING|FERMENTING|DIACETYL_REST|CRASH|CONDITIONING|TRANSFER|CIP`), `TempC`/`SetpointC`, `GravityP` (plato, fermentation kinetics: lag → exponential → taper), `pH`, `DOppb`, `PressurePSI`, `CO2VolPct`, `ABVEst`, `Level%`, `DaysInTank`, glycol valve %. 4 bright tanks with carbonation and `ReadyToPackage`. Yeast brink generation counter.
- **Packaging line:** `LineState` (`RUNNING|IDLE|STARVED|BLOCKED|DOWN|CHANGEOVER|CIP`), `SpeedCPM` (cans/min, rated 300), `GoodCount`, `RejectCount` (fill-level, seam, label), `DowntimeReason` (`NONE|FILLER_JAM|LABEL_OUT|CAN_SUPPLY|SEAMER_FAULT|CHANGEOVER|CIP|NO_BEER`), per-machine `State`/`Fault`, run `BatchId`, `OEE/Availability|Performance|Quality|OEE`, `ShiftTargetCans`, `PalletsToday`.
- **Utilities:** boiler pressure/steam flow/fuel, glycol supply/return temps and load %, CO₂ tank level and consumption, air pressure, CIP skid (`Step`, conductivity, temperature, target unit), water and energy today.
- **Quality / lab:** per batch sample results written when a phase completes (`OG`, `FG`, `ABV`, `pH`, `IBU` est., `DO`, `Status` `PASS|HOLD|FAIL`); `HOLD` blocks transfer to packaging until released via the library.
- **Batches & genealogy:** `BR/Batches/B01..B24/*` ring of batch records (`Id`, `Recipe`, `State`, `StartTime`, phase timestamps, source FV, BBT, packaging run, pallet count, QA status); `BR/Schedule/*` the brew-day plan (next recipe, start hour, brews/day).
- **KPIs:** batches in progress, brews today, hl today, cellar occupancy %, average fermentation days, line OEE (shift), good cans today, rejects %, energy/hl, water/hl, CO₂ recovered %.
- **Scenarios (`brew.*` library):** `glycol_failure` (chiller load trips → cellar temps drift up, fermenters alarm, QA at risk; `glycol_restore`), `filler_jam` (line DOWN with reason, OEE falls, rejects spike after restart), `stuck_sparge` (lauter run-off slows, brew day slips, downstream Gantt moves), `boil_over` (kettle foam/level excursion, alarm, operator reduces steam), `contamination` (one FV pH drops/gravity stalls → QA `HOLD` → genealogy shows what is at risk; `qa_release`/`qa_dump`), `rush_order` (schedule compressed to 3 brews/day, line to 2 shifts). Plus `status`, `start_batch(recipe)`, `set_schedule(brews_per_day, recipes)`, `set_speed`, `hold_line`/`resume_line`, `changeover(format)`, `clear_fault(name)`, `inject_fault(name)`, `cip(unit)`, `qa_release(batch)`, `qa_dump(batch)`, `restore()`.
- **Alarms:** mash temperature deviation, kettle boil-over / level high, lauter stuck (flow low), FV temperature deviation (per FV), FV pressure high, glycol supply temperature high, glycol load high, CO₂ level low, boiler pressure high/low, air pressure low, filler jam, seamer fault, label stock out, reject rate high, CIP conductivity low, QA hold, cellar occupancy full.
- **History:** vessel temperatures/gravity at 1 s on change (deadband), line counts and OEE at 1 s on change, utilities at 5 s, batch records on change.

### Deployment
Site `brewery` (servePath `brewery`, `TagPathPatterns` = `[default]BR/**`, same tiers/audit); Home → Launch card appears automatically; `deploy.mjs` already covers every workspace app.

### Tasks
| Task | Scope | Depends on |
|---|---|---|
| task-72-sim-brewery | `sim/brewery` tag model (~550 tags), timer `br_sim` + `brew` library, scenarios, batch ring + genealogy; site record `brewery` (disabled, scoped); loaded and proven on the lab (complete) | 66 pattern |
| task-73-br-scene | Solid + Pixi app: isometric plant, flows/particles, vessel cards, batch Gantt strip, OEE tile, HUD, `?perf`, help (complete) | 72 |
| task-74-br-overlays | Trends (fermentation curves, OEE waterfall, click-to-trend), alarms + ack, controls by tier, Batch overlay (ISA-88 + genealogy + QA), identity/audit, feature matrix (complete) | 73 |
| task-75-br-ship | enable + sync, smoke matrix, perf/a11y, screenshots, perf doc, runbook, README (complete) | 74 |

## Amendment A3 — Hero scene redo for demos 2 and 3 (2026-10-14, Accepted)

**Why:** operator review after demo 4: Data Center and Campus Microgrid read as tile dashboards — grids of numbers with a diagram between them, which a Perspective view could reproduce. The program's point is what Perspective cannot easily do: one SSE batch per animation frame driving a GPU render loop over hundreds of tags. Launch (3D pad) and Brewery (GPU isometric plant) show that; demos 2 and 3 do not. Frameworks, sims, tag models, site records and the overlays (T/A/C/L/?) stay; only the hero scene and the HUD framing change.

### Demo 2 — Data Center: 3D data hall (`datacenter/`, Vue 3 + TresJS)
- **Renderer:** TresJS (`@tresjs/core`, Vue's Three.js renderer) + `three` (same major as Launch) + `@tresjs/cientos` (orbit controls, helpers). Procedural geometry only. Post-processing bloom only if cheap (reuse the Launch approach; optional).
- **Scene:** the hall as a room: raised floor, 6 rows × 10 racks with cold-aisle containment (translucent doors/roof), hot aisles, 4 CRAH units on the walls, overhead busway and the chilled-water loop as lit conductors; UPS A/B, PDUs, ATS and generator in a visible electrical room; chillers on the roof. Rack fronts glow by inlet temperature on the ASHRAE scale, with LED strips per rack (kW load) and a "zz"/offline state. **Airflow particles** (GPU points): emitted at each CRAH at a rate ∝ fan %, flowing under the floor, up through the cold aisle, through racks (heating → colour shift), into the hot aisle and back to the CRAH return — a stalled CRAH visibly starves its aisle and heat pools; heat shimmer over hot aisles. Power chain lights the live feed path (utility / generator / UPS on battery) with pulsing conductors.
- **Camera:** cinematic idle orbit; presets (overview, cold aisle walk-through, hot aisle, electrical room, roof) on keys 1–5; click a rack / CRAH / UPS → selection (existing stores) with a focus fly-to; hover tooltip (DOM anchored).
- **HUD:** thin edge strips instead of a top tile bar: PUE ring + IT/facility kW at top-left, hall mode, health/identity chips; the row-summary block and heat-scale legend become a collapsible legend. Overlays unchanged.
- **Perf:** instanced meshes (racks, particles via `Points` with a custom shader), ≤ 60 draw calls at rest; reduced-motion → no particles, static camera.

### Demo 3 — Campus Microgrid: living campus map (`microgrid/`, Svelte 5 + PixiJS)
- **Renderer:** PixiJS 8 (already in the repo) with custom filters/shaders; procedural art only.
- **Scene:** a top-down/slightly oblique campus: 4 PV fields (per-string rows), 2 BESS containers, genset enclosure, EV lot with 10 bays, 3 buildings with windows, utility substation at the fence, roads and trees. **Real sun:** shadows of buildings, trees and PV rows sweep with `Time/HourOfDay` (length and direction from `SunElevationDeg` and azimuth derived from hour); dawn/dusk colour grading; at night windows light up by building kW and streetlights come on. **Clouds:** a noise-shader cloud layer drifts (speed/opacity from `Cloudiness`); its shadow visibly dims the PV rows it covers — the per-array irradiance tags drive array brightness so the pass reads correctly. **Energy:** power as particle streams along buried conductors whose density ∝ kW and colour by source (PV amber, BESS blue, grid green, genset grey); islanding snaps the substation link open with a flash, the campus dims for a beat, the genset enclosure starts (exhaust shimmer) and the flows re-route; EV bays show cars arriving/leaving with SOC rings; curtailed arrays flicker; faulted inverters pulse red.
- **Camera:** fit-to-viewport with gentle drift; pan/zoom; click any asset → selection (existing stores); hover tooltip.
- **HUD:** the day/tariff strip stays (it is the clock) but becomes a translucent band over the scene; KPI tiles collapse into a compact left rail; cost/CO₂ ticker stays. Overlays unchanged.
- **Perf:** sprites batched, one cloud filter pass, particles via a `ParticleContainer`; reduced-motion → static shadows, no particles.

### Consequences
- Data Center adds `three`, `@tresjs/core`, `@tresjs/cientos` (bundle +≈ 600 kB raw, lazy); the Lighthouse ≥ 90 target is carried as "recorded, not met on software GL" like Launch/Brewery.
- Screenshots, `docs/perf.md`, READMEs and the runbook are refreshed per demo; `docs/ASSETS.md` stays procedural-only.

### Tasks
| Task | Scope | Depends on |
|---|---|---|
| task-80-dc-hall-3d | TresJS hall scene replacing `datacenter/src/hall` + HUD reframing; overlays rewired to the new selection; deploy (complete) | 56, 57 |
| task-84-dc-hall-detail | detail pass on the hall (modelled racks, CRAHs, UPS/PDU/ATS, generator, chillers, containment, busways, pipework; merged batches, IBL, static shadows) (complete) | 80 |
| task-81-dc-ship | close-out: smoke matrix, perf/a11y, screenshots, perf.md, READMEs, runbook (complete) | 80, 84 |
| task-82-mg-campus | PixiJS campus scene replacing `microgrid/src/diagram` + HUD reframing; overlays rewired; deploy (complete) | 67, 68 |
| task-85-mg-campus-detail | oblique 2.5D detail pass on the campus (modelled facades, racking, containers, enclosure, canopy, substation yard, kerbed roads, pond) (complete) | 82 |
| task-83-mg-ship | close-out as above (complete) | 82, 85 |

## Amendment A4 — Demo 5: GMP Cleanroom Suite (2026-10-15, Accepted)

**Why now:** an integrator asked to see a cleanroom build on Ignite. It is also the first demo with **several pages** (router, deep links, per-page subscriptions), a **second site on the same model** (read-only touchscreen kiosks), and **21 CFR Part 11 behaviour** (electronic signatures on every change, with reason, visible in the audit log). The equipment model follows a real Ignition cleanroom BMS (FFU/HEPA units, differential pressure, temperature/humidity, dampers, duct heaters, dehumidifiers, exhaust fans, door feedback, maglocks, status lights, ACH) with particle counters and room status added, on an invented suite so nothing site-specific is published.

### Stack
`cleanroom/` — **Angular 22 + TypeScript (strict)**, Angular router (the module's SPA fallback already serves `index.html` for extension-less paths), hero in **three 0.186** (vanilla, owned by one Angular service so the scene survives route changes), ECharts (lazy) for trends. Built with Angular's application builder; output flattened to `dist/index.html` so `deploy.mjs` needs no change; base href resolved at runtime from the served path. `cleanroom-kiosk/` — **Lit 3 + Vite + TypeScript**, no router, one screen; the sixth framework and the lightest. Both use `@ignite/client`.

### Pages (control room)
- **Overview (hero).** One Three.js scene of the suite with two cameras: **Plan** (orthographic top-down, roofs off, flat architectural styling) and **Cutaway** (isometric, walls cut at 2 m, FFU ceiling grid visible); key `V` toggles, `R` resets, drag/wheel orbit and zoom. Airflow particles: emitted at each FFU in proportion to CFM, down through the room, out through door gaps and leakage toward the lower-pressure neighbour; a stalled FFU starves its room, an open door shows the cascade collapsing through it, a reversed cascade shows particles flowing the wrong way. Doors swing open/closed with `Door Feedback`; maglock state and status lights on the frames; room fill colour by state (Released / In Use / Cleaning / Out of Spec) and alarm. Filters: Entire / Positive / Negative pressure, Analog / Digital / Pressure overlays (as the Prism overview). Hover tooltip; click → room page, where the same scene flies the camera into the room.
- **Rooms/:id** — 3D of the room plus equipment cards (FFUs with CFM SP/feedback/RPM/on-board pressure, DP sensors, T/RH, damper, duct heater, dehumidifier, exhaust fan, door/maglock, lights, particle counter 0.5/5 µm with ISO class achieved vs designed), ACH, occupancy, status; trends for the room; controls (signed).
- **Trends** — tag picker (rooms × signals), ranges, history via `/tags/history`.
- **Alarms** — active board, ack with signature. **Alarm journal** — `/alarms/history` (needs a journal on the lab; see Deployment).
- **Audit log** — `/audit/logs` with actor/action/time filters, showing actor, action, target, old → new, reason, signature meaning.
- **Control** — setpoints (room T/RH/DP, FFU CFM, damper) and AHU/FFU start/stop; every change opens the signature dialog.
- **Stations** — kiosk station → allowed rooms (admin). **Login/identity** overlay as in the other demos; no auto-logout (not requested).

### Kiosk (`cleanroom-kiosk/`)
Opened as `/s/cleanroom-kiosk/?station=N`; reads `CR/Stations/N/AllowedRooms` and shows a carousel of room cards (auto-advance, swipe, tap to pin): room name/number, ISO class, status, **entry banner** (ENTER OK / DO NOT ENTER: cascade not held, active alarm, room not released, airlock in use), DP/T/RH/ACH/particles in large type, door and maglock state, FFU count running, gowning reminder for the class. Read-only: site key only, no login, no controls, reconnect banner, high-contrast dark theme sized for 1080p touch panels.

### Part 11: electronic signatures (module + client)
- **Module (ADR-006, written in its task):** `POST /tags/write`, `POST /alarms/acknowledge` and `POST /script/library` accept an optional `signature: {"username","password","meaning"}` and `reason` (≤ 200 chars). The module verifies the credential against the login user source, requires the signature username to equal the Bearer actor, and audits each action with the action value `old → new | <meaning>: <reason>` (tag writes read the previous value first; acks and library calls record the target and reason). New `SiteRecord.RequireSignature` (boolean, default false): when set, those routes return 403 `{"error":"signature required"}` without a valid signature. Lockout rules apply to signature attempts. Tests for the verifier and audit formatting.
- **Client:** `@ignite/client` write/ack/library calls take `{signature, reason}`; the app's signature dialog (meaning from a fixed list, reason text, password) wraps every control.

### Simulation (`sim/cleanroom/`, tags under `[default]CR/`)
- **Suite (15 rooms, invented):** 101 Entry (CNC, 0 Pa), 102 Gowning (ISO 8, +7.5), 103 Personnel Airlock (ISO 8, +15, interlocked), 104 Clean Corridor (ISO 8, +15), 105 Weigh & Dispense (ISO 7, +30), 106 Compounding (ISO 7, **−15**, potent, sink), 107 Compounding Anteroom (bubble airlock, +30), 108 Filling (ISO 7, +30) with LAF fill zone (ISO 5, +45), 109 Capping (ISO 7, +30), 110 Lyophiliser (ISO 7, +30), 111 Component Prep/Wash (ISO 8, +15), 112 Material Airlock (ISO 8, +15, interlocked), 113 Staging (ISO 8, +15), 114 De-gowning (ISO 8, +7.5), 115 QC Micro Lab (ISO 8, +15). AHU-1 serves the ISO 7/5 rooms, AHU-2 the ISO 8 and support rooms (supply/return fan %, supply temperature, CHW valve, pre-filter and final-filter dP, status, fault).
- **Per room:** `HF_n` (1–8 FFUs: `Setpoint`, `CFMFeedback`, `RPMFeedback`, `OnBoardPressure`, `Enable`, `Start`, `RunFeedback`, `CommsOK/CommsAlarm`, `FilterBlockedAlarm`), `DP_n` (1–2: `Value`, hi/lo alarm + SPs), `Temp_n`/`RH_n` (1–3), `Damper`, `DuctHeater`, `Dehum`, `ExhaustFan` (where fitted), `Door_n` (1–2), `Maglock`, `Lights` (red/green), `PC` (`Count05`, `Count5`, `ClassAchieved`, `ClassDesign`), `ACH`, `Occupancy`, `Status` (`RELEASED|IN_USE|CLEANING|OUT_OF_SPEC`), `OK`. ≈ 55 tags/room, ≈ 950 in all (hidden `_Sim_/State/*` excluded from the site scope).
- **Physics (1 s tick, speed 1 — the dynamics are already seconds to minutes):** room pressure from FFU supply, exhaust, AHU supply and leakage plus door open area toward the neighbour; airlock interlocks hold the second maglock while the first door is open; door traffic from occupancy events (every 1–3 min per busy room); ACH from CFM and volume; T/RH first-order to setpoint through coil, heater and dehumidifier; particles rise with occupancy, door events and FFU loss, decay with ACH; `ClassAchieved` from counts; status timers (cleaning cycles, release); filter loading (`OnBoardPressure` creeps, `FilterBlockedAlarm` over limit) accelerated by `_Sim_/FilterAccel` so days read as tens of minutes; `CFMFeedback` lags `Setpoint`.
- **Scenarios (`cr.*` library):** `door_held_open(room)`, `interlock_breach(airlock)` (both doors open → cascade reversal), `ffu_failure(room, n)`, `filter_loaded(room)`, `ahu_trip(n)`, `rh_excursion(room)`, `particle_excursion(room)`, `power_loss`, `restore`; API `status`, `set_scenario`, `set_speed`, `set_filter_accel`, `set_station(n, rooms)`, `set_room_status(room, state)`, `clear_fault`.
- **Alarms:** DP hi/lo per sensor, T/RH hi/lo, cascade reversal, door held open > 30 s, interlock breach, FFU comms / filter blocked / run failure, particle class exceeded, AHU fault, dehumidifier comms. **History:** DP/T/RH/particles at 1 s on change, FFU feedback at 5 s, status and alarms on change.

### Deployment
**Repository:** the cleanroom demo is **not** part of the public showcase monorepo. It lives in the private repo `EckmanTechLLC/ignite-cleanroom` (`sim/cleanroom/`, `cleanroom/`, `cleanroom-kiosk/`, its own `deploy` branch and `deploy.mjs`), consuming `@ignite/client` and `@ignite/theme` as pinned git dependencies on the public showcase repo. Site records point at the private repo with a read-only PAT (`Pat` field, supplied by the operator, never written to a file). Sites `cleanroom` (servePath `cleanroom`, `TagPathPatterns` `[default]CR/**`, `RequireSignature` true) and `cleanroom-kiosk` (servePath `cleanroom-kiosk`, scope `[default]CR/Rooms/**`, `[default]CR/Stations/**`, `[default]CR/KPI/**`; key only). Two Home → Launch cards. Operator items on the lab: an **alarm journal** (none exists; the Alarm journal page is empty without it) — created by the operator, not by an agent.

### Consequences
- Fifth and sixth frameworks; Angular brings its own builder into the monorepo (contained to `cleanroom/`).
- The module gains a Part 11 contract (ADR-006) that applies to every site: optional signatures, mandatory when a site asks.
- Per-page subscriptions become the pattern for larger models; the 1000-tag cap is respected by subscribing to what the page shows.
- Lighthouse ≥ 90 carried as "recorded, not met on software GL" for the WebGL pages, as before.

### Tasks
| Task | Scope | Depends on |
|---|---|---|
| task-88-cr-sim | `sim/cleanroom/` Jython sim, tag model, alarms, history, `cr` library; verified on the lab (complete; private repo) | — |
| task-89-esig-module | ADR-006; `RequireSignature`; signature verification + reason in audit on write/ack/library; tests; build (complete) | — |
| task-90-esig-deploy | deploy the e-signature build to the lab; curl verification of the signature contract and regressions | 89 |
| task-91-cr-scaffold | private repo layout (showcase submodule for `@ignite/client`/`@ignite/theme`), client signature options (public), Angular shell with routes, Lit kiosk shell, deploy pipeline, sites enabled | 88, 90 |
| task-92-cr-hero | Three.js suite scene: plan/cutaway cameras, airflow, doors, states, filters, hover/select | 91 |
| task-93-cr-hero-detail | detail pass (modelled FFUs, pass-throughs, equipment, gowning benches, LAF hood, pipework, signage) | 92 |
| task-94-cr-pages | rooms, trends, alarms, journal, audit, control (signature dialog), stations, identity | 91 |
| task-95-cr-kiosk | Lit kiosk | 91 |
| task-96-cr-ship | close-out: smoke matrix (both sites, signature paths), perf/a11y, screenshots, docs | 93, 94, 95 |
