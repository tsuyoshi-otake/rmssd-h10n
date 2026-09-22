# Verified project learnings

- Polar SDK 6.16.1 `connectToDevice` may return after installing a search subscription and later log a search failure without throwing to the caller. Supervise accepted requests with a deadline as well as catching synchronous errors. Deterministic connection tests verify timeout, cancellation-before-retry, backoff, duplicate wakes, late connection and stop invalidation.

- Android lint treats an unescaped Windows drive colon in `local.properties` as `PropertyEscape`, even when Gradle compilation succeeds. Use `sdk.dir=C\:/...`; `testDebugUnitTest`, `lintDebug` and `assembleDebug` passed with this form.
- A periodic JobScheduler callback alone does not exempt Android 12+ foreground-service launches. Recovery needs an eligible launch route or battery-optimization exemption. On the Android 15 test phone, an exempt app restored its gracefully removed service through job 2401 without opening the dashboard; repeated jobs retained one engine.

- A Java/Gradle `Unable to establish loopback connection` with `PipeImpl ... Invalid argument` on this Windows host can be caused by `TEMP`/`TMP` using the DOS 8.3 path `C:\Users\DEVELO~1\...`. Set both process variables to the full path `C:\Users\developer\tmp` before retrying Gradle; a standalone Unix-domain-socket probe and the full Android build verified this.
- A BLE promise deadline does not cancel the underlying native WinRT operation. Keep the device identity leased until a timed-out connect actually settles and any late connection has been disconnected; otherwise old cleanup can race a new connection to the same H10.
- An H10 `startRecording` timeout is an ambiguous commit. Correlate `requestRecordingStatus` with the exact attempt ID. If the wall-clock anchor cannot be proven, preserve raw RR in quarantine instead of assigning guessed timestamps or issuing a destructive second start.
