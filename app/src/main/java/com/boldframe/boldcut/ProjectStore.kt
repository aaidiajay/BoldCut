package com.boldframe.boldcut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Projects app ki private storage me save hote hain:
 *   files/projects/<id>.json  (clips, trim, markers, naam)
 *   files/projects/<id>.jpg   (thumbnail)
 * Video ki copy nahi hoti, sirf uska address (uri).
 */
class ProjectStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "projects").apply { mkdirs() }

    private fun jsonFile(id: String) = File(dir, "$id.json")
    private fun thumbFile(id: String) = File(dir, "$id.jpg")

    fun list(): List<ProjectMeta> {
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { it.extension == "json" }
            .mapNotNull { f -> runCatching { parse(f.readText()) }.getOrNull() }
            .map { p ->
                ProjectMeta(p.id, p.name, p.modifiedAt, p.clips.sumOf { it.lengthMs }, p.clips.size)
            }
            .sortedByDescending { it.modifiedAt }
    }

    fun load(id: String): SavedProject? =
        runCatching { parse(jsonFile(id).readText()) }.getOrNull()

    fun save(p: SavedProject) {
        val tmp = File(dir, "${p.id}.tmp")
        tmp.writeText(toJson(p).toString())
        if (!tmp.renameTo(jsonFile(p.id))) {
            jsonFile(p.id).writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun create(name: String, clips: List<Clip>): SavedProject {
        val now = System.currentTimeMillis()
        val p = SavedProject(UUID.randomUUID().toString(), name, now, now, clips, emptyList())
        save(p)
        return p
    }

    fun rename(id: String, name: String) {
        val p = load(id) ?: return
        save(p.copy(name = name, modifiedAt = System.currentTimeMillis()))
    }

    fun duplicate(id: String) {
        val p = load(id) ?: return
        val now = System.currentTimeMillis()
        val copy = p.copy(
            id = UUID.randomUUID().toString(),
            name = p.name + " copy",
            createdAt = now,
            modifiedAt = now
        )
        save(copy)
        val t = thumbFile(id)
        if (t.exists()) runCatching { t.copyTo(thumbFile(copy.id), overwrite = true) }
    }

    fun delete(id: String) {
        jsonFile(id).delete()
        thumbFile(id).delete()
    }

    fun saveThumbnail(id: String, bmp: Bitmap) {
        runCatching {
            thumbFile(id).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        }
    }

    fun loadThumb(id: String): Bitmap? {
        val f = thumbFile(id)
        return if (f.exists()) runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull() else null
    }

    private fun toJson(p: SavedProject): JSONObject {
        val o = JSONObject()
        o.put("id", p.id)
        o.put("name", p.name)
        o.put("createdAt", p.createdAt)
        o.put("modifiedAt", p.modifiedAt)
        val ca = JSONArray()
        for (c in p.clips) {
            val co = JSONObject()
            co.put("id", c.id)
            co.put("uri", c.uri.toString())
            co.put("durationMs", c.durationMs)
            co.put("trimStartMs", c.trimStartMs)
            co.put("trimEndMs", c.trimEndMs)
            ca.put(co)
        }
        o.put("clips", ca)
        val ma = JSONArray()
        for (m in p.markers) {
            val mo = JSONObject()
            mo.put("id", m.id)
            mo.put("timeMs", m.timeMs)
            ma.put(mo)
        }
        o.put("markers", ma)
        return o
    }

    private fun parse(text: String): SavedProject {
        val o = JSONObject(text)
        val ca = o.optJSONArray("clips") ?: JSONArray()
        val clips = (0 until ca.length()).map { i ->
            val c = ca.getJSONObject(i)
            Clip(
                c.getLong("id"),
                Uri.parse(c.getString("uri")),
                c.getLong("durationMs"),
                c.getLong("trimStartMs"),
                c.getLong("trimEndMs")
            )
        }
        val ma = o.optJSONArray("markers") ?: JSONArray()
        val markers = (0 until ma.length()).map { i ->
            val m = ma.getJSONObject(i)
            Marker(m.getLong("id"), m.getLong("timeMs"))
        }
        return SavedProject(
            o.getString("id"),
            o.optString("name", "Project"),
            o.optLong("createdAt", 0L),
            o.optLong("modifiedAt", 0L),
            clips,
            markers
        )
    }
}
