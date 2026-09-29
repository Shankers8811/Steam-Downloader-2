# Notes from studying GameNative (github.com/utkarshdalal/GameNative)

Goal: understand how that app runs Steam *natively* on Android and apply the
same architecture to this downloader.

## Sidenote: what Valve's own GitHub offers (checked, per request)

`github.com/ValveSoftware` publishes only PC/tooling projects — Proton,
Wine, DXVK, gamescope, GameNetworkingSockets (multiplayer transport for game
devs), Source SDK, SteamOS tracker, steam-runtime (Linux container env).
**Valve does not publish the CM protocol, depot download logic, or any
Android library** — that is exactly why every "native Steam on Android"
app (GameNative, Pluvia, DepotLab… — and now this one) builds on the
community-maintained, field-proven JavaSteam stack, whose semantics mirror
the official desktop client (SteamKit2 lineage). Compatibility here is
verified version-exact against the released `javasteam:1.8.0` source tag.

## How it works

1. **No .NET binary, no Wine for downloading.** GameNative talks to Steam
   directly through **JavaSteam** — `in.dragonbra:javasteam` — a JVM port of
   Valve's SteamKit2 protocol (same library family DepotLab/Pluvia use), plus
   `in.dragonbra:javasteam-depotdownloader`, a JVM re-implementation of
   SteamRE's DepotDownloader. Both are published to **Maven Central**
   (verified: `javasteam 1.8.0`, `javasteam-depotdownloader 1.8.0`).
   This is *the* native path for Steam on Android. (GameNative itself uses a
   source-built fork `io.github.joshuatam` that only adds tweaks; the official
   artifacts provide the same core API and are what we consume.)

2. **Connection**: `SteamClient(SteamConfiguration)` with
   - `withProtocolTypes(EnumSet.of(ProtocolTypes.WEB_SOCKET))` — WebSocket CM
     only, most reliable on mobile networks (they even pin pingInterval).
   - `withServerListProvider(FileServerListProvider(serverlist.bin))` — cached
     CM server list persisted to a file, so cold starts don't need DNS/probing
     every launch.
   - Callback pump: one IO loop of `callbackManager.runWaitCallbacks(1000)`.
   - `DisconnectedCallback` → mark endpoint BAD (`servers.tryMark(...,
     ServerQuality.BAD)`) and reconnect with backoff.
   - Login is a real CM session (`SteamUser.logOn(LogOnDetails(...))`).

3. **Sign-in (matches the Steam desktop app)**
   `steamClient.authentication.beginAuthSessionViaCredentials(AuthSessionDetails)`
   with `username`, `password`, `persistentSession`, `deviceFriendlyName`,
   `clientOSType`. An `IAuthenticator` bridge surfaces second factors:
   - `getDeviceCode(previousCodeWasIncorrect)` → code from the Steam Mobile App
   - `getEmailCode(email, previousCodeWasIncorrect)` → emailed code
   - `acceptDeviceConfirmation()` → "approve this sign-in in the app" UX;
     returning `false` declines it so Steam falls back to code entry.
   Then `authSession.pollingWaitForResult().await()` yields `accountName`,
   `accessToken`, `refreshToken`.
   **Session restore = `logOn` with `accessToken = <refreshToken>`** — no new
   password / 2FA challenge. Refresh token persisted (they store it in prefs;
   we store it AES/GCM-encrypted instead), afterwards CM `logOn` again.

4. **Licenses**: `LicenseListCallback` arrives automatically after logon
   (package ids). This list is handed directly to the downloader
   (`DepotDownloader(steamClient, licenses, ...)`) which enforces ownership per
   app AND per depot via `accountHasAccess`; free-to-play apps are auto-licensed
   (`requestFreeAppLicense`). DLC depots are skipped when not owned —
   ownership is CM-enforced, not trusting store metadata.

5. **PICS** (`SteamApps.picsGetProductInfo(apps, packages).await()`): appinfo
   KeyValues give depot sections: id, `config/{oslist,osarch,language,
   lowviolence,dlcappid}`, `manifests/<branch>/{gid,download,size}`,
   `common/name`. Used to compute depot plans + total download sizes +
   which depots belong to which DLC (`dlcappid` in depot config).

6. **Downloading**
   ```kotlin
   val dd = DepotDownloader(
       steamClient, licenses,
       debug = false, maxDownloads = 8, maxFileWrites = 1,
       androidEmulation = true,      // pretend Windows: picks Windows depots on Android
       parentJob = job,              // cancel job == stop the download pipeline
   )
   dd.addListener(...)               // IDownloadListener
   dd.add(AppItem(appId, installDirectory = dir, branch = "public",
                  os = "windows", osArch = "64", depot = ids, verify = resume))
   dd.finishAdding()
   dd.awaitCompletion()              // or getCompletion().await()
   dd.close()
   ```
   - Staging lives in `<installDir>/.DepotDownloader` (+ `staging/`,
     `depot.config`) — killing the process and re-running resumes the same
     depot from recorded chunks; `verify = true` on resume validates existing
     content instead of re-downloading.
   - `onChunkCompleted(depotId, depotPercent, compressedBytes, uncompressedBytes)`
     is the telemetry source: deltas of *compressed* bytes/sec = internet rate;
     deltas of *uncompressed* bytes/sec = effective materialization/disk rate.
   - `onFileCompleted(depotId, fileName, depotPercent)` = that file is whole
     (files are pre-sized and chunk-filled in place).
   - Manifest `download` sizes (compressed) drive ETA; `size`
     (uncompressed) is the installed size.

7. **Runtime deps the Pocket library actually needs on Android**
   - `in.dragonbra:javasteam:1.8.0` (+ protobuf-java, ktor, okio — transitive)
   - `in.dragonbra:javasteam-depotdownloader:1.8.0`
   - `com.github.luben:zstd-jni:<ver>` (AAR — some CDN chunks are zstd)
   - `org.tukaani:xz` (LZMA chunks)
   - `org.jetbrains.kotlinx:kotlinx-coroutines-jdk8` (CompletableFuture.await)

## What we took / changed in this app because of this
- Replaced the hand-rolled web-only sign-in with the real CM authentication
  session (same UX: password → Steam Guard code / approve-in-app).
- Replaced the simulated CDN with the native `javasteam-depotdownloader`
  engine — real depot keys, manifests, chunks, resume staging.
- License card now shows CM-enforced ownership; the downloader double-checks.
- Storage destination is a real filesystem path (app-external `SteamLibrary`,
  PC-visible over USB/MTP) because the JVM downloader writes through NIO —
  **SAF DocumentFile trees are not real paths and silently break native
  writers** (loophole closed).
- Speed/disk/ETA telemetry source = uncompressed/compressed chunk counters.
