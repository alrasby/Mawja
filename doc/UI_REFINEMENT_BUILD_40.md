# UI refinement — build 40

## Implemented
- Root owns Home/Radio/Device/Library, discovery, search and settings destinations. Discovery links to original Radio and Home destinations; it no longer embeds copies of those screens.
- Home hero opens Discover without a separate extra search-like navigation strip.
- Saved destination state restores selected tab, settings page, RSS draft, subscription selection and supported lazy-list/scroll state across navigation/configuration changes. Footer geometry, translucency and measured scroll clearance are unchanged.
- Settings split into Appearance, Device, Online sources and Playback, retaining existing controls and instant preference updates. YouTube region/language/interest settings now belong to Online sources.
- RSS subscription form opens from Add; subscriptions appear as compact filters. Episode rows display artwork, duration and the active episode's live playback position/progress, with play/pause. This is not persistent progress storage for every episode.
- Home distinguishes loading, errors and empty content using existing localized shared state components; RSS errors preserve visible cached episodes.
- YouTube stream URLs and request user-agent mappings use synchronized bounded TTL/LRU caches (64 URLs / 128 headers) with monotonic time. Reads/writes prune expiry without background timers.
- Regression tests cover cache capacity, access-order eviction, expiry boundary, replacement and duration labels.
- API 35 emulator smoke script exercises all settings pages, back navigation, settings restoration after rotation, discovery and RSS add dialog. Screenshots and results are uploaded in the runtime-profile artifact.

## Verification
Verified code commit `9305da10c624cf277c2c26096c9a969621a101ae`: [CI run 45622604721](https://github.com/alrasby/Mawja/actions/runs/45622604721) passed unit tests, Debug build and API 35 emulator UI smoke + runtime profiling. The smoke script reached completion after checking all four settings pages, back navigation, settings restoration after rotation, discovery and RSS dialog. Screenshots and raw output are in [the runtime artifact](https://github.com/alrasby/Mawja/actions/runs/45622604721/artifacts/15027400661). There was no manual visual review, real-car test or subscribed podcast playback in this run.

## Resume
Preserve the theme/glass identity and floating footer. Build and emulator UI smoke succeeded. Next step: user verification in light/dark themes, Arabic RTL, with an actual RSS subscription and Android Auto. Persistent per-episode resume and podcast directory discovery remain separate features.
