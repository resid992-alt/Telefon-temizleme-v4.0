package az.temiz

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.DownloadManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.util.Size
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : Activity() {
    private lateinit var wv: WebView
    private val IMG = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private val VID = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    private val AUD = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    private val FILES = MediaStore.Files.getContentUri("external")

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        wv = WebView(this)
        setContentView(wv)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.databaseEnabled = true
        wv.settings.allowFileAccess = true
        wv.webChromeClient = WebChromeClient()
        wv.addJavascriptInterface(Bridge(), "Native")
        wv.loadUrl("file:///android_asset/index.html")

        val prefs = getSharedPreferences("s", 0)
        if (!prefs.contains("hourly_on")) {
            prefs.edit().putBoolean("hourly_on", true).putBoolean("hourly_cache", true).apply()
        }
        Cleaner.scheduleHourly(this, forceReset = false)
        Cleaner.checkBatteryProtection(this)
        Tools.recordSnapshot(this, force = true)
        CleanWidgetProvider.updateAllWidgets(this)

        requestAllRuntimePermissions()
    }

    private fun requestAllRuntimePermissions() {
        val need = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val miss = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (miss.isNotEmpty()) requestPermissions(miss.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        if (::wv.isInitialized) wv.evaluateJavascript("window.onShow&&onShow()", null)
    }

    override fun onResume() {
        super.onResume()
        Tools.recordSnapshot(this)
        Cleaner.checkBatteryProtection(this)
        if (::wv.isInitialized) wv.evaluateJavascript("window.onShow&&onShow()", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::wv.isInitialized) {
            wv.evaluateJavascript("Boolean(window.handleBack && handleBack())") { res ->
                if (res != "true") {
                    @Suppress("DEPRECATION")
                    super.onBackPressed()
                }
            }
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(c: Int, r: Int, d: Intent?) {
        if (c == 77) wv.evaluateJavascript("onDel(${r == RESULT_OK})", null)
        if (c == 78) wv.evaluateJavascript("onDelVid(${r == RESULT_OK})", null)
        if (c == 79) wv.evaluateJavascript("onMediaAction('trash',${r == RESULT_OK})", null)
        if (c == 81) wv.evaluateJavascript("onMediaAction('restore',${r == RESULT_OK})", null)
        if (c == 82) wv.evaluateJavascript("onMediaAction('delete',${r == RESULT_OK})", null)
    }

    private fun go(i: Intent) = runOnUiThread {
        try {
            startActivity(i)
        } catch (_: ActivityNotFoundException) {
        }
    }

    private fun iconBase64(d: Drawable): String {
        return try {
            val bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            d.setBounds(0, 0, 48, 48)
            d.draw(canvas)
            val bo = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 72, bo)
            Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun ram(): String {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            return JSONObject().put("a", mi.availMem).put("t", mi.totalMem).toString()
        }

        @JavascriptInterface
        fun storage(): String {
            val s = StatFs(Environment.getDataDirectory().path)
            return JSONObject().put("f", s.availableBytes).put("t", s.totalBytes).toString()
        }

        @JavascriptInterface
        fun runningApps(): String = Cleaner.getRunningApps(this@MainActivity).toString()

        @JavascriptInterface
        fun stopApp(pkg: String): String = Cleaner.stopSingleApp(this@MainActivity, pkg).toString()

        @JavascriptInterface
        fun stopAppsBatch(pkgsJson: String): String {
            val arr = JSONArray(pkgsJson)
            val list = (0 until arr.length()).map { arr.getString(it) }
            return Cleaner.stopAppList(this@MainActivity, list).toString()
        }

        @JavascriptInterface
        fun cacheSummary(): String {
            val (bytes, count) = Cleaner.getTotalAppsCacheBytes(this@MainActivity)
            return JSONObject()
                .put("bytes", bytes)
                .put("count", count)
                .put("acc", CacheAccessibilityService.isEnabledInSettings(this@MainActivity))
                .put("usage", hasUsage())
                .toString()
        }

        @JavascriptInterface
        fun cleanAllCacheNow(): String {
            return Cleaner.startOneClickCacheClean(this@MainActivity, returnToApp = true).toString()
        }

        @JavascriptInterface
        fun cleanSingleAppCache(pkg: String, name: String, cacheBytes: Long): String {
            return Cleaner.startSingleAppCacheClean(this@MainActivity, pkg, name, cacheBytes).toString()
        }

        @JavascriptInterface
        fun cacheProgress(): String {
            return JSONObject()
                .put("run", CacheAccessibilityService.isRunning)
                .put("cur", CacheAccessibilityService.currentIndex)
                .put("tot", CacheAccessibilityService.totalPackages)
                .put("app", CacheAccessibilityService.currentAppName)
                .put("done", CacheAccessibilityService.cleanedCount)
                .put("bytes", CacheAccessibilityService.clearedBytes)
                .toString()
        }

        @JavascriptInterface
        fun stopCacheClean() {
            CacheAccessibilityService.stopBatch()
        }

        @JavascriptInterface
        fun openAccessibility() {
            go(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        @JavascriptInterface
        fun requestMediaPerm() {
            runOnUiThread { requestAllRuntimePermissions() }
        }

        @JavascriptInterface
        fun sysInfo(): String {
            val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val lvl = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scl = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val pct = if (lvl >= 0 && scl > 0) (lvl * 100 / scl) else 0
            val tempC = (bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
            val voltV = (bi?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000.0
            val status = bi?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val health = when (bi?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Sağlam"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Həddindən artıq isti"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Zəifləyib"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Yüksək gərginlik"
                else -> "Normal"
            }
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val optIgnored = pm.isIgnoringBatteryOptimizations(packageName)
            val prefs = getSharedPreferences("s", 0)

            return JSONObject()
                .put("bp", pct)
                .put("bt", tempC)
                .put("bv", voltV)
                .put("bh", health)
                .put("bc", charging)
                .put("dev", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("ver", "Android ${Build.VERSION.RELEASE}")
                .put("opt", optIgnored)
                .put("acc", CacheAccessibilityService.isEnabledInSettings(this@MainActivity))
                .put("batLimit", prefs.getInt("bat_limit", 90))
                .put("batHeat", prefs.getBoolean("bat_heat", true))
                .toString()
        }

        @JavascriptInterface
        fun setBatteryGuard(limitPct: Int, heatOn: Boolean) {
            getSharedPreferences("s", 0).edit()
                .putInt("bat_limit", limitPct)
                .putBoolean("bat_heat", heatOn)
                .remove("last_bat_alert")
                .apply()
            Cleaner.checkBatteryProtection(this@MainActivity)
        }

        @JavascriptInterface
        fun hasUsage(): Boolean = Cleaner.hasUsageAccess(this@MainActivity)

        @JavascriptInterface
        fun hasMedia(): Boolean {
            val p = if (Build.VERSION.SDK_INT >= 33) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        }

        @JavascriptInterface
        fun hasVideo(): Boolean {
            val p = if (Build.VERSION.SDK_INT >= 33) {
                Manifest.permission.READ_MEDIA_VIDEO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        }

        @JavascriptInterface
        fun openUsage() = go(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))

        @JavascriptInterface
        fun openStorage() = go(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))

        @JavascriptInterface
        fun openDownloads() = go(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))

        @JavascriptInterface
        fun openApp(p: String) = go(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$p"))
        )

        @JavascriptInterface
        fun uninstall(p: String) = go(Intent(Intent.ACTION_DELETE, Uri.parse("package:$p")))

        @JavascriptInterface
        fun requestIgnoreBatteryOpt() {
            try {
                go(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                go(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        @JavascriptInterface
        fun toggleWl(p: String): Boolean = Cleaner.toggleWhitelist(this@MainActivity, p)

        @JavascriptInterface
        fun apps(): String {
            val pm = packageManager
            val ssm = getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
            val userHandle = Process.myUserHandle()
            val usageOk = hasUsage()
            val wl = Cleaner.getWhitelist(this@MainActivity)
            val stoppedMap = Cleaner.getManuallyStoppedSet(this@MainActivity)
            val now = System.currentTimeMillis()
            val last = HashMap<String, Long>()
            val fgTime = HashMap<String, Long>()
            val runningPkgs = HashSet<String>()

            try {
                val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                am.runningAppProcesses?.forEach { proc ->
                    proc.pkgList?.forEach { runningPkgs.add(it) }
                }
            } catch (_: Exception) {
            }

            try {
                val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                for (u in usm.queryUsageStats(UsageStatsManager.INTERVAL_YEARLY, now - 365L * 86400000L, now)) {
                    last[u.packageName] = maxOf(last[u.packageName] ?: 0L, u.lastTimeUsed)
                }
                for (u in usm.queryUsageStats(UsageStatsManager.INTERVAL_WEEKLY, now - 7L * 86400000L, now)) {
                    fgTime[u.packageName] = (fgTime[u.packageName] ?: 0L) + u.totalTimeInForeground
                }
            } catch (_: Exception) {
            }

            val arr = JSONArray()
            val seen = HashSet<String>()
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            for (r in pm.queryIntentActivities(i, 0)) {
                val p = r.activityInfo.packageName
                if (p == packageName || p == "com.android.settings" || !seen.add(p)) continue
                val appInfo = r.activityInfo.applicationInfo
                val inst = try {
                    pm.getPackageInfo(p, 0).firstInstallTime
                } catch (_: Exception) {
                    0L
                }
                var totalBytes = 0L
                var cacheBytes = 0L
                if (usageOk) {
                    try {
                        val uuid = appInfo.storageUuid ?: StorageManager.UUID_DEFAULT
                        val st = ssm.queryStatsForPackage(uuid, p, userHandle)
                        totalBytes = st.appBytes + st.dataBytes
                        cacheBytes = st.cacheBytes
                    } catch (_: Exception) {
                    }
                }
                val lastTs = last[p] ?: 0L
                val stoppedAt = stoppedMap[p] ?: 0L
                val isRecent = lastTs > 0L && (now - lastTs < 2L * 3600_000L)
                val isOpen = (runningPkgs.contains(p) || isRecent) && (stoppedAt <= lastTs)

                val ic = try {
                    iconBase64(r.loadIcon(pm))
                } catch (_: Exception) {
                    ""
                }
                arr.put(
                    JSONObject()
                        .put("n", r.loadLabel(pm).toString())
                        .put("p", p)
                        .put("l", lastTs)
                        .put("i", inst)
                        .put("fg", fgTime[p] ?: 0L)
                        .put("sz", totalBytes)
                        .put("ca", cacheBytes)
                        .put("ic", ic)
                        .put("wl", wl.contains(p))
                        .put("run", isOpen)
                )
            }
            return arr.toString()
        }

        @JavascriptInterface
        fun privacyAudit(): String {
            val pm = packageManager
            val arr = JSONArray()
            val seen = HashSet<String>()
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            for (r in pm.queryIntentActivities(i, 0)) {
                val p = r.activityInfo.packageName
                if (p == packageName || !seen.add(p)) continue
                val appInfo = r.activityInfo.applicationInfo
                val f = appInfo.flags
                if ((f and ApplicationInfo.FLAG_SYSTEM) != 0 && (f and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) continue

                try {
                    val pi = pm.getPackageInfo(p, PackageManager.GET_PERMISSIONS)
                    val req = pi.requestedPermissions ?: continue
                    val flags = pi.requestedPermissionsFlags ?: IntArray(req.size)
                    val perms = JSONArray()
                    for (idx in req.indices) {
                        val granted = (flags[idx] and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                        if (!granted) continue
                        when (req[idx]) {
                            Manifest.permission.CAMERA -> perms.put("Kamera")
                            Manifest.permission.RECORD_AUDIO -> perms.put("Mikrofon")
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION -> {
                                var hasLoc = false
                                for (k in 0 until perms.length()) {
                                    if (perms.getString(k) == "Məkan") hasLoc = true
                                }
                                if (!hasLoc) perms.put("Məkan")
                            }
                            Manifest.permission.READ_SMS,
                            Manifest.permission.RECEIVE_SMS -> {
                                var hasSms = false
                                for (k in 0 until perms.length()) {
                                    if (perms.getString(k) == "SMS") hasSms = true
                                }
                                if (!hasSms) perms.put("SMS")
                            }
                            Manifest.permission.READ_CONTACTS -> perms.put("Kontaktlar")
                        }
                    }
                    if (perms.length() > 0) {
                        arr.put(
                            JSONObject()
                                .put("n", r.loadLabel(pm).toString())
                                .put("p", p)
                                .put("perms", perms)
                        )
                    }
                } catch (_: Exception) {
                }
            }
            return arr.toString()
        }

        @JavascriptInterface
        fun kill(): String = Cleaner.run(this@MainActivity).toString()

        @JavascriptInterface
        fun history(): String = Cleaner.getHistory(this@MainActivity)

        @JavascriptInterface
        fun total(): Int =
            contentResolver.query(IMG, arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use { it.count } ?: 0

        @JavascriptInterface
        fun images(offset: Int, limit: Int): String {
            val q = Bundle().apply {
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
                putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
                putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            }
            val cols = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH
            )
            val out = JSONArray()
            contentResolver.query(IMG, cols, q, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    var t = ""
                    try {
                        val bmp = contentResolver.loadThumbnail(ContentUris.withAppendedId(IMG, id), Size(96, 96), null)
                        val bo = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 60, bo)
                        t = Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
                    } catch (_: Exception) {
                    }
                    out.put(
                        JSONObject()
                            .put("id", id)
                            .put("n", c.getString(1) ?: "")
                            .put("s", c.getLong(2))
                            .put("w", c.getInt(3))
                            .put("h", c.getInt(4))
                            .put("b", c.getString(5) ?: "")
                            .put("r", c.getString(6) ?: "")
                            .put("t", t)
                    )
                }
            }
            return out.toString()
        }

        @JavascriptInterface
        fun fullImage(id: Long): String {
            return try {
                val bmp = contentResolver.loadThumbnail(ContentUris.withAppendedId(IMG, id), Size(720, 720), null)
                val bo = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 82, bo)
                Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
            } catch (_: Exception) {
                ""
            }
        }

        @JavascriptInterface
        fun videos(limit: Int): String {
            val q = Bundle().apply {
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Video.Media.SIZE))
                putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            }
            val cols = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Video.Media.RELATIVE_PATH
            )
            val out = JSONArray()
            contentResolver.query(VID, cols, q, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    var t = ""
                    try {
                        val bmp = contentResolver.loadThumbnail(ContentUris.withAppendedId(VID, id), Size(96, 96), null)
                        val bo = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 60, bo)
                        t = Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
                    } catch (_: Exception) {
                    }
                    out.put(
                        JSONObject()
                            .put("id", id)
                            .put("n", c.getString(1) ?: "")
                            .put("s", c.getLong(2))
                            .put("d", c.getLong(3))
                            .put("b", c.getString(4) ?: "")
                            .put("r", c.getString(5) ?: "")
                            .put("t", t)
                    )
                }
            }
            return out.toString()
        }

        @JavascriptInterface
        fun largeFiles(): String {
            val filesUri = MediaStore.Files.getContentUri("external")
            val q = Bundle().apply {
                putInt(ContentResolver.QUERY_ARG_LIMIT, 120)
                putString(
                    ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "${MediaStore.Files.FileColumns.SIZE} > ? AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} != ? AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} != ?"
                )
                putStringArray(
                    ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    arrayOf(
                        "2097152",
                        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
                    )
                )
                putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Files.FileColumns.SIZE))
                putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            }
            val cols = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.RELATIVE_PATH,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.DATA,
                MediaStore.MediaColumns.DATE_MODIFIED
            )
            val pm = packageManager
            val out = JSONArray()
            try {
                contentResolver.query(filesUri, cols, q, null)?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(1) ?: "Fayl"
                        val filePath = c.getString(5) ?: ""
                        val isApk = name.lowercase().endsWith(".apk")
                        val isDupName = Regex("""\(\d+\)\.[a-z0-9]+$""", RegexOption.IGNORE_CASE).containsMatchIn(name)
                        var apkStatus = ""
                        if (isApk && filePath.isNotEmpty()) {
                            try {
                                @Suppress("DEPRECATION")
                                val pkgInfo = pm.getPackageArchiveInfo(filePath, 0)
                                if (pkgInfo != null) {
                                    val installed = try {
                                        pm.getPackageInfo(pkgInfo.packageName, 0)
                                        true
                                    } catch (_: Exception) {
                                        false
                                    }
                                    apkStatus = if (installed) "Artıq quraşdırılıb ✓" else "Quraşdırılmayıb"
                                }
                            } catch (_: Exception) {
                            }
                        }
                        out.put(
                            JSONObject()
                                .put("id", c.getLong(0))
                                .put("n", name)
                                .put("s", c.getLong(2))
                                .put("r", c.getString(3) ?: "")
                                .put("m", c.getString(4) ?: "")
                                .put("apk", isApk)
                                .put("apkSt", apkStatus)
                                .put("dup", isDupName)
                                .put("d", c.getLong(6) * 1000)
                        )
                    }
                }
            } catch (_: Exception) {
            }
            return out.toString()
        }

        @JavascriptInterface
        fun del(ids: String) {
            val a = JSONArray(ids)
            val uris = (0 until a.length()).map { ContentUris.withAppendedId(IMG, a.getLong(it)) }
            runOnUiThread {
                try {
                    startIntentSenderForResult(
                        MediaStore.createDeleteRequest(contentResolver, uris).intentSender,
                        77, null, 0, 0, 0
                    )
                } catch (_: Exception) {
                    wv.evaluateJavascript("onDel(false)", null)
                }
            }
        }

        @JavascriptInterface
        fun delVideos(ids: String) {
            val a = JSONArray(ids)
            val uris = (0 until a.length()).map { ContentUris.withAppendedId(VID, a.getLong(it)) }
            runOnUiThread {
                try {
                    startIntentSenderForResult(
                        MediaStore.createDeleteRequest(contentResolver, uris).intentSender,
                        78, null, 0, 0, 0
                    )
                } catch (_: Exception) {
                    wv.evaluateJavascript("onDelVid(false)", null)
                }
            }
        }


        // ---------------- v4.0 əlavələri ----------------
        private fun kindUri(k: String, id: Long): Uri = ContentUris.withAppendedId(
            when (k) {
                "v" -> VID
                "a" -> AUD
                "f" -> FILES
                else -> IMG
            }, id
        )

        /** op: trash | restore | delete ; itemsJson: [{id:123,k:"i|v|a|f"}] */
        @JavascriptInterface
        fun mediaAction(op: String, itemsJson: String) {
            val a = JSONArray(itemsJson)
            if (a.length() == 0) return
            val uris = (0 until a.length()).map {
                val o = a.getJSONObject(it)
                kindUri(o.optString("k", "i"), o.getLong("id"))
            }
            val code = when (op) {
                "trash" -> 79
                "restore" -> 81
                else -> 82
            }
            runOnUiThread {
                try {
                    val pi = when (op) {
                        "trash" -> MediaStore.createTrashRequest(contentResolver, uris, true)
                        "restore" -> MediaStore.createTrashRequest(contentResolver, uris, false)
                        else -> MediaStore.createDeleteRequest(contentResolver, uris)
                    }
                    startIntentSenderForResult(pi.intentSender, code, null, 0, 0, 0)
                } catch (_: Exception) {
                    wv.evaluateJavascript("onMediaAction('$op',false)", null)
                }
            }
        }

        @JavascriptInterface
        fun trashList(): String {
            val out = JSONArray()
            val sources = listOf(Pair(IMG, "i"), Pair(VID, "v"))
            for ((uri, kind) in sources) {
                val q = Bundle().apply {
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
                    putInt(ContentResolver.QUERY_ARG_LIMIT, 200)
                    putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.SIZE))
                    putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                }
                val cols = arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_EXPIRES
                )
                try {
                    contentResolver.query(uri, cols, q, null)?.use { c ->
                        while (c.moveToNext()) {
                            val id = c.getLong(0)
                            var t = ""
                            try {
                                val bmp = contentResolver.loadThumbnail(ContentUris.withAppendedId(uri, id), Size(96, 96), null)
                                val bo = ByteArrayOutputStream()
                                bmp.compress(Bitmap.CompressFormat.JPEG, 60, bo)
                                t = Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
                            } catch (_: Exception) {
                            }
                            out.put(
                                JSONObject()
                                    .put("id", id)
                                    .put("n", c.getString(1) ?: "")
                                    .put("s", c.getLong(2))
                                    .put("e", c.getLong(3) * 1000)
                                    .put("k", kind)
                                    .put("t", t)
                            )
                        }
                    }
                } catch (_: Exception) {
                }
            }
            return out.toString()
        }

        @JavascriptInterface
        fun hasAudio(): Boolean {
            val p = if (Build.VERSION.SDK_INT >= 33) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        }

        @JavascriptInterface
        fun hasNotif(): Boolean =
            Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        @JavascriptInterface
        fun hasAllFiles(): Boolean = Tools.hasAllFiles()

        @JavascriptInterface
        fun openAllFiles() {
            go(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        @JavascriptInterface
        fun storageBreakdown(): String = Tools.storageBreakdown(this@MainActivity).toString()

        @JavascriptInterface
        fun timeline(): String = Tools.timeline(this@MainActivity)

        @JavascriptInterface
        fun msgGroups(): String = Tools.msgGroups(this@MainActivity)

        @JavascriptInterface
        fun msgItems(app: String, type: String, limit: Int): String =
            Tools.msgItems(this@MainActivity, app, type, limit)

        @JavascriptInterface
        fun junkStart() = Tools.junkStart()

        @JavascriptInterface
        fun junkStatus(): String = Tools.junkStatus()

        @JavascriptInterface
        fun junkDelete(cat: String): String = Tools.junkDelete(this@MainActivity, cat)

        @JavascriptInterface
        fun shareText(t: String) {
            go(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, t)
                    }, null
                )
            )
        }

        @JavascriptInterface
        fun schedule(on: Boolean, h: Int, m: Int, d: Int, note: Boolean) {
            getSharedPreferences("s", 0).edit()
                .putBoolean("on", on)
                .putInt("h", h)
                .putInt("m", m)
                .putInt("d", d)
                .putBoolean("n", note)
                .apply()
            Cleaner.schedule(this@MainActivity, false)
        }

        @JavascriptInterface
        fun setHourly(hourlyOn: Boolean, hourlyCache: Boolean) {
            getSharedPreferences("s", 0).edit()
                .putBoolean("hourly_on", hourlyOn)
                .putBoolean("hourly_cache", hourlyCache)
                .apply()
            Cleaner.scheduleHourly(this@MainActivity, forceReset = true)
        }

        @JavascriptInterface
        fun getSch(): String {
            val p = getSharedPreferences("s", 0)
            return JSONObject()
                .put("on", p.getBoolean("on", false))
                .put("h", p.getInt("h", 3))
                .put("m", p.getInt("m", 0))
                .put("d", p.getInt("d", 1))
                .put("n", p.getBoolean("n", true))
                .put("hourlyOn", p.getBoolean("hourly_on", true))
                .put("hourlyCache", p.getBoolean("hourly_cache", true))
                .toString()
        }
    }
}
