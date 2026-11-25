package com.mawja.player.data.radio

/** Local never inherits a manual country, including preferences from older builds. */
internal fun radioBrowseCountry(local: Boolean, manual: String?, location: String?): String? =
    if (local) location else manual
