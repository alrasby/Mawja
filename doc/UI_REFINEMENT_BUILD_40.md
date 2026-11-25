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
Pending CI on the implementation commit. No claim of visual approval or real-car playback. Podcast playback needs an actual subscription/network and is outside the empty-library navigation smoke scenario.

## Resume
Preserve the theme/glass identity and floating footer. Next step: inspect CI unit/build and emulator smoke results; repair any failure before delivering. Persistent per-episode resume and podcast directory discovery remain separate features.
