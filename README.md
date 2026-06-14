# 2Fauth-Cloudflare-Android

Android client for `2Fauth-Cloudflare`.

This version uses the Worker `/api/v1` Bearer Token API directly instead of loading the Web UI in a WebView.

## Features

- Native username/password login through `POST /api/v1/auth/login`
- Encrypted local storage for access and refresh tokens
- Access token refresh through `POST /api/v1/auth/refresh`
- Local biometric or device-credential unlock before showing saved sessions
- Native entry list from `GET /api/v1/entries`
- Batched TOTP refresh through `POST /api/v1/codes/batch`
- HOTP generation through `POST /api/v1/entries/:id/hotp`
- Tap a visible code to copy it to the clipboard

## Build

Set your Worker URL at build time:

```bash
gradle assembleDebug -PworkerUrl="https://your-worker.workers.dev"
```

If `-PworkerUrl` is omitted, the value in `gradle.properties` is used.

This repository currently does not include a Gradle Wrapper. Install Gradle locally or add a wrapper before running `assembleDebug`.

## GitHub Actions

The workflow at `.github/workflows/android-build.yml` accepts a `worker_url` input and injects it as:

```bash
gradle assembleDebug -PworkerUrl="${WORKER_URL}"
```

The resulting debug APK is uploaded as an artifact.

## API Notes

The app expects the Worker API documented in `F:\code\2Fauth-Cloudflare\API.md`:

- `clientType` is sent as `android`
- `Authorization: Bearer <accessToken>` is used for authenticated routes
- Refresh tokens are rotated, so the app persists the latest token after every refresh
- Browser-only cookie routes and `/api/session/close-soon` are not used
