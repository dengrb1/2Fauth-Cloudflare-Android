# Repository Guidelines

## Project Structure & Module Organization

Single-module Android app written in Kotlin. Key paths:

- `app/src/main/java/com/dengrb1/twfauth/cloudflare/` — application source (`MainActivity.kt`, `LocaleHelper.kt`)
- `app/src/main/res/` — layouts, drawables, strings (includes `values-zh-rCN` for Chinese localization)
- `app/src/main/AndroidManifest.xml` — manifest
- `gradle.properties` — default `workerUrl` and Gradle JVM settings
- `.github/workflows/android-build.yml` — CI workflow (manual dispatch)
- `Photos/` — screenshots and documentation images

The Gradle root contains a single `:app` module defined in `settings.gradle.kts`.

## Build, Test, and Development Commands

```bash
# Debug APK (inject Worker URL)
gradle assembleDebug -PworkerUrl="https://your-worker.workers.dev"

# Debug APK (uses default URL from gradle.properties)
gradle assembleDebug

# Unit tests
gradle test

# Android instrumented tests
gradle connectedAndroidTest
```

The Gradle Wrapper (`gradlew` / `gradlew.bat`) is not currently bundled; install Gradle locally or generate the wrapper first.

## Coding Style & Naming Conventions

- **Language:** Kotlin with `kotlin.code.style = official` (set in `gradle.properties`).
- **JVM target:** 17 (`sourceCompatibility` / `targetCompatibility` / `jvmTarget`).
- **Indentation:** 4 spaces (Android/Kotlin default).
- **Naming:** PascalCase for classes, camelCase for functions and properties, UPPER_SNAKE_CASE for constants.
- **View binding** is enabled; use generated binding classes (e.g., `ActivityMainBinding`), not `findViewById`.
- **No lint config** is customized beyond defaults; follow standard Android Lint rules.

## Testing Guidelines

- **Unit tests:** JUnit 4 under `app/src/test/`.
- **Instrumented tests:** AndroidJUnitRunner + Espresso under `app/src/androidTest/`.
- No coverage thresholds are enforced yet.
- Name test methods using backtick-quoted descriptive sentences (Kotlin convention) or standard `testMethodName` style.

## Commit & Pull Request Guidelines

Commits follow **Conventional Commits** (observed in history): `feat:`, `fix:`, `chore:` prefixes are used consistently. Keep subject lines imperative and under 72 characters.

Pull requests should include:
- A clear description of the change.
- A linked issue when applicable.
- Screenshots or screen recordings for UI changes.
- Confirmation that `gradle assembleDebug` succeeds locally.

## Security & Configuration

- The Worker URL is injected at build time via `-PworkerUrl` and stored in `BuildConfig.WORKER_URL`. Do not hard-code production URLs.
- Tokens are stored in `EncryptedSharedPreferences` backed by `MasterKey`. Never log or persist tokens in plain text.
- Biometric or device-credential authentication is required before displaying saved entries.
