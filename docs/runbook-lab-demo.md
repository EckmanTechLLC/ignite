# Runbook: lab demo

How to prepare and run the two Ignite showcase demos on the lab gateway. No addresses or credentials are recorded here; get them from the operator.

## Before a demo

1. The gateway runs on a trial licence that expires every two hours. Open the gateway home page and reset the trial. Until then history and alarms stop updating.
2. In the gateway, open Home > Ignite > Launch. It lists exactly two sites; each card opens its demo.
3. Open each demo once and wait about ten seconds. Launch should read "Live - 111 tags", Data Center "LIVE - 675 tags".
4. Check the simulations are at rest: Launch scenario nominal, speed 4x, auto-cycle on; Data Center scenario normal, speed 1x, no faults. Acknowledge anything left on the alarm board (`A`).

## The two URLs

Both live under the gateway's `/data/ignite/s/` path:

- Launch Operations: `/data/ignite/s/launch/`
- Data Center: `/data/ignite/s/datacenter/`

## Keys (both demos)

| Key | Panel |
|---|---|
| `T` | Trends / telemetry charts |
| `A` | Alarm board |
| `C` | Controls (sequencer and director in Launch; scenarios in Data Center) |
| `L` | Log in, session tier, audit |
| `Esc` | Close panels |

Anonymous visitors see everything but cannot operate. Log in as an operator to acknowledge alarms and run scenarios; an administrator also gets speed, fault injection and the audit view.

## Scenarios worth showing

- Launch: in `C`, start the LOX leak scenario (`lox_leak_abort`). Pressure falls, a NO-GO hold follows and the count aborts, with the alarm chips lighting in the alarm board. Allow the cycle to recycle afterwards, or press recycle.
- Data Center: in `C`, trigger utility loss. The UPS carries the hall, the generator starts, and rack inlet alarms appear in the board; restore utility to recover. Use "restore all" to return to normal.

After a demo, set Launch back to nominal at 4x with auto-cycle on, Data Center to normal at 1x, and acknowledge remaining alarms.

## "History / alarms unavailable"

The page shows this notice when the historian returns only empty values, which is how an expired trial looks from the browser. Reset the trial on the gateway home page. The notice clears within a minute; alarm events resume once the trial is active. If it persists after a reset, check that the historian provider is running.

## If the live chip says Reconnecting

The page keeps one live stream and reopens it on its own about every 20 minutes. Persistent "Reconnecting" for more than a few seconds means the gateway is restarting or unreachable.
