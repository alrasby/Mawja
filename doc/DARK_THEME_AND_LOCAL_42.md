# Dark theme and location-only Local radio — build 42

Evidence: supplied 80-second recording 145788.mp4 and two photos show black default text/icons in dark mode, see-through radio pickers and touching card edges.

## Corrections
- MaterialTheme alone didn't set LocalContentColor because the root wasn't a Material Surface. Supply onSurface at the theme boundary so default Text/Icon content updates with light/dark selection, including dialog compositions.
- System status/navigation icon brightness was hard-coded light in MainActivity. It now reacts to the actual app theme on every theme change.
- Glass cards now use 97% surface opacity with subtle theme-derived accents/borders. Modal GlassDialog and radio country/language pickers add a fully opaque themed backing, avoiding underlying artwork/text bleeding through. The floating footer remains translucent and keeps its existing geometry/insets.
- Country/language picker height is capped to 85% of the screen and only the options list takes remaining space, keeping Close visible on small screens. Country labels sort by localized name rather than the flag/ISO code.
- Add 10 dp vertical spacing to Home/Device grid rows and 12 dp to Radio rows so card borders don't touch.
- Local radio only offers location activation/refresh and a read-only detected-country label. It has no country/language picker and requires location permission. Ignore legacy manual local-country/language selections in the model. World/category filters retain their country and language controls; no default Oman selection is introduced.

## Checks and resume
Pending CI. Unit tests verify Local ignores a stale manual country and World preserves manual selection without silently falling back to location. Device instrumentation tests inherited/default dialog text colors in dark/light modes and captures screenshots; UI smoke switches dark appearance, opens dialogs and verifies Local versus World controls. Existing actual-service playback regression remains enabled.

Next: verify CI, inspect generated dark/light captures and deliver the APK. Actual location resolution still depends on device permissions, enabled providers and geocoding availability. No live station/network availability is asserted by UI tests.
