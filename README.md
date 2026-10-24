# Orbit

A Stremio-addon media player for Android TV and Google TV, with a TV-first interface.

- Catalogs, search and streams from your Stremio addons (sign in to sync your account's addons)
- My List and watch progress synced with your Stremio library
- TMDB details: logos, cast, trailers, "More like this"
- Subtitles from the video or subtitle addons, with delay and forced-subtitle support
- Skip intro, next episode, match frame rate
- Profiles, with Wi-Fi transfer between devices
- Nuvio collections

## Install

Download the latest APK from [Releases](../../releases) — or on the TV, use the
Downloader app with the code listed on the release.

## Build

1. Put a TMDB API key in `tmdb.properties` at the project root: `TMDB_API_KEY=...`
2. `gradle assembleDebug` (installs as "Orbit (test)", alongside a release install)
3. Release builds are signed with a key read from `~/orbit-keys/keystore.properties`.

## Licence

GPL-3.0. Parts of Orbit are based on [NuvioTV](https://github.com/NuvioMedia/NuvioTV) (GPL-3.0).
The Inter font is under the SIL Open Font License (`app/src/main/assets/licenses/Inter-OFL.txt`).
