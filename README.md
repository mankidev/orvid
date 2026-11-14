<p align="center"><img src="docs/logo.png" width="128" alt="Orvid logo"></p>

# Orvid

Orvid sends one search to 250+ video sites through Tor and plays the results in its own Android player. The app comes empty. The sites are scripts in signed repositories that you add.

<p align="center"><img src="docs/screenshots.webp" alt="Search results from many sites, the player, the source list and privacy settings"></p>

## What happens when you search

Orvid starts its own Tor client and holds your query until Tor is connected. Then it asks the sites in parallel, each on a separate Tor circuit, so each Tor exit sees the request to one site only.

The answers come back as one ranked list. While you scroll, Orvid works out which videos you are likely to open and loads their first seconds, so they start sooner. The video itself streams from the site, through Tor.

Tap "I've seen this" on a video and it stays out of later results. Orvid keeps that list encrypted on the phone, with only a hash of the link, a fingerprint of the thumbnail and the length of each video.

Thumbnails live in memory. Closing Orvid clears its cache and cookies and stops Tor. Tor's relay list stays on the phone so the next start connects faster.

## Panic exit and lock

Swipe down with two fingers to close Orvid at once and mute the video. The optional app lock takes a fingerprint or PIN and hides Orvid's screen in recent apps.

## When Tor gets in the way

If your network blocks Tor, turn on a bridge (obfs4, Snowflake or meek) under Privacy in the filter panel. The same section can turn Tor off for a faster direct connection, and a banner on the main screen then says sites can see your IP address.

DuckDuckGo and Yandex video search work only with Tor off, because both block Tor. In that mode they see your IP address and your query.

## Compared with NewPipe and Orbot

| | Orvid | NewPipe + Orbot |
|---|---|---|
| Sources | 250+ | 5 |
| Enter to result list | 1.9 s | 7.1 s |
| Enter to first 8 thumbnails | 6.9 s | 10.1 s |
| First video frame | 7.0 s | 18.0 s |

[Performance by version](docs/PERFORMANCE.md) tracks search quality and start times across releases.

## Adding sources

Search once and tap **Add repository**, or open Filters → Repositories → Add repository, and paste:

```
https://raw.githubusercontent.com/mankidev/orvid-sources/main/
```

Any site with public videos can become a source. [orvid-sources](https://github.com/mankidev/orvid-sources) describes the script format.

Adult sources come from a separate repository and stay hidden until you unlock them: spread two fingers apart on the main screen, confirm your age, then turn them on in the filter panel.

## Download

Get the APK from [Releases](https://github.com/mankidev/orvid/releases/latest), or add Orvid to Obtainium for updates:

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/mankidev/orvid"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="54"></a>

Android 8.0 or newer.

## Build

On Android with [Termux](https://termux.dev):

```sh
pkg install openjdk-21 aapt2 apksigner zip unzip
./tools/fetch.sh
./build.sh
```

The APK lands in `build/orvid.apk`, signed with a local debug key. For a release build, point `ORVID_SIGNING_ENV` at a file that sets `ORVID_KEYSTORE`, `ORVID_KEY_ALIAS` and `ORVID_KS_PASS`.

## License

GPL-3.0-or-later. Third-party components are listed in [NOTICE](NOTICE).

Orvid is not affiliated with or endorsed by The Tor Project. It is produced independently from the Tor® anonymity software and carries no guarantee from The Tor Project about quality, suitability or anything else. Tor and the Tor onion logo are trademarks of The Tor Project, Inc.
