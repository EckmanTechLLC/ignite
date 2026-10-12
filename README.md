# Ignite

Ignite is an Ignition 8.3 gateway module that turns the gateway into a host for modern web front ends. It serves git-synced static sites and exposes the gateway's data through a REST and SSE API, so you can build dashboards with any web framework instead of Perspective.

## What it does

- **Static sites**: each site is synced from a git repository and served under `/data/ignite/s/<mount>/`.
- **API**: REST plus Server-Sent Events (SSE) for tags, alarms, history, named queries and scripts.
- **Auth**: a per-site API key gives read access; write and admin operations require an Ignition user holding the configured security level.
- **Configuration**: manage sites and gateway settings in the Gateway config UI under Connections > Ignite > Sites.

## Repository layout

- `common/` - shared code used by the gateway and designer scopes
- `gateway/` - gateway module: routes, auth, git sync, config UI
- `designer/` - designer scope hook
- `web/` - placeholder subproject
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

## Status

Verified on Ignition 8.3.10 (October 2026). Early-stage: the module is unsigned and has no automated tests yet.

## License

MIT. See [LICENSE](LICENSE).
