# DebridTV

A native Android TV streaming app that works like a Stremio + AllDebrid + Torrentio
setup, but as a single self-contained APK with a built-in player.

- **Metadata / search:** Cinemeta (the same keyless catalog Stremio uses — indexed by IMDb id)
- **Sources:** pluggable scrapers — Torrentio + a custom module (YTS, EZTV, TPB) → raw infoHashes
- **Resolving:** your AllDebrid account (magnet → cache → direct stream link)
- **Player:** Media3 / ExoPlayer, built in (plays MKV/MP4/etc.)
- **Audio:** 4K video + surround/Atmos **passthrough** to a receiver/soundbar
  (toggle in Settings)
- **Subtitles:** OpenSubtitles (keyless v3 addon), picked from the player's
  subtitle button
- **Features:** search & play, browse what's already on AllDebrid, auto-queue the top
  source while you browse, resume/continue-watching.

Your AllDebrid API key is entered on the TV (Settings screen) and stored only on the
device. It is never hardcoded and never logged.

---

## How to get an installable APK (no tools needed on your PC)

The project builds itself in the cloud via GitHub Actions and hands you a
sideloadable `DebridTV-debug.apk`.

1. Create a free GitHub account and a new **private** repository (e.g. `debridtv`).
2. Upload this whole `DebridTV` folder to the repo:
   - On the repo page click **Add file → Upload files**, then drag the entire
     `DebridTV` folder in. GitHub preserves the folder structure. Commit.
   - (Or, if you install Git / GitHub Desktop, push the folder normally.)
3. The **Build APK** workflow runs automatically on push. Watch it under the
   repo's **Actions** tab (~3–5 min).
4. When it's green, open the run and download the **DebridTV-debug-apk** artifact
   (a zip containing `DebridTV-debug.apk`). Manual runs and tags also attach the
   APK to a GitHub **Release**.

## Install on an Android TV

1. On the TV: **Settings → System → About →** click **Build** 7× to enable
   Developer options, then enable **Apps from unknown sources** for your file app.
2. Get the APK onto the TV — easiest options:
   - **Downloader app** (from the Play Store): paste the APK's direct download URL.
   - Or `adb install DebridTV-debug.apk` if you have adb on any computer on the
     same network (`adb connect <tv-ip>`).
3. Launch **DebridTV** from the home screen, open **Settings**, paste your
   AllDebrid API key, and hit **Save & verify**.

Get your key at **alldebrid.com → My Account → API keys**.

---

## Using it

- **Search** — type a title, pick it, choose a source. It's added to AllDebrid,
  cached if needed (progress shown), unlocked, and played.
- **Library** — everything already on your AllDebrid account; ready items play
  in one click.
- **Auto-queue** — opening a title starts caching the top source in the
  background; each source row also has a **Queue** button to pre-cache manually.
- **Continue Watching** — resume position is remembered per title/episode.

---

## Sources / scrapers

Sources come from a list of pluggable `SourceProvider`s
(`app/.../data/scraper/`), all resolved through AllDebrid:

| Provider  | Covers        | Query by        |
|-----------|---------------|-----------------|
| Torrentio | movies + TV   | IMDb id         |
| YTS       | movies        | IMDb id         |
| EZTV      | TV episodes   | IMDb id         |
| TPB       | anything      | title / SxxExx  |

They run **in parallel**; results are merged and de-duped by infoHash, and one
failing indexer never breaks the others. To add another indexer, implement
`SourceProvider` and register it in the provider list in
`di/ServiceLocator.kt` — nothing else changes.

> The EZTV public API host moves occasionally; if TV sources dry up, update the
> base URL in `di/ServiceLocator.kt`.

## Roadmap / next steps

- Per-provider on/off toggles in Settings.
- Trakt sync for watch state.
- Preferred-subtitle-language auto-select.
- Signed **release** APK (currently ships a debug-signed APK, which sideloads
  fine but should be replaced with your own signing key for distribution).

## Project layout

```
app/src/main/java/io/debridtv/app/
  data/        Cinemeta, Torrentio, AllDebrid clients + local storage
  domain/      StreamResolver (magnet→playable URL), MediaRepository, models
  di/          ServiceLocator (manual DI)
  ui/          Compose TV screens + Media3 PlayerActivity
```
