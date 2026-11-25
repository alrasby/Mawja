# Android Auto radio taxonomy parity — build 43

## Root cause
Phone RadioScreen iterates `RadioFilters.all` (Local, World, News, Music, Sports, Talk, Culture, Quran). PlaybackService's independent hard-coded radio menu still returned only Local/World/News. This is a missing service update, not stale Android Auto cache. The supplied 146188.mp4 shows the current phone categories; the previous car recording shows the three-item service menu.

## Correction
- Car radio menu now derives directly from the same `RadioFilters.all` taxonomy as the phone. Additions there automatically become browsable car categories.
- Keep existing `radio:local`, `radio:world`, `radio:news` IDs stable; add matching IDs/routes and item lookup for all other categories.
- New category station requests use the same RadioRepository filters/tags and per-category phone country/language preferences. Share the news language default helper with RadioVm; choosing All languages (empty preference) remains honored.
- World retains its existing countries → stations hierarchy; country selection there overrides the phone country while keeping the chosen world language. Local retains its automatic location/region implementation; no manual local country picker is added.
- This does not introduce a separate country/language picker UI in the car for every category: the detailed category filters come from phone settings, while World still supports country browsing directly.

## Verification and resume
Pending CI. JVM tests cover taxonomy/routes, legacy IDs, unknown IDs, country/language preferences and Local ignoring manual choices. New device instrumentation connects a real MediaBrowser to PlaybackService, browses `radio`, checks all eight folder IDs/titles/flags, and resolves every item via `getItem`. Existing playback, light/dark and UI regression checks remain.

No claim that this tests a GMC head unit or validates every live radio URL. Next: inspect CI results and deliver the exact successful APK; real car should list all eight categories (the host may require scrolling).
