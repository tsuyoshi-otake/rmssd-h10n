# Android collection recovery (#26)

## Symptom and evidence

The Android 15 phone ran the foreground engine but retained exactly 31,505 old
points. Status stayed `connecting/initial` with no RR. Bluetooth diagnostics
timed out; an OFF/ON request subsequently left the system in `BLE_ON`, not `ON`.
The user authorized a phone reboot. This OS radio failure is separate from
keeping the app process alive; an app cannot promise to repair a wedged system
Bluetooth stack.

Polar SDK 6.16.1 `connectToDevice` starts a search subscription when the selected
device has no session. Its asynchronous search error is logged internally and
does not throw to our original five-attempt catch block. A request accepted with
no callback therefore remained `connecting` forever. A service notification
also said it was measuring before any RR had arrived.

## Ownership and contract

`PolarBle` retains the single SDK instance. `BleConnectionRetry` runs on its
existing control executor and owns exactly one timeout or retry timer. An
accepted request has a 60-second deadline. A silent/failed attempt first cancels
the prior request, then waits 30 seconds with exponential backoff and jitter,
capped at 5 minutes. Normal SDK auto-reconnect gets a deadline without starting a
second request. A connected callback clears the deadline and resets backoff.
Stop invalidates dequeued callbacks; cancellation failure terminates retrying
with `needs_action/connect_cancel_failed`, rather than overlapping native work.
No recording operation, deletion, SDK replacement or Bluetooth power toggle is
performed by this retry policy.

`HrvEngine` derives `CollectionState` from connected/fresh accepted RR/stalled
state. `MonitorService` displays that state and preserves it across duplicate
service start intents. Running the service alone no longer reports collection.

## Verification rubric

1. Verify `:app:testDebugUnitTest`. Expect silent/throwing connects, deduplication,
   SDK reconnect, late connection, stopped callbacks, cancellation failure and
   notification freshness cases to pass with all native regressions.
2. Verify `:app:lintDebug :app:assembleDebug`. Expect zero lint errors and the
   version 1.0.4/code 5 APK; report warnings without hiding them.
3. Verify the phone after reboot and `adb install -r`: inspect the SQLite
   snapshot, service diagnostics and installed APK hash. Expect fresh RR and
   increasing persisted point count, with all original points preserved.
4. Verify a screen-off interval with two SQLite snapshots. Expect fresh sample
   timestamps and points advancing while the display is off. A service tick
   alone does not satisfy this criterion.

## Scope and future reading

The change owns connection-request deadlines and truthful collection status.
Investigation expanded from app lifecycle into the exact SDK connect contract
and Android Bluetooth state because service uptime did not explain missing RR.
Recording persistence, HRV math and dashboard contracts are unchanged. The next
silent-connect issue can start at this document and `BleConnectionRetryTest`,
without first reading PFTP recovery. SDK and OS radio limitations still matter.

## Verification results

- Native tests: 94 passed, zero failures/errors. Android lint: zero errors,
  38 warnings. Debug assembly succeeded. Build/test runner process audit was
  empty after completion. No dependency version or lockfile changed.
- Device verification is pending reconnect/unlock after the authorized reboot.
  Do not equate a successful build or advancing service status with live RR.
