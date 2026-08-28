# uhdmovies.autos — site analysis (2026-08-28)

## Stack

WordPress + gridlove theme. Fully open (no Cloudflare challenge observed from any network
tested, including datacenter IPs). Posts live at `/download-{slug}/`.

| Endpoint | Purpose |
|---|---|
| `/{section}/page/N/` | lists: `movies/`, `web-series/`, `tv-series/`, `movies/collection-movies/`, `4k-hdr/`, `2160p-hevc/`, `imax/` — 18 `article.gridlove-post` cards |
| `/?s={query}` | WP search, same card markup |
| post page | `h1.entry-title`, categories in `.entry-category a`, poster = first `.entry-content img` |

Buttons: `a[class*=maxbutton]` → `cloud.unblockedgames.world/?sid=<base64>`.
Movies use `maxbutton-download-g-drive`, episodes `maxbutton-gdrive-episode`.
Series episode labels sit in the preceding `<p><strong>… S01E01 … [6.08 GB/E]</strong>`.

⚠️ sid discriminator: description paragraphs also contain cloud links wrapped around
quality words ("4k", "2160p HEVC"…) that resolve to uhdmovies.mov **category pages**
(navigation, not downloads). Real download sids sit on `maxbutton` anchors — the provider
only collects those.

## Download chain (reverse-engineered + verified live)

```
GET  cloud.unblockedgames.world/?sid=<sid>
     → <form id="landing" action=…><input name="_wp_http" value=<sid>>
POST action  { _wp_http: sid }
     → blog-looking page with ANOTHER hidden form#landing (action = fake blog post URL)
       + input name="_wp_http2" (zlib(base64(sid)))
POST action2 { _wp_http2: … }
     → page whose inline JS calls s_NNN('<cookieName=pepe-hash>', '<cookieValue>', 60)
GET  cloud.unblockedgames.world/?go=<cookieName>   Cookie: <cookieName>=<cookieValue>
     → "Redirecting …" page with <meta http-equiv=refresh content="0;url=DRIVESEED">
DRIVESEED = https://driveseed.org/r?key=<b64>&id=<b64>
GET  /r?…
     → <script>window.location.replace("/file/<KEY>")</script>
GET  /file/<KEY>
     → title = full file name; li "Size : 72.89GB";
       a.btn-danger  "Instant Download"  → https://cdn.video-gen.xyz/<hex>::<sig>
                                            302 → https://video-seed.dev/?url=<enc GDrive>
                                            ?url= decodes to video-downloads.googleusercontent.com/…
                                            (direct file: Content-Type video/mkv, 78 GB)
       a.btn-warning "Resume Cloud"      → /zfile/<KEY> page → https://<sub>.workers.dev/<hex>::<sig>/<name>.mkv
       (POST /file|/mfile with action=original|cloud|instant + x-token header exists too,
        but "original" requires login and "cloud" rejects headless tokens — the plain
        href buttons are the working path.)
```

## Verification log (sandbox)

- all 8 sections paginate (18 cards/page) ✓
- WP search parsing ✓
- movie post → 5 G-Drive maxbuttons ✓
- full chain for a movie (Rush 2013) → driveseed file page → Instant → decoded
  googleusercontent direct link (earlier HEAD: `video/mkv`, `content-length` 78 GB) ✓
- full chain for a series episode (Lanterns S01E01, 8 GB) ✓
- build: `./gradlew make` → `UHDMovies.cs3` ✓ (JDK 17, AGP 8.7.3, cloudstream gradle 81b1d424d2)

Reference used for cross-checking the flow: phisher98/cloudstream-extensions-phisher
`UHDmoviesProvider` v38 (decompiled) — their `bypassHrefli` implements the same landing
dance; this implementation was written from scratch against the live site.
