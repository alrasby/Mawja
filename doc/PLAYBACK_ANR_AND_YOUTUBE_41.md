# Playback audit — build 41

## Evidence
The supplied 12.29-second recording (145716.mp4) shows rapid battery-saver/equalizer changes and volume dragging. Separate photos show a system ANR dialog and Android Auto Source error. No device ANR trace or failing YouTube video ID was supplied; the exact original ANR cannot be proven from screenshots.

## Changes
- Equalizer construction, band reads/writes, enable and release were synchronous native/binder calls on Main. All now execute on a dedicated serial AudioEffects worker, with conflated session/preset updates and asynchronous teardown. ExoPlayer operations remain on its application thread. The worker asserts its thread identity.
- YouTube phone playback uses YouTubeFallbackActivity's official iframe, while Auto uses extracted audio URLs through ExoPlayer. Therefore success on the phone doesn't validate Auto's direct URL.
- Resolver candidates were accepted without checking whether their bytes were actually playable/accessible. A small range GET now validates status, media content type and a nonempty body using playback headers; rejected candidates fall through to another resolver/client. This doesn't bypass YouTube restrictions or guarantee availability.
- On an expired/forbidden YouTube HTTP response, the service invalidates the URL cache and retries preparation once per item; it never loops indefinitely.
- Playback settings separate the volume title and percentage. A share-diagnostics button collects bounded breadcrumbs and available Android historical ANR trace on IO. The user chooses whether/where to share; no automatic upload and no stream URLs in breadcrumbs.

## Verification
Unit tests cover HTTP 403 rejection, media-range/headers and HTML rejection. A new on-device instrumentation test generates a local WAV and exercises actual PlaybackService/MediaController playback readiness, duration, seek, pause/resume, rapid equalizer/saver/volume changes and error state. Existing UI navigation and runtime profiling remain enabled.

CI pending on the code commit. This is an emulator/service regression test, not proof of live YouTube availability, physical battery use or GMC compatibility. Remaining device validation: repeat the recorded settings actions during local playback, test the failing YouTube item in Auto, and if there is another freeze/error use Settings → Playback → Share playback diagnostics after reopening the app. Android may not retain a historical trace on every device.
