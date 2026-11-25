# RSS podcasts, discovery and runtime validation

- Discover groups RSS subscriptions, global radio (country/language/categories), and existing Audius/YouTube discovery. It reuses the selected region/language and existing themed surfaces and footer insets.
- RSS 2.0 and Atom audio enclosures are playable through the shared MediaSession, also exposed in the Android Auto podcast folder. HTTPS subscriptions are stored privately and persist. Removal doesn't remove favorites/history. The user provides official RSS URLs; no unlicensed directory is bundled.
- Limits: 50 subscriptions, 200 episodes/feed, 1500 displayed episodes, 2 MiB/feed, nesting 64, text node 256 KiB. DTD/entity declarations rejected and external entities resolved to empty input. Podcast metadata never executes HTML. No scheduled polling or automatic media downloads.
- Metadata cache: one-hour freshness, stale fallback per feed, serialized mutation, atomic writes, SHA-256 keys and 16 MiB/50 file bounds. Images: existing 10% memory allowance, disk capped at 64 MiB rather than a percentage of the phone's disk. These limits exclude pre-existing radio caches and YouTube/Audius caches; no claim that all caches have been replaced.
- CI unit tests exercise RSS/Atom parsing, duplicate episodes, duration, unsafe enclosure schemes, entity declarations and excessive nesting.
- `scripts/profile-runtime.sh` records startup, PSS/heap snapshots before/after eight background/resume cycles, background memory, deterministic 150-event smoke test, crash buffer, and emulator batterystats. CI publishes raw measurements in `Mawja-runtime-profile-emulator`.
- Emulator batterystats are diagnostic only, **not physical battery consumption**. This short smoke test doesn't prove absence of leaks or long-term stability. Playback endurance (local/RSS/radio), screen-off wake locks, audio focus, reconnects, real Android Auto and physical battery measurements still require a phone/car. No numeric memory/battery results are claimed until the profiling job succeeds.

## Resume
Implementation in versionCode 38. Check the unit-test/build and runtime-profile jobs on the exact commit. Remaining product work: podcast directory/search provider, playback progress per episode, offline episode downloads and physical endurance profiling.
