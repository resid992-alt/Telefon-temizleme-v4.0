package az.temiz

import android.app.usage.StorageStatsManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaScannerConnection
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import kotlin.concurrent.thread

/**
 * v4.0 əlavə alətlər:
 *  - saatlıq jurnal (batareya, temperatur, boş yaddaş) → qrafik və yaddaş proqnozu
 *  - yaddaşın tərkibi (şəkil / video / səs / tətbiq)
 *  - mesajlaşma proqramlarının (WhatsApp / Telegram / Viber) media təmizləyicisi
 *  - "Bütün fayllara giriş" ilə zibil fayl skaneri
 */
object Tools {

    // ------------------------------------------------------------------
    // 1) Jurnal
    // ------------------------------------------------------------------
    fun recordSnapshot(c: Context, force: Boolean = false) {
        try {
            val sp = c.getSharedPreferences("tl", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (!force && now - sp.getLong("last", 0L) < 25 * 60_000L) return
            val bi = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val lvl = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scl = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val pct = if (lvl >= 0 && scl > 0) lvl * 100 / scl else 0
            val temp = (bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
            val status = bi?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val ch = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val st = StatFs(Environment.getDataDirectory().path)
            val old = try {
                JSONArray(sp.getString("a", "[]"))
            } catch (_: Exception) {
                JSONArray()
            }
            old.put(
                JSONObject()
                    .put("t", now)
                    .put("b", pct)
                    .put("c", temp)
                    .put("ch", ch)
                    .put("f", st.availableBytes)
                    .put("tot", st.totalBytes)
            )
            val out = JSONArray()
            for (i in maxOf(0, old.length() - 400) until old.length()) out.put(old.get(i))
            sp.edit().putString("a", out.toString()).putLong("last", now).apply()
        } catch (_: Exception) {
        }
    }

    fun timeline(c: Context): String =
        c.getSharedPreferences("tl", Context.MODE_PRIVATE).getString("a", "[]") ?: "[]"

    // ------------------------------------------------------------------
    // 2) Yaddaşın tərkibi
    // ------------------------------------------------------------------
    fun storageBreakdown(c: Context): JSONObject {
        val o = JSONObject()
        try {
            val ssm = c.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
            val ext = ssm.queryExternalStatsForUser(StorageManager.UUID_DEFAULT, Process.myUserHandle())
            o.put("img", ext.imageBytes)
                .put("vid", ext.videoBytes)
                .put("aud", ext.audioBytes)
                .put("app", ext.appBytes)
                .put("tot", ext.totalBytes)
                .put("ok", true)
        } catch (_: Exception) {
            o.put("ok", false)
        }
        return o
    }

    // ------------------------------------------------------------------
    // 3) Mesajlaşma media təmizləyicisi
    // ------------------------------------------------------------------
    private const val OLD_MS = 30L * 24 * 60 * 60 * 1000

    private fun filesUri() = MediaStore.Files.getContentUri("external")

    private fun msgApp(path: String): String? {
        val l = path.lowercase()
        return when {
            l.contains("whatsapp") -> "WhatsApp"
            l.contains("telegram") -> "Telegram"
            l.contains("viber") -> "Viber"
            else -> null
        }
    }

    private fun typeOf(mt: Int): String = when (mt) {
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> "img"
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> "vid"
        MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> "aud"
        else -> "doc"
    }

    private fun kindOf(type: String): String = when (type) {
        "img" -> "i"
        "vid" -> "v"
        "aud" -> "a"
        else -> "f"
    }

    private fun msgQuery(c: Context, block: (Long, String, Long, String, Int, Long) -> Unit) {
        val rp = MediaStore.MediaColumns.RELATIVE_PATH
        val q = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "$rp LIKE ? OR $rp LIKE ? OR $rp LIKE ?"
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf("%WhatsApp%", "%Telegram%", "%Viber%")
            )
        }
        val cols = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        try {
            c.contentResolver.query(filesUri(), cols, q, null)?.use { cur ->
                while (cur.moveToNext()) {
                    block(
                        cur.getLong(0),
                        cur.getString(1) ?: "",
                        cur.getLong(2),
                        cur.getString(3) ?: "",
                        cur.getInt(4),
                        cur.getLong(5)
                    )
                }
            }
        } catch (_: Exception) {
        }
    }

    /** Hər (proqram × tip) üçün say, ölçü, 30+ gün köhnə say və ölçü. */
    fun msgGroups(c: Context): String {
        val now = System.currentTimeMillis()
        val map = LinkedHashMap<String, LongArray>()
        msgQuery(c) { _, _, size, path, mt, mod ->
            val app = msgApp(path)
            if (app != null) {
                val key = app + "|" + typeOf(mt)
                val a = map.getOrPut(key) { LongArray(4) }
                a[0]++
                a[1] += size
                if (mod * 1000 < now - OLD_MS) {
                    a[2]++
                    a[3] += size
                }
            }
        }
        val out = JSONArray()
        for ((k, v) in map) {
            val p = k.split("|")
            out.put(
                JSONObject().put("a", p[0]).put("t", p[1])
                    .put("n", v[0]).put("b", v[1]).put("on", v[2]).put("ob", v[3])
            )
        }
        return out.toString()
    }

    /** Seçilmiş qrup üçün ən böyük fayllar. */
    fun msgItems(c: Context, app: String, type: String, limit: Int): String {
        val now = System.currentTimeMillis()
        class It(val id: Long, val n: String, val s: Long, val d: Long, val sent: Boolean)
        val list = ArrayList<It>()
        msgQuery(c) { id, name, size, path, mt, mod ->
            if (msgApp(path) == app && typeOf(mt) == type) {
                val low = path.lowercase()
                list.add(It(id, name, size, mod, low.contains("/sent") || low.contains("sent/")))
            }
        }
        list.sortByDescending { it.s }
        val out = JSONArray()
        for (e in list.take(limit)) {
            out.put(
                JSONObject()
                    .put("id", e.id)
                    .put("n", e.n)
                    .put("s", e.s)
                    .put("d", e.d * 1000)
                    .put("old", e.d * 1000 < now - OLD_MS)
                    .put("snt", e.sent)
                    .put("k", kindOf(type))
            )
        }
        return out.toString()
    }

    // ------------------------------------------------------------------
    // 4) Zibil fayl skaneri ("Bütün fayllara giriş" lazımdır)
    // ------------------------------------------------------------------
    fun hasAllFiles(): Boolean = try {
        Environment.isExternalStorageManager()
    } catch (_: Exception) {
        false
    }

    private val TMP_EXT = setOf("tmp", "temp", "chk", "dmp")
    private val PART_EXT = setOf("crdownload", "part", "partial", "download")
    private val THUMB_DIRS = setOf(".thumbnails", "lost.dir", ".cache", ".tmp", ".temp")
    private val STD_DIRS = setOf(
        "dcim", "pictures", "download", "movies", "music", "documents", "alarms",
        "notifications", "ringtones", "podcasts", "audiobooks", "recordings"
    )

    @Volatile var junkRunning = false
    @Volatile var junkDone = false
    @Volatile var junkVisited = 0
    @Volatile var junkSummary = "{}"
    private val junkLists = HashMap<String, MutableList<Pair<String, Long>>>()

    private fun dirSize(d: File): Long {
        var total = 0L
        try {
            for (f in d.walkTopDown()) if (f.isFile) total += f.length()
        } catch (_: Exception) {
        }
        return total
    }

    fun junkStart() {
        if (junkRunning) return
        if (!hasAllFiles()) return
        junkRunning = true
        junkDone = false
        junkVisited = 0
        junkSummary = "{}"
        thread(name = "junk-scan") {
            val lists = LinkedHashMap<String, MutableList<Pair<String, Long>>>()
            for (k in listOf("tmp", "log", "part", "thumb", "empty")) lists[k] = ArrayList()
            val capPerCat = 20000
            try {
                val root = Environment.getExternalStorageDirectory()
                val t0 = System.currentTimeMillis()
                val stack = ArrayDeque<Pair<File, Int>>()
                stack.addLast(Pair(root, 0))
                while (stack.isNotEmpty() && junkVisited < 400_000 &&
                    System.currentTimeMillis() - t0 < 90_000
                ) {
                    val cur = stack.removeLast()
                    val dir = cur.first
                    val depth = cur.second
                    val kids = dir.listFiles()
                    if (kids == null) {
                        // oxunmur — keç
                    } else if (kids.isEmpty()) {
                        val isStd = depth == 1 && dir.name.lowercase() in STD_DIRS
                        if (depth >= 1 && !isStd && lists["empty"]!!.size < capPerCat) {
                            lists["empty"]!!.add(Pair(dir.absolutePath, 0L))
                        }
                    } else {
                        for (k in kids) {
                            junkVisited++
                            val low = k.name.lowercase()
                            if (k.isDirectory) {
                                val skip = (depth == 0 && k.name == "Android") ||
                                    Files.isSymbolicLink(k.toPath())
                                if (!skip) {
                                    if (low in THUMB_DIRS) {
                                        val sz = dirSize(k)
                                        if (sz > 0 && lists["thumb"]!!.size < capPerCat) {
                                            lists["thumb"]!!.add(Pair(k.absolutePath, sz))
                                        }
                                    } else if (depth < 12) {
                                        stack.addLast(Pair(k, depth + 1))
                                    }
                                }
                            } else {
                                val ext = low.substringAfterLast('.', "")
                                val cat = when {
                                    ext == "log" -> "log"
                                    ext in TMP_EXT -> "tmp"
                                    ext in PART_EXT -> "part"
                                    else -> null
                                }
                                if (cat != null && lists[cat]!!.size < capPerCat) {
                                    lists[cat]!!.add(Pair(k.absolutePath, k.length()))
                                }
                            }
                        }
                    }
                }
                val rootPath = root.absolutePath
                val cats = JSONObject()
                for ((cat, list) in lists) {
                    val total = list.sumOf { it.second }
                    val top = JSONArray()
                    for (p in list.sortedByDescending { it.second }.take(6)) {
                        top.put(
                            JSONObject()
                                .put("p", p.first.removePrefix(rootPath).trimStart('/'))
                                .put("s", p.second)
                        )
                    }
                    cats.put(cat, JSONObject().put("n", list.size).put("b", total).put("top", top))
                }
                synchronized(this) {
                    junkLists.clear()
                    junkLists.putAll(lists)
                }
                junkSummary = JSONObject().put("cats", cats).put("visited", junkVisited).toString()
            } catch (_: Exception) {
                junkSummary = "{}"
            }
            junkDone = true
            junkRunning = false
        }
    }

    fun junkStatus(): String =
        JSONObject()
            .put("run", junkRunning)
            .put("done", junkDone)
            .put("visited", junkVisited)
            .put("all", hasAllFiles())
            .put("res", try {
                JSONObject(junkSummary)
            } catch (_: Exception) {
                JSONObject()
            })
            .toString()

    fun junkDelete(c: Context, cat: String): String {
        if (!hasAllFiles()) return JSONObject().put("n", 0).put("freed", 0L).toString()
        val root = Environment.getExternalStorageDirectory().canonicalPath
        val list = synchronized(this) { junkLists[cat]?.toList() ?: emptyList() }
        var freed = 0L
        var n = 0
        val scan = ArrayList<String>()
        for ((p, s) in list) {
            try {
                val f = File(p)
                val cp = f.canonicalPath
                val safe = cp.startsWith("$root/") && !cp.startsWith("$root/Android")
                if (safe) {
                    val ok = when {
                        cat == "empty" -> f.isDirectory && (f.list()?.isEmpty() == true) && f.delete()
                        f.isDirectory -> f.deleteRecursively()
                        else -> f.delete()
                    }
                    if (ok) {
                        freed += s
                        n++
                        if (scan.size < 400) scan.add(p)
                    }
                }
            } catch (_: Exception) {
            }
        }
        if (scan.isNotEmpty()) {
            try {
                MediaScannerConnection.scanFile(c, scan.toTypedArray(), null, null)
            } catch (_: Exception) {
            }
        }
        synchronized(this) { junkLists.remove(cat) }
        return JSONObject().put("n", n).put("freed", freed).toString()
    }
}
