# Environment and credentials

The backend requires Python 3.12+ and the dependencies pinned in `requirements.txt`. Native builds require JDK 17, SDK 34 and the checked-in build wrapper.

## Backend

- `ORANGE_DB`: private persistent database path. Never point verification at the deployed database.
- `ORANGE_ADMIN_TOKEN`: random administrator credential of at least 32 characters. Keep it server-side; never enter it in the reception app.
- Issue separate hotel-scoped device and staff credentials through the authenticated dashboard. Tokens are shown only on issuance; the database stores their hashes.
- Serve the actual deployment over HTTPS. Do not bypass certificate checks or put credentials in URLs.
- Configure real hotel name, timezone, approved languages, knowledge, arrival instructions and human contacts. The admin form starts empty. A successful JSON save is not staff approval of its content.
- `demo` is not an accepted connector. Existing stale demo settings cannot produce synthetic reservations. Arrival guidance uses approved hotel instructions without reservation lookup; it does not complete check-in.

Application-level inbox payloads are not encrypted by the database layer. Configure storage access, disk/backup encryption, retention, deletion and consent before real guest use. Do not collect identity documents, access codes or payment information.

## Device

Authenticated staff setup accepts the actual HTTPS hotel service URL and a device credential. They are stored as one encrypted bundle through `HotelConnection` / `SecretStore`, using authenticated encryption and a device-keystore key. Replacing the bundle cannot accidentally pair a new token with an old URL. The URL cannot contain login information, query parameters or a fragment.

Current signed releases do not embed a shared Fish credential, backend URL, device token or administrator credential. A staff-saved voice credential remains encrypted. Devices that depended solely on an older embedded credential need an enrolled hotel backend or a staff-saved credential before translation can start. Old local-only notes are not automatically uploaded or erased; they must not be interpreted as staff delivery.

## Live audio route

Direct translation uses a staff-saved Fish credential. An enrolled device instead requests a managed session from the backend: `VOICE_ROUTE=fish` uses the hosted Fish agent, while `VOICE_ROUTE=livekit` uses the separately deployed worker described in [the self-hosted route](selfhosted-voice-route.md). Provider credentials stay on that worker/backend. The legacy generic relay remains disabled. Staff-led operational endpoints remain available.

Provider usage is billed to the owner's provider account. Internal provider component tests intercept all network requests explicitly.

Managed agent cache identity includes the complete published configuration and credential scope. A changed prompt/model/recognizer/voice/turn setting gets a new identity, without mutating an agent already serving another session.

Timing logs contain endpoint names, sequences and monotonic timestamps, not guest text or credentials. Input activity estimates, provider-final receipt, decoded output receipt and sampled playback-head progress are distinct. None is an acoustic p50/p95 measurement or a reliable input/translation association by itself.

## Signing and publication

Signing uses `ORANGE_KEYSTORE`, `ORANGE_STORE_PASSWORD`, `ORANGE_KEY_ALIAS` and `ORANGE_KEY_PASSWORD`; CI also reads `ORANGE_KEYSTORE_BASE64`. No build embeds a Fish credential: `BuildConfig.FISH_API_KEY` is always empty in every build, including CI artifacts, and the release job no longer receives `FISH_API_KEY`. Fish access comes from a device-entered key (encrypted `SecretStore`) or an enrolled hotel service. Never commit credentials and never inject a production key into any distributed build. Public releases v17.0.37 through v17.0.39 embedded the production Fish key (extractable from those APKs); rotate that key.

`android/VERSION` and `android/VERSION_CODE` advance explicitly. Publication must preserve `dev.orange.hotel` and the existing signer, supersede the previous public APK and reproduce `release-metadata.json` from the exact artifact. Android may require system confirmation for file-installed apps.

The credential embedded in earlier pilot APKs must be rotated/revoked by its account owner. Removing embedding in this version does not revoke previously exposed copies. Never print or attach that credential while investigating.

## Optional read-only integrations

Reservation gateway credentials stay server-side under `PMS_<UPPERCASE_UTF8_HEX_OF_EXACT_HOTEL_ID>_URL` and `_TOKEN`. The fixed lookup route is read-only by contract; it is not used to complete check-in. Edge deployments use a hotel-scoped role, WSS and an owner-controlled export. No sample export may serve a real hotel.
