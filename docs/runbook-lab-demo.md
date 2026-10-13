# Runbook: lab demo

How to prepare and run the three Ignite showcase demos on the lab gateway. No addresses or credentials are recorded here; get them from the operator.

## Before a demo

1. The gateway runs on a trial licence that expires every two hours. Open the gateway home page and reset the trial. Until then history and alarms stop updating.
2. In the gateway, open Home > Ignite > Launch. It lists exactly three sites (launch, datacenter, microgrid); each card opens its demo.
3. Open each demo once and wait about ten seconds. Launch should read "Live - 111 tags", Data Center "LIVE - 675 tags", Campus Microgrid "LIVE - 405 tags".
4. Check the simulations are at rest: Launch scenario nominal, speed 4x, auto-cycle on; Data Center scenario normal, speed 4x, no faults; Campus Microgrid scenario normal, speed 120x (one simulated day per 12 real minutes), no faults, BESS B1 PEAK_SHAVE and B2 SELF_CONSUME. Acknowledge anything left on the alarm board (`A`).

## The three URLs

Both live under the gateway's `/data/ignite/s/` path:

- Launch Operations: `/data/ignite/s/launch/`
- Data Center: `/data/ignite/s/datacenter/`
- Campus Microgrid: `/data/ignite/s/microgrid/` (site `microgrid`, tag scope `[default]MG/**`)

## Keys (all demos)

| Key | Panel |
|---|---|
| `T` | Trends / telemetry charts |
| `A` | Alarm board |
| `C` | Controls (sequencer and director in Launch; scenarios in Data Center; dispatch, faults and scenario in Campus Microgrid) |
| `L` | Log in, session tier, audit |
| `Esc` | Close panels |
| `?` | Help / about (all demos); `?perf` in the URL plus `P` toggles the performance readout |

Anonymous visitors see everything but cannot operate. Log in as an operator to acknowledge alarms and run scenarios; an administrator also gets speed, fault injection and the audit view.

## Scenarios worth showing

- Launch: in `C`, start the LOX leak scenario (`lox_leak_abort`). Pressure falls, a NO-GO hold follows and the count aborts, with the alarm chips lighting in the alarm board. Allow the cycle to recycle afterwards, or press recycle.
- Data Center: in `C`, trigger utility loss. The UPS carries the hall, the generator starts, and rack inlet alarms appear in the board; restore utility to recover. Use "restore all" to return to normal.
- Campus Microgrid (the show-piece): log in as an operator, press `C`, run Grid outage (script `mg.grid_outage()`). The utility opens, the bus and feeders go red, buildings 1 and 3 are shed, the genset counts down and starts, PV curtails to balance and the mode chip reads ISLAND then GENSET. Press `A` for the Grid alarms. After a minute run Grid restore (`mg.grid_restore()`): the campus resynchronises after a few seconds, the genset cools down and PV is released. Other pieces: Fault A2-B (inverter fault cascade in the alarm board, then Clear), Jump to hour, and for an administrator the scenario and speed buttons.

After a demo, set Launch back to nominal at 4x with auto-cycle on, Data Center to normal at 4x, Campus Microgrid to scenario normal at 120x with no faults (Controls, or `mg.restore()`), and acknowledge remaining alarms.

## Site keys

Each site has its own read-tier API key set on the site record (Home > Ignite > Launch, or `PUT /sites/{id}` as an administrator); the gateway injects it into the served page, so there is nothing to type. To rotate the microgrid key, generate a random value, `PUT /sites/5 {"apiKey": "<key>"}` and `POST /sites/5/sync`; the key opens only `[default]MG/**`. Keep rotated keys out of tracked files.

## "History / alarms unavailable"

The page shows this notice when the historian returns only empty values, which is how an expired trial looks from the browser. Reset the trial on the gateway home page. The notice clears within a minute; alarm events resume once the trial is active. If it persists after a reset, check that the historian provider is running.

## If the live chip says Reconnecting

The page keeps one live stream and reopens it on its own about every 20 minutes. Persistent "Reconnecting" for more than a few seconds means the gateway is restarting or unreachable.
