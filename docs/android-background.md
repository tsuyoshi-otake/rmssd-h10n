# Android automatic startup and recovery (#24)

`MonitorService` owns the sole native engine. `HrvDb.kv.engine == native` is the
persisted intent to measure; `deviceMac`, `acc` and `user` are committed together
before engine initialization. An explicit stop stores `engine=js` and cancels the
recovery job before BLE cleanup. It still marks the user's recording discarded
after the worker stops. Unexpected service loss preserves recording recovery.

## Restart routes

- `BootReceiver`: boot after first unlock and APK replacement. Credential-encrypted
  SQLite is never accessed before unlock. `connectedDevice` is eligible for the
  Android boot/update foreground-service exemption.
- `MainActivity`: restores an enabled engine without waiting for WebView boot.
- `MonitorService`: `START_STICKY` requests an OS restart, and `stopWithTask=false`
  keeps measurement separate from dismissing the dashboard.
- `MonitorRecoveryJob`: one persisted JobScheduler job checks every 15 minutes.
  Android can defer that interval; this is a fallback, not a restart deadline.
  It never creates a second engine or polls Bluetooth. Missing permissions and
  missing device selection do not cause retry loops.

Android 12+ requires an exemption to launch a foreground service from a periodic
job. The app asks once, using the system UI, to exempt continuous BLE monitoring
from battery optimization. A refusal is respected; the user can later change
Android Settings > Apps > RMSSD > Battery. Boot/update and foreground return remain
separate eligible launch routes. Permission revocation defers recovery until
permissions are restored. Android's explicit **Force stop** is not bypassed.

Startup reserves a persisted retry deadline before native initialization.
Failures back off from 30 seconds to at most 15 minutes, with jitter. The periodic
job supplies the retry; there is no independent fast alarm or tight polling loop.
Successful initialization clears the failure count. Repeated boot/activity/job
requests are coalesced, and the service rechecks ownership before starting BLE.

Sources: [foreground-service launch exemptions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start),
[battery exemptions](https://developer.android.com/training/monitoring-device-state/doze-standby).

## Verification rubric

1. Verify: `:app:testDebugUnitTest`. Expect: recovery policy covers stopped/missing
   device/locked storage/revoked permission/already running/deadline/backoff cases,
   and all existing native regressions pass.
2. Verify: `:app:lintDebug :app:assembleDebug`. Expect: no new lint errors and an
   APK with versionName 1.0.3 / versionCode 4. Existing lint findings, if any,
   must be reported separately rather than suppressed.
3. Verify on device: `adb install -r` followed by service/job/SQLite inspection.
   Expect: existing points preserved, one foreground service and one persisted
   recovery job, automatic package-update restart for enabled monitoring.
4. Verify on device: dismiss dashboard / screen off, then inspect saved status.
   Expect: native timestamps keep advancing without the WebView. Verify a
   graceful service removal and forced execution of job 2401. Expect: one engine
   restarts from persisted intent; this is not a force-stop bypass test.
5. Verify: release safety scan, APK hash, installed version, runner process audit.
   Expect: findings recorded, release artifact matches installed APK, no surviving
   test/build runners. Real boot and long-term OEM behavior require separate
   observations if not exercised during this release.

## Scope and future reading

Changes are confined to service lifecycle, startup entry points, atomic restart
configuration, manifest, version metadata, and recovery tests. The periodic job
required examining Android background-launch rules beyond the existing service.
BLE, recording recovery, HRV calculation and the Web dashboard retain their
contracts. Future startup issues can start here and at `MonitorRecoveryPolicyTest`
without reading Polar transport internals. Android exemption and scheduling
constraints remain necessary context.

## Release verification (2026-09-22)

- Native tests: 84 passed; Node regressions: 20 passed. Android lint: zero errors,
  38 warnings, including the intentional battery-exemption request. Debug APK
  assembly succeeded. No matching build/test runner survived the checks.
- Android 15 device: updating with `adb install -r` automatically started the
  foreground service through `MY_PACKAGE_REPLACED`. Graceful service removal
  followed by `cmd jobscheduler run -f dev.otake.rmssdh10n 2401` restored it.
  Repeated job execution logged `RUNNING` without a second engine.
- SQLite integrity passed; all 31,505 original points were compared by user,
  timestamp and JSON and remained unchanged. A private backup stays outside Git.
  Screen-off status timestamps continued advancing. Live sensor reception,
  task dismissal and a real reboot are not yet certified by this check.
- Installed APK and release candidate SHA-256:
  `9a351f97e1de4383bdb35660a0b3b63d948f1b14f835a09201c4e0c992d61fa4`.
- Existing production dependency audits remain unresolved: root 13 findings
  (3 moderate, 9 high, 1 critical), app 4 (1 low, 2 high, 1 critical).
  No dependency or lockfile was changed in this release.
- Release safety scan: repository was already public; existing tags/releases
  were v1.0.0 through v1.0.2; no LICENSE file. Matches comprised existing Git
  attribution, account/local-path references, protocol UUIDs and test device
  identifiers. No token/private-key patterns or tracked credential files were
  found. Visibility and history were not changed.
