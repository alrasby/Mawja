# Adaptive icon fix — build 55
Date: 2026-11-28. Version 1.0.0 (55), com.mawja.player.
Verified source: ac302334f0dc3785847c46fafcad1b1ed2c9b434.
CI: https://github.com/alrasby/Mawja/actions/runs/46114895960 — build, runtime-profile, release and release-smoke succeeded.

## Change
The build54 cyan frame was baked into the image. Build55 removes it, using a transparent silver/turquoise wave foreground and full-bleed native dark turquoise gradient background. Adaptive foreground inset22%, legacy inset8%. Application, round, MainActivity and compatibility resource names resolve the new artwork. API33 monochrome uses its alpha; system-themed icons may be recolored by Android.
Changed resources: drawable-nodpi/mawja_mark_silver_turquoise.png; drawable/mawja_icon_{foreground,legacy_foreground,background}.xml; mipmap-anydpi, v26 and v33 launcher definitions; Manifest activity icon. Updated ThemeRegressionTest and build.yml preview collection.
Source foreground blob: efcff771210e7abfbe9cc2d1021bb8b6c840f54c. Removed framed mawja_icon_clean.png.

## Evidence
Android emulator regression assertions cover adaptive Application/Activity/compatibility resources, transparent corners, safe66dp foreground region, no gold pixels and matching central RGB across masks. Native Canvas launcher-circle, launcher-rounded-square and launcher-square previews visually reviewed: full background, no baked frame, no clipping of logo. Signed APK UI smoke passed theme, language, rotation, settings, radio filters and live France24 playback. Crash log empty.
APK artifact15250994076: 4808960 bytes; SHA256 2ff013f71f1028804b4029fabe526b7420bc7d0a31b40c77724feefd231504c9.
AAB artifact15252020177: 9512405 bytes; SHA256 944e8fc9553861db9039771ee77a4a5abdeaea5676565929a69a4f9dc15a322f.
Downloaded files independently match these sizes/hashes. Verification artifact15251730806 confirms non-debuggable and matching APK/AAB certificate:
63d1df1550d1541aaa87f48627c37256e5bac4af4ddfbcf85618da2cdc29c676.
Runtime preview artifact15250389582; signed smoke15251194682.

## Limits and next step
User confirmed Samsung Theme Park / Samsung themes. A saved external icon pack may retain old artwork; this is a hypothesis, not a reproduced Samsung cache diagnosis. If gold persists after installing55, recreate/reapply the icon pack. Do not clear One UI Home data. No physical Samsung Theme Park or car test performed. No Google Play upload. Next: user verifies icon under default launcher and a newly created Theme Park pack.
