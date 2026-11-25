# Android Auto: YouTube video entries as audio

## Root cause and correction (build 39)
`playableItem()` only converted local videos to audio. YouTube results with `isVideo=true` retained VIDEO metadata and `isPlayable=false`; `onAddMediaItems()` rejected them. This could produce an empty/unsupported item in the car before stream resolution even ran.

A pure `Track.forCarAudio()` conversion now clears the YouTube video flag while preserving the `yt:` ID, title, artwork and duration. The playback URI consequently omits `mawja_video=1`, selecting the existing audio resolver. Local videos keep their filtering prefix. The external-controller admission path also applies this normalization, and the media source filters car YouTube music items to audio tracks. Phone playback and original saved video metadata are unchanged.

Regression tests cover the resulting YouTube audio URI, unchanged original, idempotence, local content URIs and unaffected audio sources. CI unit tests and Debug build must pass on this code. Actual GMC head-unit playback and live YouTube extraction require device verification; this fixes the demonstrated metadata/admission defect, not all possible network/extractor failures.

## UI refinement review
Based on the current Compose implementation and previously supplied screenshots (not a fresh visual device inspection):

- Preserve the established theme/glass identity and floating footer.
- Unify Home and Discover navigation: Discover currently embeds Home and Radio, repeating headers/source selectors; a shared section-based discovery surface would reduce nesting.
- Podcast episode cards currently use full artwork cards in a vertical list. A compact episode row with duration, playback state and progress would improve browsing of long feeds.
- Move the persistent RSS entry form behind an Add action and give subscriptions a compact, clearly grouped presentation.
- Settings have appearance, folders, online sources and playback grouped already; searchable subpages would reduce the length as features grow.
- Root screen/tab flags use `remember`, so position/selection restoration across activity recreation needs a dedicated pass with `rememberSaveable` / navigation state rather than an ad hoc replacement of individual flags.
- Keep error/loading/empty states distinguishable and localized; avoid showing all failures as an empty list.

These UI items are a proposed refinement scope, not implemented redesigns in this bug fix. Next confirmed step: test build 39 in Android Auto using a YouTube video entry and a saved favorite, then test a local video and radio for regression.
