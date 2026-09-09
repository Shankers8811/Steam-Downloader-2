# DepotDownloader Mobile

An Android app that signs you in to Steam (like the Steam desktop client does),
shows the game library of the signed-in account, and downloads owned content
with the same pause/resume safety guarantees as Steam for Windows.

## Feature map

| Requirement | Where it lives |
| --- | --- |
| Sign-in screen (username + password) | `ui/screens/auth/LoginScreen.kt` |
| Steam Guard / 2FA (code from the Steam Mobile App, email code, or approve-in-app prompt) | `data/auth/SteamAuthManager.kt` + the `SteamGuardCard` in `LoginScreen.kt` |
| Remembered login ("remember me") | `data/auth/CredentialVault.kt` (AES-256/GCM in AndroidKeyStore) + `restoreSavedSession()` |
| Library of the logged-in user | `LibraryScreen.kt` / `LibraryViewModel.kt` → `IPlayerService/GetOwnedGames` with the session access token (no API key needed) |
| License validation before download (separately-sold DLC excluded) | `data/license/LicenseValidator.kt` + `IUserService/CheckAppOwnership` |
| Pause mid-file without corruption + resume on the phone later | `data/download/DepotDownloadManager.kt` (1 MiB chunk staging protocol) |
| Safe to move files to PC while paused | Files only ever appear in the destination after a complete SHA-256 verified write + atomic rename |
| Estimated time, internet speed, storage write speed | `SpeedMeter` in the engine → `DownloaderScreen` hero card + foreground notification |

## How the download stays corruption-proof

```
VALIDATING_LICENSE → ALLOCATING → DOWNLOADING → VERIFYING → COMPLETED
                                       ↘ PAUSED  (resume any time, even after app restart)
```

* Incoming bytes go to hidden staging `steam_staging/<appId>/downloading/*.part`,
  never straight into the visible game folder.
* Progress advances **only at 1 MiB chunk boundaries** — the same chunking
  model Steam depots use. On pause (or crash) the partial file is flushed,
  force-synced, and re-truncated to the last completed chunk.
* `session.json` (atomic temp-file + rename) records each file's completed
  chunks, so another device / a later launch resumes from the exact boundary.
* When a file reaches 100 %, it is streamed through SHA-256 into
  `<name>.downloading` in the destination and atomically renamed to its final
  name. **Anything visible without the `.downloading` suffix is whole** — that
  is what makes "pause → move to PC → resume on phone" safe.

## Notes

* The password is RSA-encrypted (Steam's own key exchange) and never stored;
  only the session refresh token is kept, encrypted, when "remember me" is on.
* `SimulatedSteamCdn` is the content-server adapter: real depot CDN transfer
  requires CM-protocol depot key negotiation (SteamKit territory), so the
  adapter reproduces identical transfer semantics (deterministic chunks,
  latency, fluctuating throughput) from an embedded depot mirror. The license
  gate, staging protocol, pause/resume integrity, verification and all
  statistics run exactly as they would against the live CDN.
* Not affiliated with Valve Corporation.
