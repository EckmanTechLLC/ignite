# Ignite

Ignite is an Ignition 8.3 gateway module that turns the gateway into a host for modern web front ends. It serves git-synced static sites and exposes the gateway's data through a REST and SSE API, so you can build dashboards with any web framework instead of Perspective.

## What it does

- **Static sites**: each site is synced from a git repository and served under `/data/ignite/s/<mount>/`.
- **API**: REST plus Server-Sent Events (SSE) for tags, alarms, history, named queries and scripts. The live stream is documented in [docs/api/sse.md](docs/api/sse.md).
- **Auth**: a per-site API key gives read access; write and admin operations require an Ignition user holding the configured security level.
- **Configuration**: manage sites and gateway settings in the Gateway config UI under Connections > Ignite > Sites.

## Repository layout

- `common/` - shared scope (GD) reserved for code used by both gateway and designer; currently empty
- `gateway/` - gateway module: routes, auth, git sync, config UI
- `designer/` - designer scope hook
- `demo/` - React showcase app served by Ignite
- `docs/decisions/` - architecture decision records (ADRs)

## Build

Requires a JDK compatible with the Ignition 8.3 SDK.

    ./gradlew build && ./gradlew zipModule --rerun-tasks

The module is written to `build/Ignite.unsigned.modl`. Run `zipModule` with `--rerun-tasks`, otherwise Gradle may report it up to date and leave a stale module.

## Install

The module is unsigned, so the gateway must allow unsigned modules: add `-Dignition.allowunsignedmodules=true` to `data/ignition.conf` and restart the gateway.

1. First install: upload the `.modl` in the Gateway under Config > Modules. Ignition 8.3 needs this step to register the module.
2. Upgrades: replace the installed `.modl` file in the gateway's `user-lib/modules/` directory with the new build and restart the gateway.

## Authentication model

- The per-site API key is a public read credential (it is injected into the served site). It never grants write or admin access.
- Write and admin operations require a user identity, either an Ignition web session or a Bearer session from `POST /auth/login`, with the required security level.
- Arbitrary SQL is not supported; database access is through named queries only.
- Script calls require the write tier and strictly validated function paths.

The full design is in [ADR-004](docs/decisions/adr-004-auth-hardening.md), building on [ADR-003](docs/decisions/adr-003-auth-audit-scripts.md).

## CORS for API calls

A page served from another origin can call the API when that origin is listed in the site's `corsOrigins` (comma-separated, exact match, or `*`). For a request carrying an `Origin` header, the site is resolved from the API key, `X-Ignite-Site` or the `site` query parameter; if the origin is listed, the response includes `Access-Control-Allow-Origin` (the origin echoed, plus `Vary: Origin`), and `Allow-Headers` (`Authorization, Content-Type, X-Ignite-Key, X-Ignite-Site, X-CSRF-Token`), `Allow-Methods` and `Max-Age: 600`. Every API path also answers an `OPTIONS` preflight with 204 and no auth check; the CORS headers appear only when the origin is listed on some site. Unlisted origins get no CORS headers, so the browser blocks the call. Credentials mode is not supported: send the Bearer token or API key as a header.

## Showcase

Four demos live in [EckmanTechLLC/ignite-showcase](https://github.com/EckmanTechLLC/ignite-showcase) (`deploy` branch served by four site records, see [ADR-005](docs/decisions/adr-005-showcase-program.md) and Amendments A1 and A2). Each is a single full-viewport screen over a gateway-side simulation, driven only through the routes below. The earlier dairy-plant demo (`demo/`, ADR-002) is retired; its source stays in this repo.

| Demo | Mount | Stack | Shows |
|---|---|---|---|
| Launch Operations | `/data/ignite/s/launch/` | React, Three.js | A rocket countdown with live propellant, ground and weather data, holds and aborts |
| Data Center | `/data/ignite/s/datacenter/` | Vue 3, TresJS (Three.js) | A detailed procedural 3D hall (60 racks, CRAHs, UPS, PDUs, ATS, generator, roof chillers) with GPU airflow particles, a lit power and chilled-water path, camera presets 1 to 5, and thermal and power-loss scenarios (CRAH failure, utility loss) |
| Campus Microgrid | `/data/ignite/s/microgrid/` | Svelte 5, PixiJS | An oblique 2.5D campus map (PV fields, batteries, genset, EV lot, substation) with a real sun, projected shadows, drifting cloud shadows and particle power flows, plus forecast-vs-actual history, tariff and cost, islanding and faults |
| Craft Brewery | `/data/ignite/s/brewery/` | Solid, Pixi (WebGL) | An isometric brewery: brewhouse, fermentation cellar, packaging line and utilities with batch genealogy, ISA-88 phases, QA holds, OEE and glycol/line-down scenarios |

Route families they exercise:

- `GET /sse` - one folder subscription per page (111 / 675 / 405 / 908 tags; the brewery model has 1 039 tags, so it subscribes with 12 folder patterns because one subscription is capped at 1 000), with an initial snapshot.
- `GET /system/status` - gateway name, uptime and platform trial state (see [docs/api/system.md](docs/api/system.md)).
- `GET /tags/history` - trend charts and the trial/historian health probe.
- `GET /alarms/status`, `POST /alarms/acknowledge` - alarm boards, acknowledge tier-gated.
- `POST /script/library` - scenario, speed and fault controls through project library functions.
- `POST /auth/login`, `GET /auth/session`, `POST /auth/logout` - viewer, operator and administrator tiers.
- `GET /audit/logs` - audit view for administrators.

Lab operation is in [docs/runbook-lab-demo.md](docs/runbook-lab-demo.md).

## Status

Verified on Ignition 8.3.10 (October 2026). Early-stage: the module is unsigned and has no automated tests yet.

## License

MIT. See [LICENSE](LICENSE).
