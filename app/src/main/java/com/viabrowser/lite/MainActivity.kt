package com.viabrowser.lite

import android.Manifest
import android.app.DownloadManager
import android.content.ClipData
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Base64
import android.util.Log
import android.view.DragEvent
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.viabrowser.lite.databinding.ActivityMainBinding
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.BufferedReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class BookmarkItem(var title: String, var url: String, var icon: Bitmap? = null)

data class SitePermission(val host: String, val type: String, var decision: String)

data class DownloadRecord(val fileName: String, val url: String, val timestamp: Long)

data class HistoryEntry(val title: String, val url: String, val timestamp: Long)

data class SavedAddress(
    val id: Long,
    var label: String,
    var fullName: String,
    var email: String,
    var phone: String,
    var address: String
)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val bookmarks = mutableListOf<BookmarkItem>()

    private val tabManager = TabManager()
    private val tabs: MutableList<TabInfo> get() = tabManager.tabs
    private var currentTabIndex: Int
        get() = tabManager.currentTabIndex
        set(value) { tabManager.currentTabIndex = value }

    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var pendingGeoPermissionCallback: GeolocationPermissions.Callback? = null
    private var pendingGeoPermissionOrigin: String? = null

    companion object {
        private const val REQUEST_CODE_CAMERA_MIC = 1001
        private const val REQUEST_CODE_LOCATION = 1002
        private const val BOOKMARK_MIME = "application/x-via-bookmark"
        private const val BOOKMARK_DRAG_LABEL = "via_bookmark"
        private const val ADBLOCK_REMOTE_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
        private const val ADBLOCK_CACHE_FILE = "adblock_hosts_remote.txt"
        private const val ADBLOCK_UPDATE_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000 // 7 gÃ¼n
        private const val ADBLOCK_MIN_VALID_ENTRIES = 1000
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    private val longPressHandler = Handler(Looper.getMainLooper())
    private var defaultUserAgent: String? = null

    private val adBlockHosts: MutableSet<String> by lazy { loadAdBlockHosts() }

    private fun loadAdBlockHosts(): MutableSet<String> {
        val cacheFile = File(filesDir, ADBLOCK_CACHE_FILE)
        if (cacheFile.exists()) {
            val fromCache = parseSimpleHostsList { cacheFile.bufferedReader(Charsets.UTF_8) }
            if (fromCache.isNotEmpty()) return fromCache
        }
        return parseSimpleHostsList { assets.open("adblock_hosts.txt").bufferedReader(Charsets.UTF_8) }
    }

    private fun parseSimpleHostsList(openReader: () -> BufferedReader): MutableSet<String> {
        val set = HashSet<String>()
        try {
            openReader().useLines { lines ->
                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                        set.add(trimmed.lowercase())
                    }
                }
            }
        } catch (e: Exception) {
            // liste okunamazsa boÅŸ set dÃ¶ner, Ã§aÄŸÄ±ran taraf yedek listeye dÃ¼ÅŸer
        }
        return set
    }

    // ---- Reklam engelleme listesi: periyodik uzaktan gÃ¼ncelleme ----

    private fun maybeUpdateAdBlockList() {
        val prefs = getSharedPreferences("via_lite_prefs", MODE_PRIVATE)
        val lastUpdate = prefs.getLong("adblock_last_update", 0L)
        val now = System.currentTimeMillis()
        if (now - lastUpdate < ADBLOCK_UPDATE_INTERVAL_MS) return

        Thread {
            val downloaded = downloadAdBlockList()
            if (downloaded != null) {
                try {
                    File(filesDir, ADBLOCK_CACHE_FILE).writeText(downloaded.joinToString("\n"), Charsets.UTF_8)
                    prefs.edit().putLong("adblock_last_update", now).apply()
                    runOnUiThread {
                        adBlockHosts.clear()
                        adBlockHosts.addAll(downloaded)
                    }
                } catch (e: Exception) {
                    // yazma baÅŸarÄ±sÄ±z olursa mevcut liste ile devam edilir
                }
            }
        }.start()
    }

    private fun downloadAdBlockList(): Set<String>? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(ADBLOCK_REMOTE_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 15000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome Mobile Safari/537.36"
            )
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null

            val result = HashSet<String>()
            val whitespace = Regex("\\s+")
            connection.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { rawLine ->
                    val line = rawLine.trim()
                    if (line.isEmpty() || line.startsWith("#")) return@forEach
                    val parts = line.split(whitespace)
                    if (parts.size < 2) return@forEach
                    val ip = parts[0]
                    val domain = parts[1].lowercase()
                    if ((ip == "0.0.0.0" || ip == "127.0.0.1") &&
                        domain.isNotEmpty() &&
                        domain != "localhost" &&
                        domain != "localhost.localdomain" &&
                        domain != "local" &&
                        domain != "broadcasthost" &&
                        !domain.startsWith("ip6-") &&
                        !domain.startsWith("ff0")
                    ) {
                        result.add(domain)
                    }
                }
            }
            // Beklenmedik ÅŸekilde kÄ±sa/bozuk bir indirme mevcut listeyi bozmasÄ±n
            if (result.size < ADBLOCK_MIN_VALID_ENTRIES) null else result
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
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
        // Alt domainler iÃ§in Ã¼st domainleri de kontrol et (Ã¶rn. pubads.g.doubleclick.net -> doubleclick.net)
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
        setupKeyboardAvoidance()

        loadBookmarks()
        refreshBookmarksGrid()
        maybeUpdateAdBlockList()

        tabs.add(TabInfo(id = tabManager.nextId()))
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

    override fun onDestroy() {
        // ArtÄ±k her sekme kendi WebView'ine sahip olduÄŸundan, Activity
        // kapanÄ±rken hepsini aÃ§Ä±kÃ§a yok etmemiz gerekiyor -- aksi halde
        // her biri native kaynaklarÄ±nÄ± (render sÃ¼reci baÄŸlantÄ±sÄ± vb.)
        // tutmaya devam edip bellek sÄ±zÄ±ntÄ±sÄ±na yol aÃ§abilir.
        tabs.forEach { tab ->
            tab.webView?.let {
                (it.parent as? ViewGroup)?.removeView(it)
                it.destroy()
            }
        }
        super.onDestroy()
    }

    private fun consumePendingAppearanceReload() {
        val prefs = getSharedPreferences("via_lite_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("pending_appearance_reload", false)) {
            prefs.edit().putBoolean("pending_appearance_reload", false).apply()
            val webView = currentWebView()
            if (!webView.url.isNullOrBlank() && webView.url != "about:blank") {
                webView.reload()
            }
        }
    }

    private fun consumePendingCacheClear() {
        val prefs = getSharedPreferences("via_lite_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("pending_clear_cache", false)) {
            tabs.forEach { tab ->
                tab.webView?.let {
                    it.clearCache(true)
                    it.clearHistory()
                }
            }
            prefs.edit().putBoolean("pending_clear_cache", false).apply()
        }
    }

    private fun applyCookieSettings() {
        val blockThirdParty = getSharedPreferences("via_lite_prefs", MODE_PRIVATE)
            .getBoolean("block_third_party_cookies", false)
        tabs.forEach { tab ->
            tab.webView?.let { CookieManager.getInstance().setAcceptThirdPartyCookies(it, !blockThirdParty) }
        }
    }

    // ---- Dosya indirme ----

    private fun startFileDownload(url: String, userAgent: String, contentDisposition: String, mimeType: String) {
        val fileName = try {
            URLUtil.guessFileName(url, contentDisposition, mimeType)
        } catch (e: Exception) {
            "dosya"
        }

        val prefs = getSharedPreferences("via_lite_prefs", MODE_PRIVATE)
        val askBeforeDownload = prefs.getBoolean("ask_before_download", false)

        if (askBeforeDownload) {
            AlertDialog.Builder(this)
                .setTitle("DosyayÄ± Ä°ndir")
                .setMessage("\"$fileName\" indirilsin mi?")
                .setPositiveButton("Ä°ndir") { _, _ ->
                    enqueueDownload(url, userAgent, mimeType, fileName)
                }
                .setNegativeButton("VazgeÃ§", null)
                .show()
        } else {
            enqueueDownload(url, userAgent, mimeType, fileName)
        }
    }

    private fun enqueueDownload(url: String, userAgent: String, mimeType: String, fileName: String) {
        try {
            val prefs = getSharedPreferences("via_lite_prefs", MODE_PRIVATE)
            val showNotifications = prefs.getBoolean("download_notifications", true)

            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                addRequestHeader("User-Agent", userAgent)
                setTitle(fileName)
                setDescription("Ä°ndiriliyor...")
                setNotificationVisibility(
                    if (showNotifications) {
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    } else {
                        DownloadManager.Request.VISIBILITY_HIDDEN
                    }
                )
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)
            addDownloadRecord(fileName, url)
            Toast.makeText(this, "Ä°ndiriliyor: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ä°ndirme baÅŸlatÄ±lamadÄ±", Toast.LENGTH_SHORT).show()
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
                    text = "HenÃ¼z indirilen dosya yok"
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

    // ---- Otomatik doldurma (adresler) ----

    private fun loadSavedAddresses(): MutableList<SavedAddress> {
        val raw = getSharedPreferences("via_lite_prefs", MODE_PRIVATE).getString("saved_addresses", "") ?: ""
        if (raw.isBlank()) return mutableListOf()
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split("::")
            if (parts.size == 6) {
                SavedAddress(
                    id = parts[0].toLongOrNull() ?: 0L,
                    label = parts[1],
                    fullName = parts[2],
                    email = parts[3],
                    phone = parts[4],
                    address = parts[5]
                )
            } else null
        }.toMutableList()
    }

    private fun saveSavedAddresses(list: List<SavedAddress>) {
        val raw = list.joinToString("\n") {
            "${it.id}::${it.label}::${it.fullName}::${it.email}::${it.phone}::${it.address}"
        }
        getSharedPreferences("via_lite_prefs", MODE_PRIVATE).edit().putString("saved_addresses", raw).apply()
    }

    private fun showAddressFillList() {
        val list = loadSavedAddresses()
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
                    text = "KayÄ±tlÄ± adres yok. Ayarlar > Otomatik Doldurma'dan ekleyebilirsin."
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                    setTextColor(0xFF8E8E93.toInt())
                }
            )
        } else {
            list.forEach { addr ->
                container.addView(buildAddressFillRow(addr, dialog))
            }
        }

        dialog.setContentView(scrollView)
        dialog.show()
    }

    private fun buildAddressFillRow(addr: SavedAddress, dialog: BottomSheetDialog): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dialog.dismiss()
                fillAddressIntoPage(addr)
            }
        }
        row.addView(
            TextView(this).apply {
                text = addr.label
                textSize = 15f
                setTextColor(0xFF1A1A1A.toInt())
            }
        )
        row.addView(
            TextView(this).apply {
                text = addr.fullName
                textSize = 12f
                setTextColor(0xFF8E8E93.toInt())
                setPadding(0, dp(2), 0, 0)
            }
        )
        return row
    }

    private fun fillAddressIntoPage(addr: SavedAddress) {
        val json = org.json.JSONObject().apply {
            put("fullName", addr.fullName)
            put("email", addr.email)
            put("phone", addr.phone)
            put("address", addr.address)
        }
        val js = """
            (function(data){
                function setVal(el, val) {
                    if (el && val) {
                        el.value = val;
                        el.dispatchEvent(new Event('input', {bubbles:true}));
                        el.dispatchEvent(new Event('change', {bubbles:true}));
                    }
                }
                function find(selectors) {
                    for (var i = 0; i < selectors.length; i++) {
                        var el = document.querySelector(selectors[i]);
                        if (el) return el;
                    }
                    return null;
                }
                setVal(find(['input[autocomplete="name"]','input[name*="name" i]','input[id*="name" i]']), data.fullName);
                setVal(find(['input[type="email"]','input[autocomplete="email"]','input[name*="email" i]','input[id*="email" i]']), data.email);
                setVal(find(['input[type="tel"]','input[autocomplete="tel"]','input[name*="phone" i]','input[id*="phone" i]']), data.phone);
                setVal(find(['textarea[name*="address" i]','input[autocomplete="street-address"]','input[name*="address" i]','input[id*="address" i]']), data.address);
            })($json);
        """.trimIndent()
        currentWebView().evaluateJavascript(js, null)
        Toast.makeText(this, "Adres dolduruldu", Toast.LENGTH_SHORT).show()
    }

    // ---- GeÃ§miÅŸ ----

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
        // YÃ¶nlendirme zincirleri veya tekrarlÄ± tetiklenmeler aynÄ± gezinmeyi
        // birden fazla kayÄ±t olarak eklemesin diye, son kayÄ±t Ã§ok yakÄ±n zamanda
        // eklenmiÅŸse onu gÃ¼ncelliyoruz, yeni satÄ±r eklemiyoruz.
        if (list.isNotEmpty() && (now - list[0].timestamp) < 3000) {
            list[0] = HistoryEntry(title, url, now)
        } else {
            list.add(0, HistoryEntry(title, url, now))
        }
        // Listeyi gereksiz bÃ¼yÃ¼mesin diye en fazla 200 kayÄ±tla sÄ±nÄ±rlÄ±yoruz.
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
            text = "GeÃ§miÅŸi Temizle"
            textSize = 14f
            setTextColor(0xFFD32F2F.toInt())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                clearHistory()
                dialog.dismiss()
                Toast.makeText(this@MainActivity, "GeÃ§miÅŸ temizlendi", Toast.LENGTH_SHORT).show()
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
                    text = "GeÃ§miÅŸ boÅŸ"
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
                currentWebView().loadUrl(entry.url)
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
            .setTitle("Ä°zin Ä°steÄŸi")
            .setMessage("$host, $typeNames eriÅŸimi istiyor. Ä°zin verilsin mi?")
            .setPositiveButton("Ä°zin Ver") { _, _ ->
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
        currentWebView().settings.textZoom = effectiveTextZoomFor(currentHost())
        // Web sayfalarÄ±nÄ±n karartÄ±lmasÄ± artÄ±k CSS injection ile yapÄ±lÄ±yor
        // (bkz. applyForceDarkIfNeeded), uygulamanÄ±n kendi temasÄ± bundan etkilenmiyor.
    }

    // ---- Web sayfalarÄ±nÄ± karartma (sadece iÃ§erik, uygulama arayÃ¼zÃ¼ etkilenmez) ----

    private fun isForceDarkWebEnabled(): Boolean {
        return getSharedPreferences("via_lite_prefs", MODE_PRIVATE).getBoolean("force_dark_web", false)
    }

    private fun applyForceDarkIfNeeded(view: WebView) {
        if (isForceDarkWebEnabled()) {
            val js = "(function(){" +
                "var id='via-force-dark-style';" +
                "var existing=document.getElementById(id);" +
                "if(existing)existing.remove();" +
                "var style=document.createElement('style');" +
                "style.id=id;" +
                "style.textContent='html{filter:invert(1) hue-rotate(180deg) !important;background:#fff !important;}' +" +
                "'img,video,picture,canvas,svg,iframe{filter:invert(1) hue-rotate(180deg) !important;}';" +
                "document.documentElement.appendChild(style);" +
                "})();"
            view.evaluateJavascript(js, null)
        } else {
            val js = "(function(){" +
                "var existing=document.getElementById('via-force-dark-style');" +
                "if(existing)existing.remove();" +
                "})();"
            view.evaluateJavascript(js, null)
        }
    }

    // ---- Site-Ã¶zel yazÄ± boyutu ----

    private fun currentHost(): String? = currentWebView().url?.let { Uri.parse(it).host }

    private fun effectiveTextZoomFor(host: String?): Int {
        val prefs = getSharedPreferences("via_lite_prefs", MODE_PRIVATE)
        if (host != null) {
            val siteZoom = getSiteTextZoom(host)
            if (siteZoom != null) return siteZoom
        }
        return prefs.getInt("text_zoom", 100)
    }

    private fun getSiteTextZoom(host: String): Int? {
        val raw = getSharedPreferences("via_lite_prefs", MODE_PRIVATE).getString("site_text_zoom", "") ?: ""
        if (raw.isBlank()) return null
        raw.split("\n").forEach { line ->
            val parts = line.split("::")
            if (parts.size == 2 && parts[0] == host) {
                return parts[1].toIntOrNull()
            }
        }
        return null
    }

    private fun setSiteTextZoom(host: String, zoom: Int) {
        val prefs = getSharedPreferences("via_lite_prefs", MODE_PRIVATE)
        val raw = prefs.getString("site_text_zoom", "") ?: ""
        val lines = if (raw.isBlank()) mutableListOf() else raw.split("\n").toMutableList()
        val filtered = lines.filterNot { it.split("::").getOrNull(0) == host }.toMutableList()
        filtered.add("$host::$zoom")
        prefs.edit().putString("site_text_zoom", filtered.joinToString("\n")).apply()
    }

    private fun showSiteTextZoomDialog() {
        val host = currentHost()
        if (host.isNullOrBlank()) {
            Toast.makeText(this, "YazÄ± boyutu ayarlanacak bir sayfa yok", Toast.LENGTH_SHORT).show()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }

        val currentZoom = effectiveTextZoomFor(host)

        val previewBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(0xFFF5F5F5.toInt())
                cornerRadius = dp(8).toFloat()
            }
        }

        val previewText = TextView(this).apply {
            text = "Ã–rnek metin bÃ¶yle gÃ¶rÃ¼necek."
            setTextColor(0xFF1A1A1A.toInt())
            textSize = 16f * (currentZoom / 100f)
        }
        previewBox.addView(previewText)

        val hostLabel = TextView(this).apply {
            text = host
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFF8E8E93.toInt())
            setPadding(0, 0, 0, dp(4))
        }

        val valueLabel = TextView(this).apply {
            text = "%$currentZoom"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFF8E8E93.toInt())
            setPadding(0, dp(12), 0, dp(4))
        }

        val seekBar = android.widget.SeekBar(this).apply {
            max = 150 // 50 ile 200 arasÄ±, +50 ekleyerek gerÃ§ek yÃ¼zdeye Ã§eviriyoruz
            progress = (currentZoom - 50).coerceIn(0, 150)
        }
        seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                val zoom = progress + 50
                valueLabel.text = "%$zoom"
                previewText.textSize = 16f * (zoom / 100f)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) {}
        })

        container.addView(hostLabel)
        container.addView(previewBox)
        container.addView(valueLabel)
        container.addView(seekBar)

        AlertDialog.Builder(this)
            .setTitle("YazÄ± Boyutu â€” Bu Site")
            .setView(container)
            .setPositiveButton("Kaydet") { _, _ ->
                val zoom = seekBar.progress + 50
                setSiteTextZoom(host, zoom)
                currentWebView().settings.textZoom = zoom
            }
            .setNegativeButton("VazgeÃ§", null)
            .show()
    }

    private fun currentWebView(): NestedScrollWebView {
        val tab = currentTab()
        return tab.webView ?: createWebViewForTab(tab)
    }

    private fun createWebViewForTab(tab: TabInfo): NestedScrollWebView {
        val webView = NestedScrollWebView(this)
        webView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        configureWebView(webView)
        tab.webView = webView
        return webView
    }

    // Konteynerdeki o anki sekmenin WebView'ini gÃ¶sterip eski sekmenin
    // WebView'ini ayÄ±rÄ±yor (yok etmiyor -- bellekte saklÄ± kalÄ±yor, sekmeler
    // arasÄ± gezinti, scroll pozisyonu ve geÃ§miÅŸ kaybolmuyor).
    private fun activateCurrentTabWebView(): NestedScrollWebView {
        val webView = currentWebView()
        binding.webViewContainer.removeAllViews()
        (webView.parent as? ViewGroup)?.removeView(webView)
        binding.webViewContainer.addView(webView)
        return webView
    }

    private fun configureWebView(webView: NestedScrollWebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setGeolocationEnabled(true)
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }
        if (defaultUserAgent == null) {
            defaultUserAgent = webView.settings.userAgentString
        }

        val blockThirdParty = getSharedPreferences("via_lite_prefs", MODE_PRIVATE)
            .getBoolean("block_third_party_cookies", false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, !blockThirdParty)

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startFileDownload(url, userAgent, contentDisposition, mimeType)
        }

        webView.setFindListener { activeMatchOrdinal, numberOfMatches, isDoneCounting ->
            if (webView !== currentTab().webView) return@setFindListener
            if (isDoneCounting) {
                binding.findInPageCount.text = if (numberOfMatches == 0) {
                    "0/0"
                } else {
                    "${activeMatchOrdinal + 1}/$numberOfMatches"
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // KRÄ°TÄ°K: bu callback override edilmezse, WebView'in render
                // sÃ¼reci herhangi bir sebeple (bellek baskÄ±sÄ±, belirli site
                // iÃ§eriÄŸi, GPU sÃ¼rÃ¼cÃ¼ sorunu) Ã§Ã¶kerse Android varsayÄ±lan
                // olarak TÃœM UYGULAMA SÃœRECÄ°NÄ° sonlandÄ±rÄ±yor (geriye dÃ¶nÃ¼k
                // uyumluluk iÃ§in bilinÃ§li bir tasarÄ±m kararÄ±). Burada true
                // dÃ¶ndÃ¼rÃ¼p aynÄ± WebView Ã¼zerinde reload() Ã§aÄŸÄ±rmak, render
                // sÃ¼recini sessizce yeniden baÅŸlatÄ±p uygulamanÄ±n aÃ§Ä±k
                // kalmasÄ±nÄ± saÄŸlÄ±yor.
                if (detail.didCrash()) {
                    Log.e("Browsy", "WebView render sÃ¼reci Ã§Ã¶ktÃ¼, yeniden yÃ¼kleniyor")
                } else {
                    Log.w("Browsy", "Sistem WebView render sÃ¼recini bellek kazanmak iÃ§in sonlandÄ±rdÄ±, yeniden yÃ¼kleniyor")
                }
                view.reload()
                return true
            }

            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }
                return try {
                    val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    intent.addCategory(Intent.CATEGORY_BROWSABLE)
                    if (intent.resolveActivity(packageManager) != null) {
                        startActivity(intent)
                    }
                    true
                } catch (e: Exception) {
                    // ayrÄ±ÅŸtÄ±rÄ±lamayan veya aÃ§Ä±lamayan ÅŸema; sessizce yok say
                    true
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // YazÄ± boyutu sayfa boyanmadan Ã¶nce uygulanmalÄ±; aksi halde
                // kullanÄ±cÄ± Ã¶nce varsayÄ±lan boyutla render edilen sayfayÄ±
                // gÃ¶rÃ¼p sonra site-Ã¶zel boyuta "zÄ±pladÄ±ÄŸÄ±nÄ±" fark ediyor.
                view.settings.textZoom = effectiveTextZoomFor(Uri.parse(url).host)
            }

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
                view.settings.textZoom = effectiveTextZoomFor(Uri.parse(url).host)
                if (!binding.editUrl.hasFocus()) {
                    binding.editUrl.setText(view.title?.takeIf { it.isNotBlank() } ?: url)
                }
                addHistoryEntry(view.title?.takeIf { it.isNotBlank() } ?: url, url)
                binding.swipeRefresh.isRefreshing = false
                showBars()
                applyThemeColorFromPage()
                applyForceDarkIfNeeded(view)
                if (currentTab().isDesktopMode) {
                    view.evaluateJavascript(
                        "(function(){var m=document.querySelector('meta[name=viewport]');" +
                            "if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');document.head.appendChild(m);}" +
                            "m.setAttribute('content','width=1024');})();",
                        null
                    )
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (view !== currentTab().webView) return
                binding.progressBar.progress = newProgress
                binding.progressBar.visibility =
                    if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onReceivedIcon(view: WebView, icon: Bitmap) {
                super.onReceivedIcon(view, icon)
                val tab = tabs.find { it.webView === view } ?: return
                tab.favicon = icon
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
                val tab = tabs.find { it.webView === view } ?: return
                if (title.isNotBlank()) {
                    tab.title = title
                }
                if (view === currentTab().webView && !binding.editUrl.hasFocus() && title.isNotBlank()) {
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
                            .setTitle("Konum Ä°zni")
                            .setMessage("$host, konumunuza eriÅŸmek istiyor. Ä°zin verilsin mi?")
                            .setPositiveButton("Ä°zin Ver") { _, _ ->
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

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false

                // Chromium'un resmi dokÃ¼mantasyonu: onCreateWindow iÃ§in mevcut bir
                // WebView'i yeniden kullanmak desteklenmiyor ("it is better to not
                // reuse an existing WebView") -- denenirse sessizce (hatasÄ±z) baÅŸarÄ±sÄ±z
                // oluyor ve render sÃ¼recini Ã§Ã¶kertebiliyor. Bu yÃ¼zden popup'Ä±n gerÃ§ekte
                // gitmek istediÄŸi URL'yi yakalamak iÃ§in tek seferlik, gÃ¶rÃ¼nmez bir
                // "yakalayÄ±cÄ±" WebView kullanÄ±yoruz; gerÃ§ek iÃ§erik tamamen YENÄ° bir
                // sekmenin kendi (yeni oluÅŸturulan) WebView'inde aÃ§Ä±lÄ±yor.
                var handled = false
                val catcherWebView = WebView(this@MainActivity)
                catcherWebView.settings.javaScriptEnabled = true
                catcherWebView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                        if (handled || url == null || url == "about:blank") return
                        handled = true
                        val newTab = prepareNewTabForPopup()
                        newTab.url = url
                        // GerÃ§ek sayfa baÅŸlÄ±ÄŸÄ± yÃ¼klenene kadar (onReceivedTitle)
                        // sekme listesinde "Yeni Sekme" yerine baÄŸlantÄ±nÄ±n
                        // adresini gÃ¶ster.
                        newTab.title = url.removePrefix("https://").removePrefix("http://")
                        restoreCurrentTab()
                        v.stopLoading()
                        v.post { v.destroy() }
                    }
                }

                transport.webView = catcherWebView
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) {
                closeTab(currentTabIndex)
            }
        }

        binding.swipeRefresh.setOnRefreshListener {
            currentWebView().reload()
        }
    }

    private fun setupWebView() {
        // AppBarLayout Ã¼st barÄ± native olarak (gerÃ§ek dokunma fiziÄŸiyle senkronize)
        // kaydÄ±rÄ±nca gizleyip gÃ¶steriyor. Alt barÄ± da aynÄ± orana gÃ¶re kaydÄ±rarak
        // ikisini birlikte hareket ettiriyoruz.
        binding.appBarLayout.addOnOffsetChangedListener { layout, verticalOffset ->
            val range = layout.totalScrollRange
            val fraction = if (range != 0) kotlin.math.abs(verticalOffset).toFloat() / range else 0f
            binding.bottomNavBar.translationY = fraction * binding.bottomNavBar.height
        }
    }

    private fun showBars() {
        binding.appBarLayout.setExpanded(true, true)
    }

    private fun applyThemeColorFromPage() {
        val js = "(function(){" +
            "var nodes=document.querySelectorAll('meta[name=\"theme-color\"]');" +
            "for(var i=0;i<nodes.length;i++){" +
            "if(!nodes[i].getAttribute('media')){" +
            "var c=nodes[i].getAttribute('content');if(c)return c;" +
            "}" +
            "}" +
            "if(nodes.length>0){" +
            "var c2=nodes[0].getAttribute('content');if(c2)return c2;" +
            "}" +
            "var bg=window.getComputedStyle(document.body).backgroundColor;" +
            "if(bg && bg!=='rgba(0, 0, 0, 0)' && bg!=='transparent')return bg;" +
            "return '';" +
            "})();"
        currentWebView().evaluateJavascript(js) { result ->
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

    // ---- Klavye aÃ§Ä±lÄ±nca alt barÄ± gizleme ----
    // KaydÄ±rmak yerine doÄŸrudan gizlemek daha gÃ¼venilir: hem klavye animasyonu
    // sÄ±rasÄ±nda taÅŸma/boÅŸluk riski olmuyor hem de yazarken ekstra alan aÃ§Ä±lÄ±yor.

    private var maxObservedRootHeight = 0
    private val suggestionsHandler = Handler(Looper.getMainLooper())
    private val searchSuggestionsProvider = SearchSuggestionsProvider()

    private fun setupKeyboardAvoidance() {
        val rootView = binding.root
        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            val rect = Rect()
            rootView.getWindowVisibleDisplayFrame(rect)
            val visibleHeight = rect.height()
            if (visibleHeight > maxObservedRootHeight) {
                maxObservedRootHeight = visibleHeight
            }
            val heightDiff = maxObservedRootHeight - visibleHeight
            // Klavye genelde ekranÄ±n %15'inden daha yÃ¼ksek bir alan kaplar;
            // bundan dÃ¼ÅŸÃ¼k farklar durum Ã§ubuÄŸu/sistem bar deÄŸiÅŸimi olabilir.
            val keyboardThreshold = maxObservedRootHeight / 6
            val keyboardVisible = heightDiff > keyboardThreshold
            binding.bottomNavBar.visibility = if (keyboardVisible) View.GONE else View.VISIBLE
        }
    }

    private fun setupControls() {
        binding.editUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                suppressUrlFocusRevert = true
                loadFromInput()
                binding.editUrl.clearFocus()
                hideKeyboard(binding.editUrl)
                hideSuggestions()
                true
            } else {
                false
            }
        }
        binding.editUrl.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                suppressUrlFocusRevert = false
                val currentUrl = currentWebView().url
                if (!currentUrl.isNullOrBlank()) {
                    binding.editUrl.setText(currentUrl)
                }
                binding.editUrl.post { binding.editUrl.selectAll() }
            } else {
                if (!suppressUrlFocusRevert) {
                    val title = currentWebView().title
                    if (!title.isNullOrBlank()) {
                        binding.editUrl.setText(title)
                    }
                }
                suppressUrlFocusRevert = false
                hideSuggestions()
            }
        }
        binding.editUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!binding.editUrl.hasFocus()) return
                val query = s?.toString()?.trim() ?: ""
                suggestionsHandler.removeCallbacksAndMessages(null)
                if (query.isEmpty()) {
                    hideSuggestions()
                    return
                }
                suggestionsHandler.postDelayed({ requestSuggestions(query) }, 250)
            }
        })

        // Alt bar
        binding.btnBottomBack.setOnClickListener {
            handleBackNavigation()
        }
        binding.btnBottomForward.setOnClickListener {
            if (currentWebView().canGoForward()) {
                showBrowser()
                currentWebView().goForward()
            }
        }
        binding.btnHome.setOnClickListener {
            val customUrl = getCustomStartUrlIfEnabled()
            if (!customUrl.isNullOrBlank()) {
                showBrowser()
                currentWebView().loadUrl(customUrl)
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

        // Sayfada bul Ã§ubuÄŸu (FindListener her WebView iÃ§in configureWebView'da kuruluyor)
        binding.findInPageInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                if (query.isEmpty()) {
                    currentWebView().clearMatches()
                    binding.findInPageCount.text = ""
                } else {
                    currentWebView().findAllAsync(query)
                }
            }
        })
        binding.findInPageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                currentWebView().findNext(true)
                true
            } else {
                false
            }
        }
        binding.btnFindPrev.setOnClickListener { currentWebView().findNext(false) }
        binding.btnFindNext.setOnClickListener { currentWebView().findNext(true) }
        binding.btnFindClose.setOnClickListener { closeFindInPage() }

        // AÃ§Ä±lÄ±ÅŸ ekranÄ± arama kutusu
        binding.homeSearchBox.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                val input = binding.homeSearchBox.text.toString().trim()
                if (input.isNotEmpty()) {
                    val url = resolveUrl(input)
                    showBrowser()
                    currentWebView().loadUrl(url)
                }
                binding.homeSearchBox.clearFocus()
                hideKeyboard(binding.homeSearchBox)
                true
            } else {
                false
            }
        }
    }

    private fun showBottomMenu(anchor: View) {
        val dialog = BottomSheetDialog(this)
        val rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val adBlockOn = isAdBlockEnabled()
        container.addView(
            buildFunctionMenuCard(
                iconRes = R.drawable.ic_block,
                label = "Reklam Engelleme",
                statusText = if (adBlockOn) "AÃ§Ä±k" else "KapalÄ±",
                isActive = adBlockOn
            ) {
                val newState = !isAdBlockEnabled()
                setAdBlockEnabled(newState)
                dialog.dismiss()
                Toast.makeText(
                    this,
                    if (newState) "Reklam engelleme aÃ§Ä±ldÄ±" else "Reklam engelleme kapatÄ±ldÄ±",
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
                label = "Ä°ndirilenler",
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
                label = "GeÃ§miÅŸ",
                statusText = null,
                isActive = false
            ) {
                dialog.dismiss()
                showHistoryList()
            }
        )

        val container2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        container2.addView(
            buildFunctionMenuCard(
                iconRes = R.drawable.ic_search,
                label = "Sayfada Bul",
                statusText = null,
                isActive = false
            ) {
                dialog.dismiss()
                showFindInPage()
            }
        )

        container2.addView(
            buildFunctionMenuCard(
                iconRes = R.drawable.ic_share,
                label = "PaylaÅŸ",
                statusText = null,
                isActive = false
            ) {
                dialog.dismiss()
                shareCurrentPage()
            }
        )

        container2.addView(
            buildFunctionMenuCard(
                iconRes = R.drawable.ic_desktop,
                label = "MasaÃ¼stÃ¼ Sitesi",
                statusText = if (currentTab().isDesktopMode) "AÃ§Ä±k" else "KapalÄ±",
                isActive = currentTab().isDesktopMode
            ) {
                dialog.dismiss()
                toggleDesktopMode()
            }
        )

        container2.addView(
            buildFunctionMenuCard(
                iconRes = R.drawable.ic_text_size,
                label = "YazÄ± Boyutu",
                statusText = "%${effectiveTextZoomFor(currentHost())}",
                isActive = false
            ) {
                dialog.dismiss()
                showSiteTextZoomDialog()
            }
        )

        container2.addView(
            buildFunctionMenuCard(
                iconRes = R.drawable.ic_bookmark,
                label = "Yer Ä°mine Ekle",
                statusText = null,
                isActive = false
            ) {
                dialog.dismiss()
                addCurrentPageToHome()
            }
        )

        val utilityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }

        utilityRow.addView(
            ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                background = cardRippleBackground()
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setImageResource(R.drawable.ic_power)
                setColorFilter(0xFF1A1A1A.toInt())
                scaleType = ImageView.ScaleType.FIT_CENTER
                isClickable = true
                isFocusable = true
                setOnClickListener { finishAffinity() }
            }
        )

        utilityRow.addView(
            ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) }
                background = cardRippleBackground()
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setImageResource(R.drawable.ic_chevron_down)
                scaleType = ImageView.ScaleType.FIT_CENTER
                isClickable = true
                isFocusable = true
                setOnClickListener { dialog.dismiss() }
            }
        )

        rootContainer.addView(container2)
        rootContainer.addView(container)
        rootContainer.addView(utilityRow)
        dialog.setContentView(rootContainer)
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
            setPadding(dp(4), dp(10), dp(4), dp(10))
            background = cardRippleBackground()
            setOnClickListener { onClick() }
        }

        val iconView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
            setImageResource(iconRes)
            setColorFilter(
                if (isActive) ContextCompat.getColor(this@MainActivity, R.color.colorPrimary) else 0xFF1A1A1A.toInt()
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val labelView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
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

    private fun cardRippleBackground(): RippleDrawable {
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(0x1F000000), null, mask)
    }

    private fun addCurrentPageToHome() {
        val url = currentWebView().url
        if (url.isNullOrBlank()) {
            Toast.makeText(this, "Eklenecek bir sayfa yok", Toast.LENGTH_SHORT).show()
            return
        }
        val title = currentWebView().title?.takeIf { it.isNotBlank() }
            ?: url.removePrefix("https://").removePrefix("http://")

        // WebView'in kendi favicon'u varsa anlÄ±k Ã¶nizleme olarak kullan,
        // gerÃ§ek/gÃ¼venilir ikon iÃ§in her zaman aÄŸdan da indirmeyi dene.
        val placeholderIcon = currentWebView().favicon
        bookmarks.add(BookmarkItem(title, url, placeholderIcon))
        saveBookmarksList()
        refreshBookmarksGrid()
        Toast.makeText(this, "Yer imlerine eklendi", Toast.LENGTH_SHORT).show()

        fetchFaviconAsync(url)
    }

    // ---- MasaÃ¼stÃ¼ sitesi modu ----

    private fun toggleDesktopMode() {
        val tab = currentTab()
        tab.isDesktopMode = !tab.isDesktopMode
        applyDesktopModeSetting(tab.isDesktopMode)
        currentWebView().reload()
        Toast.makeText(
            this,
            if (tab.isDesktopMode) "MasaÃ¼stÃ¼ sitesi aÃ§Ä±ldÄ±" else "MasaÃ¼stÃ¼ sitesi kapatÄ±ldÄ±",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun applyDesktopModeSetting(enabled: Boolean) {
        currentWebView().settings.apply {
            userAgentString = if (enabled) DESKTOP_USER_AGENT else defaultUserAgent
            useWideViewPort = true
            loadWithOverviewMode = true
        }
    }

    // ---- PaylaÅŸ ----

    private fun shareCurrentPage() {
        val url = currentWebView().url
        if (url.isNullOrBlank()) {
            Toast.makeText(this, "PaylaÅŸÄ±lacak bir sayfa yok", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            putExtra(Intent.EXTRA_SUBJECT, currentWebView().title?.takeIf { it.isNotBlank() } ?: url)
        }
        startActivity(Intent.createChooser(intent, "PaylaÅŸ"))
    }

    // ---- Sayfada bul ----

    private fun showFindInPage() {
        showBrowser()
        binding.findInPageBar.visibility = View.VISIBLE
        binding.findInPageInput.setText("")
        binding.findInPageCount.text = ""
        binding.findInPageInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.findInPageInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeFindInPage() {
        currentWebView().clearMatches()
        binding.findInPageBar.visibility = View.GONE
        hideKeyboard(binding.findInPageInput)
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

    // ---- Sekme yÃ¶netimi ----

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
        val webView = tab.webView ?: return
        tab.url = webView.url
        val title = webView.title
        if (!title.isNullOrBlank()) {
            tab.title = title
        }
    }

    private fun restoreCurrentTab() {
        val tab = currentTab()
        val isFreshTab = tab.webView == null
        val webView = activateCurrentTabWebView()
        applyDesktopModeSetting(tab.isDesktopMode)
        applyAppearanceSettings()

        if (isFreshTab) {
            // Bu sekmenin WebView'i ilk kez oluÅŸturuluyor -- ilk navigasyonu
            // baÅŸlatmamÄ±z gerekiyor. Mevcut bir sekmeye dÃ¶nÃ¼lÃ¼rken ise
            // WebView'in kendi iÃ§eriÄŸi/geÃ§miÅŸi/scroll pozisyonu zaten olduÄŸu
            // gibi korunuyor, hiÃ§bir ÅŸey yeniden yÃ¼klenmiyor.
            when {
                !tab.url.isNullOrBlank() -> {
                    webView.loadUrl(tab.url!!)
                    showBrowser()
                }
                else -> {
                    val customUrl = getCustomStartUrlIfEnabled()
                    if (!customUrl.isNullOrBlank()) {
                        webView.loadUrl(customUrl)
                        showBrowser()
                    } else {
                        showHomeScreen()
                    }
                }
            }
        } else {
            if (tab.url.isNullOrBlank()) {
                showHomeScreen()
            } else {
                showBrowser()
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
        tabs.add(TabInfo(id = tabManager.nextId()))
        currentTabIndex = tabs.size - 1
        restoreCurrentTab()
    }

    // window.open()/target="_blank" ile aÃ§Ä±lan popup'lar iÃ§in: yeni sekme
    // kaydÄ±nÄ± oluÅŸturur ama henÃ¼z WebView'ini yaratmaz/yÃ¼klemez -- Ã§aÄŸÄ±ran
    // taraf (onCreateWindow) hedef URL'yi tab.url'e atayÄ±p restoreCurrentTab()
    // Ã§aÄŸÄ±rarak gerÃ§ek (yepyeni, hiÃ§bir ÅŸeyle paylaÅŸÄ±lmayan) WebView'i
    // oluÅŸturup navigasyonu baÅŸlatÄ±yor.
    private fun prepareNewTabForPopup(): TabInfo {
        val openerId = currentTab().id
        saveCurrentTabState()
        val newTab = TabInfo(id = tabManager.nextId(), openerTabId = openerId)
        tabs.add(newTab)
        currentTabIndex = tabs.size - 1
        return newTab
    }

    private fun closeTab(index: Int, switchToIndex: Int? = null) {
        if (index !in tabs.indices) return
        val wasCurrent = index == currentTabIndex
        val closedTab = tabManager.closeTab(index, switchToIndex) ?: return
        destroyTabWebView(closedTab)

        if (wasCurrent || switchToIndex != null) {
            restoreCurrentTab()
        } else {
            updateTabCountBadge()
        }
    }

    private fun destroyTabWebView(tab: TabInfo) {
        val webView = tab.webView ?: return
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        tab.webView = null
    }

    // Geri tuÅŸuyla sekme kapatÄ±lÄ±rken Chrome/Safari'nin davranÄ±ÅŸÄ±: listede
    // sÄ±rayla Ã¶nceki sekmeye deÄŸil, bu sekmeyi AÃ‡AN (opener) sekmeye dÃ¶nÃ¼lÃ¼r.
    // Bu, kullanÄ±cÄ± araya baÅŸka sekmeler aÃ§Ä±p/gezip sonra geri tuÅŸuna bassa
    // bile her zaman doÄŸru sekmeye dÃ¶nÃ¼lmesini garantiliyor.
    private fun closeCurrentTabReturningToOpener() {
        val closingTab = currentTab()
        val openerIndex = tabManager.indexOfOpener(closingTab)
        closeTab(currentTabIndex, switchToIndex = if (openerIndex != -1) openerIndex else null)
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
        currentWebView().loadUrl(resolveUrl(input))
    }

    // ---- Arama Ã¶nerileri ----

    private fun requestSuggestions(query: String) {
        val engine = getSharedPreferences("via_lite_prefs", MODE_PRIVATE).getString("search_engine", "google") ?: "google"
        searchSuggestionsProvider.fetchSuggestions(query, engine) { suggestions ->
            // KullanÄ±cÄ± bu sÃ¼re iÃ§inde yazmayÄ± bitirip URL Ã§ubuÄŸundan
            // ayrÄ±lmÄ±ÅŸ olabilir; eski bir sonucu geÃ§ gÃ¶sterme.
            if (!binding.editUrl.hasFocus() || binding.editUrl.text.toString().trim() != query) return@fetchSuggestions
            showSuggestionsList(suggestions, query)
        }
    }

    private fun showSuggestionsList(suggestions: List<String>, query: String) {
        if (suggestions.isEmpty()) {
            hideSuggestions()
            return
        }
        binding.suggestionsContainer.removeAllViews()
        suggestions.forEach { suggestion ->
            binding.suggestionsContainer.addView(buildSuggestionRow(suggestion))
        }
        binding.suggestionsContainer.visibility = View.VISIBLE
    }

    private fun buildSuggestionRow(suggestion: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                suppressUrlFocusRevert = true
                binding.editUrl.setText(suggestion)
                binding.editUrl.clearFocus()
                hideKeyboard(binding.editUrl)
                hideSuggestions()
                currentWebView().loadUrl(resolveUrl(suggestion))
            }
        }
        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(12) }
            setImageResource(R.drawable.ic_search)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val label = TextView(this).apply {
            text = suggestion
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(0xFF1A1A1A.toInt())
        }
        row.addView(icon)
        row.addView(label)
        return row
    }

    private fun hideSuggestions() {
        suggestionsHandler.removeCallbacksAndMessages(null)
        binding.suggestionsContainer.visibility = View.GONE
        binding.suggestionsContainer.removeAllViews()
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

    // ---- Favicon Ã¶nbellekleme ----

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
            // Ã¶nbellekleme baÅŸarÄ±sÄ±z olursa sessizce yoksay
        }
    }

    private val maxHtmlChars = 100_000

    private fun fetchFaviconAsync(url: String) {
        Thread {
            // 1) Ã–nce sitenin kendi HTML'sindeki apple-touch-icon / manifest ikonunu dene
            // (Opera/Chrome'un yaptÄ±ÄŸÄ± gibi) â€” bunlar boÅŸluksuz, yÃ¼ksek Ã§Ã¶zÃ¼nÃ¼rlÃ¼klÃ¼dÃ¼r.
            var bitmap: Bitmap? = null
            val pageIconUrl = findBestIconUrlFromPage(url)
            if (pageIconUrl != null) {
                bitmap = downloadBitmap(pageIconUrl)
            }

            // 2) Bulunamazsa veya Ã§ok kÃ¼Ã§Ã¼kse eski favicon servislerine dÃ¼ÅŸ.
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

    // ---- SayfanÄ±n kendi HTML'sinden yÃ¼ksek Ã§Ã¶zÃ¼nÃ¼rlÃ¼klÃ¼ ikon bulma ----

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
                currentWebView().loadUrl(url)
            }
        }

        // ScrollView iÃ§inde standart uzun-basma algÄ±layÄ±cÄ±sÄ± en ufak kaydÄ±rma
        // titremesinde iptal olabiliyor; bu yÃ¼zden kendi toleranslÄ± algÄ±layÄ±cÄ±mÄ±zÄ± kuruyoruz.
        if (!isAddTile && url != null) {
            container.tag = url

            var longPressFired = false
            var dragStarted = false
            var downX = 0f
            var downY = 0f
            val moveTolerance = dp(20)
            val longPressRunnable = Runnable {
                longPressFired = true
                showBookmarkOptionsPopup(title, url)
            }
            container.setOnTouchListener { touchedView, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        longPressFired = false
                        dragStarted = false
                        downX = event.x
                        downY = event.y
                        longPressHandler.postDelayed(longPressRunnable, 500)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!dragStarted &&
                            (kotlin.math.abs(event.x - downX) > moveTolerance ||
                                kotlin.math.abs(event.y - downY) > moveTolerance)
                        ) {
                            longPressHandler.removeCallbacks(longPressRunnable)
                            if (!longPressFired) {
                                dragStarted = true
                                startBookmarkDrag(touchedView, url)
                            }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        longPressHandler.removeCallbacks(longPressRunnable)
                        if (!longPressFired && !dragStarted) {
                            touchedView.performClick()
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        longPressHandler.removeCallbacks(longPressRunnable)
                    }
                }
                true
            }

            container.setOnDragListener { dragView, event ->
                val draggedUrl = event.localState as? String
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> {
                        val accept = event.clipDescription?.hasMimeType(BOOKMARK_MIME) == true
                        if (accept && draggedUrl == url) {
                            dragView.alpha = 0.3f
                        }
                        accept
                    }
                    DragEvent.ACTION_DRAG_ENTERED -> {
                        if (draggedUrl != url) {
                            dragView.setBackgroundColor(0x33000000)
                        }
                        true
                    }
                    DragEvent.ACTION_DRAG_EXITED -> {
                        dragView.background = null
                        true
                    }
                    DragEvent.ACTION_DROP -> {
                        dragView.background = null
                        if (draggedUrl != null) {
                            moveBookmark(draggedUrl, url)
                        }
                        true
                    }
                    DragEvent.ACTION_DRAG_ENDED -> {
                        dragView.alpha = 1f
                        dragView.background = null
                        true
                    }
                    else -> true
                }
            }
        }

        return container
    }

    // ---- Yer imi sÃ¼rÃ¼kle-bÄ±rak (sÄ±ralama ve silme) ----

    private fun startBookmarkDrag(view: View, url: String) {
        val item = ClipData.Item(url)
        val clipData = ClipData(BOOKMARK_DRAG_LABEL, arrayOf(BOOKMARK_MIME), item)
        val shadowBuilder = View.DragShadowBuilder(view)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            view.startDragAndDrop(clipData, shadowBuilder, url, 0)
        } else {
            @Suppress("DEPRECATION")
            view.startDrag(clipData, shadowBuilder, url, 0)
        }
    }

    private fun moveBookmark(fromUrl: String, toUrl: String) {
        if (fromUrl == toUrl) return
        val fromIndex = bookmarks.indexOfFirst { it.url == fromUrl }
        val toIndex = bookmarks.indexOfFirst { it.url == toUrl }
        if (fromIndex == -1 || toIndex == -1) return
        val item = bookmarks.removeAt(fromIndex)
        bookmarks.add(toIndex, item)
        saveBookmarksList()
        refreshBookmarksGrid()
    }

    private fun showBookmarkOptionsPopup(title: String, url: String) {
        val options = arrayOf("Sil", "DÃ¼zenle", "Yeni Sekmede AÃ§")
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        bookmarks.removeAll { it.url == url }
                        getSharedPreferences("via_lite_prefs", MODE_PRIVATE).edit().remove(faviconKey(url)).apply()
                        saveBookmarksList()
                        refreshBookmarksGrid()
                        Toast.makeText(this, "Yer imi silindi", Toast.LENGTH_SHORT).show()
                    }
                    1 -> showEditBookmarkDialog(url)
                    2 -> {
                        addNewTab()
                        showBrowser()
                        currentWebView().loadUrl(url)
                    }
                }
            }
            .show()
    }

    private fun showEditBookmarkDialog(url: String) {
        val bookmark = bookmarks.find { it.url == url } ?: return
        val oldUrl = bookmark.url

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), 0)
        }
        val titleInput = EditText(this).apply {
            hint = "BaÅŸlÄ±k"
            setText(bookmark.title)
        }
        val urlInput = EditText(this).apply {
            hint = "URL"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(bookmark.url)
        }
        container.addView(titleInput)
        container.addView(urlInput)

        AlertDialog.Builder(this)
            .setTitle("Yer Ä°mini DÃ¼zenle")
            .setView(container)
            .setPositiveButton("Kaydet") { _, _ ->
                var newUrl = urlInput.text.toString().trim()
                var newTitle = titleInput.text.toString().trim()
                if (newUrl.isEmpty()) return@setPositiveButton
                if (!newUrl.startsWith("http://") && !newUrl.startsWith("https://")) {
                    newUrl = "https://$newUrl"
                }
                if (newTitle.isEmpty()) {
                    newTitle = newUrl.removePrefix("https://").removePrefix("http://")
                }

                val urlChanged = oldUrl != newUrl
                bookmark.title = newTitle
                bookmark.url = newUrl

                if (urlChanged) {
                    val prefs = getSharedPreferences("via_lite_prefs", MODE_PRIVATE)
                    val oldIcon = prefs.getString(faviconKey(oldUrl), null)
                    prefs.edit().remove(faviconKey(oldUrl)).apply()
                    if (oldIcon != null) {
                        prefs.edit().putString(faviconKey(newUrl), oldIcon).apply()
                    } else {
                        fetchFaviconAsync(newUrl)
                    }
                }

                saveBookmarksList()
                refreshBookmarksGrid()
            }
            .setNegativeButton("VazgeÃ§", null)
            .show()
    }

    private fun showAddBookmarkDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), 0)
        }
        val titleInput = EditText(this).apply { hint = "BaÅŸlÄ±k" }
        val urlInput = EditText(this).apply {
            hint = "URL"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        container.addView(titleInput)
        container.addView(urlInput)

        AlertDialog.Builder(this)
            .setTitle("Yer Ä°mi Ekle")
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
            .setNegativeButton("VazgeÃ§", null)
            .show()
    }

    // Geri tuÅŸu: Ã¶nce sekme iÃ§i geÃ§miÅŸ, yoksa (ve birden fazla sekme aÃ§Ä±ksa)
    // bu sekmeyi kapatÄ±p Ã¶nceki sekmeye dÃ¶n -- Ã¶zellikle target="_blank" ile
    // aÃ§Ä±lan yeni sekmelerde geÃ§miÅŸ olmadÄ±ÄŸÄ±ndan bu davranÄ±ÅŸ olmazsa geri
    // tuÅŸu uygulamayÄ± kapatmaya Ã§alÄ±ÅŸÄ±rdÄ±.
    private fun handleBackNavigation(): Boolean {
        if (currentWebView().canGoBack()) {
            showBrowser()
            currentWebView().goBack()
            return true
        }
        if (tabs.size > 1) {
            closeCurrentTabReturningToOpener()
            return true
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK &&
            binding.browserRoot.visibility == View.VISIBLE
        ) {
            if (handleBackNavigation()) {
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}