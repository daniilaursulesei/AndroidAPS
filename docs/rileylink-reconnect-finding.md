# RileyLink does not reconnect in the background after a dropped link

## Short version

When the phone↔RileyLink Bluetooth link drops while AndroidAPS is in the background,
the RileyLink / Medtronic driver does **not** get the link back on its own. It only
reconnects when the user opens the app. In one logged case the loop was open for
**1 hour 48 minutes** with the pump about 30 cm from the phone the whole time.

The cause is the *kind* of Bluetooth connection the RileyLink driver uses for
reconnection. It relies on `connectGatt(autoConnect = true)`, a passive background
connect that Android does not service reliably while the app is backgrounded. Every
always-Bluetooth pump driver in the project (Dana, Diaconn, Medtrum) instead uses a
**direct connect**, `connectGatt(autoConnect = false)`, and none of them has this
"only reconnects when I open the app" problem.

## Symptom

- Overnight, stationary: works perfectly. The link never drops, so it never has to
  reconnect — keeping an open link alive in the background is fine.
- On the move (a drop happens): the link goes down and stays down. The loop is open
  and AndroidAPS enacts nothing. It recovers only when the app is brought to the
  foreground.

"Fine overnight, broken when I move" is the signature of a broken **re-connect** path,
not a broken app. The app keeps running the whole time.

## Evidence (log window 15:30–18:00, one day)

| Time | Event |
|---|---|
| 16:03:58 | Link drops, GATT status **147** (abnormal disconnect), `Expected: false`. Client kept open on purpose so `autoConnect=true` can recover it. |
| 16:06 → 17:52 | Reconnect watchdog fires **65 times**, each `close()` + `connectGatt(autoConnect=true)`. |
| 16:04 → 17:52 | **Zero** connection callbacks. Not one `CONNECTED`, **not even a single `CONNECTING`** state. The stack never acted on any of the 65 requests. |
| 17:52:34 | One `CONNECTED`, ~29 s after the watchdog's 17:52:05 reopen — i.e. right when the user opened the app. |
| 17:52:43 | Pump reached (wakeup OK), loop could resume. |
| 17:56:41 | Dropped again, status 147. |

Gap with the loop open: **16:03:58 → 17:52:34 = 1 h 48 min**, ended by the user opening
the app, nothing else.

## Root cause

The RileyLink driver opens every connection — the first one and every reconnect — with
`connectGatt(autoConnect = true)` (`RileyLinkBLE.connectGattInternal`). That is a
*passive* request: Android parks it in a low duty-cycle background scanner. While the
app is not in the foreground that scanner is too slow to catch the RileyLink's
advertising, so the request sits there producing no callback.

The reconnect watchdog made it worse. By calling `close()` + reopen every 60–90 s with
the *same* `autoConnect=true`, it kept restarting the slow background scan from scratch,
so no single attempt ever matured. 65 attempts, nothing recovered — then an instant
connect the moment the app was foregrounded.

The word "Doze" is imprecise and was wrong: Doze did not close the app (the watchdog
thread ticked 65 times). The precise statement is: **Android does not service a passive
background GATT connect for this app state; it only completes once the app is in the
foreground.**

## Cross-pump investigation (what the other drivers do)

AndroidAPS already solved background reconnection for its always-Bluetooth pumps. The
RileyLink driver just does not use the same technique.

### 1. Direct connect vs passive connect

| Driver | `connectGattCompat(context, autoConnect, …)` |
|---|---|
| danars | `false` → **direct connect** |
| diaconn | `false` → **direct connect** |
| medtrum | `false` → **direct connect** |
| **rileylink** | **`true`** → passive background connect ← the one with this bug |
| equil | `true` (equil users report the same reconnect complaint) |

A direct connect (`autoConnect=false`) is an *active* attempt with a ~30 s timeout that
the Android stack services even for a backgrounded foreground-service app (AndroidAPS
runs one). That matches the ~29–38 s connect times seen in the log once it did connect.

### 2. Connection is queue-driven for the other pumps, but a no-op for Medtronic

`CommandExecutor` (the command queue) actively manages the link for the other pumps:

- When a command is queued it calls `pump.connect("Connection needed")`, which for
  Dana/Diaconn/Medtrum opens a fresh **direct** connection.
- If it does not connect within `PUMP_MAX_CONNECTION_TIME_IN_SECONDS` (119 s), it calls
  `pump.stopConnecting()` and then the **Bluetooth watchdog**: toggle the phone's
  Bluetooth radio (`platform.restartBluetooth()`) and `connect()` again.
- When the queue empties it calls `pump.disconnect()`.

For Medtronic / RileyLink, `connect()`, `disconnect()` and `stopConnecting()` are the
empty default implementations in `PumpPluginAbstract`. So **none** of the queue's
connection machinery — connect-on-demand, the 119 s timeout, `stopConnecting`, the
Bluetooth-toggle watchdog — does anything for RileyLink. The RileyLink link is managed
separately and, until this fix, relied entirely on passive `autoConnect`.

This is by design in part (the RileyLink keeps a persistent link because the
RileyLink↔pump radio tune-up is expensive, so it should not connect/disconnect per
command like Dana does). But it means the RileyLink driver had to grow its own
reconnection, and that reconnection used the wrong kind of connect.

### Architectural note (important)

The other pumps have integrated Bluetooth: the phone connects **directly to the pump**.
The RileyLink is a **relay** — phone↔RileyLink over BLE, then RileyLink↔pump over
916 MHz radio. But the segment that was failing here is the **phone↔RileyLink BLE
segment**, which is an ordinary GATT connection exactly like phone↔Dana. So the
direct-connect technique applies to it directly. The radio relay (tune-up, frequency)
is a separate layer that only runs once the BLE link is up, and it was working (the
pump answered in 38 s once BLE reconnected).

## What was tried before and did not solve it

- Capping the recovery-probe backoff and probing on queued work — helps the
  pump-radio recovery, but does nothing when the **BLE** link itself is down.
- A reconnect watchdog that reopened with `autoConnect=true` — this is the churn above;
  it did not recover the link and may have slowed it down.
- A "pump unreachable / loop open" audible alarm — correct as a *last-resort* safety
  net, but it is not a fix: the user cannot make the phone reconnect by hearing a sound.
  The system has to self-recover. (This alarm was reverted; it can come back later as a
  safety net on top of real recovery.)

## The fix implemented

Make the RileyLink reconnect the way Dana/Diaconn/Medtrum connect.

- `RileyLinkBLE.findRileyLink(address, autoConnect: Boolean = true)` and
  `connectGattInternal(autoConnect: Boolean = true)` — new parameter, default `true`,
  so every existing caller (first connect, reconfigure, unblock) is unchanged.
- `RileyLinkService.reconnectIfLinkLost()` (the watchdog) now reopens with a
  **direct connect**, `findRileyLink(address, autoConnect = false)`.
- The passive `autoConnect=true` is still used for the first connection and for the
  short grace window right after a drop (cheap, sometimes enough). Only the *forced
  reopen* after the grace window switches to the direct connect.
- No churn: the retry cadence (60 s) is longer than the direct-connect timeout (~30 s),
  so each attempt runs to a definite success/failure before the next, instead of
  cancelling an attempt that is still in progress.

Files:
- `pump/rileylink/.../ble/RileyLinkBLE.kt`
- `pump/rileylink/.../service/RileyLinkService.kt`

## Recommendations to the AndroidAPS maintainers / further work

1. **Adopt direct connect for RileyLink reconnection** (this change). This is the core
   fix and brings RileyLink in line with the other drivers.
2. Consider giving the RileyLink driver the same **Bluetooth-toggle watchdog** the
   command queue gives the other pumps, as a last resort after N failed direct connects
   (needs care — toggling the radio also affects the CGM link, so it should be
   behind the existing `PumpBtWatchdog` preference and rate-limited).
3. Equil uses `autoConnect=true` too and likely benefits from the same change.
4. Separate, smaller gaps found while investigating (not fixed here, worth a look):
   - `KeepAliveWorker.checkPump()` only *evaluates* the core "pump unreachable" alarm
     when a read-status was issued in the last 5.5 min. During a BLE outage the queue
     cannot issue one, so the guard trips and the core alarm is never reached — a long
     outage can be silent. The alarm depends on the very machinery that fails during a
     backgrounded BLE outage.
   - `MedtronicPumpPlugin.isPumpNotReachable` returns `false` ("reachable") when the
     RileyLink state is not Ready, so the Medtronic-level unreachable notification does
     not fire while BLE is down.
