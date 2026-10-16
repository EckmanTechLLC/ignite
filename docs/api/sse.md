# SSE live data: `GET /data/ignite/sse`

Server-Sent Events stream of tag changes (and optionally alarms). Auth: `X-Ignite-Key` header
or `apiKey` query param (site API key), or an Ignition session.

## Query parameters

| Param    | Meaning |
|----------|---------|
| `paths`  | Comma-separated tag paths. Entries may be folder patterns (below). |
| `alarms` | `true` to also receive alarm events. |
| `path`   | Alarm source path filter when `alarms=true`. |

## Patterns

A `paths` entry is a tag path whose segments may use `*`; the last segment may be `**`.

- `*` inside a segment matches any characters of that one segment; a lone `*` is exactly one segment (never crosses `/`). It may appear in any segment, including the leaf.
- `**` is allowed only as the last segment: every leaf tag at any depth below the preceding segments (folders and UDT instances are descended into).
- No wildcard: the literal tag (not browsed).
- Segments compare exactly (case-sensitive); the provider cannot contain `*`. Brackets after the provider are ordinary characters.

| Entry | Selects |
|-------|---------|
| `[default]DC/Hall/*` | direct leaf (atomic) tags of the folder |
| `[default]DC/**` | all leaf tags below `DC`, recursively |
| `[default]CR/Rooms/*/DP_1/Value` | one tag per room |
| `[default]CR/Rooms/*/HF_*/CFMFeedback` | `CFMFeedback` under every `HF_...` folder of every room |
| `[default]CR/Rooms/1*/Status` | `Status` of rooms whose name starts with `1` |

Expansion is done once, by tag browse, when the connection opens (or when a subscription is
registered, below): browse starts at the longest literal prefix, descends only into folders matching the
next segment (a literal segment is entered directly) and collects leaves matching the leaf segment.
Every expanded path is checked against the site's tag scope. Tags added later are not picked up
until the client reconnects. A pattern on a non-existent folder or provider gives
`400 {"error":"tag folder not found: ..."}`; an existing folder with no matching tags yields
`subscribed` with `count` 0. An invalid pattern (`**` not last, empty segment, `..`, bad provider)
gives `400 {"error":"invalid tag pattern: <entry>"}`. More tags than the limit (Gateway Setting
`maxSubscriptionTags`, default 1000, max 50000) gives
`400 {"error":"too many tags in subscription (limit 1000)"}`. Duplicates across entries are removed.

## Long lists: register, then stream

`paths` travels in the URL, which proxies and servers cap at roughly 8 KB (about 100 explicit
paths). For longer lists register the subscription first. Auth, site rules and the 400/403
errors are the same as for `GET /sse`, but credentials go in headers (`X-Ignite-Key` or
`Authorization: Bearer`, plus `X-Ignite-Site`); query credentials are accepted on `GET /sse` only.

```
POST /data/ignite/sse/subscriptions
X-Ignite-Site: cleanroom
X-Ignite-Key: <site key>
Content-Type: application/json

{"paths": ["[default]CR/Rooms/*/DP_1/Value", "[default]CR/Rooms/1*/Status"], "alarms": false, "path": null}

200 {"id": "3f9c...", "count": 412, "expiresInMs": 60000}
```

```
GET /data/ignite/sse?subscription=3f9c...&site=cleanroom&apiKey=<site key>
```

- Expansion, scope checks and the tag cap run in the POST, so `400 too many tags...`, `400 invalid tag pattern...` and `403 tag path not permitted` come from it.
- The id is opaque, bound to one site and to the credential (kind and actor, or API key) that registered it; any other caller gets `403`.
- It can be used any number of times; each use refreshes it, and it expires 60 s after the last use. An unknown or expired id gives `404 {"error":"subscription not found or expired"}`: register again (clients treat this as a fresh connect, which is how a reconnect after the 20-minute cap works).
- `alarms` and `path` come from the registration; `subscription` cannot be combined with `paths` (400).
- Registrations are in memory only (lost on module restart) and limited to 256 live ones (`503` beyond).
- `@ignite/client` `subscribe()` does this automatically when the joined `paths` would exceed 6 000 characters, or with `register: true`.

## Events

- Initial snapshot: one `tagChange` per subscribed tag, immediately after connect. The snapshot is authoritative: the subscription's own initial callback is suppressed, so after `subscribed` there is no duplicate of the snapshot (a genuine change, with a newer timestamp or different value, is always delivered).
- `data: {"type":"tagChange","path":"...","value":...,"quality":"Good","timestamp":"..."}`
- `event: subscribed` / `data: {"type":"subscribed","count":N}` : sent once, after the snapshot.
- `data: {"type":"alarm", ...}` : alarm events when requested.
- `: ping` : heartbeat comment every 10 s when idle (also detects dead clients).
- `event: reconnect` / `data: {"type":"reconnect"}` : sent just before the server closes the
  stream (connections last at most 20 minutes). Clients should reconnect.

If a client falls behind (500 queued events) the oldest events are dropped.

## Capacity

At most `maxSseClients` concurrent streams per gateway (Gateway Setting, default 64, max 1000, read on every connect). Beyond that: `503 {"error":"too many live connections"}`.
Slots free when the client disconnects (detected within about one heartbeat), at the 20-minute
cap, or at module shutdown/restart.

The gateway's per-route permit pool is sized once, when the module mounts its routes
(`max(maxSseClients, 64) + 16`). Lowering `maxSseClients` takes effect immediately; raising it above
the value the module started with needs a module restart before the extra streams are served.

## Settings

`GET`/`PUT /settings` (gateway admin) and the Gateway Settings panel of the config UI carry
`maxSubscriptionTags` (1-50000, default 1000, effective immediately) and `maxSseClients` (1-1000,
default 64, see above).
