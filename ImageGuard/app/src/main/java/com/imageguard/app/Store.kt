package com.imageguard.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class QuarantinedItem(
    val key: String,
    val displayName: String,
    val originalPath: String?,
    val mimeType: String?,
    val score: Float,
    val quarantinedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", key)
        .put("name", displayName)
        .put("path", originalPath ?: JSONObject.NULL)
        .put("mime", mimeType ?: JSONObject.NULL)
        .put("score", score.toDouble())
        .put("time", quarantinedAt)

    companion object {
        fun fromJson(o: JSONObject) = QuarantinedItem(
            key = o.getString("key"),
            displayName = o.optString("name", "image"),
            originalPath = if (o.isNull("path")) null else o.getString("path"),
            mimeType = if (o.isNull("mime")) null else o.getString("mime"),
            score = o.optDouble("score", 1.0).toFloat(),
            quarantinedAt = o.optLong("time", 0L),
        )
    }
}

/** Small persistent store: settings, cached scores, quarantine records, user allow-list. */
class Store private constructor(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("imageguard", Context.MODE_PRIVATE)
    private val scoresFile = File(context.filesDir, "scores.tsv")
    private val quarantineFile = File(context.filesDir, "quarantine.json")
    val quarantineDir: File = File(context.filesDir, "quarantine").apply { mkdirs() }

    private val scores: HashMap<Long, Float> by lazy { loadScores() }

    // ---- settings ----

    /** Images with score >= threshold are removed. Lower = stricter. */
    var threshold: Float
        get() = prefs.getFloat("threshold", DEFAULT_THRESHOLD)
        set(v) = prefs.edit().putFloat("threshold", v).apply()

    var autoProtect: Boolean
        get() = prefs.getBoolean("auto_protect", false)
        set(v) = prefs.edit().putBoolean("auto_protect", v).apply()

    var lastFullScan: Long
        get() = prefs.getLong("last_full_scan", 0L)
        set(v) = prefs.edit().putLong("last_full_scan", v).apply()

    // ---- score cache (MediaStore id -> score) ----

    @Synchronized
    fun cachedScore(id: Long): Float? = scores[id]

    @Synchronized
    fun saveScore(id: Long, score: Float) {
        scores[id] = score
        scoresFile.appendText("$id\t$score\n")
    }

    private fun loadScores(): HashMap<Long, Float> {
        val map = HashMap<Long, Float>()
        if (!scoresFile.exists()) return map
        scoresFile.forEachLine { line ->
            val parts = line.split('\t')
            if (parts.size == 2) {
                val id = parts[0].toLongOrNull()
                val s = parts[1].toFloatOrNull()
                if (id != null && s != null) map[id] = s
            }
        }
        return map
    }

    // ---- allow-list: paths the user restored, never remove again ----

    @Synchronized
    fun isAllowed(path: String?): Boolean =
        path != null && prefs.getStringSet("allowed", emptySet())!!.contains(path)

    @Synchronized
    fun allow(path: String) {
        val set = HashSet(prefs.getStringSet("allowed", emptySet())!!)
        set.add(path)
        prefs.edit().putStringSet("allowed", set).apply()
    }

    // ---- quarantine records ----

    @Synchronized
    fun quarantined(): List<QuarantinedItem> {
        if (!quarantineFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(quarantineFile.readText())
            (0 until arr.length()).map { QuarantinedItem.fromJson(arr.getJSONObject(it)) }
                .sortedByDescending { it.quarantinedAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun addQuarantined(item: QuarantinedItem) = writeQuarantined(quarantined() + item)

    @Synchronized
    fun removeQuarantined(key: String) = writeQuarantined(quarantined().filterNot { it.key == key })

    fun quarantinedFile(item: QuarantinedItem) = File(quarantineDir, item.key)

    private fun writeQuarantined(items: List<QuarantinedItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        val tmp = File(quarantineFile.path + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(quarantineFile)
    }

    companion object {
        const val DEFAULT_THRESHOLD = 0.60f

        @Volatile private var instance: Store? = null
        fun get(context: Context): Store =
            instance ?: synchronized(this) {
                instance ?: Store(context.applicationContext).also { instance = it }
            }
    }
}
