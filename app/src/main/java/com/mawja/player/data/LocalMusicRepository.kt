package com.mawja.player.data

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads local audio/video with bounded intermediate allocations for large libraries. */
class LocalMusicRepository(private val context: Context) {
    suspend fun load(): List<Track> = withContext(Dispatchers.IO) {
        val output = ArrayList<Track>()
        // Android can grant audio and video independently; keep the available half.
        runCatching { queryMedia(video = false, output) }
        runCatching { queryMedia(video = true, output) }
        output
    }

    /** Lightweight folder-only scan used by Settings; it does not allocate Track objects or artwork URLs. */
    suspend fun folders(): List<String> = withContext(Dispatchers.IO) {
        buildSet {
            runCatching { queryFolders(video = false, this) }
            runCatching { queryFolders(video = true, this) }
        }.filter { it.isNotBlank() }.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    private fun queryMedia(video: Boolean, output: MutableList<Track>) {
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.TITLE)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.DURATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.MediaColumns.RELATIVE_PATH)
            else add(MediaStore.MediaColumns.DATA)
            if (!video) {
                add(MediaStore.Audio.Media.ARTIST)
                add(MediaStore.Audio.Media.ALBUM)
                add(MediaStore.Audio.Media.ALBUM_ID)
            }
        }.toTypedArray()
        val selection = "${MediaStore.MediaColumns.DURATION} > 0"
        val order = "${MediaStore.MediaColumns.TITLE} COLLATE NOCASE ASC"
        context.contentResolver.query(collection, projection, selection, null, order)?.use { cursor ->
            val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
            if (idCol < 0) return
            val titleCol = cursor.getColumnIndex(MediaStore.MediaColumns.TITLE)
            val displayNameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val durationCol = cursor.getColumnIndex(MediaStore.MediaColumns.DURATION)
            val relativePathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            } else -1
            val dataCol = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            } else -1
            val artistCol = if (!video) cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST) else -1
            val albumCol = if (!video) cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM) else -1
            val albumIdCol = if (!video) cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID) else -1
            while (cursor.moveToNext()) {
                val mediaId = cursor.longOrZero(idCol)
                if (mediaId <= 0L) continue
                val folder = extractFolder(cursor.text(relativePathCol), cursor.text(dataCol))
                val title = cursor.text(titleCol).ifBlank {
                    cursor.text(displayNameCol).substringBeforeLast('.').ifBlank { "—" }
                }
                val artist = if (video) "" else cursor.text(artistCol).cleanUnknown()
                val album = if (video) "" else cursor.text(albumCol).cleanUnknown()
                val albumId = cursor.longOrZero(albumIdCol)
                val uri = ContentUris.withAppendedId(collection, mediaId).toString()
                val id = if (video) "local:video:$mediaId" else "local:audio:$mediaId"
                output += Track(
                    id = id,
                    source = SourceType.LOCAL,
                    title = title,
                    subtitle = artist.ifBlank { if (video) "Video" else "" },
                    artist = artist,
                    album = album,
                    folder = folder,
                    artworkUrl = if (video) uri else albumId.takeIf { it > 0L }?.let { "content://media/external/audio/albumart/$it" },
                    streamUrl = uri,
                    durationMs = cursor.longOrZero(durationCol),
                    isVideo = video,
                )
            }
        }
    }

    private fun queryFolders(video: Boolean, output: MutableSet<String>) {
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
        context.contentResolver.query(collection, arrayOf(pathColumn), null, null, null)?.use { cursor ->
            val pathCol = cursor.getColumnIndex(pathColumn)
            if (pathCol < 0) return
            while (cursor.moveToNext()) {
                val path = cursor.text(pathCol)
                val folder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    path.replace('\\', '/').trimEnd('/').substringAfterLast('/').ifBlank { path }
                } else {
                    extractFolder("", path)
                }
                if (folder.isNotBlank()) output += folder
            }
        }
    }

    private fun extractFolder(relativePath: String, absolutePath: String): String {
        val relative = relativePath.replace('\\', '/').trimEnd('/')
        if (relative.isNotBlank()) return relative.substringAfterLast('/').ifBlank { relative }
        val absoluteParent = absolutePath.replace('\\', '/').substringBeforeLast('/', "")
        return absoluteParent.substringAfterLast('/').ifBlank { "Music" }
    }

    private fun Cursor.text(index: Int): String =
        if (index >= 0 && !isNull(index)) getString(index).orEmpty() else ""

    private fun Cursor.longOrZero(index: Int): Long =
        if (index >= 0 && !isNull(index)) getLong(index) else 0L

    private fun String.cleanUnknown(): String =
        trim().takeUnless { it.isBlank() || it.equals("<unknown>", ignoreCase = true) }.orEmpty()
}
