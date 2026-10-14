# Runbook: lab demo

How to prepare and run the four Ignite showcase demos on the lab gateway. No addresses or credentials are recorded here; get them from the operator.

## Before a demo

1. The gateway runs on a trial licence that expires every two hours. Open the gateway home page and reset the trial. Until then history and alarms stop updating.
2. In the gateway, open Home > Ignite > Launch. It lists exactly four sites (launch, datacenter, microgrid, brewery); each card opens its demo.
3. Open each demo once and wait about ten seconds. Launch should read "Live - 111 tags", Data Center "LIVE - 675 tags", Campus Microgrid "LIVE - 405 tags", Craft Brewery "LIVE - 908 tags" (the model has 1039 tags; the 131 engine-state tags are not streamed because one subscription is capped at 1000).
4. Check the simulations are at rest: Launch scenario nominal, speed 4x, auto-cycle on; Data Center scenario normal, speed 4x, no faults; Campus Microgrid scenario normal, speed 120x (one simulated day per 12 real minutes), no faults, BESS B1 PEAK_SHAVE and B2 SELF_CONSUME; Craft Brewery scenario normal, speed 60x (one simulated hour per real minute), fermentation acceleration 8, no faults, packaging line RUNNING. Acknowledge anything left on the alarm board (`A`).

## The four URLs

All live under the gateway's `/data/ignite/s/` path:

- Launch Operations: `/data/ignite/s/launch/`
- Data Center: `/data/ignite/s/datacenter/`
- Campus Microgrid: `/data/ignite/s/microgrid/` (site `microgrid`, tag scope `[default]MG/**`)
- Craft Brewery: `/data/ignite/s/brewery/` (site `brewery`, tag scope `[default]BR/**`)

## Keys (all demos)

| Key | Panel |
|---|---|
| `T` | Trends / telemetry charts |
| `A` | Alarm board |
| `C` | Controls (sequencer and director in Launch; scenarios in Data Center; dispatch, faults and scenario in Campus Microgrid; brewing, line and QA actions plus the director in Craft Brewery). `B` opens the Batch overlay in the brewery |
| `L` | Log in, session tier, audit |
| `Esc` | Close panels |
| `?` | Help / about (all demos); `?perf` in the URL plus `P` toggles the performance readout |

Anonymous visitors see everything but cannot operate. Log in as an operator to acknowledge alarms and run scenarios; an administrator also gets speed, fault injection and the audit view.

## Scenarios worth showing

- Launch: in `C`, start the LOX leak scenario (`lox_leak_abort`). Pressure falls, a NO-GO hold follows and the count aborts, with the alarm chips lighting in the alarm board. Allow the cycle to recycle afterwards, or press recycle.
- Data Center (the show-piece, a detailed 3D hall): start with the camera. Keys `1` to `5` fly to Overview, Cold aisle A/B, Hot aisle, Electrical room and Roof chillers, `R` resets; drag to orbit, wheel to zoom, hover an asset for the live tooltip, click it to select it (card plus Trends). Airflow particles run CRAH, underfloor, cold aisle, racks, hot aisle, return; rack doors, heat caps and server fronts glow with inlet and outlet temperature. As an operator (press `L`, then `C`) run Fail CRAH 2 (script `dc.fail_crah(2)`): CRAH 2 turns red and its fan stops, the particle density thins in rows C and D, those racks go amber then red (inlet about 32 C, max inlet 35 C within a minute), the mode chip reads THERMAL EVENT and the alarm board (`A`) fills with rack inlet alarms; `dc.restore_crah(2)` cools it back. Second: Utility loss (`dc.utility_loss()`): the utility feeder goes dark, the ATS lever swings to the generator, the UPS shows battery state and the busways stay lit on battery then generator colours (press `4`); `dc.utility_restore()` recovers, and "restore all" (`dc.restore()`) returns to normal.
- Campus Microgrid (the show-piece, an oblique 2.5D campus map): start with the sun and the clouds. As an operator (press `L`, then `C`) run Jump to hour (script `mg.set_hour(7.5)`): long soft shadows of the buildings, trees and PV rows fall west and shorten toward noon; `mg.set_hour(21)` gives the night grade with street lights and lit windows. Drag the Cloudiness slider (script `mg.set_cloudiness(0.9)`): a cloud shadow drifts over the PV fields, the arrays it covers dim and PV kW falls; Auto (or `mg.restore()`) releases it. Then run Grid outage (`mg.grid_outage()`): the utility link snaps open with a flash and the campus dips, the genset counts down and runs (exhaust), the BESS forms the grid, buildings 1 and 3 are shed, EV bays are curtailed and the flows re-route west; the mode chip reads GRID OUTAGE then GENSET. Press `A` for the Grid alarms. After a minute run Grid restore (`mg.grid_restore()`): the link closes, the campus resynchronises and the genset cools down. Camera: drag to pan, wheel to zoom, `R` resets; hover an asset for the live tooltip, click it to open its Trends. Other pieces: Fault A2-B (inverter fault cascade in the alarm board, then Clear) and, for an administrator, the scenario and speed buttons.
- Craft Brewery: one batch takes about 50 real minutes, so the plant is always mid-brew. As an operator press `C` to start a batch, hold or resume the line; as an administrator set the scenario `filler_jam` (script `brew.set_scenario('filler_jam')`): the line goes DOWN with FILLER JAM on the filler, the OEE tile gives the reason and the mode chip reads LINE_DOWN; clear it with `brew.clear_fault('filler_jam')` or the Clear button. The glycol show-piece is `glycol_failure` (`brew.set_scenario('glycol_failure')`): supply temperature climbs, the cellar fermenters drift and alarm in a cascade (press `A` for the timeline) and QA is at risk; `brew.glycol_restore()` recovers it. Press `B` on a strip bar for the batch's ISA-88 phases, QA results and genealogy; `contamination` puts a batch on QA HOLD.

After a demo, set Launch back to nominal at 4x with auto-cycle on, Data Center to normal at 4x, Campus Microgrid to scenario normal at 120x with no faults (Controls, or `mg.restore()`), Craft Brewery to scenario normal at 60x with fermentation acceleration 8, no faults and the line resumed (Controls, or `brew.restore()` then `brew.resume_line()`), and acknowledge remaining alarms.

## Site keys

Each site has its own read-tier API key set on the site record (Home > Ignite > Launch, or `PUT /sites/{id}` as an administrator); the gateway injects it into the served page, so there is nothing to type. To rotate the microgrid key, generate a random value, `PUT /sites/5 {"apiKey": "<key>"}` and `POST /sites/5/sync`; the key opens only `[default]MG/**`. The brewery works the same with site 6 (`[default]BR/**`). Open pages hold the old key until reloaded, and a rotation during a long run ends the live stream at its next reconnect. Keep rotated keys out of tracked files.

## "History / alarms unavailable"

The page shows this notice when the historian returns only empty values, which is how an expired trial looks from the browser. Reset the trial on the gateway home page. The notice clears within a minute; alarm events resume once the trial is active. If it persists after a reset, check that the historian provider is running.

## If the live chip says Reconnecting

The page keeps one live stream and reopens it on its own about every 20 minutes. Persistent "Reconnecting" for more than a few seconds means the gateway is restarting or unreachable.
