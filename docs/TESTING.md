# Build → APK → Huawei Nova 7i → PC Steam

## 1. Get the APK (three options — pick ONE)

### Option A — GitHub builds it for you (no PC tools needed)
1. Push/merge to `main` (or any push to the working branch).
2. Open the repo on GitHub → **Actions** tab → click the latest
   **"Android APK (debug)"** run → scroll to **Artifacts** → download
   `depotdownloader-debug-apk` (a zip containing `app-debug.apk`).

### Option B — Android Studio (menu only, no "Run" button)
- **Build ▸ Build Bundle(s) / APK(s) ▸ Build APK(s)** → when it finishes,
  click **locate** in the popup → `app/build/outputs/apk/debug/app-debug.apk`.

### Option C — Command line (needs JDK 17 + Android SDK local)
- In Android Studio's terminal: `gradle :app:assembleDebug` (no wrapper ships
  with the repo by design; CI pins Gradle 9.1.0).
- Output: `app/build/outputs/apk/debug/app-debug.apk`.

## 2. Install on the Huawei Nova 7i (EMUI / HMS, Android 10)

The app is built **HMS-safe** — no Google Play services, no Firebase, nothing
in the startup path that requires GMS. It runs on the Nova 7i the same as on
any Google phone.

1. Copy `app-debug.apk` to the phone (USB cable → MTP, Bluetooth, Telegram
   "Saved Messages", anything).
2. On the phone: **Settings ▸ Security ▸ Install unknown apps** → allow for
   your file manager, then tap the APK → **Install**.
3. First launch shows the Steam sign-in screen. Your real Steam credentials
   move only between the phone and Valve's servers; Steam Guard works with
   the mobile-app code, email code, or "approve this sign-in" prompt.
4. If you tick **Remember me**, later launches auto-sign-in from an encrypted
   token (no password/2FA each time).

Notes specific to Nova 7i:
- Game files arrive in **Android/data/com.aistudio.depotdownloader.app/files/
  SteamLibrary/steamapps/common/<Game>** on internal storage. You can reach it
  over USB (MTP) from your PC, or with Huawei's **Files** app.
- Pause/resume survives app restarts; if anything fails mid-download, press
  Resume — content is re-verified, not re-downloaded.

## 3. After testing: move the game into Steam (PC)

1. Copy the game folder (`steamapps/common/<Game>`) from the phone to your
   PC's Steam library folder (e.g. `C:\Program Files (x86)\Steam\steamapps\
   common\`).
2. In desktop Steam: **Library ▸ the game ▸ Install** (point it at the same
   library folder). Steam discovers the pre-existing files and just verifies
   them — only missing parts download.
3. **Verify integrity of game files** (right-click → Properties → Installed
   Files) to be 100% sure before launching.

## 4. About "publishing the APK on Steam"

Steam does **not** accept Android APKs at all — it ships Windows/macOS/Linux
builds only, and Android is not a publishable platform on the Steam store.
Your distribution options for this app are:
- **Direct APK sharing** (what we do for testing),
- **Google Play Console** — web UI upload, no Android Studio needed; new apps
  must upload an **AAB**, which `gradle :app:bundleRelease` produces,
- **Huawei AppGallery** — better fit for HMS devices like the Nova 7i.

## 5. Updating the GitHub page (repo housekeeping)

- **PR #1** merges this work into `main` — open it on GitHub and click
  **Merge** (repo owner rights required). The Actions workflow runs after the
  merge and produces the first artifact automatically.
- Useful description topics to set in the repo "About" box:
  `android`, `steam`, `depotdownloader`, `kotlin`, `jetpack-compose`,
  `javasteam`, `steamkit`.
