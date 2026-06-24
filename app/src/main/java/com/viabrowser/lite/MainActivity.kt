package com.viabrowser.lite

import android.Manifest
import android.app.DownloadManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.viabrowser.lite.databinding.ActivityMainBinding
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class BookmarkItem(val title: String, val url: String, var icon: Bitmap? = null)

data class TabInfo(
    val id: Long,
    var title: String = "Yeni Sekme",
    var url: String? = null,
    var favicon: Bitmap? = null,
    var webViewState: Bundle? = null
)

data class SitePermission(val host: String, val type: String, var decision: String)

data class DownloadRecord(val fileName: String, val url: String, val timestamp: Long)

data class HistoryEntry(val title: String, val url: String, val timestamp: Long)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val bookmarks = mutableListOf<BookmarkItem>()

    private val tabs = mutableListOf<TabInfo>()
    private var currentTabIndex = 0
    private var nextTabId = 1L

    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var pendingGeoPermissionCallback: GeolocationPermissions.Callback? = null
    private var pendingGeoPermissionOrigin: String? = null

    companion object {
        private const val REQUEST_CODE_CAMERA_MIC = 1001
        private const val REQUEST_CODE_LOCATION = 1002
    }

    private val longPressHandler = Handler(Looper.getMainLooper())

    private var scrollAnchorY = 0
    private var lastScrollDirection = 0

    private val adBlockHosts: MutableSet<String> by lazy { loadAdBlockHosts() }

    private fun loadAdBlockHosts(): MutableSet<String> {
        val set = HashSet<String>()
        try {
            assets.open("adblock_hosts.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        set.add(trimmed.lowercase())
                    }
                }
            }
        } catch (e: Exception) {
            // liste okunamazsa boş set ile devam et, tarayıcı yine çalışır
        }
        return set
    }

    private fun isAdBlockEnabled(): Boolean {
        return getSharedPreferences("via_lite_prefs", MODE_PRIVATE).getBoolean("ad_block_enabled", true)
    }

    private fun setAdBlockEnabled(enabled: Boolean) {
        getSharedPreferences("via_lite_prefs", MODE_PRIVATE).edit().putBoolean("ad_block_enabled", enabled).apply()
    }

    private fun isHostBlocked(host: String): Boolean {
        if (host.isEmpty()) return false
        if (adBlockHosts.contains(host)) return true
        // Alt domainler için üst domainleri de kontrol et (örn. pubads.g.doubleclick.net -> doubleclick.net)
        var idx = host.indexOf('.')
        while (idx != -1) {
            val parent = host.substring(idx + 1)
            if (adBlockHosts.contains(parent)) return true
            idx = host.indexOf('.', idx + 1)
        }
        return false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebView()
        setupControls()

        loadBookmarks()
        refreshBookmarksGrid()

        tabs.add(TabInfo(id = nextTabId++))
        currentTabIndex = 0
        restoreCurrentTab()
    }

    override fun onResume() {
        super.onResume()
        applyAppearanceSettings()
        applyCookieSettings()
        consumePendingCacheClear()
        consumePendingAppearanceReload()
    }

    private fun consumePendingAppearanceReload() {
        val prefs = getSharedPreferences("via_lite_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("pending_appearance_reload", false)) {
            prefs.edit().putBoolean("pending_appearance_reload", false).apply()
            if (!binding.webView.url.isNullOrBlank() && binding.webView.url != "about:blank") {
                binding.webView.reload()
            }
        }
    }

    private fun consumePendingCacheClear() {
        val prefs = getSharedPreferences("via_lite_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("pending_clear_cache", false)) {
            binding.webView.clearCache(true)
            binding.webView.clearHistory()
            prefs.edit().putBoolean("pending_clear_cache", false).apply()
        }
    }

    private fun applyCookieSettings() {
        val blockThirdParty = getSharedPreferences("via_lite_prefs", MODE_PRIVATE)
            .getBoolean("block_third_party_cookies", false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, !blockThirdParty)
    }

    // ---- Dosya indirme ----

    private fun startFileDownload(url: String, userAgent: String, contentDisposition: String, mimeType: String) {
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                addRequestHeader("User-Agent", userAgent)
                setTitle(fileName)
                setDescription("İndiriliyor...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)
            addDownloadRecord(fileName, url)
            Toast.makeText(this, "İndiriliyor: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "İndirme başlatılamadı", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadDownloads(): MutableList<DownloadRecord> {
        val raw = getSharedPreferences("via_lite_prefs", MODE_PRIVATE).getString("downloads", "") ?: ""
        if (raw.isBlank()) return mutableListOf()
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split("::")
            if (parts.size == 3) DownloadRecord(parts[0], parts[1], parts[2].toLongOrNull() ?: 0L) else null
        }.toMutableList()
    }

    private fun saveDownloads(list: List<DownloadRecord>) {
        val raw = list.joinToString("\n") { "${it.fileName}::${it.url}::${it.timestamp}" }
        getSharedPreferences("via_lite_prefs", MODE_PRIVATE).edit().putString("downloads", raw).apply()
    }

    private fun addDownloadRecord(fileName: String, url: String) {
        val list = loadDownloads()
        list.add(0, DownloadRecord(fileName, url, System.currentTimeMillis()))
        saveDownloads(list)
    }

    private fun formatDownloadTimestamp(ts: Long): String {
        val fmt = java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale("tr"))
        return fmt.format(java.util.Date(ts))
    }

    private fun showDownloadsList() {
        val list = loadDownloads()
        val dialog = BottomSheetDialog(this)
        val scrollView = android.widget.ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(16), dp(8), dp(16))
        }
        scrollView.addView(container)

        if (list.isEmpty()) {
            container.addView(
                TextView(this).apply {
                    text = "Henüz indirilen dosya yok"
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                    setTextColor(0xFF8E8E93.toInt())
                }
            )
        } else {
            list.forEach { item ->
                container.addView(buildDownloadRow(item))
                container.addView(
                    View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                        setBackgroundColor(0xFFEEEEEE.toInt())
                    }
                )
            }
        }

        dialog.setContentView(scrollView)
        dialog.show()
    }

    private fun buildDownloadRow(item: DownloadRecord): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        row.addView(
            TextView(this).apply {
                text = item.fileName
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(0xFF1A1A1A.toInt())
            }
        )
        row.addView(
            TextView(this).apply {
                text = formatDownloadTimestamp(item.timestamp)
                textSize = 12f
                setTextColor(0xFF8E8E93.toInt())
                setPadding(0, dp(2), 0, 0)
            }
        )
        return row
    }

    // ---- Geçmiş ----

    private fun loadHistory(): MutableList<HistoryEntry> {
        val raw = getSharedPreferences("via_lite_prefs", MODE_PRIVATE).getString("history", "") ?: ""
        if (raw.isBlank()) return mutableListOf()
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split("::")
            if (parts.size == 3) HistoryEntry(parts[0], parts[1], parts[2].toLongOrNull() ?: 0L) else null
        }.toMutableList()
    }

    private fun saveHistory(list: List<HistoryEntry>) {
        val raw = list.joinToString("\n") { "${it.title}::${it.url}::${it.timestamp}" }
        getSharedPreferences("via_lite_prefs", MODE_PRIVATE).edit().putString("history", raw).apply()
    }

    private fun addHistoryEntry(title: String, url: String) {
        val list = loadHistory()
        val now = System.currentTimeMillis()
        // Yönlendirme zincirleri veya tekrarlı tetiklenmeler aynı gezinmeyi
        // birden fazla kayıt olarak eklemesin diye, son kayıt çok yakın zamanda
        // eklenmişse onu güncelliyoruz, yeni satır eklemiyoruz.
        if (list.isNotEmpty() && (now - list[0].timestamp) < 3000) {
            list[0] = HistoryEntry(title, url, now)
        } else {
            list.add(0, HistoryEntry(title, url, now))
        }
        // Listeyi gereksiz büyümesin diye en fazla 200 kayıtla sınırlıyoruz.
        while (list.size > 200) {
            list.removeAt(list.size - 1)
        }
        saveHistory(list)
    }

    private fun clearHistory() {
        getSharedPreferences("via_lite_prefs", MODE_PRIVATE).edit().remove("history").apply()
    }

    private fun showHistoryList() {
        val list = loadHistory()
        val dialog = BottomSheetDialog(this)
        val rootColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val clearRow = TextView(this).apply {
            text = "Geçmişi Temizle"
            textSize = 14f
            setTextColor(0xFFD32F2F.toInt())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                clearHistory()
                dialog.dismiss()
                Toast.makeText(this@MainActivity, "Geçmiş temizlendi", Toast.LENGTH_SHORT).show()
            }
        }

        val scrollView = android.widget.ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(16))
        }
        scrollView.addView(container)

        if (list.isEmpty()) {
            container.addView(
                TextView(this).apply {
                    text = "Geçmiş boş"
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                    setTextColor(0xFF8E8E93.toInt())
                }
            )
        } else {
            list.forEach { entry ->
                container.addView(buildHistoryRow(entry, dialog))
            }
        }

        if (list.isNotEmpty()) {
            rootColumn.addView(clearRow)
            rootColumn.addView(
                View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                    setBackgroundColor(0xFFEEEEEE.toInt())
                }
            )
        }
        rootColumn.addView(scrollView)

        dialog.setContentView(rootColumn)
        dialog.show()
    }

    private fun buildHistoryRow(entry: HistoryEntry, dialog: BottomSheetDialog): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dialog.dismiss()
                showBrowser()
                binding.webView.loadUrl(entry.url)
            }
        }
        row.addView(
            TextView(this).apply {
                text = entry.title
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(0xFF1A1A1A.toInt())
            }
        )
        row.addView(
            TextView(this).apply {
                text = formatDownloadTimestamp(entry.timestamp)
                textSize = 12f
                setTextColor(0xFF8E8E93.toInt())
                setPadding(0, dp(2), 0, 0)
            }
        )
        return row
    }

    // ---- Site izinleri (kamera / mikrofon / konum) ----

    private fun resourceToType(resource: String): String? = when (resource) {
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "camera"
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "microphone"
        else -> null
    }

    private fun androidPermissionsFor(type: String): Array<String> = when (type) {
        "camera" -> arrayOf(Manifest.permission.CAMERA)
        "microphone" -> arrayOf(Manifest.permission.RECORD_AUDIO)
        "location" -> arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        else -> emptyArray()
    }

    private fun hasAndroidPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun permissionDisplayName(type: String): String = when (type) {
        "camera" -> "kamera"
        "microphone" -> "mikrofon"
        "location" -> "konum"
        else -> type
    }

    private fun showSitePermissionDialog(host: String, types: List<String>, request: PermissionRequest) {
        val typeNames = types.joinToString(" ve ") { permissionDisplayName(it) }
        AlertDialog.Builder(this)
            .setTitle("İzin İsteği")
            .setMessage("$host, $typeNames erişimi istiyor. İzin verilsin mi?")
            .setPositiveButton("İzin Ver") { _, _ ->
                types.forEach { setSitePermissionDecision(host, it, "allow") }
                resolveWebPermissionRequest(host, request)
            }
            .setNegativeButton("Reddet") { _, _ ->
                types.forEach { setSitePermissionDecision(host, it, "deny") }
                request.deny()
            }
            .setCancelable(false)
            .show()
    }

    private fun resolveWebPermissionRequest(host: String, request: PermissionRequest) {
        val granted = mutableListOf<String>()
        for (resource in request.resources) {
            val type = resourceToType(resource) ?: continue
            val decision = getSitePermissionDecision(host, type)
            if (decision == "allow") {
                val androidPerms = androidPermissionsFor(type)
                if (androidPerms.all { hasAndroidPermission(it) }) {
                    granted.add(resource)
                } else {
                    pendingWebPermissionRequest = request
                    ActivityCompat.requestPermissions(this, androidPerms, REQUEST_CODE_CAMERA_MIC)
                    return
                }
            }
        }
        if (granted.isNotEmpty()) {
            request.grant(granted.toTypedArray())
        } else {
            request.deny()
        }
    }

    private fun resolveGeoPermission(origin: String, callback: GeolocationPermissions.Callback) {
        val host = Uri.parse(origin).host ?: origin
        val decision = getSitePermissionDecision(host, "location")
        if (decision == "allow") {
            val androidPerms = androidPermissionsFor("location")
            if (androidPerms.any { hasAndroidPermission(it) }) {
                callback.invoke(origin, true, false)
            } else {
                pendingGeoPermissionCallback = callback
                pendingGeoPermissionOrigin = origin
                ActivityCompat.requestPermissions(this, androidPerms, REQUEST_CODE_LOCATION)
            }
        } else {
            callback.invoke(origin, false, false)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CODE_CAMERA_MIC -> {
                val request = pendingWebPermissionRequest
                pendingWebPermissionRequest = null
                if (request != null) {
                    val host = Uri.parse(request.origin.toString()).host ?: request.origin.toString()
                    resolveWebPermissionRequest(host, request)
                }
            }
            REQUEST_CODE_LOCATION -> {
                val callback = pendingGeoPermissionCallback
                val origin = pendingGeoPermissionOrigin
                pendingGeoPermissionCallback = null
                pendingGeoPermissionOrigin = null
                if (callback != null && origin != null) {
                    resolveGeoPermission(origin, callback)
                }
            }
        }
    }

    private fun loadSitePermissions(): MutableList<SitePermission> {
        val raw = getSharedPreferences("via_lite_prefs", MODE_PRIVATE).getString("site_permissions", "") ?: ""
        if (raw.isBlank()) return mutableListOf()
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split("::")
            if (parts.size == 3) SitePermission(parts[0], parts[1], parts[2]) else null
        }.toMutableList()
    }

    private fun saveSitePermissions(list: List<SitePermission>) {
        val raw = list.joinToString("\n") { "${it.host}::${it.type}::${it.decision}" }
        getSharedPreferences("via_lite_prefs", MODE_PRIVATE).edit().putString("site_permissions", raw).apply()
    }

    private fun getSitePermissionDecision(host: String, type: String): String? {
        return loadSitePermissions().find { it.host == host && it.type == type }?.decision
    }

    private fun setSitePermissionDecision(host: String, type: String, decision: String) {
        val list = loadSitePermissions()
        val existing = list.find { it.host == host && it.type == type }
        if (existing != null) {
            existing.decision = decision
        } else {
            list.add(SitePermission(host, type, decision))
        }
        saveSitePermissions(list)
    }

    private fun applyAppearanceSettings() {
        val prefs = getSharedPreferences("via_lite_prefs", MODE_PRIVATE)

        binding.webView.settings.textZoom = prefs.getInt("text_zoom", 100)

        val forceDark = prefs.getBoolean("force_dark_web", false)

        // FORCE_DARK: eski ama uygulamanın kendi temasından bağımsız, doğrudan zorlayan API.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(
                binding.webView.settings,
                if (forceDark) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF
            )
        }

        // ALGORITHMIC_DARKENING: daha yeni API, FORCE_DARK ile birlikte ayarlanması zararsız.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(binding.webView.settings, forceDark)
        }
    }

    private fun setupWebView() {
        val webView = binding.webView

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setGeolocationEnabled(true)
        }

        applyAppearanceSettings()
        applyCookieSettings()

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startFileDownload(url, userAgent, contentDisposition, mimeType)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                if (isAdBlockEnabled()) {
                    val host = request.url.host?.lowercase() ?: ""
                    if (isHostBlocked(host)) {
                        return WebResourceResponse(
                            "text/plain",
                            "utf-8",
                            ByteArrayInputStream(ByteArray(0))
                        )
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                currentTab().url = url
                if (!binding.editUrl.hasFocus()) {
                    binding.editUrl.setText(view.title?.takeIf { it.isNotBlank() } ?: url)
                }
                addHistoryEntry(view.title?.takeIf { it.isNotBlank() } ?: url, url)
                binding.swipeRefresh.isRefreshing = false
                scrollAnchorY = 0
                lastScrollDirection = 0
                showBars()
                applyThemeColorFromPage()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                binding.progressBar.progress = newProgress
                binding.progressBar.visibility =
                    if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onReceivedIcon(view: WebView, icon: Bitmap) {
                super.onReceivedIcon(view, icon)
                currentTab().favicon = icon
                val url = view.url
                if (url != null) {
                    val item = bookmarks.find { it.url == url }
                    if (item != null && item.icon == null) {
                        item.icon = icon
                        cacheFavicon(url, icon)
                        refreshBookmarksGrid()
                    }
                }
            }

            override fun onReceivedTitle(view: WebView, title: String) {
                super.onReceivedTitle(view, title)
                if (title.isNotBlank()) {
                    currentTab().title = title
                }
                if (!binding.editUrl.hasFocus() && title.isNotBlank()) {
                    binding.editUrl.setText(title)
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val host = Uri.parse(request.origin.toString()).host ?: request.origin.toString()
                    val types = request.resources.mapNotNull { resourceToType(it) }
                    if (types.isEmpty()) {
                        request.deny()
                        return@runOnUiThread
                    }
                    val undecided = types.filter { getSitePermissionDecision(host, it) == null }
                    if (undecided.isNotEmpty()) {
                        showSitePermissionDialog(host, undecided, request)
                    } else {
                        resolveWebPermissionRequest(host, request)
                    }
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                runOnUiThread {
                    val host = Uri.parse(origin).host ?: origin
                    val decision = getSitePermissionDecision(host, "location")
                    if (decision == null) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Konum İzni")
                            .setMessage("$host, konumunuza erişmek istiyor. İzin verilsin mi?")
                            .setPositiveButton("İzin Ver") { _, _ ->
                                setSitePermissionDecision(host, "location", "allow")
                                resolveGeoPermission(origin, callback)
                            }
                            .setNegativeButton("Reddet") { _, _ ->
                                setSitePermissionDecision(host, "location", "deny")
                                callback.invoke(origin, false, false)
                            }
                            .setCancelable(false)
                            .show()
                    } else {
                        resolveGeoPermission(origin, callback)
                    }
                }
            }
        }

        binding.swipeRefresh.setOnRefreshListener {
            binding.webView.reload()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            webView.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                val rawDelta = scrollY - oldScrollY
                if (kotlin.math.abs(rawDelta) >= dp(2)) {
                    val direction = if (rawDelta > 0) 1 else -1
                    if (direction != lastScrollDirection) {
                        // Yön gerçekten değişti (ufak titreşim değil), takibi buradan başlat.
                        scrollAnchorY = oldScrollY
                        lastScrollDirection = direction
                    }
                }

                val threshold = resources.displayMetrics.heightPixels / 3
                val cumulativeDelta = scrollY - scrollAnchorY

                if (cumulativeDelta > threshold) {
                    hideBars()
                    scrollAnchorY = scrollY
                } else if (cumulativeDelta < -threshold) {
                    showBars()
                    scrollAnchorY = scrollY
                }
            }
        }
    }

    private fun hideBars() {
        if (binding.topToolbar.translationY == 0f) {
            binding.topToolbar.animate().translationY(-binding.topToolbar.height.toFloat()).start()
        }
        if (binding.bottomNavBar.translationY == 0f) {
            binding.bottomNavBar.animate().translationY(binding.bottomNavBar.height.toFloat()).start()
        }
    }

    private fun showBars() {
        if (binding.topToolbar.translationY != 0f) {
            binding.topToolbar.animate().translationY(0f).start()
        }
        if (binding.bottomNavBar.translationY != 0f) {
            binding.bottomNavBar.animate().translationY(0f).start()
        }
    }

    private fun applyThemeColorFromPage() {
        val js = "(function(){" +
            "var nodes=document.querySelectorAll('meta[name=\"theme-color\"]');" +
            "if(!nodes||nodes.length===0)return '';" +
            "for(var i=0;i<nodes.length;i++){" +
            "if(!nodes[i].getAttribute('media'))return nodes[i].getAttribute('content')||'';" +
            "}" +
            "return nodes[0].getAttribute('content')||'';" +
            "})();"
        binding.webView.evaluateJavascript(js) { result ->
            val raw = result?.trim('"')?.takeIf { it.isNotBlank() && it != "null" }
            val color = raw?.let { parseCssColor(it) } ?: Color.WHITE
            binding.topToolbar.setBackgroundColor(color)
            binding.bottomNavBar.setBackgroundColor(color)
        }
    }

    private fun parseCssColor(value: String): Int? {
        return try {
            if (value.startsWith("rgb")) {
                val nums = Regex("\\d+").findAll(value).map { it.value.toInt() }.toList()
                if (nums.size >= 3) Color.rgb(nums[0], nums[1], nums[2]) else null
            } else {
                Color.parseColor(value)
            }
        } catch (e: Exception) {
            null
        }
    }

    private var suppressUrlFocusRevert = false

    private fun setupControls() {
        binding.btnMenu.setOnClickListener { anchor ->
            showBrowserMenu(anchor)
        }
        binding.editUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                suppressUrlFocusRevert = true
                loadFromInput()
                binding.editUrl.clearFocus()
                hideKeyboard(binding.editUrl)
                true
            } else {
                false
            }
        }
        binding.editUrl.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                suppressUrlFocusRevert = false
                val currentUrl = binding.webView.url
                if (!currentUrl.isNullOrBlank()) {
                    binding.editUrl.setText(currentUrl)
                }
                binding.editUrl.post { binding.editUrl.selectAll() }
            } else {
                if (!suppressUrlFocusRevert) {
                    val title = binding.webView.title
                    if (!title.isNullOrBlank()) {
                        binding.editUrl.setText(title)
                    }
                }
                suppressUrlFocusRevert = false
            }
        }

        // Alt bar
        binding.btnBottomBack.setOnClickListener {
            if (binding.webView.canGoBack()) {
                showBrowser()
                binding.webView.goBack()
            }
        }
        binding.btnBottomForward.setOnClickListener {
            if (binding.webView.canGoForward()) {
                showBrowser()
                binding.webView.goForward()
            }
        }
        binding.btnHome.setOnClickListener {
            val customUrl = getCustomStartUrlIfEnabled()
            if (!customUrl.isNullOrBlank()) {
                showBrowser()
                binding.webView.loadUrl(customUrl)
            } else {
                showHomeScreen()
            }
        }
        binding.btnTabs.setOnClickListener {
            showTabSwitcher()
        }
        binding.btnBottomMenu.setOnClickListener { anchor ->
            showBottomMenu(anchor)
        }

        // Açılış ekranı arama kutusu
        binding.homeSearchBox.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                val input = binding.homeSearchBox.text.toString().trim()
                if (input.isNotEmpty()) {
                    val url = resolveUrl(input)
                    showBrowser()
                    binding.webView.loadUrl(url)
                }
                binding.homeSearchBox.clearFocus()
                hideKeyboard(binding.homeSearchBox)
                true
            } else {
                false
            }
        }
    }

    private fun showBrowserMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.browser_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.menu_add_to_home) {
                addCurrentPageToHome()
                true
            } else {
                false
            }
        }
        popup.show()
    }

    private fun showBottomMenu(anchor: View) {
        val dialog = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(24), dp(8), dp(32))
        }

        val adBlockOn = isAdBlockEnabled()
        container.addView(
            buildFunctionMenuCard(
                iconRes = R.drawable.ic_block,
                label = "Reklam Engelleme",
                statusText = if (adBlockOn) "Açık" else "Kapalı",
                isActive = adBlockOn
            ) {
                val newState = !isAdBlockEnabled()
                setAdBlockEnabled(newState)
                dialog.dismiss()
                Toast.makeText(
                    this,
                    if (newState) "Reklam engelleme açıldı" else "Reklam engelleme kapatıldı",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )

        container.addView(
            buildFunctionMenuCard(
                iconRes = R.drawable.ic_settings,
                label = "Ayarlar",
                statusText = null,
                isActive = false
            ) {
                dialog.dismiss()
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        )

        container.addView(
            buildFunctionMenuCard(
                iconRes = R.drawable.ic_download,
                label = "İndirilenler",
                statusText = null,
                isActive = false
            ) {
                dialog.dismiss()
                showDownloadsList()
            }
        )

        container.addView(
            buildFunctionMenuCard(
                iconRes = R.drawable.ic_history,
                label = "Geçmiş",
                statusText = null,
                isActive = false
            ) {
                dialog.dismiss()
                showHistoryList()
            }
        )

        container.addView(
            buildFunctionMenuCard(
                iconRes = R.drawable.ic_power,
                label = "Kapat",
                statusText = null,
                isActive = false
            ) {
                finishAffinity()
            }
        )

        dialog.setContentView(container)
        dialog.show()
    }

    private fun buildFunctionMenuCard(
        iconRes: Int,
        label: String,
        statusText: String?,
        isActive: Boolean,
        onClick: () -> Unit
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            isClickable = true
            isFocusable = true
            setPadding(dp(4), dp(8), dp(4), dp(8))
            setOnClickListener { onClick() }
        }

        val iconView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
            background = roundedSquareDrawable(if (isActive) 0xFFD32F2F.toInt() else 0xFFF1F0F5.toInt())
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setImageResource(iconRes)
            setColorFilter(if (isActive) Color.WHITE else 0xFF3C3C43.toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val labelView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFF1A1A1A.toInt())
        }

        card.addView(iconView)
        card.addView(labelView)

        if (statusText != null) {
            val statusView = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
                text = statusText
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(0xFF8E8E93.toInt())
            }
            card.addView(statusView)
        }

        return card
    }

    private fun roundedSquareDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(color)
        }
    }

    private fun addCurrentPageToHome() {
        val url = binding.webView.url
        if (url.isNullOrBlank()) {
            Toast.makeText(this, "Eklenecek bir sayfa yok", Toast.LENGTH_SHORT).show()
            return
        }
        val title = binding.webView.title?.takeIf { it.isNotBlank() }
            ?: url.removePrefix("https://").removePrefix("http://")

        // WebView'in kendi favicon'u varsa anlık önizleme olarak kullan,
        // gerçek/güvenilir ikon için her zaman ağdan da indirmeyi dene.
        val placeholderIcon = binding.webView.favicon
        bookmarks.add(BookmarkItem(title, url, placeholderIcon))
        saveBookmarksList()
        refreshBookmarksGrid()
        Toast.makeText(this, "Ana ekrana eklendi", Toast.LENGTH_SHORT).show()

        fetchFaviconAsync(url)
    }

    private fun showHomeScreen() {
        binding.browserRoot.visibility = View.GONE
        binding.homeContainer.visibility = View.VISIBLE
        binding.homeSearchBox.setText("")
    }

    private fun showBrowser() {
        binding.homeContainer.visibility = View.GONE
        binding.browserRoot.visibility = View.VISIBLE
    }

    // ---- Sekme yönetimi ----

    private fun currentTab(): TabInfo = tabs[currentTabIndex]

    private fun getCustomStartUrlIfEnabled(): String? {
        val prefs = getSharedPreferences("via_lite_prefs", MODE_PRIVATE)
        if (prefs.getString("home_mode", "default") != "custom") return null
        return prefs.getString("home_custom_url", null)
    }

    private fun updateTabCountBadge() {
        binding.tabCountText.text = tabs.size.toString()
    }

    private fun saveCurrentTabState() {
        val tab = currentTab()
        val bundle = Bundle()
        binding.webView.saveState(bundle)
        tab.webViewState = bundle
        tab.url = binding.webView.url
        val title = binding.webView.title
        if (!title.isNullOrBlank()) {
            tab.title = title
        }
    }

    private fun restoreCurrentTab() {
        val tab = currentTab()
        val state = tab.webViewState
        when {
            state != null -> {
                binding.webView.restoreState(state)
                showBrowser()
            }
            !tab.url.isNullOrBlank() -> {
                binding.webView.loadUrl(tab.url!!)
                showBrowser()
            }
            else -> {
                val customUrl = getCustomStartUrlIfEnabled()
                if (!customUrl.isNullOrBlank()) {
                    binding.webView.loadUrl(customUrl)
                    showBrowser()
                } else {
                    binding.webView.loadUrl("about:blank")
                    showHomeScreen()
                }
            }
        }
        if (tab.url != null) {
            binding.editUrl.setText(tab.title.takeIf { it.isNotBlank() } ?: tab.url)
        } else {
            binding.editUrl.setText("")
        }
        updateTabCountBadge()
    }

    private fun switchToTab(newIndex: Int) {
        if (newIndex !in tabs.indices || newIndex == currentTabIndex) return
        saveCurrentTabState()
        currentTabIndex = newIndex
        restoreCurrentTab()
    }

    private fun addNewTab() {
        saveCurrentTabState()
        tabs.add(TabInfo(id = nextTabId++))
        currentTabIndex = tabs.size - 1
        restoreCurrentTab()
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return

        if (tabs.size <= 1) {
            tabs[0] = TabInfo(id = nextTabId++)
            currentTabIndex = 0
            restoreCurrentTab()
            return
        }

        val wasCurrent = index == currentTabIndex
        tabs.removeAt(index)
        if (index < currentTabIndex) {
            currentTabIndex -= 1
        } else if (wasCurrent && currentTabIndex >= tabs.size) {
            currentTabIndex = tabs.size - 1
        }

        if (wasCurrent) {
            restoreCurrentTab()
        } else {
            updateTabCountBadge()
        }
    }

    private fun showTabSwitcher() {
        saveCurrentTabState()

        val dialog = BottomSheetDialog(this)
        val scrollView = android.widget.ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(16), dp(8), dp(16))
        }
        scrollView.addView(container)

        tabs.forEachIndexed { index, tab ->
            container.addView(buildTabRow(tab, index, dialog))
        }
        container.addView(buildAddTabRow(dialog))

        dialog.setContentView(scrollView)
        dialog.show()
    }

    private fun buildTabRow(tab: TabInfo, index: Int, dialog: BottomSheetDialog): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                switchToTab(index)
                dialog.dismiss()
            }
        }

        val iconView: View = if (tab.favicon != null) {
            ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = circleDrawable(Color.TRANSPARENT)
                clipToOutline = true
                setImageBitmap(tab.favicon)
            }
        } else {
            TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                gravity = Gravity.CENTER
                textSize = 14f
                setTextColor(Color.WHITE)
                background = circleDrawable(0xFF9E9E9E.toInt())
                text = tab.title.firstOrNull()?.uppercase() ?: "?"
            }
        }

        val titleView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { marginStart = dp(12) }
            text = tab.title
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(0xFF1A1A1A.toInt())
        }

        val closeView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setImageResource(R.drawable.ic_close)
            setColorFilter(0xFF8E8E93.toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener {
                closeTab(index)
                dialog.dismiss()
            }
        }

        row.addView(iconView)
        row.addView(titleView)
        row.addView(closeView)
        return row
    }

    private fun buildAddTabRow(dialog: BottomSheetDialog): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(12), dp(14))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                addNewTab()
                dialog.dismiss()
            }
        }

        val plusIcon = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            gravity = Gravity.CENTER
            textSize = 20f
            setTextColor(Color.WHITE)
            background = circleDrawable(0xFF1976D2.toInt())
            text = "+"
        }

        val label = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { marginStart = dp(12) }
            text = "Yeni Sekme"
            textSize = 15f
            setTextColor(0xFF1A1A1A.toInt())
        }

        row.addView(plusIcon)
        row.addView(label)
        return row
    }

    private fun resolveUrl(raw: String): String {
        val looksLikeUrl = raw.contains(".") && !raw.contains(" ")
        return when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            looksLikeUrl -> "https://$raw"
            else -> searchEngineUrlPrefix() + URLEncoder.encode(raw, "UTF-8")
        }
    }

    private fun searchEngineUrlPrefix(): String {
        val engine = getSharedPreferences("via_lite_prefs", MODE_PRIVATE).getString("search_engine", "google")
        return when (engine) {
            "bing" -> "https://www.bing.com/search?q="
            "duckduckgo" -> "https://duckduckgo.com/?q="
            else -> "https://www.google.com/search?q="
        }
    }

    private fun loadFromInput() {
        val input = binding.editUrl.text.toString().trim()
        if (input.isEmpty()) return
        binding.webView.loadUrl(resolveUrl(input))
    }

    private fun hideKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun circleDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    // ---- Yer imi depolama ----

    private fun loadBookmarks() {
        bookmarks.clear()
        val raw = getSharedPreferences("via_lite_prefs", MODE_PRIVATE).getString("bookmarks", "") ?: ""
        if (raw.isNotBlank()) {
            raw.split("\n").forEach { line ->
                val parts = line.split("::")
                if (parts.size == 2) {
                    val title = parts[0]
                    val url = parts[1]
                    bookmarks.add(BookmarkItem(title, url, loadCachedFavicon(url)))
                }
            }
        }
    }

    private fun saveBookmarksList() {
        val raw = bookmarks.joinToString("\n") { "${it.title}::${it.url}" }
        getSharedPreferences("via_lite_prefs", MODE_PRIVATE).edit().putString("bookmarks", raw).apply()
    }

    // ---- Favicon önbellekleme ----

    private fun faviconKey(url: String): String = "favicon::$url"

    private fun loadCachedFavicon(url: String): Bitmap? {
        val base64 = getSharedPreferences("via_lite_prefs", MODE_PRIVATE).getString(faviconKey(url), null)
            ?: return null
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    private fun cacheFavicon(url: String, bitmap: Bitmap) {
        try {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            val base64 = Base64.encodeToString(stream.toByteArray(), Base64.DEFAULT)
            getSharedPreferences("via_lite_prefs", MODE_PRIVATE).edit().putString(faviconKey(url), base64).apply()
        } catch (e: Exception) {
            // önbellekleme başarısız olursa sessizce yoksay
        }
    }

    private val maxHtmlChars = 100_000

    private fun fetchFaviconAsync(url: String) {
        Thread {
            // 1) Önce sitenin kendi HTML'sindeki apple-touch-icon / manifest ikonunu dene
            // (Opera/Chrome'un yaptığı gibi) — bunlar boşluksuz, yüksek çözünürlüklüdür.
            var bitmap: Bitmap? = null
            val pageIconUrl = findBestIconUrlFromPage(url)
            if (pageIconUrl != null) {
                bitmap = downloadBitmap(pageIconUrl)
            }

            // 2) Bulunamazsa veya çok küçükse eski favicon servislerine düş.
            if (bitmap == null || bitmap.width < 48 || bitmap.height < 48) {
                val host = Uri.parse(url).host
                if (host != null) {
                    val sources = listOf(
                        "https://www.google.com/s2/favicons?domain=$host&sz=128",
                        "https://favicon.yandex.net/favicon/$host",
                        "https://icons.duckduckgo.com/ip3/$host.ico"
                    )
                    for (source in sources) {
                        val result = downloadBitmap(source) ?: continue
                        if (bitmap == null) bitmap = result
                        if (result.width >= 48 && result.height >= 48) {
                            bitmap = result
                            break
                        }
                    }
                }
            }

            if (bitmap != null) {
                cacheFavicon(url, bitmap)
                runOnUiThread {
                    bookmarks.find { it.url == url }?.icon = bitmap
                    refreshBookmarksGrid()
                }
            }
        }.start()
    }

    // ---- Sayfanın kendi HTML'sinden yüksek çözünürlüklü ikon bulma ----

    private fun findBestIconUrlFromPage(pageUrl: String): String? {
        val html = fetchHtmlSnippet(pageUrl) ?: return null

        val linkTagRegex = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
        var bestUrl: String? = null
        var bestSize = 0
        var manifestUrl: String? = null

        for (match in linkTagRegex.findAll(html)) {
            val tag = match.value
            val rel = Regex("rel\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                .find(tag)?.groupValues?.get(1)?.lowercase() ?: continue
            val href = Regex("href\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                .find(tag)?.groupValues?.get(1) ?: continue

            when {
                rel.contains("apple-touch-icon") -> {
                    val sizesAttr = Regex("sizes\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                        .find(tag)?.groupValues?.get(1)
                    val size = parseSize(sizesAttr) ?: 180
                    if (size > bestSize) {
                        bestSize = size
                        bestUrl = toAbsoluteUrl(pageUrl, href)
                    }
                }
                rel == "manifest" -> {
                    manifestUrl = toAbsoluteUrl(pageUrl, href)
                }
            }
        }

        if (bestUrl != null) return bestUrl

        if (manifestUrl != null) {
            val manifestIcon = findBestIconFromManifest(manifestUrl)
            if (manifestIcon != null) return manifestIcon
        }

        return null
    }

    private fun parseSize(sizesAttr: String?): Int? {
        if (sizesAttr.isNullOrBlank()) return null
        val match = Regex("(\\d+)x(\\d+)", RegexOption.IGNORE_CASE).find(sizesAttr)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun toAbsoluteUrl(baseUrl: String, href: String): String? {
        return try {
            URL(URL(baseUrl), href).toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchHtmlSnippet(pageUrl: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(pageUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome Mobile Safari/537.36"
            )
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null

            val reader = connection.inputStream.bufferedReader(Charsets.UTF_8)
            val sb = StringBuilder()
            val buffer = CharArray(4096)
            var totalRead = 0
            while (totalRead < maxHtmlChars) {
                val read = reader.read(buffer)
                if (read == -1) break
                sb.append(buffer, 0, read)
                totalRead += read
                if (sb.contains("</head>", ignoreCase = true)) break
            }
            reader.close()
            if (sb.isEmpty()) null else sb.toString()
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun findBestIconFromManifest(manifestUrl: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(manifestUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null

            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val json = JSONObject(text)
            val icons = json.optJSONArray("icons") ?: return null

            var bestSrc: String? = null
            var bestSize = 0
            for (i in 0 until icons.length()) {
                val icon = icons.getJSONObject(i)
                val sizesAttr = icon.optString("sizes", "")
                val size = parseSize(sizesAttr) ?: 0
                val src = icon.optString("src", "")
                if (src.isNotBlank() && size > bestSize) {
                    bestSize = size
                    bestSrc = src
                }
            }
            bestSrc?.let { toAbsoluteUrl(manifestUrl, it) }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun downloadBitmap(urlString: String): Bitmap? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(urlString).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome Mobile Safari/537.36"
            )
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                null
            } else {
                BitmapFactory.decodeStream(connection.inputStream)
            }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    // ---- Ana ekran grid'i ----

    private fun refreshBookmarksGrid() {
        binding.bookmarksGrid.removeAllViews()
        bookmarks.forEach { item ->
            binding.bookmarksGrid.addView(buildBookmarkTile(item.title, item.url, item.icon, false))
        }
        binding.bookmarksGrid.addView(buildBookmarkTile("", null, null, true))
    }

    private fun buildBookmarkTile(title: String, url: String?, icon: Bitmap?, isAddTile: Boolean): View {
        val tileSize = resources.displayMetrics.widthPixels / 4
        val iconSize = dp(60)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(4), dp(12), dp(4), dp(12))
            layoutParams = GridLayout.LayoutParams().apply {
                width = tileSize
                height = LinearLayout.LayoutParams.WRAP_CONTENT
            }
        }

        val iconView: View = if (icon != null && !isAddTile) {
            ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = circleDrawable(Color.TRANSPARENT)
                clipToOutline = true
                setImageBitmap(icon)
            }
        } else {
            TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                gravity = Gravity.CENTER
                textSize = 22f
                setTextColor(Color.WHITE)
                background = circleDrawable(if (isAddTile) 0xFF1976D2.toInt() else 0xFF9E9E9E.toInt())
                text = if (isAddTile) "+" else (title.firstOrNull()?.uppercase() ?: "?")
            }
        }

        val labelView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            text = if (isAddTile) "Ekle" else title
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(0xFF424242.toInt())
        }

        container.addView(iconView)
        container.addView(labelView)

        container.setOnClickListener {
            if (isAddTile) {
                showAddBookmarkDialog()
            } else if (url != null) {
                showBrowser()
                binding.editUrl.setText(url)
                binding.webView.loadUrl(url)
            }
        }

        // ScrollView içinde standart uzun-basma algılayıcısı en ufak kaydırma
        // titremesinde iptal olabiliyor; bu yüzden kendi toleranslı algılayıcımızı kuruyoruz.
        if (!isAddTile && url != null) {
            var longPressFired = false
            var downX = 0f
            var downY = 0f
            val moveTolerance = dp(20)
            val longPressRunnable = Runnable {
                longPressFired = true
                showDeleteBookmarkDialog(title, url)
            }
            container.setOnTouchListener { touchedView, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        longPressFired = false
                        downX = event.x
                        downY = event.y
                        longPressHandler.postDelayed(longPressRunnable, 500)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (kotlin.math.abs(event.x - downX) > moveTolerance ||
                            kotlin.math.abs(event.y - downY) > moveTolerance
                        ) {
                            longPressHandler.removeCallbacks(longPressRunnable)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        longPressHandler.removeCallbacks(longPressRunnable)
                        if (!longPressFired) {
                            touchedView.performClick()
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        longPressHandler.removeCallbacks(longPressRunnable)
                    }
                }
                true
            }
        }

        return container
    }

    private fun showDeleteBookmarkDialog(title: String, url: String) {
        AlertDialog.Builder(this)
            .setTitle("Yer İmini Sil")
            .setMessage("\"$title\" silinsin mi?")
            .setPositiveButton("Sil") { _, _ ->
                bookmarks.removeAll { it.url == url }
                getSharedPreferences("via_lite_prefs", MODE_PRIVATE).edit().remove(faviconKey(url)).apply()
                saveBookmarksList()
                refreshBookmarksGrid()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun showAddBookmarkDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), 0)
        }
        val titleInput = EditText(this).apply { hint = "Başlık" }
        val urlInput = EditText(this).apply {
            hint = "URL"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        container.addView(titleInput)
        container.addView(urlInput)

        AlertDialog.Builder(this)
            .setTitle("Yer İmi Ekle")
            .setView(container)
            .setPositiveButton("Ekle") { _, _ ->
                var url = urlInput.text.toString().trim()
                var title = titleInput.text.toString().trim()
                if (url.isEmpty()) return@setPositiveButton
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://$url"
                }
                if (title.isEmpty()) {
                    title = url.removePrefix("https://").removePrefix("http://")
                }
                bookmarks.add(BookmarkItem(title, url, null))
                saveBookmarksList()
                refreshBookmarksGrid()
                fetchFaviconAsync(url)
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK &&
            binding.browserRoot.visibility == View.VISIBLE &&
            binding.webView.canGoBack()
        ) {
            binding.webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
