# V1 translation and night reception

V1 has two independent voice features. OpenAI handles full-duplex translation
between the two selected languages. Cartesia handles the nighttime receptionist.
Translation uses one centered transcript without guest, receptionist, or speaker
assignment. The layout alone makes no claim about model or audible latency.
Fish Audio is removed from the active app and provider route.

## Credentials and the owner's V1 exception

| Feature | Credential source | Scope |
| --- | --- | --- |
| OpenAI translation | The person configuring the tablet enters their own key in the app | Translation only; native encrypted device storage |
| Cartesia night receptionist | The approved CI release secret `CARTESIA_API_KEY` | Embedded in the signed release APK; no guest key prompt |
| Debug builds | Empty bundled Cartesia key | Isolated verification; no release credential |

The owner explicitly requested that Cartesia be included in the V1 APK. This is
a narrow exception to the repository's general guidance against shared provider
keys in public APKs. A bundled APK credential is extractable; CI secret masking,
Android app signing, and native code do not make it private after distribution.
Keep the actual value out of source control, logs, screenshots, and documentation.
The release build uses `-PrequireBundledCartesia` to reject a missing approved
secret. Debug builds keep `BuildConfig.CARTESIA_API_KEY` empty even when the
release secret is present in the build environment.

The exception does not apply to OpenAI. Its key must never enter BuildConfig,
JavaScript or release assets. A separately supplied temporary benchmark key may be used only in an explicitly requested CI latency test; it never enters Android build steps. The app's OpenAI key screen can
save, replace, or remove the key through `SecretStore(context, "openai")`. The
field stays masked, and the stored value is not displayed when setup reopens.
The key is encrypted with the device Keystore and excluded from backup.
Translation setup does not require the hotel backend or a Google account.

## Nighttime receptionist setup

Hotel setup stores the hotel's name, address, area, reception hours, approved
facts and check-in guidance, optional coordinates, and an optional reviewed local
guide. Unknown facts remain unknown. The agent must not invent hotel details or
room availability. Setup does not include Google authentication or an admin
portal; those are V2 work.

“Use device location” requests Android location permission from the visible app.
A voice tool cannot grant permission. The native location provider accepts a
recent position with usable accuracy and otherwise falls back to manually
configured hotel coordinates. If neither is available, nearby suggestions report
that location is unavailable rather than guessing.

Nearby suggestions use approved hotel guide entries when available, with a
best-effort OpenStreetMap lookup as a fallback. Map results include source
attribution in the reception interface. They do not establish that a business is
open, a route is safe, or a straight-line distance is a walking distance. Location
is supplied to the voice provider when needed for the active conversation; map
lookups also send the lookup coordinates to the map service.

The taxi API has not been supplied. The default taxi gateway returns
`TAXI_API_NOT_CONFIGURED` and states that no taxi was called or booked. The
confirmation and receipt boundary is prepared for a future integration; do not
present it as working dispatch.

## Delivery and verification

Preserve the published application ID `dev.orange.hotel`, the existing pilot
signing key, and the GitHub Release update path. CI signs through the existing
`ORANGE_KEYSTORE_BASE64`, `ORANGE_STORE_PASSWORD`, `ORANGE_KEY_ALIAS`, and
`ORANGE_KEY_PASSWORD` secrets. Increase both Android version files for each
release. Never uninstall the real tablet app or clear its data to perform an
update. A PR merge or successful build alone does not prove publication or an
installed-device update.

Run generated-asset checks, isolated browser fixtures, applicable Android JVM
tests, lint, and APK assembly as described in
[verification guidance](verification/README.md). Use fake transports and
synthetic data for tests. Report exactly which checks ran and any environment
limitations. Full-duplex acoustic behavior, live provider availability,
translation quality, latency, and physical tablet operation need separate
measured acceptance; source tests and compilation do not establish those claims.

## Verification candidate (updated for 17.0.44)

Local source verification passes: 194 Python tests, 21 Node tests, 13 offline
agent-provision/probe tests, Android JVM tests, debug lint, release APK assembly
and debug instrumentation assembly. The release assembled locally has no live
Cartesia secret and is not the distributable signed APK.

GitHub CI additionally runs the browser scenarios, Android runtime fixtures,
and the same-signer retained-data upgrade. The 17.0.44 repair reuses the existing
Cartesia agent and performs no Cartesia API calls, as requested by the owner.
A manual OpenAI-only latency probe is separate from release builds and runs only
with an explicitly supplied temporary test secret. A provider handshake is separate from
physical tablet microphone/speaker acceptance. OpenAI live access is checked by
the person supplying their own key on the device.
