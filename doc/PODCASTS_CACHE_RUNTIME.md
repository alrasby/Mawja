# RSS podcasts, discovery and runtime validation

- Discover groups RSS subscriptions, global radio (country/language/categories), and existing Audius/YouTube discovery. It reuses the selected region/language and existing themed surfaces and footer insets.
- RSS 2.0 and Atom audio enclosures are playable through the shared MediaSession, also exposed in the Android Auto podcast folder. HTTPS subscriptions are stored privately and persist. Removal doesn't remove favorites/history. The user provides official RSS URLs; no unlicensed directory is bundled.
- Limits: 50 subscriptions, 200 episodes/feed, 1500 displayed episodes, 2 MiB/feed, nesting 64, text node 256 KiB. DTD/entity declarations rejected and external entities resolved to empty input. Podcast metadata never executes HTML. No scheduled polling or automatic media downloads.
- Metadata cache: one-hour freshness, stale fallback per feed, serialized mutation, atomic writes, SHA-256 keys and 16 MiB/50 file bounds. Images: existing 10% memory allowance, disk capped at 64 MiB rather than a percentage of the phone's disk. Radio metadata now uses atomic SHA-256 keyed files, a 16 MiB/64-file cap and seven-day fallback expiry; old unbounded radio files are removed after a successful cache write. YouTube/Audius in-memory discovery caches retain their existing limits. Podcast audio and images require HTTPS; unsupported cleartext episodes are omitted. Android Auto restores subscribed episodes from disk without waiting for feed network requests.
- CI unit tests exercise RSS/Atom parsing, duplicate episodes, duration, unsafe enclosure schemes, entity declarations and excessive nesting.
- `scripts/profile-runtime.sh` records startup, PSS/heap snapshots before/after eight background/resume cycles, background memory, deterministic 150-event smoke test, crash buffer, and emulator batterystats. CI publishes raw measurements in `Mawja-runtime-profile-emulator`.
- Emulator batterystats are diagnostic only, **not physical battery consumption**. This short smoke test doesn't prove absence of leaks or long-term stability. Playback endurance (local/RSS/radio), screen-off wake locks, audio focus, reconnects, real Android Auto and physical battery measurements still require a phone/car. Measured emulator memory is recorded below; no physical battery consumption is claimed.

## Resume
Implementation in versionCode 38. Check the unit-test/build and runtime-profile jobs on the exact commit. Remaining product work: podcast directory/search provider, playback progress per episode, offline episode downloads and physical endurance profiling.

## Verified run — 2026-11-25

Code commit: `2080de078c07db99be14ace54dd3eb232fce3514`, versionCode 38.
[CI run](https://github.com/alrasby/Mawja/actions/runs/45571571754): unit tests + Debug APK and API 35 x86_64 runtime profile succeeded.

| Snapshot | Total PSS (KiB) | MiB |
| --- | ---: | ---: |
| 10 seconds after launch | 143504 | 140.1 |
| After eight background/resume cycles | 128127 | 125.1 |
| Background after 150-event smoke test | 133391 | 130.3 |

Startup `am start -W` TotalTime: 2685 ms. Crash buffer empty; Monkey completed 150 injected events. Cache usage at the end: 132 KiB. These are **Debug emulator** results, with no RSS subscriptions installed. Monkey reported no connected network, so this run does **not** validate live catalog/feed fetching or audio playback. Memory didn't rise across the resume snapshots, but three snapshots and a short run cannot rule out leaks. Emulator energy counters report unsupported values; no physical battery percentage/hour is available.

[Raw runtime report](https://github.com/alrasby/Mawja/actions/runs/45571571754/artifacts/15003125402) · [Debug APK artifact](https://github.com/alrasby/Mawja/actions/runs/45571571754/artifacts/15003180239).

Next verification: on-phone RSS stream + screen-off endurance, switching local/radio/RSS playback, connection recovery, and actual Android Auto. No ADB is required from the user for normal app operation. A podcast directory and offline audio downloads are not part of this implementation.
