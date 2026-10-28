package com.firehome.launcher

import android.app.Activity
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.text.format.Formatter
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The System page: storage, memory, processor and network at a glance, the "Close background
 * apps" action, Streaming (the Dream Core Resolver: its debrid account and scraper sources,
 * [CoreChecks.dreamCore]), the
 * biggest apps (OK opens the app's info page) and, last, [WhatsInside].
 *
 * Numbers refresh when the page opens and every few seconds after that, but only while the
 * page is on screen (attached and its window visible). Every read happens off the main thread;
 * the screen only gets text/scale updates, and only for values that changed.
 *
 * App sizes including data and cache need usage access, which tools/deploy.sh grants with
 * `appops set com.firehome.launcher GET_USAGE_STATS allow`. Without it the list falls back
 * to the installed APK size.
 */
class SystemHealth(
    private val act: Activity,
    /** The launcher's standard button, so the actions match the rest of Dream OS. */
    private val button: (label: String, icon: Int, onClick: () -> Unit) -> TextView,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val text = Theme.get(act, Theme.TEXT)
    private val dim = Theme.get(act, Theme.TEXT_DIM)
    private val tile = Theme.get(act, Theme.TILE)
    private val select = Theme.get(act, Theme.SELECT)
    private val onSelect = Theme.contrastOn(select)
    private val corner = listOf(0, 10, 22)[Theme.get(act, Theme.CORNERS)]
    private val font = Theme.typeface(act)
    private val fontBold = Theme.typeface(act, Typeface.BOLD)

    private var attached = false
    private var windowVisible = false
    private var running = false
    private var closing = false

    val view: LinearLayout = object : LinearLayout(act) {
        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            attached = true
            windowVisible = windowVisibility == View.VISIBLE
            updateRunning()
        }

        override fun onDetachedFromWindow() {
            attached = false
            updateRunning()
            super.onDetachedFromWindow()
        }

        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            windowVisible = visibility == View.VISIBLE
            updateRunning()
        }
    }

    private val storage = Card("STORAGE", R.drawable.ic_apps)
    private val memory = Card("MEMORY", R.drawable.ic_bolt)
    private val cpu = Card("PROCESSOR", R.drawable.ic_system)
    private val network = Card("NETWORK", R.drawable.ic_wifi)
    private val status = label(13f, dim).apply {
        text = "Frees memory held by apps running in the background."
        maxLines = 2
        gravity = Gravity.CENTER_VERTICAL
    }
    private val appsNote = label(13f, dim).apply { gravity = Gravity.END }
    private val appsList = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
    }
    private val rows = LinkedHashMap<String, Row>()
    /** Dream Core ([CoreChecks.dreamCore]): plain facts, one row each. */
    private val streaming = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val checkRows = LinkedHashMap<String, CheckRow>()

    init {
        view.orientation = LinearLayout.VERTICAL
        view.clipChildren = false
        view.clipToPadding = false
        view.setPadding(PAD.dp, 0, PAD.dp, 0)

        view.addView(label(14f, dim).apply { text = deviceLine() })

        val cards = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(storage, memory, cpu, network).forEachIndexed { i, c ->
            cards.addView(c.view, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = (Ui.S4).dp })
        }
        // The four gauges rise in one after another when the page first opens.
        Ui.rise(listOf(storage, memory, cpu, network).map { it.view }, fromDp = 16, duration = Ui.OVERLAY_IN)
        view.addView(cards, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 18.dp })

        val actions = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        actions.addView(button("Close background apps", R.drawable.ic_bolt) { closeBackgroundApps() })
        actions.addView(button("Manage apps", R.drawable.ic_apps) { openFireSettings("tv.applications.ApplicationsActivity") })
        actions.addView(button("Network", R.drawable.ic_wifi) { openFireSettings("tv.network.NetworkActivity") })
        actions.addView(status, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = 6.dp })
        view.addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 22.dp })

        view.addView(sectionTitle("Streaming"), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 26.dp; bottomMargin = 8.dp })
        streaming.addView(label(13f, dim).apply { text = "Checking…"; gravity = Gravity.CENTER_VERTICAL; setPadding(12.dp, 0, 0, 0) },
            LinearLayout.LayoutParams(WRAP_CONTENT, 44.dp))
        view.addView(streaming, MATCH_PARENT, WRAP_CONTENT)

        val header = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(sectionTitle("Largest apps"), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(appsNote, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        view.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 26.dp; bottomMargin = 8.dp })
        appsNote.text = "Measuring…"
        view.addView(appsList, MATCH_PARENT, WRAP_CONTENT)
        // The About card closes the page (WhatsInside.kt); app rows fill in above it.
        view.addView(WhatsInside(act).view, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 30.dp })
    }

    // ---- refresh loop: only while the page is actually visible ----

    private val tick = Runnable { refresh(full = false) }

    private fun updateRunning() {
        val want = attached && windowVisible
        if (want == running) return
        running = want
        handler.removeCallbacks(tick)
        if (want) refresh(full = true)
    }

    /** Light stats every tick; [full] also re-measures app sizes (on open / return to the page). */
    private fun refresh(full: Boolean) {
        exec.execute {
            val snap = try { snapshot() } catch (e: Exception) { Log.w(TAG, "snapshot failed", e); null }
            handler.post {
                if (!running) return@post
                snap?.let { render(it) }
                handler.removeCallbacks(tick)
                handler.postDelayed(tick, REFRESH_MS)
            }
            if (!full) return@execute
            checkStreaming()
            val apps = try { biggestApps() } catch (e: Exception) { Log.w(TAG, "app sizes failed", e); null }
            handler.post { if (attached) showApps(apps) }
        }
    }

    // ---- reading the system (background thread) ----

    private class Snap(
        val dataTotal: Long, val dataFree: Long,
        val memTotal: Long, val memAvail: Long, val lowMemory: Boolean,
        val cores: Int, val curKhz: Long?, val maxKhz: Long?, val tempC: Float?, val uptimeMs: Long,
        val netName: String, val netDetail: String, val netExtra: String, val signal: Float,
    )

    private fun snapshot(): Snap {
        val fs = StatFs(Environment.getDataDirectory().path)
        val mem = memInfo()
        val cores = Runtime.getRuntime().availableProcessors()
        val cur = (0 until cores).mapNotNull { readLong("/sys/devices/system/cpu/cpu$it/cpufreq/scaling_cur_freq") }.maxOrNull()
        if (maxKhz == null) maxKhz = readLong("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq")
        val net = readNetwork()
        return Snap(
            fs.totalBytes, fs.availableBytes,
            mem.totalMem, mem.availMem, mem.lowMemory,
            cores, cur, maxKhz, temperature(), SystemClock.elapsedRealtime(),
            net.name, net.detail, net.extra, net.signal,
        )
    }

    private fun memInfo() = ActivityManager.MemoryInfo().also {
        (act.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
    }

    /** [signal] is the bar fraction (0 = no bar). */
    private class Net(val name: String, val detail: String, val extra: String, val signal: Float)

    @Suppress("DEPRECATION")
    private fun readNetwork(): Net {
        val ip = PhoneRemote.localIp()
        val caps = try {
            val cm = act.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.getNetworkCapabilities(cm.activeNetwork)
        } catch (e: Exception) {
            null // no ACCESS_NETWORK_STATE yet (older install)
        }
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) {
            return Net("Ethernet", "Wired connection", if (ip != null) "IP $ip" else "", 1f)
        }
        val info = try {
            (act.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
        } catch (e: Exception) {
            null
        }
        if (info == null || info.networkId == -1 && info.ipAddress == 0) {
            return Net(if (ip != null) "Connected" else "Offline", if (ip != null) "Network details unavailable" else "Not connected to Wi-Fi",
                if (ip != null) "IP $ip" else "Check Network settings", 0f)
        }
        val ssid = info.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } ?: "Wi-Fi"
        val level = WifiManager.calculateSignalLevel(info.rssi, 5)
        val quality = listOf("Very weak", "Weak", "Fair", "Good", "Excellent")[level]
        val band = if (info.frequency > 4900) "5 GHz" else "2.4 GHz"
        val detail = listOfNotNull(quality, info.linkSpeed.takeIf { it > 0 }?.let { "$it Mbps" }, band).joinToString("  ·  ")
        val extra = listOfNotNull(ip?.let { "IP $it" }, "${info.rssi} dBm").joinToString("  ·  ")
        return Net(ssid, detail, extra, (level + 1) / 5f)
    }

    /** Hottest readable thermal zone in °C; on Fire OS these are locked to apps, so usually null. */
    private fun temperature(): Float? {
        if (tempUnreadable) return null
        val zones = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }.orEmpty()
        val t = zones.mapNotNull { readLong("${it.path}/temp") }.filter { it > 0 }.maxOrNull()
        if (t == null) {
            tempUnreadable = true // SELinux won't change while we run; stop trying
            return null
        }
        return if (t > 1000) t / 1000f else t.toFloat()
    }

    private fun readLong(path: String): Long? = try {
        File(path).readText().trim().toLong()
    } catch (e: Exception) {
        null
    }

    private class AppSize(val pkg: String, val label: String, val icon: Drawable?, val bytes: Long)

    private var fullSizes = false

    /** Apps people see (launchable or user-installed), largest first. */
    private fun biggestApps(): List<AppSize> {
        val pm = act.packageManager
        val apps = candidateApps()
        var ssm: StorageStatsManager? = null
        if (Build.VERSION.SDK_INT >= 26 && hasUsageAccess()) ssm = act.getSystemService(StorageStatsManager::class.java)
        val sized = apps.map { ai ->
            var bytes = -1L
            val m = ssm
            if (m != null && Build.VERSION.SDK_INT >= 26) {
                try {
                    val st = m.queryStatsForPackage(ai.storageUuid, ai.packageName, Process.myUserHandle())
                    bytes = st.appBytes + st.dataBytes + st.cacheBytes
                } catch (e: SecurityException) {
                    ssm = null // access was revoked; APK sizes from here on
                } catch (e: Exception) {
                }
            }
            ai to if (bytes >= 0) bytes else apkBytes(ai)
        }
        fullSizes = ssm != null
        return sized.filter { it.second > 0 }.sortedByDescending { it.second }.take(TOP_APPS).map { (ai, bytes) ->
            AppSize(ai.packageName, ai.loadLabel(pm).toString(), try { ai.loadIcon(pm) } catch (e: Exception) { null }, bytes)
        }
    }

    private fun candidateApps(): List<ApplicationInfo> {
        val pm = act.packageManager
        val launchable = HashSet<String>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0).forEach { launchable += it.activityInfo.packageName }
        }
        return pm.getInstalledApplications(0).filter {
            it.packageName != act.packageName && (it.packageName in launchable ||
                it.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != ApplicationInfo.FLAG_SYSTEM)
        }
    }

    /** Installed APK size; only APKs on /data count (stock system apps don't use the user's space). */
    private fun apkBytes(ai: ApplicationInfo): Long {
        val dirs = listOfNotNull(ai.sourceDir) + ai.splitSourceDirs.orEmpty()
        return dirs.filter { it.startsWith("/data/") }.sumOf { File(it).length() }
    }

    @Suppress("DEPRECATION")
    private fun hasUsageAccess(): Boolean = try {
        val ops = act.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), act.packageName) == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        false
    }

    // ---- showing it (main thread) ----

    private fun render(s: Snap) {
        val usedData = s.dataTotal - s.dataFree
        val dataFrac = if (s.dataTotal > 0) usedData.toFloat() / s.dataTotal else 0f
        storage.set(size(s.dataFree), "free", dataFrac, levelColor(dataFrac, 0.8f, 0.9f),
            "${size(usedData)} of ${size(s.dataTotal)} used",
            if (dataFrac > 0.9f) "Almost full — remove unused apps" else "${(dataFrac * 100).toInt()}% used",
            if (dataFrac > 0.9f) BAD else dim)

        val usedMem = s.memTotal - s.memAvail
        val memFrac = if (s.memTotal > 0) usedMem.toFloat() / s.memTotal else 0f
        memory.set(size(s.memAvail), "free", memFrac, if (s.lowMemory) BAD else levelColor(memFrac, 0.85f, 2f),
            "${size(usedMem)} of ${size(s.memTotal)} used",
            if (s.lowMemory) "Low memory — close background apps" else "Memory OK",
            if (s.lowMemory) BAD else GOOD)

        val cur = s.curKhz
        val max = s.maxKhz
        val freqFrac = if (cur != null && max != null && max > 0) cur.toFloat() / max else 0f
        cpu.set(if (cur != null) ghz(cur) else "${s.cores}", if (cur != null) "GHz" else "cores", freqFrac, select,
            listOfNotNull("${s.cores} cores", max?.let { "up to ${ghz(it)} GHz" }).joinToString("  ·  "),
            listOfNotNull(s.tempC?.let { "${it.toInt()}°C" }, "Up ${uptime(s.uptimeMs)}").joinToString("  ·  "),
            if ((s.tempC ?: 0f) >= 80f) BAD else dim)

        network.set(s.netName, "", s.signal, levelColor(1f - s.signal, 0.55f, 0.75f), s.netDetail, s.netExtra, dim)
    }

    private fun showApps(list: List<AppSize>?) {
        if (list == null) {
            appsNote.text = "Couldn't measure apps"
            return
        }
        appsNote.text = if (fullSizes) "App, data & cache" else "App size only  ·  allow usage access for data & cache"
        val max = list.maxOfOrNull { it.bytes }?.coerceAtLeast(1L) ?: 1L
        if (list.map { it.pkg } == rows.keys.toList()) {
            for (a in list) rows[a.pkg]?.set(a.bytes, a.bytes.toFloat() / max)
            return
        }
        // Rebuilding the rows would drop focus; put it back on the same app afterwards.
        val focusedPkg = rows.entries.firstOrNull { it.value.view.hasFocus() }?.key
        appsList.removeAllViews()
        rows.clear()
        if (list.isEmpty()) {
            appsList.addView(label(15f, dim).apply { text = "No apps to show." })
            return
        }
        list.forEachIndexed { i, a ->
            val row = Row(a)
            rows[a.pkg] = row
            row.set(a.bytes, a.bytes.toFloat() / max)
            appsList.addView(row.view)
            // A short, one-time stagger as the list arrives.
            row.view.alpha = 0f
            row.view.translationY = 10.dp.toFloat()
            row.view.animate().alpha(1f).translationY(0f).setStartDelay(i * Ui.STAGGER).setDuration(320)
                .setInterpolator(SmoothScrollView.EASE).withLayer().start()
        }
        focusedPkg?.let { rows[it]?.view?.requestFocus() }
    }

    /** Network calls (the debrid account): their own pool, so the gauges never wait on them. */
    private fun checkStreaming() {
        val app = act.applicationContext
        com.firehome.launcher.Net.background({ CoreChecks.dreamCore(app) }) { list ->
            if (attached && list != null) showStreaming(list)
        }
    }

    private fun showStreaming(list: List<CoreChecks.Check>) {
        if (list.map { it.key } != checkRows.keys.toList()) {
            streaming.removeAllViews()
            checkRows.clear()
            // Dream Core's row opens Settings › Accounts, the Scrapers row Settings › Scrapers.
            for (c in list) CheckRow(when (c.key) { CoreChecks.DREAM_CORE -> ({ DreamSettingsPage.open(act, DreamSettingsPage.ACCOUNTS) }); CoreChecks.SCRAPERS -> ({ DreamSettingsPage.open(act, DreamSettingsPage.SCRAPERS) }); else -> null })
                .also { checkRows[c.key] = it; streaming.addView(it.view) }
        }
        for (c in list) checkRows[c.key]?.set(c)
    }

    // ---- actions ----

    private fun closeBackgroundApps() {
        if (closing) return
        closing = true
        status.text = "Closing background apps…"
        exec.execute {
            val am = act.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val before = memInfo().availMem
            val keep = setOf(act.packageName, AMAZON_LAUNCHER, currentIme())
            var count = 0
            var denied = false
            for (ai in candidateApps()) {
                if (ai.packageName in keep) continue
                try {
                    am.killBackgroundProcesses(ai.packageName)
                    count++
                } catch (e: SecurityException) {
                    denied = true // missing KILL_BACKGROUND_PROCESSES (older install)
                    break
                }
            }
            SystemClock.sleep(1200) // let the memory actually come back before measuring
            val freed = memInfo().availMem - before
            Log.w(TAG, "closed background apps: $count checked, freed ${freed / 1048576} MB")
            handler.post {
                closing = false
                status.text = when {
                    denied -> "Not allowed yet — update Dream OS to enable this."
                    freed > 4L * 1048576 -> "Freed ${size(freed)} of memory."
                    else -> "All clear — nothing was running in the background."
                }
                if (running) refresh(full = false)
            }
        }
    }

    private fun currentIme(): String =
        Settings.Secure.getString(act.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/').orEmpty()

    private fun openAppInfo(pkg: String) {
        try {
            act.startActivity(
                // CLEAR_TASK: otherwise Fire TV may resurface a stale page for a different app.
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        } catch (e: Exception) {
            Toast.makeText(act, "Couldn't open app info", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFireSettings(activity: String) {
        try {
            act.startActivity(Intent().setClassName(FIRE_SETTINGS, "$FIRE_SETTINGS.$activity"))
        } catch (e: Exception) {
            Toast.makeText(act, "Couldn't open settings", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- views ----

    /** One stat tile: label, big value, a bar that glides to its level, and two lines of detail. */
    private inner class Card(title: String, icon: Int) {
        val value = label(26f, text, bold = true).apply {
            typeface = Ui.semibold(font)
            letterSpacing = Ui.TRACK_TITLE
        }
        val unit = label(13f, dim)
        val detail = label(12f, dim)
        val extra = label(12f, dim)
        val fill = View(act).apply {
            background = GradientDrawable().apply { cornerRadius = 3.dp.toFloat(); setColor(select) }
            pivotX = 0f
            scaleX = 0f
        }
        private var fillColor = select
        private var frac = -1f
        val view = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            // Quiet glass with a hairline edge; the corner setting still applies.
            setPadding(Ui.S4.dp, Ui.S4.dp, Ui.S4.dp, Ui.S4.dp)
            background = Ui.rounded(act, Ui.surface(act, 0.86f), corner, 1f, Ui.alpha(text, 0.08f))
            addView(LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(act).apply {
                    setImageResource(icon)
                    setColorFilter(dim)
                }, LinearLayout.LayoutParams(14.dp, 14.dp).apply { marginEnd = 7.dp })
                addView(label(11f, dim, bold = true).apply { text = title; letterSpacing = Ui.TRACK_CAPS })
            })
            addView(LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                addView(value, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = 6.dp })
                addView(unit, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = 3.dp })
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8.dp })
            addView(FrameLayout(act).apply {
                background = GradientDrawable().apply { cornerRadius = 3.dp.toFloat(); setColor(Ui.alpha(text, 0.09f)) }
                // Rounded track clips the scaled fill so its end stays round as it grows.
                clipToOutline = true
                addView(fill, MATCH_PARENT, MATCH_PARENT)
            }, LinearLayout.LayoutParams(MATCH_PARENT, 6.dp).apply { topMargin = 12.dp; bottomMargin = 12.dp })
            addView(detail)
            addView(extra, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 3.dp })
        }

        init {
            value.text = "—"
            detail.text = " "
            extra.text = " "
        }

        fun set(v: String, u: String, f: Float, color: Int, d: String, e: String, eColor: Int) {
            setText(value, v)
            setText(unit, u)
            setText(detail, d)
            setText(extra, e)
            if (extra.currentTextColor != eColor) extra.setTextColor(eColor)
            if (color != fillColor) {
                fillColor = color
                (fill.background as GradientDrawable).setColor(color)
            }
            val target = f.coerceIn(0f, 1f)
            if (abs(target - frac) > 0.004f) {
                frac = target
                fill.animate().scaleX(target).setDuration(700).setInterpolator(SmoothScrollView.EASE).start()
            }
        }
    }

    /** A focusable app row; OK opens the app's settings page. */
    private inner class Row(app: AppSize) {
        private val fg = states(onSelect, text)
        private val sizeText = label(14f, text, bold = true).apply {
            setTextColor(fg)
            gravity = Gravity.END
            isDuplicateParentStateEnabled = true
        }
        private val fill = View(act).apply {
            background = GradientDrawable().apply { cornerRadius = 2.dp.toFloat(); color = states(onSelect, select) }
            isDuplicateParentStateEnabled = true
            pivotX = 0f
            scaleX = 0f
        }
        private var frac = -1f
        val view = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            tag = "sys|${app.pkg}"
            setPadding(12.dp, 0, 16.dp, 0)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    cornerRadius = corner.dp.toFloat()
                    setColor(select)
                })
                addState(IntArray(0), ColorDrawable(Color.TRANSPARENT))
            }
            addView(ImageView(act).apply {
                setImageDrawable(app.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(34.dp, 34.dp).apply { marginEnd = 14.dp })
            addView(label(15f, text).apply {
                text = app.label
                setTextColor(fg)
                isDuplicateParentStateEnabled = true
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(FrameLayout(act).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 2.dp.toFloat()
                    color = states(Ui.alpha(onSelect, 0.3f), Ui.alpha(text, 0.12f))
                }
                isDuplicateParentStateEnabled = true
                addView(fill, MATCH_PARENT, MATCH_PARENT)
            }, LinearLayout.LayoutParams(150.dp, 5.dp).apply { marginStart = 16.dp })
            addView(sizeText, LinearLayout.LayoutParams(84.dp, WRAP_CONTENT))
            // Only shows on the focused row (text color swap, so no layout change).
            addView(label(12f, text).apply {
                text = "App info ›"
                gravity = Gravity.END
                setTextColor(states(Ui.alpha(onSelect, 0.8f), Color.TRANSPARENT))
                isDuplicateParentStateEnabled = true
            }, LinearLayout.LayoutParams(78.dp, WRAP_CONTENT))
            setOnClickListener { openAppInfo(app.pkg) }
            setOnFocusChangeListener { v, has -> Ui.lift(v, has, Ui.LIFT_ROW) }
            cinemaRowFocus(this, corner) // Dream Cinema: pearl face under the rainbow border
        }

        init {
            view.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 54.dp).apply { bottomMargin = 4.dp }
        }

        fun set(bytes: Long, f: Float) {
            setText(sizeText, size(bytes))
            val target = f.coerceIn(0.02f, 1f)
            if (abs(target - frac) > 0.004f) {
                frac = target
                fill.animate().scaleX(target).setDuration(700).setInterpolator(SmoothScrollView.EASE).start()
            }
        }
    }

    /**
     * One check as a quiet fact: a status dot (green when good, the theme's accent when it's simply a
     * state, amber only for what needs the user), the name, and its state on the right. Not focusable,
     * unless [onClick] is set (Dream Core's row opens Streaming Accounts): then it's a row with a chevron
     * and the usual focus (Dream Cinema's pearl face and rainbow border).
     */
    private inner class CheckRow(onClick: (() -> Unit)? = null) {
        private val dot = View(act)
        private val title = label(15f, text)
        private val state = label(13f, dim).apply { gravity = Gravity.END }
        val view = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp, 0, 16.dp, 0)
            addView(dot, LinearLayout.LayoutParams(8.dp, 8.dp).apply { marginEnd = 14.dp })
            addView(title, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = 24.dp })
            addView(state, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 44.dp)
        }

        init {
            if (onClick != null) {
                view.addView(label(20f, Ui.alpha(text, 0.4f)).apply { this.text = "›" },
                    LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = 12.dp })
                view.isFocusable = true
                view.isClickable = true
                view.setOnClickListener { onClick() }
                view.setOnFocusChangeListener { v, has -> Ui.lift(v, has, Ui.LIFT_ROW) }
                cinemaRowFocus(view, Ui.R_MD, Ui.alpha(text, 0.045f))
                inkOnPearl(view)
            }
        }

        fun set(c: CoreChecks.Check) {
            setText(title, c.title)
            setText(state, c.detail)
            state.maxLines = if (c.state == CoreChecks.State.FIX) 2 else 1
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(when (c.state) { CoreChecks.State.OK -> GOOD; CoreChecks.State.FIX -> WARN; CoreChecks.State.INFO -> Ui.alpha(select, 0.85f) })
            }
        }
    }

    private fun sectionTitle(s: String) = label(18f, text, bold = true).apply {
        text = s
        typeface = Ui.semibold(font)
        letterSpacing = Ui.TRACK_TITLE
    }

    private fun label(sp: Float, color: Int, bold: Boolean = false) = TextView(act).apply {
        textSize = sp
        typeface = if (bold) fontBold else font
        setTextColor(color)
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    /** Skips identical text so periodic refreshes don't trigger layouts. */
    private fun setText(v: TextView, s: String) {
        if (v.text.toString() != s) v.text = s
    }

    private fun states(focused: Int, normal: Int) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), IntArray(0)), intArrayOf(focused, normal),
    )

    private fun levelColor(f: Float, warn: Float, bad: Float) = when {
        f >= bad -> BAD
        f >= warn -> WARN
        else -> select
    }

    /** "940 MB" below 1 GB, "1.4 GB" above (Android's formatter jumps to "0.94 GB" past 900 MB, which reads as less). */
    private fun size(bytes: Long): String {
        val b = maxOf(0L, bytes)
        val gb = 1024L * 1024L * 1024L
        return if (b < gb) "${b / (1024L * 1024L)} MB" else String.format("%.1f GB", b / gb.toFloat())
    }

    private fun ghz(khz: Long) = String.format("%.2f", khz / 1_000_000f)

    private fun uptime(ms: Long): String {
        val min = ms / 60_000
        val h = min / 60
        val d = h / 24
        return when {
            d > 0 -> "${d} d ${h % 24} h"
            h > 0 -> "${h} h ${min % 60} min"
            else -> "${min} min"
        }
    }

    private fun deviceLine(): String {
        val model = if (Build.MODEL == "AFTKA") "Fire TV Stick 4K Max" else Build.MODEL
        val os = try {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, "ro.build.version.name") as? String
        } catch (e: Exception) {
            null
        }?.takeIf { it.isNotBlank() }?.substringBefore(" (") ?: "Android ${Build.VERSION.RELEASE}"
        return listOfNotNull(model, os, Build.HARDWARE.takeIf { it.isNotBlank() }?.uppercase()).joinToString("  ·  ")
    }

    private fun abs(f: Float) = kotlin.math.abs(f)

    private val Int.dp get() = (this * act.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "FireHome"
        private const val PAD = 28
        private const val REFRESH_MS = 3000L
        private const val TOP_APPS = 10
        private const val FIRE_SETTINGS = "com.amazon.tv.settings.v2"
        private const val AMAZON_LAUNCHER = "com.amazon.tv.launcher"
        private val GOOD = Color.parseColor("#4ADE80")
        private val WARN = Color.parseColor("#FBBF24")
        private val BAD = Color.parseColor("#F87171")

        /** One shared worker: reads are short and must never overlap each other. */
        private val exec = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>()).apply {
            allowCoreThreadTimeOut(true) // exits when the page is closed instead of idling forever
        }
        @Volatile private var tempUnreadable = false
        @Volatile private var maxKhz: Long? = null
    }
}
