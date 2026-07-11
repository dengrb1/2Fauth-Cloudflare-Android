# 2Fauth Cloudflare for Android

Native Android client for [`dengrb1/2Fauth-Cloudflare`](https://github.com/dengrb1/2Fauth-Cloudflare), built against the Worker v1.4 Bearer API. The app uses a single-activity Jetpack Compose interface; it does not embed the 2Fauth web application.

## v1.4 feature scope

- Capability and user-profile discovery before and after login.
- Access/refresh-token authentication with rotated refresh-token persistence.
- TOTP and HOTP entries, including SHA-1, SHA-256, and SHA-512 algorithms.
- Search, A–Z/Z–A sorting, group filters, responsive OTP cards, and batch management.
- Entry create/edit/delete, enable/disable, QR scanning, and `otpauth://` URI entry.
- Group create, rename, recolor, and delete. Deleting a group leaves its entries ungrouped.
- OTPAuth text/file import with an optional destination group and detailed import results.
- Passphrase-protected encrypted JSON import/export through Android's Storage Access Framework.
- Profile, password, app-lock, system/light/dark theme, English/Simplified Chinese, API information, logout, and about settings.
- Optional Turnstile login challenge when the Worker advertises it through capabilities.

The v1 Bearer API does not provide the browser application's sharing, WebAuthn, registration, password-recovery, or administrator features, so the Android app does not expose placeholder screens for them.

## Security model

- Authenticated calls use `Authorization: Bearer`; the client neither sends cookies nor falls back to browser-only `/api/*` routes.
- Session tokens remain in encrypted device storage and are excluded from cloud backup and device-to-device transfer.
- A refresh token is rotated atomically. A failed authenticated request is refreshed and replayed at most once; refresh failure clears the local session.
- Saved sessions are gated by biometric or device-credential unlock. Devices without a secure lock do not retain new sessions long term.
- Cleartext network traffic is disabled. A required Turnstile challenge is restricted to the configured Worker origin and is reset after expiry or failed login.
- Plain JSON import/export and OTPAuth export are intentionally unavailable. Backup passphrases must contain 12–256 characters.

## Build

The Gradle Wrapper is included. A JDK 17 or newer and Android SDK 35 are required.

macOS/Linux:

```bash
./gradlew assembleDebug -PworkerUrl="https://your-worker.workers.dev"
```

Windows:

```powershell
.\gradlew.bat assembleDebug -PworkerUrl="https://your-worker.workers.dev"
```

If `-PworkerUrl` is omitted, the value from `gradle.properties` is used. Production builds should always provide an HTTPS Worker URL.

Run the local verification sequence with:

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

Connected Compose tests additionally require a running Android emulator or attached device:

```bash
./gradlew connectedDebugAndroidTest
```

An optional end-to-end repository acceptance test can be run against a disposable v1.4 Worker.
It exercises capability validation, login, TOTP/HOTP, entry and group mutations, OTPAuth import,
encrypted export/import, password-change sign-out, re-login, cleanup, and logout. The test is
skipped unless all three environment variables are present:

```bash
LIVE_WORKER_URL="https://your-test-worker.workers.dev" \
LIVE_WORKER_USERNAME="acceptance-user" \
LIVE_WORKER_PASSWORD="test-password" \
./gradlew testDebugUnitTest --tests "*.LiveWorkerV14AcceptanceTest"
```

Use a disposable test account or deployment; the test changes the password temporarily and cleans
up the entries and groups it creates.

## GitHub Actions

`.github/workflows/android-build.yml` runs unit tests, lint, and the debug build in that order for pushes, pull requests, and manual runs. Manual runs can override the Worker URL and opt into connected tests; the instrumentation job starts an emulator before executing them. The debug APK and lint report are uploaded as workflow artifacts.

## API compatibility

The app requires the Worker to advertise API v1 and Android as a compatible client. It uses the v1.4 routes for capabilities, profile, entries, groups, codes, password changes, OTPAuth import, and encrypted backup import/export. HTTP 400, 401, 403, 404, 409, 413, 429, and 503 responses are surfaced as structured errors; rate limits honor `Retry-After` when present.
