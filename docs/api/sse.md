# SSE live data: `GET /data/ignite/sse`

Server-Sent Events stream of tag changes (and optionally alarms). Auth: `X-Ignite-Key` header
or `apiKey` query param (site API key), or an Ignition session.

## Query parameters

| Param    | Meaning |
|----------|---------|
| `paths`  | Comma-separated tag paths. Entries may be folder patterns (below). |
| `alarms` | `true` to also receive alarm events. |
| `path`   | Alarm source path filter when `alarms=true`. |

## Folder subscriptions

- `[default]DC/Hall/*`  : direct leaf (atomic) tags of the folder
- `[default]DC/**`      : all leaf tags, recursively (folders and UDT instances are descended into)

Expansion is done once, by tag browse, when the connection opens (same provider/path syntax as
`/tags/browse`). Tags added to the folder later are not picked up until the client reconnects.
A pattern on a non-existent folder or provider gives `400 {"error":"tag folder not found: ..."}`; an existing folder with no leaf tags yields `subscribed` with `count` 0.
More than 1000 tags in total gives `400 {"error":"too many tags in subscription"}`.
Duplicates across entries are removed.

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

At most 64 concurrent streams per gateway. Beyond that: `503 {"error":"too many live connections"}`.
Slots free when the client disconnects (detected within about one heartbeat), at the 20-minute
cap, or at module shutdown/restart.
