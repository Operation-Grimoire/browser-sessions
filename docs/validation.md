# Validation

Results are recorded after running the named checks. Pending items are not passes.

| Gate | Status |
| --- | --- |
| Shared cookie/HTTP regressions | Eight tests passed |
| JCEF adapter unit tests | Seven tests passed; includes command-ordering race, timeouts and cancellation |
| Android release AAR and lint | Passed before the metadata-reader refinement; final run pending |
| Native Android WebView round trip | Local emulator aborted with System has crashed; fresh CI emulator pending |
| Native JCEF round trip | Passed on Windows with embedded Chromium 152; final CI head pending |
| Separate-process cookie restore/logout | Pending on Android and Windows |
| Real-source login/browser-to-HTTP | Pending user participation |
| macOS/Linux native runtime | Unexecuted |
| Kotlin/Native | Unimplemented |
| Hosted account sync, device keys, encryption | Outside this library slice |

Generated test reports and browser profiles stay under ignored build directories.
The desktop example uses its own browser profile under the current user's local
application-data directory, separate from everyday browser profiles.

The local Android API 35 emulator restarted system_server during APK installation,
then aborted instrumentation with `INSTRUMENTATION_ABORTED: System has crashed`.
That local run is blocked by emulator stability and is not a passing library test.
CI uses a fresh Linux/KVM emulator and explicitly checks instrumentation success
for all four separate-process persistence phases.

Native Windows testing exposed an upstream JCEF convenience-client ordering race.
The maintained adapter owns a native observer and correlates replies received
before or after the generated command ID. Unit regressions cover both orders,
unrelated replies, cancellation and close. The embedded browser round trip passed
with that binding; publication also requires the Windows CI runtime job.
