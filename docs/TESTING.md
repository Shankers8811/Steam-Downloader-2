# Testing notes — device & distribution deep-dive

The key steps (get the APK, sideload it, move games into PC Steam) now live
in the [README](../README.md). This file keeps the deeper device-specific and
distribution notes.

## Building the APK yourself

- **Android Studio (menu only, no "Run" button needed):**
  *Build ▸ Build Bundle(s) / APK(s) ▸ Build APK(s)* → click **locate** in the
  popup → `app/build/outputs/apk/debug/app-debug.apk`.
- **Command line (JDK 17 + Android SDK required):**
  `gradle :app:assembleDebug` — no wrapper ships with the repo by design; CI
  pins Gradle 9.3.1. Output: `app/build/outputs/apk/debug/app-debug.apk`.
- **GitHub builds it for you:** every push produces the APK both as a
  workflow artifact (`depotdownloader-debug-apk`) and on the `apk-builds`
  branch / Releases pre-release.

## Huawei Nova 7i (EMUI / HMS, Android 10) specifics

The app is built **HMS-safe** — no Google Play services, no Firebase, nothing
in the startup path that requires GMS.

- Game files land in
  `Android/data/com.aistudio.depotdownloader.app/files/SteamLibrary/steamapps/common/<Game>`
  on internal storage — reachable over USB (MTP) or with Huawei's **Files**
  app.
- Pause/resume survives app restarts; if a download fails mid-flight, press
  Resume — content is re-verified, not re-downloaded.

## Publishing options (beyond sideloading)

Steam does **not** accept Android APKs at all — it ships Windows/macOS/Linux
builds only, and Android is not a publishable platform on the Steam store.
Your distribution options for this app are:

- **Direct APK sharing** (what the Releases pre-release + `apk-builds` branch
  do),
- **Google Play Console** — web UI upload, no Android Studio needed; new
  apps must upload an **AAB**, which `gradle :app:bundleRelease` produces
  (release signing needs `KEYSTORE_PATH` / `STORE_PASSWORD` / `KEY_PASSWORD`
  env vars — never the committed `debug.keystore`),
- **Huawei AppGallery** — better fit for HMS devices like the Nova 7i.

## What CI does *not* cover (test by hand)

See the checklist in the [README](../README.md#manual-testing): real
Steam Guard login, library sync against a real account, a real download, and
pause/resume on a physical device. Use the in-app **🐞 Report** button if
anything crashes — it carries the full crash trace.
