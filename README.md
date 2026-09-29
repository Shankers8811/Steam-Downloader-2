# DepotDownloader Mobile

An Android app that signs you in to Steam — **over Steam's real CM protocol**,
the same one the desktop client uses — shows the game library of the signed-in
account, and downloads owned content **from Valve's real CDN** with the same
pause/resume safety guarantees as Steam for Windows.

This release replaces the earlier web-only sign-in + simulated CDN with a
native Steam session built exactly the way [GameNative](https://github.com/utkarshdalal/GameNative)
runs Steam on Android — see [docs/GAMENATIVE_NOTES.md](docs/GAMENATIVE_NOTES.md).

## Feature map

| Requirement | Where it lives |
| --- | --- |
| Sign-in screen (username + password) | `ui/screens/auth/LoginScreen.kt` |
| Steam Guard / 2FA (code from the Steam Mobile App, email code, or approve-in-app prompt) | `data/auth/SteamAuthManager.kt` — a JavaSteam `IAuthenticator` bridges the 2FA challenge to the UI; "enter a code instead" during app approval uses `sendSteamGuardCode` |
| Real CM session (WebSocket CM transport, cached server list, auto-reconnect) | `data/steam/SteamRuntime.kt` |
| Remembered login ("remember me") | `data/auth/CredentialVault.kt` (AES-256/GCM in AndroidKeyStore) + CM `logOn` with the persisted **refresh token** — no password re-entry |
| Library of the logged-in user | `LibraryScreen.kt` / `LibraryViewModel.kt` → `IPlayerService/GetOwnedGames` with the session access token (no API key needed) |
| License validation before download (separately-sold DLC excluded) | `data/license/LicenseValidator.kt` (+ `IUserService/CheckAppOwnership`) on top — and the download engine itself enforces ownership per account licenses from the CM session |
| Real depot downloads (manifests, depot keys, chunk decompression, checksums) | `data/download/DepotDownloadManager.kt` wrapping **JavaSteam's DepotDownloader** (DepotDownloader port, native on the JVM/Android) |
| Pause mid-download without corruption + resume on the phone later | Per-depot chunk staging in `<installDir>/.DepotDownloader/`; resume passes `verify = true` (full re-verification) and continues at chunk boundaries |
| Safe to move files to PC | Per-chunk checksum verification; files that have finished installing are listed in RECENTLY INSTALLED — those are whole. At COMPLETED the whole folder is safe to move |
| Estimated time, internet speed, storage write speed | Chunk telemetry: compressed bytes/s = network, uncompressed bytes/s = effective write rate → hero stats card + foreground notification |

## The download pipeline

```
VALIDATING_LICENSE → ALLOCATING → DOWNLOADING → VERIFYING → COMPLETED
                                       ↘ PAUSED  (resume any time, even after app restart)
```

* Content arrives as ~1 MiB depot chunks over Valve's CDN; each chunk is
  decompressed (gzip/VZip/**zstd**/LZMA — hence the `zstd-jni` and `xz`
  dependencies) and checksum-verified **before** it reaches disk.
* The `.DepotDownloader` staging ledger per depot records completed chunks —
  pause, process death or a reboot resume from exactly those boundaries, and
  resumed sessions re-verify existing content (`verify = true`) instead of
  trusting it.
* Depot ownership is enforced twice: an up-front license report (web
  validation of the base app + each DLC) and the engine's CM-side
  `accountHasAccess` check with auto-licensing for free-to-play.
* Install destination is a **real filesystem path** —
  `Android/data/<package>/files/SteamLibrary/steamapps/common/<Title>` —
  visible to your PC over USB. (SAF document-tree destinations were removed:
  they are not real paths and silently break native file writers.)
* The phone asks for the **Windows x64** depot selection, because the files
  are meant to be moved to a PC — identical to what GameNative does.

## Dependencies (native Steam)

| Artifact | Purpose |
| --- | --- |
| `in.dragonbra:javasteam` | Steam CM protocol, auth sessions, PICS, licenses (JVM port of SteamKit2) |
| `in.dragonbra:javasteam-depotdownloader` | Manifest/chunk download engine |
| `com.github.luben:zstd-jni` (AAR) | zstd-compressed CDN chunks |
| `org.tukaani:xz` | LZMA-compressed CDN chunks |
| `org.jetbrains.kotlinx:kotlinx-coroutines-jdk8` | `CompletableFuture.await()` glue |

## Security notes

* The password exists only inside the JavaSteam sign-in call (RSA-encrypted in
  transit by Steam's key exchange) and is never stored.
* The persisted refresh token is AES-256/GCM-encrypted with a hardware-backed
  AndroidKeyStore key ("remember me").
* Not affiliated with Valve Corporation.
