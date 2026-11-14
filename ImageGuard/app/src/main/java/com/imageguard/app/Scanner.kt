package com.imageguard.app

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.util.UUID

data class MediaImage(
    val id: Long,
    val uri: Uri,
    val path: String?,
    val displayName: String,
    val mimeType: String?,
)

data class ScanOutcome(val score: Float?, val removed: Boolean)

/** Finds images in the phone's media library, scores them, and quarantines explicit ones. */
class Scanner(private val context: Context) {

    private val store = Store.get(context)
    private val resolver = context.contentResolver

    /** Every image in the library, newest first. */
    fun allImages(): List<MediaImage> = query(null, null)

    /** Images added in the last [seconds] seconds that have not been scored yet. */
    fun recentUnscored(seconds: Long): List<MediaImage> {
        val since = System.currentTimeMillis() / 1000 - seconds
        return query("${MediaStore.Images.Media.DATE_ADDED} >= ?", arrayOf(since.toString()))
            .filter { store.cachedScore(it.id) == null }
    }

    @Suppress("DEPRECATION") // DATA is still populated and is needed for "All files access" deletes.
    private fun query(selection: String?, args: Array<String>?): List<MediaImage> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
        )
        val out = ArrayList<MediaImage>()
        try {
            resolver.query(
                collection, projection, selection, args,
                "${MediaStore.Images.Media._ID} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dataCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    out += MediaImage(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        path = c.getString(dataCol),
                        displayName = c.getString(nameCol) ?: "image_$id",
                        mimeType = c.getString(mimeCol),
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission to read media", e)
        }
        return out
    }

    /** Scores one image (using the cache when possible) and quarantines it if over the threshold. */
    fun process(image: MediaImage): ScanOutcome {
        if (store.isAllowed(image.path)) return ScanOutcome(null, false)
        val score = store.cachedScore(image.id)
            ?: (NsfwClassifier.get(context).score(resolver, image.uri) ?: UNREADABLE)
                .also { store.saveScore(image.id, it) }
        if (score == UNREADABLE) return ScanOutcome(null, false) // corrupt/unsupported file
        val removed = score >= store.threshold && quarantine(image, score)
        return ScanOutcome(score, removed)
    }

    /** Moves the image into app-private storage and deletes the original. */
    private fun quarantine(image: MediaImage, score: Float): Boolean {
        val key = UUID.randomUUID().toString()
        val dest = File(store.quarantineDir, key)
        try {
            val input = resolver.openInputStream(image.uri) ?: return false
            input.use { inp -> dest.outputStream().use { inp.copyTo(it) } }
        } catch (e: Exception) {
            Log.w(TAG, "Could not copy ${image.uri}", e)
            dest.delete()
            return false
        }

        if (!deleteOriginal(image)) {
            Log.w(TAG, "Could not delete ${image.path}; leaving it in place")
            dest.delete()
            return false
        }

        store.addQuarantined(
            QuarantinedItem(
                key = key,
                displayName = image.displayName,
                originalPath = image.path,
                mimeType = image.mimeType,
                score = score,
                quarantinedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    private fun deleteOriginal(image: MediaImage): Boolean {
        // 1) Through MediaStore (removes the file and its gallery entry together).
        try {
            if (resolver.delete(image.uri, null, null) > 0) {
                if (image.path == null || !File(image.path).exists()) return true
            }
        } catch (e: Exception) {
            Log.d(TAG, "MediaStore delete refused, falling back to file delete", e)
        }
        // 2) Directly on the file (needs "All files access" on Android 11+).
        val path = image.path ?: return false
        val file = File(path)
        if (file.exists() && !file.delete()) return false
        MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
        return !file.exists()
    }

    /** Puts a quarantined image back and remembers never to remove it again. */
    fun restore(item: QuarantinedItem): File? {
        val src = store.quarantinedFile(item)
        if (!src.exists()) {
            store.removeQuarantined(item.key)
            return null
        }
        val target = restoreTarget(item) ?: return null
        return try {
            target.parentFile?.mkdirs()
            src.inputStream().use { inp -> target.outputStream().use { inp.copyTo(it) } }
            store.allow(target.path)
            MediaScannerConnection.scanFile(context, arrayOf(target.path), null, null)
            src.delete()
            store.removeQuarantined(item.key)
            target
        } catch (e: Exception) {
            Log.w(TAG, "Restore failed", e)
            target.delete()
            null
        }
    }

    private fun restoreTarget(item: QuarantinedItem): File? {
        val original = item.originalPath?.let { File(it) }
        if (original != null && original.parentFile?.exists() == true && !original.exists()) {
            return original
        }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "ImageGuard Restored"
        )
        var candidate = File(dir, item.displayName)
        var n = 1
        while (candidate.exists()) {
            candidate = File(dir, "${n++}_${item.displayName}")
        }
        return candidate
    }

    fun deleteForever(item: QuarantinedItem) {
        store.quarantinedFile(item).delete()
        store.removeQuarantined(item.key)
    }

    companion object {
        private const val TAG = "ImageGuard"
        private const val UNREADABLE = -1f
    }
}
