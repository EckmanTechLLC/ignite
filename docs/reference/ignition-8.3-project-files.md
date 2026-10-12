# Ignition 8.3 project files and history queries (observed reference)

Source: files and behaviour observed on an Ignition 8.3 lab gateway (task-50).
Everything below was read from disk or measured; where it was not, it is marked unknown.
`<data>` is the gateway data directory; `<user>` is the OS account that owns the install.

## Project layout

Project `demo` under `<data>/projects/demo/` (directories 0755/0775, files 0644/0664, owner `<user>`):

- `project.json` - project metadata (below).
- `ignition/timer/<name>/` - one directory per gateway timer event script (scope "G").
- `ignition/script-python/<pkg>/` - one directory per library script package (scope "A").
- `ignition/global-props/` - `resource.json` + `data.bin` (project global properties; binary).
- `com.inductiveautomation.perspective/{page-config,session-props}/` and `com.inductiveautomation.vision/client-tags/` - module resources, each `resource.json` + data file.
- `<data>/projects/.resources/` - content-addressed blob store: files named by the SHA-256 of a script file's bytes (verified: `handleTimerEvent.py`, `code.py` hashes match store names). Gateway-managed, mode 0600 for entries written by the Designer path, 0644 for those the gateway added itself. `resource.json` files are NOT stored there. Do not edit.

`project.json` (exact bytes, no trailing newline):

    {
      "title": "demo",
      "description": "",
      "enabled": true,
      "inheritable": false,
      "parent": ""
    }

Fields: `title` display name, `enabled` project runs, `inheritable` other projects may inherit from it, `parent` inherited-from project ("" = none). All fields self-explanatory; nothing else unknown.

## Timer script resource

`ignition/timer/sim_sample/resource.json` (exact bytes, 2-space indent, LF, no trailing newline):

    {
      "scope": "G",
      "version": 1,
      "restricted": false,
      "overridable": true,
      "files": [
        "handleTimerEvent.py"
      ],
      "attributes": {
        "sharedThread": true,
        "delay": 1000,
        "lastModificationSignature": "<64 hex chars>",
        "fixedDelay": true,
        "lastModification": {
          "actor": "<user>",
          "timestamp": "2026-10-12T15:07:02Z"
        },
        "enabled": true
      }
    }

`handleTimerEvent.py` (exact bytes; indent is a single TAB; no trailing newline):

    def handleTimerEvent():
    <TAB>system.tag.writeBlocking(["[default]MyTag/Value"], [system.date.getSecond(system.date.now())])

Field notes:
- The interval, fixed-rate and enabled flags live INSIDE `resource.json` `attributes` (a search of `projects/` found no other copy): `delay` = period in ms, `fixedDelay: true` = fixed-rate (false would be fixed-delay), `sharedThread: true` = run on the shared timer thread, `enabled`.
- `scope` "G" = gateway; `files` lists the resource's payload files in the same directory; `version` = resource format version (1); `restricted`/`overridable` = inheritance flags (copied unchanged).
- `lastModification` = actor + UTC timestamp. `lastModificationSignature` = 64-hex integrity value; algorithm unknown, but a copied resource.json carrying another resource's signature and timestamp was accepted without complaint (see results).
- The script file name `handleTimerEvent.py` and function name `handleTimerEvent` are fixed by convention; the function takes no arguments.

## Library script resource

`ignition/script-python/sample/resource.json` (exact bytes):

    {
      "scope": "A",
      "version": 1,
      "restricted": false,
      "overridable": true,
      "files": [
        "code.py"
      ],
      "attributes": {
        "hintScope": 2,
        "lastModificationSignature": "<64 hex chars>",
        "lastModification": {
          "actor": "<user>",
          "timestamp": "2026-10-12T15:06:57Z"
        }
      }
    }

`code.py` (exact bytes; TAB indent; no trailing newline):

    def ping():
    <TAB>return "pong"

Notes: scope "A" = all scopes (gateway/client/designer). `hintScope: 2` is a Designer autocomplete hint (meaning of the value otherwise unknown). The package name is the directory name (`sample.ping`); nested packages are nested directories (not tested).

## Historian provider + tag history config

Historian provider `IgniteHistorian` at `<data>/config/resources/core/com.inductiveautomation.historian/historian-provider/IgniteHistorian/` (0644):

`config.json`:

    {
      "profile": { "type": "CoreHistorian" },
      "settings": {
        "dataDeduplication": false,
        "maintenanceSettings": {
          "directory": "",
          "maintenanceAge": 6,
          "maintenanceAgeUnits": "MONTH",
          "strategy": "PRUNE"
        },
        "partitionInterval": "MONTH"
      }
    }

(on disk the braces are expanded over multiple lines; content identical). `resource.json` has the same envelope as the project resources (`scope` "A", `version` 1, `files: ["config.json"]`) with attributes `uuid`, `enabled: true`, `lastModification`, `lastModificationSignature`. Core Historian: data is stored in internal partitions (monthly), pruned after 6 months.

Tag provider `default`, `<data>/config/resources/core/ignition/tag-provider/default/config.json` (0644):

    {
      "profile": { "allowBackfill": false, "enableTagReferenceStore": true, "type": "STANDARD" },
      "settings": {
        "defaultDatasourceName": null,
        "editPermissions":  { "securityLevels": [], "type": "AllOf" },
        "readOnly": false,
        "readPermissions":  { "securityLevels": [], "type": "AllOf" },
        "valuePersistence": "Database",
        "writePermissions": { "securityLevels": [], "type": "AllOf" }
      }
    }

`resource.json`: scope "A", description "Default tag provider", `overridable: false`, `files: ["config.json"]`, attribute `uuid` only.

Tag history config (read via `system.tag.getConfiguration`): memory tag `[default]MyTag/Value` has `dataType: Int4`, `valueSource: memory`, `historyEnabled: true`, `historyProvider: "IgniteHistorian"`, `defaultValue` 42.

## Round-trip results (what the sim tasks may rely on)

Probe: timer `sim_probe` (copy of `sim_sample`, resource.json byte-identical, script writing the current minute to a new memory tag) and library `probe` (`def pong(): return "pong"`) were written directly to disk by the OS account that owns the install, with the gateway running.

1. NOT picked up live. The gateway did not notice the new timer directory in ~2.5 min of polling (tag stayed at its initial value; no project-scan, resource or error log line appeared), and the new library module was still unimportable ~1 min after being written (`ImportError: No module named probe` through the library route).
2. PICKED UP after a gateway service restart (gateway reported "started in 17 seconds"; project `demo` started ~20 s after the stop). Right after startup the timer was writing the tag every second (fixed 1000 ms), and the library function returned "pong". The gateway also added the two new script files to the `.resources` blob store at that restart.
3. The Designer path differs: saving in the Designer logs "Restarting gateway scripts" for the project immediately (that is the only live-reload trigger observed). Authoring on disk from outside therefore means: write files, then restart the gateway service once.
4. A copied `lastModificationSignature` / `lastModification` was accepted - no regeneration needed for hand-authored resources (accepted at least on this version, not validated by anything observed).
5. Tags created through the API (`system.tag.configure`, memory tag, Int4) are live immediately and persist across a restart. `system.tag.deleteTags` removes them immediately.
6. Tag-config-only changes need no restart; only project resources (timer/library) do.
7. Removing the probe directories from disk did not stop the already-running probe timer or unload the module in the running gateway (not re-verified after a second restart; it only writes a now-nonexistent tag). A restart is assumed to drop them.
8. Existing resources were unaffected: the sample library function and the sample timer kept working throughout, including across the restart.

## History query cookbook

Route: `GET /data/ignite/tags/history`. Headers: `X-Ignite-Site: <site>` and `X-Ignite-Key: <key>` (read tier). Parameters: `paths` (comma-separated, URL-encode brackets as %5B/%5D), `start`, `end` (ISO-8601 UTC), `returnSize`.

What the route does (from `TagRoutes.queryHistory`, confirmed by measurement):
- `end` defaults to now; `start` defaults to end minus 1 hour; `returnSize` defaults to 100. Valid `returnSize` is 1..10000 - `-1` and `0` return HTTP 400 (`returnSize must be between 1 and 10000`), so raw/"all changes" rows are NOT available through this route.
- It always asks for aggregation mode LastValue, wide format, with a fixed row count: the window is divided into `returnSize` evenly spaced time samples, and each row is the last stored value at that sample time (sample-and-hold). Rows are therefore resampled, never raw change events, and values are held (not interpolated between samples).
- Row i is at `start + i * (end - start) / returnSize`, so the last row is one step BEFORE `end`.
- Response: `{"columns":["t_stamp","<Tag/Name>",...],"rows":[[ts,value],...],"rowCount":N}`. Column names drop the provider and folder prefix up to the tag name (`MyTag/Value`). `ts` is a UTC ISO string: whole seconds as `2026-10-12T15:09:18Z`, with milliseconds (`...18.984Z`) when the step is sub-second.
- Values are serialised as JSON STRINGS even for numeric tags (`"56"`); parse client-side. Missing = JSON `null`.

Measured row counts (10-minute window, one 1 Hz tag): omitted -> 100 rows (6 s step); 5 -> 5 rows (2 min step); 600 -> 600 rows (1 s step); -1 -> 400.

(a) 60-point strip-chart back-fill (last minute, 1 s step; tested, 60 rows, no null rows):

    GET /data/ignite/tags/history?paths=%5Bdefault%5DMyTag/Value&start=<now-60s ISO>&end=<now ISO>&returnSize=60

(b) 24-hour trend (10-minute step, 144 points; tested, 144 rows):

    GET /data/ignite/tags/history?paths=%5Bdefault%5DMyTag/Value&start=<now-24h ISO>&end=<now ISO>&returnSize=144

Both: send the headers above; build `start`/`end` from the client clock in UTC with whole seconds (`toISOString()` without milliseconds is accepted). For several tags use one call with comma-separated `paths` (one column each; multi-tag not tested).

First-row `null`: seen in every window whose start precedes the first continuous 1 Hz record (all 10-minute, 12-minute and 24-hour tests, and 5-10 minute windows with 60 rows), absent in windows that start inside it (60 s windows). Best explanation (unconfirmed): LastValue does not look back before `start`, so the first sample is null unless a stored value falls at/after the window start. Sims should store a value every second (or the clients should drop a leading null / start the window slightly earlier).

Data caveat: for this tag, history stored before roughly 15:07 on the lab day contains large garbage numbers (e.g. 19-digit values) on an Int4 tag, and a historian "Failed to store 1 points" warning was logged at startup; clean 0..59 values start when the sample timer was (re)deployed. Cause unknown - treat history that predates the sim scripts as untrusted, and expect nothing older than the sim to be meaningful.

## Open questions

- Algorithm behind `lastModificationSignature` (not needed for hand-authored resources on this version, but unverified for other versions).
- Whether a different live trigger exists (e.g. an API/MBean project rescan or Designer "update project") that avoids the restart; none was found, none was tried beyond restart.
- Whether deleting resource directories while running unloads them (see result 7) and what a restart does with stale `.resources` blobs.
- Whether a single historian-enabled tag with `valueSource` other than memory behaves the same for LastValue back-fill.
- Cause of the garbage historic values and the startup "Failed to store" warning.
- Multi-tag `paths` and Float/Boolean tag serialisation in `/tags/history` (not tested).
- Nested script packages (`a.b.fn`) on disk (not tested).
