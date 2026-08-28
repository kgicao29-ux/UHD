# UHDMovies — CloudStream extension

CloudStream provider for **UHDMovies** ([uhdmovies.autos](https://uhdmovies.autos)) — 4K/2160p/1080p
dual-audio (Hindi/English) movies & web series, Google-Drive backed.

Independent Kotlin implementation; the download chain was reverse-engineered from the live
site (see `ANALYSIS.md`). phisher98's `UHDmoviesProvider` (in their extension repo) was used
only as a sanity reference for the site's flow.

## Features

- **Main page** — 8 sections: Latest, Movies, Web Series, TV Series, Hollywood,
  4K HDR, 2160p HEVC, IMAX (WordPress `/page/N/` pagination, 18 cards/page).
- **Search** — native WP search (`/?s=…`).
- **Detail** — poster, year, category tags; movies get every G-Drive button;
  series group buttons into `SxxEyy` episodes (quality strings kept as link labels).
- **Links** — full download chain ported 1:1 from phisher98's working
  UHDmoviesProvider v38 (decompiled reference), each step re-verified live:
  1. `cloud.unblockedgames.wtf/?sid=…` landing: `GET → POST all landing inputs →
     POST second landing inputs` → script `?go=<token>` → `GET /?go=<token>` with
     cookie `{token: _wp_http2}` → meta-refresh → `driveseed.org/r?…` →
     `window.location.replace("/file/KEY")`
  2. file page buttons:
     - **Instant** (`a.btn-danger` → `cdn.video-gen.xyz` → `video-seed.dev/?url=…`):
       POST `https://video-seed.xyz/api` `{keys: …}` + `x-token` header → `{url}` =
       fresh **direct Google-Drive link** (exactly phisher's flow); if that API is
       fingerprint-walled, falls back to the URL-decoded `?url=` GDrive direct file
       (verified: `200 video/mkv`)
     - **Resume Cloud** (`a.btn-warning` → `/zfile/KEY`): POST `action=cloud` (key
       regex from page) → `{url: /zfile?token=…}` → `a.btn-success` / direct
       `*.workers.dev` file (verified: `206 video/x-matroska`, range-supported)
- Every link is a direct VIDEO link → playable and **downloadable** in CloudStream.

## Notes

- Google Drive links are minted per resolution and can hit Google's download quota for
  very popular files — retry later or pick another quality/episode link in that case.
- Files are large (Remuxes up to ~80 GB); check the size shown in the link name.

## Install

Install `release/UHDMovies.cs3` directly, or add the repository (after pushing to your
fork's `builds` branch — update `repo.json` to your username):

```
https://raw.githubusercontent.com/kgicao29-ux/UHDMovies/master/repo.json
```

## Build

JDK 17 + Android SDK 35:

```
./gradlew make            # -> UHDMovies/build/UHDMovies.cs3
./gradlew makePluginsJson # -> build/plugins.json
```
