# Validation

Results are recorded after running the named checks. Pending items are not passes.

| Gate | Status |
| --- | --- |
| Shared cookie/HTTP regressions | Expanded metadata/scope tests run in the required build job |
| JCEF adapter unit tests | Required build job covers command-ordering race, metadata, timeouts and cancellation |
| Android release AAR and lint | Required build job produces the AAR and lint report |
| Native Android WebView round trip | Fresh Linux/KVM CI passed; local emulator stability is blocked |
| Native JCEF round trip | Local Windows and required Windows CI passed with embedded Chromium 152 |
| Separate-process cookie restore/logout | Required Android and Windows CI jobs passed the four process phases |
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
for all four separate-process persistence phases. Initial CI run 42583141160 passed
all three jobs; subsequent fixes are revalidated at each PR head.

Native Windows testing exposed an upstream JCEF convenience-client ordering race.
The maintained adapter owns a native observer and correlates replies received
before or after the generated command ID. Unit regressions cover both orders,
unrelated replies, cancellation and close. The embedded browser round trip passed
with that binding; publication also requires the Windows CI runtime job.
