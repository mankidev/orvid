# Performance

One column per release. The first table has the measurements that are taken for every release, the second keeps one-off measurements from earlier releases. A cell is empty when that release was not measured.

## Every release

The query-word row ranks the same saved search results with the code of each release tag, so it covers every version. The three time rows are measured on a phone over Tor with the app itself, from v0.3.9 on.

| | v0.1.0 | v0.2.1 | v0.3.0 | v0.3.1 | v0.3.2 | v0.3.3 | v0.3.4 | v0.3.5 | v0.3.6 | v0.3.7 | v0.3.8 | v0.3.9 | v0.4.0 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Sources | 56 built in | 68 built in | 70 from repositories | 96 from repositories | 103 from repositories | 103 from repositories | 103 from repositories | 103 from repositories | 103 from repositories | 103 from repositories | 150 from repositories | 168 from repositories | 199 from repositories |
| Results that sit above one matching more query words (8 queries, top 30 pairs) | 26 | 26 | 88 | 88 | 88 | 90 | 90 | 90 | 103 | 103 | 103 | 0 | 0 |
| Enter to result list over Tor, median of 5 queries | - | - | - | - | - | - | - | - | - | - | - | - | - |
| Tap to first frame over Tor, median of 10 videos | - | - | - | - | - | - | - | - | - | - | - | - | - |
| Cold start to first search results over Tor, median of 3 | - | - | - | - | - | - | - | - | - | - | - | - | - |

## Other measurements

Search quality here comes from `test/RankBench.java`, which ranks the same saved results for fixed queries. A result is junk when the query is not in its title or channel, when it is in another script, or when it has under 1,000 views. Times are measured on a phone over Tor.

| | v0.1.0 | v0.2.1 | v0.3.0 | v0.3.1 | v0.3.2 | v0.3.3 | v0.3.4 | v0.3.5 | v0.3.6 | v0.3.7 | v0.3.8 | v0.3.9 | v0.4.0 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Junk in top 10 (10 queries) | 37 | 37 | 8 | 8 | 8 | 8 | 8 | 8 | 8 | 8 | - | - | - |
| First YouTube result, average position | 1.5 | 1.5 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | 1.2 | - | - | - |
| Tap to first frame, YouTube | 5.9 s | 5.9 s | 0.3 s (centre video), 2.6 s (others) | - | - | - | - | - | - | - | - | - | - |
| Requests when a video opens | ~11 + 12-20 thumbnails | ~11 + 12-20 thumbnails | 3 + 6 thumbnails | - | - | - | - | - | - | - | - | - | - |
| First 8 YouTube thumbnails, download time | - | - | - | - | - | 5.1 s | 2.4 s | - | - | - | - | - | - |
| Opening a video from a repository source, median / slowest 10% | - | - | - | - | - | 2.38 s / 6.13 s | 2.07 s / 4.55 s | - | - | - | - | - | - |
| Vertical YouTube video that has 1080p, quality picked | - | - | - | - | - | 480p-608p | 1080p | - | - | - | - | - | - |
| Error for an Instagram video that needs a login | - | - | - | - | - | 3-20 s | 1-3 s | - | - | - | - | - | - |
| Script sources, CPU time on the first search (22 scripts) | - | - | - | - | - | 1005 ms | 38 ms | - | - | - | - | - | - |
| Right YouTube search sent before typing ends (6 queries) | - | - | - | - | - | none | 5 of 6 | - | - | - | - | - | - |
| YouTube search 1 s after Tor is ready | - | - | - | - | - | - | 14.7-17.9 s | 3.4-4.0 s | - | - | - | - | - |
| YouTube search answer after decompression | - | - | - | - | - | - | - | 164-474 KB | 26-41 KB | 26-41 KB | - | - | - |
| YouTube channel page, first 30 videos (3 channels) | - | - | - | - | - | - | - | - | 0.5-1.4 s | 0.5-1.4 s | - | - | - |
| YouTube player answer after decompression (5 videos) | - | - | - | - | - | - | - | - | 282 KB | 141 KB | - | - | - |
| 2 MB of a YouTube video over Tor, in the player's request pattern | - | - | - | - | - | - | - | - | 61-152 s | 10-41 s | - | - | - |
| First byte from a repository source's media server, link already known (67 videos) | - | - | - | - | - | - | - | - | - | 882 ms | 433 ms | - | - |
| Odysee search results that open (3 tried) | - | - | - | - | - | - | - | - | - | 1 of 3 | 3 of 3 | - | - |
| Opening an Odysee video, median | - | - | - | - | - | - | - | - | - | 34.9 s | 3.9 s | - | - |
| Odysee search | - | - | - | - | - | - | - | - | - | 1.7 s | 4.3-4.8 s | - | - |
| Junk in top 10 (22 queries) | - | - | - | - | - | - | - | - | - | - | 13 | 3-4 | - |
| First YouTube result, average position (22 queries) | - | - | - | - | - | - | - | - | - | - | 1.4 | 1.0-1.1 | - |
| Same query ranked twice, top 10 results in the same place (22 queries) | - | - | - | - | - | - | - | - | - | - | 10 of 10 | 0-5 of 10 | - |
| APK size | 12.1 MB | 29.6 MB | 29.7 MB | 28.3 MB | 28.3 MB | 28.1 MB | 28.1 MB | 26.9 MB | 26.9 MB | 26.9 MB | 26.9 MB | 27.0 MB | - |

## Reproduce

The query-word row comes from `test/WordOrder.java`, which counts the query words itself so every release is measured the same way. For each tag, extract `src`, `libs`, `res` and `AndroidManifest.xml`, generate `R.java` with `aapt2` (needed from v0.3.4), then:

```sh
CP="$(ls libs/*.jar | tr '\n' ':')tools/android33.jar"
javac -proc:none -sourcepath src:gen -cp "$CP" -d out test/WordOrder.java
java -cp "out:$CP" WordOrder
```

The time rows follow one protocol: Tor on, 5 fixed queries for the result list, 10 videos from different sources for the first frame, and 3 cold starts with a query typed right away. Times come from the app's own debug log.

The other measurements:

```sh
CP="build/classes:$(ls libs/*.jar | tr '\n' ':')tools/android33.jar"
javac -cp "$CP" -d /tmp/rb test/RankBench.java
java -cp "/tmp/rb:$CP" RankBench
```

The saved results come from `test/MixProbe.java`. The rows added in v0.3.4 come from these probes, run the same way: `test/ThumbAB.java` (thumbnails, 3 runs of 8), `test/HedgeAB.java` (24 videos, each opened both ways in alternating order), `test/VqProbe.java` (quality), `test/InstaProbe.java` (Instagram), `test/RhinoCost.java` (scripts) and `test/TypeSim.java` (typing at 180 ms per letter). `test/FewProbe.java` saves the search results the first three read. The Tor start row in v0.3.5 comes from `test/StormProbe.java`, which warms the same number of circuits as the app at start (48 in v0.3.4, 12 in v0.3.5) and then runs one YouTube search.

The rows added in v0.3.9 come from `test/RankBench.java` and `test/VarProbe.java` over the same saved results. Their v0.3.8 column uses the v0.3.8 `Ranker`, `Filters` and `Source` compiled over the v0.3.9 classes. The v0.3.9 ranking adds a small random factor per search, so these rows give the range of 6 runs.

The rows added in v0.3.6 come from `test/MaskCheck.java` (the same YouTube requests with and without a field mask, compared field by field) and `test/ChannelProbe.java` (the first page and one more page of three channels).

The rows added in v0.3.7 come from `test/PlayerMaskCheck.java` (player requests with and without a field mask for 5 videos, compared field by field) and `test/SegProbe.java` (2 MB of a 360p stream as 20 requests of 100 KB, the way the player asked for it until v0.3.6, and as one request, the way it reads a stream from v0.3.7).

The rows added in v0.3.8 come from `test/MediaWarmProbe.java` (11 sources, 6 videos each, opened in turn with and without a 1-byte request to the media server 1.5 s earlier, the way the app now warms visible videos) and `test/NightProbe.java` (search, then the first 3 videos of every source, each resolved and read for its first 512 KB). Set `ROOT` to a folder with the source repositories and `NET=direct` to run without Tor.
