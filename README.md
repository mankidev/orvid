<p align="center"><img src="docs/logo.png" width="128" alt="Orvid logo"></p>

# Orvid

Android video player that searches many video sites at once and sends its requests over Tor. Sources come from repositories you add.

<p align="center"><img src="docs/screenshots.webp" alt="Search results from many sites, the player, the source list and privacy settings"></p>

- **Tor built in.** Orvid starts its own Tor client and gives each source and each video its own circuit. Requests wait until Tor is connected. If your network blocks Tor, turn on a bridge (obfs4, Snowflake or meek) under Privacy in the filter panel. You can turn Tor off in the same section for a faster direct connection. While it is off, a banner on the main screen warns that sites can see your IP address.
- **Sources from repositories.** Orvid ships with no sources. Add a repository to search YouTube, Dailymotion, VK, Twitch, PeerTube, Internet Archive, Odysee, TED and more at once, merged and ranked together. Adult sources live in a separate repository and stay hidden until unlocked with a gesture and an age check (spread two fingers apart on the main screen, then turn them on in the filter panel).
- **Fast start.** Orvid resolves the videos you are likely to open and fetches their first seconds while you browse, so videos start sooner.
- **Privacy tools.** Optional app lock with fingerprint or PIN that also hides the app in recent apps, and a panic exit: swipe down with two fingers to close Orvid and wipe its memory.
- **Memory only.** Orvid does not record what you watch, and thumbnails stay in memory. Closing the app clears its cache and cookies and stops Tor. Tor's relay list is kept on the phone so the next start connects faster.

## Scope

Orvid's goal is one private search box for videos from across the web. Any site with public videos fits: Orvid searches it and plays the videos straight from the site, through Tor.

[Performance by version](docs/PERFORMANCE.md) compares search quality and start times across releases.

## Compared with NewPipe

| | Orvid | NewPipe + Orbot |
|---|---|---|
| Sources | 250+ | 5 |
| Enter to result list | 1.9 s | 7.1 s |
| Enter to first 8 thumbnails | 6.9 s | 10.1 s |
| First video frame | 7.0 s | 18.0 s |


## Getting started

Search once and tap **Add repository**, or open Filters → Repositories → Add repository, and paste:

```
https://raw.githubusercontent.com/mankidev/orvid-sources/main/
```

[orvid-sources](https://github.com/mankidev/orvid-sources) explains the source format, so you can write sources for other sites.

orvid-sources also holds DuckDuckGo and Yandex video search. Search engines block Tor, so these two work only with Tor turned off, and then they see your IP address and your search.

## Download

Get the APK from [Releases](https://github.com/mankidev/orvid/releases/latest), or add Orvid to Obtainium to get updates:

<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/mankidev/orvid"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="54"></a>

Requires Android 8.0+.

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
