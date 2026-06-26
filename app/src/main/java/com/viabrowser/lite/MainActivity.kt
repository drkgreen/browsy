package com.viabrowser.lite

import android.Manifest
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
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
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
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.viabrowser.lite.databinding.ActivityMainBinding
import java.io.ByteArrayInputStream
import java.io.BufferedReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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

    private val tabManager = TabManager()
    private val tabs: MutableList<TabInfo> get() = tabManager.tabs
    private var currentTabIndex: Int
        get() = tabManager.currentTabIndex
        set(value) { tabManager.currentTabIndex = value }

    companion object {
        private const val ADBLOCK_REMOTE_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
        private const val ADBLOCK_CACHE_FILE = "adblock_hosts_remote.txt"
        private const val ADBLOCK_UPDATE_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000 // 7 gün
        private const val ADBLOCK_MIN_VALID_ENTRIES = 1000
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    private var defaultUserAgent: String? = null
    private val downloadsManager by lazy { DownloadsManager(this) }
    private val historyManager by lazy { HistoryManager(this) }
    private val sitePermissionsManager by lazy { SitePermissionsManager(this) }
    private val bookmarksManager by lazy {
        BookmarksManager(
            context = this,
            bookmarksGrid = binding.bookmarksGrid,
            onOpenUrl = { url ->
                showBrowser()
                binding.editUrl.setText(url)
                currentWebView().loadUrl(url)
            },
            onOpenInNewTab = { url ->
                addNewTab()
                showBrowser()
                currentWebView().loadUrl(url)
            }
        )
    }

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
            // liste okunamazsa boş set döner, çağıran taraf yedek listeye düşer
        }
        return set
    }

    // ---- Reklam engelleme listesi: periyodik uzaktan güncelleme ----

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
                    // yazma başarısız olursa mevcut liste ile devam edilir
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
            // Beklenmedik şekilde kısa/bozuk bir indirme mevcut listeyi bozmasın
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
        setupKeyboardAvoidance()

        bookmarksManager.loadBookmarks()
        bookmarksManager.refreshBookmarksGrid()
        maybeUpdateAdBlockList()

        val resumeSession = getSharedPreferences("via_lite_prefs", MODE_PRIVATE)
            .getString("startup_behavior", "start_page") == "resume_session"
        val sessionTabs = if (resumeSession) loadSessionTabs() else emptyList()

        if (sessionTabs.isNotEmpty()) {
            sessionTabs.forEach { (url, title) ->
                tabs.add(TabInfo(id = tabManager.nextId(), url = url, title = title))
            }
        } else {
            tabs.add(TabInfo(id = tabManager.nextId()))
        }
        currentTabIndex = 0
        restoreCurrentTab()
    }

    override fun onPause() {
        super.onPause()
        saveSessionTabs()
    }

    // ---- Oturum kalıcılığı (kaldığım yerden devam et) ----

    private fun saveSessionTabs() {
        saveCurrentTabState()
        val raw = tabs.filterNot { it.isPrivate }.joinToString("\n") { tab -> "${tab.url ?: ""}::${tab.title}" }
        getSharedPreferences("via_lite_prefs", MODE_PRIVATE).edit().putString("session_tabs", raw).apply()
    }

    private fun loadSessionTabs(): List<Pair<String, String>> {
        val raw = getSharedPreferences("via_lite_prefs", MODE_PRIVATE).getString("session_tabs", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split("::")
            if (parts.size == 2 && parts[0].isNotBlank()) parts[0] to parts[1] else null
        }
    }

    override fun onResume() {
        super.onResume()
        applyAppearanceSettings()
        applyCookieSettings()
        consumePendingCacheClear()
        consumePendingAppearanceReload()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        sitePermissionsManager.onRequestPermissionsResult(requestCode)
    }

    override fun onDestroy() {
        // Artık her sekme kendi WebView'ine sahip olduğundan, Activity
        // kapanırken hepsini açıkça yok etmemiz gerekiyor -- aksi halde
        // her biri native kaynaklarını (render süreci bağlantısı vb.)
        // tutmaya devam edip bellek sızıntısına yol açabilir.
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
                    text = "Kayıtlı adres yok. Ayarlar > Otomatik Doldurma'dan ekleyebilirsin."
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

    private fun applyAppearanceSettings() {
        currentWebView().settings.textZoom = effectiveTextZoomFor(currentHost())
        // Web sayfalarının karartılması artık CSS injection ile yapılıyor
        // (bkz. applyForceDarkIfNeeded), uygulamanın kendi teması bundan etkilenmiyor.
    }

    // ---- Web sayfalarını karartma (sadece içerik, uygulama arayüzü etkilenmez) ----

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

    // ---- Site-özel yazı boyutu ----

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
            Toast.makeText(this, "Yazı boyutu ayarlanacak bir sayfa yok", Toast.LENGTH_SHORT).show()
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
            text = "Örnek metin böyle görünecek."
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
            max = 150 // 50 ile 200 arası, +50 ekleyerek gerçek yüzdeye çeviriyoruz
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
            .setTitle("Yazı Boyutu — Bu Site")
            .setView(container)
            .setPositiveButton("Kaydet") { _, _ ->
                val zoom = seekBar.progress + 50
                setSiteTextZoom(host, zoom)
                currentWebView().settings.textZoom = zoom
            }
            .setNegativeButton("Vazgeç", null)
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

    // Konteynerdeki o anki sekmenin WebView'ini gösterip eski sekmenin
    // WebView'ini ayırıyor (yok etmiyor -- bellekte saklı kalıyor, sekmeler
    // arası gezinti, scroll pozisyonu ve geçmiş kaybolmuyor).
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
            downloadsManager.startFileDownload(url, userAgent, contentDisposition, mimeType)
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
                // KRİTİK: bu callback override edilmezse, WebView'in render
                // süreci herhangi bir sebeple (bellek baskısı, belirli site
                // içeriği, GPU sürücü sorunu) çökerse Android varsayılan
                // olarak TÜM UYGULAMA SÜRECİNİ sonlandırıyor (geriye dönük
                // uyumluluk için bilinçli bir tasarım kararı). Burada true
                // döndürüp aynı WebView üzerinde reload() çağırmak, render
                // sürecini sessizce yeniden başlatıp uygulamanın açık
                // kalmasını sağlıyor.
                if (detail.didCrash()) {
                    Log.e("Browsy", "WebView render süreci çöktü, yeniden yükleniyor")
                } else {
                    Log.w("Browsy", "Sistem WebView render sürecini bellek kazanmak için sonlandırdı, yeniden yükleniyor")
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
                    // ayrıştırılamayan veya açılamayan şema; sessizce yok say
                    true
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Yazı boyutu sayfa boyanmadan önce uygulanmalı; aksi halde
                // kullanıcı önce varsayılan boyutla render edilen sayfayı
                // görüp sonra site-özel boyuta "zıpladığını" fark ediyor.
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
                // KRİTİK: view, arka plandaki (görünmeyen) bir sekmenin WebView'i
                // olabilir -- örn. bir yönlendirme tamamlanırken kullanıcı zaten
                // başka bir sekmeye geçmiş olabilir. Bu durumda currentTab()
                // kullanmak aktif sekmenin verisini yanlışlıkla ezerdi; bu yüzden
                // önce view'in GERÇEKTEN hangi sekmeye ait olduğunu buluyoruz.
                val tab = tabs.find { it.webView === view } ?: return
                tab.url = url
                view.settings.textZoom = effectiveTextZoomFor(Uri.parse(url).host)
                if (!tab.isPrivate) {
                    historyManager.addHistoryEntry(view.title?.takeIf { it.isNotBlank() } ?: url, url)
                }

                if (tab !== currentTab()) return

                if (!binding.editUrl.hasFocus()) {
                    binding.editUrl.setText(view.title?.takeIf { it.isNotBlank() } ?: url)
                }
                binding.swipeRefresh.isRefreshing = false
                showBars()
                applyThemeColorFromPage()
                applyForceDarkIfNeeded(view)
                if (tab.isDesktopMode) {
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
                    bookmarksManager.updateBookmarkIconIfMissing(url, icon)
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
                    val types = request.resources.mapNotNull { sitePermissionsManager.resourceToType(it) }
                    if (types.isEmpty()) {
                        request.deny()
                        return@runOnUiThread
                    }
                    val undecided = types.filter { sitePermissionsManager.getSitePermissionDecision(host, it) == null }
                    if (undecided.isNotEmpty()) {
                        sitePermissionsManager.showSitePermissionDialog(host, undecided, request)
                    } else {
                        sitePermissionsManager.resolveWebPermissionRequest(host, request)
                    }
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                runOnUiThread {
                    val host = Uri.parse(origin).host ?: origin
                    val decision = sitePermissionsManager.getSitePermissionDecision(host, "location")
                    if (decision == null) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Konum İzni")
                            .setMessage("$host, konumunuza erişmek istiyor. İzin verilsin mi?")
                            .setPositiveButton("İzin Ver") { _, _ ->
                                sitePermissionsManager.setSitePermissionDecision(host, "location", "allow")
                                sitePermissionsManager.resolveGeoPermission(origin, callback)
                            }
                            .setNegativeButton("Reddet") { _, _ ->
                                sitePermissionsManager.setSitePermissionDecision(host, "location", "deny")
                                callback.invoke(origin, false, false)
                            }
                            .setCancelable(false)
                            .show()
                    } else {
                        sitePermissionsManager.resolveGeoPermission(origin, callback)
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

                // Chromium'un resmi dokümantasyonu: onCreateWindow için mevcut bir
                // WebView'i yeniden kullanmak desteklenmiyor ("it is better to not
                // reuse an existing WebView") -- denenirse sessizce (hatasız) başarısız
                // oluyor ve render sürecini çökertebiliyor. Bu yüzden popup'ın gerçekte
                // gitmek istediği URL'yi yakalamak için tek seferlik, görünmez bir
                // "yakalayıcı" WebView kullanıyoruz; gerçek içerik tamamen YENİ bir
                // sekmenin kendi (yeni oluşturulan) WebView'inde açılıyor.
                var handled = false
                val catcherWebView = WebView(this@MainActivity)
                catcherWebView.settings.javaScriptEnabled = true
                catcherWebView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                        if (handled || url == null || url == "about:blank") return
                        handled = true
                        val newTab = prepareNewTabForPopup()
                        newTab.url = url
                        // Gerçek sayfa başlığı yüklenene kadar (onReceivedTitle)
                        // sekme listesinde "Yeni Sekme" yerine bağlantının
                        // adresini göster.
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
        // SwipeRefreshLayout varsayılan olarak doğrudan alt view'inin (webViewContainer,
        // sade bir FrameLayout) kaydırılabilirliğine bakıyor -- FrameLayout'un kendisi hiç
        // kaydırılamadığından bu kontrol her zaman "üstte" sonucu veriyor ve sayfa nerede
        // olursa olsun her aşağı çekişte yenileme tetikleniyordu. Asıl kaydırma durumunu
        // o anki aktif sekmenin WebView'inden okuyarak bunu düzeltiyoruz.
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            currentWebView().scrollY > 0
        }
    }

    private fun setupWebView() {
        // AppBarLayout üst barı native olarak (gerçek dokunma fiziğiyle senkronize)
        // kaydırınca gizleyip gösteriyor. Alt barı da aynı orana göre kaydırarak
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

    // ---- Klavye açılınca alt barı gizleme ----
    // Kaydırmak yerine doğrudan gizlemek daha güvenilir: hem klavye animasyonu
    // sırasında taşma/boşluk riski olmuyor hem de yazarken ekstra alan açılıyor.

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
            // Klavye genelde ekranın %15'inden daha yüksek bir alan kaplar;
            // bundan düşük farklar durum çubuğu/sistem bar değişimi olabilir.
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

        // Sayfada bul çubuğu (FindListener her WebView için configureWebView'da kuruluyor)
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

        // Açılış ekranı arama kutusu
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
                iconRes = R.drawable.ic_download,
                label = "İndirilenler",
                statusText = null,
                isActive = false
            ) {
                dialog.dismiss()
                downloadsManager.showDownloadsList()
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
                historyManager.showHistoryList { url ->
                    showBrowser()
                    currentWebView().loadUrl(url)
                }
            }
        )

        container.addView(
            buildFunctionMenuCard(
                iconRes = R.drawable.ic_bookmark,
                label = "Yer İmine Ekle",
                statusText = null,
                isActive = false
            ) {
                dialog.dismiss()
                bookmarksManager.addCurrentPageToHome(
                    currentWebView().url,
                    currentWebView().title,
                    currentWebView().favicon
                )
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
                label = "Paylaş",
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
                label = "Masaüstü Sitesi",
                statusText = if (currentTab().isDesktopMode) "Açık" else "Kapalı",
                isActive = currentTab().isDesktopMode
            ) {
                dialog.dismiss()
                toggleDesktopMode()
            }
        )

        container2.addView(
            buildFunctionMenuCard(
                iconRes = R.drawable.ic_text_size,
                label = "Yazı Boyutu",
                statusText = "%${effectiveTextZoomFor(currentHost())}",
                isActive = false
            ) {
                dialog.dismiss()
                showSiteTextZoomDialog()
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
                setImageResource(R.drawable.ic_settings)
                setColorFilter(0xFF1A1A1A.toInt())
                scaleType = ImageView.ScaleType.FIT_CENTER
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                }
            }
        )

        utilityRow.addView(
            ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) }
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

    // ---- Masaüstü sitesi modu ----

    private fun toggleDesktopMode() {
        val tab = currentTab()
        tab.isDesktopMode = !tab.isDesktopMode
        applyDesktopModeSetting(tab.isDesktopMode)
        currentWebView().reload()
        Toast.makeText(
            this,
            if (tab.isDesktopMode) "Masaüstü sitesi açıldı" else "Masaüstü sitesi kapatıldı",
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

    // ---- Paylaş ----

    private fun shareCurrentPage() {
        val url = currentWebView().url
        if (url.isNullOrBlank()) {
            Toast.makeText(this, "Paylaşılacak bir sayfa yok", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            putExtra(Intent.EXTRA_SUBJECT, currentWebView().title?.takeIf { it.isNotBlank() } ?: url)
        }
        startActivity(Intent.createChooser(intent, "Paylaş"))
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
            // Bu sekmenin WebView'i ilk kez oluşturuluyor -- ilk navigasyonu
            // başlatmamız gerekiyor. Mevcut bir sekmeye dönülürken ise
            // WebView'in kendi içeriği/geçmişi/scroll pozisyonu zaten olduğu
            // gibi korunuyor, hiçbir şey yeniden yüklenmiyor.
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

    private fun addNewPrivateTab() {
        saveCurrentTabState()
        tabs.add(TabInfo(id = tabManager.nextId(), title = "Gizli Sekme", isPrivate = true))
        currentTabIndex = tabs.size - 1
        restoreCurrentTab()
    }

    // window.open()/target="_blank" ile açılan popup'lar için: yeni sekme
    // kaydını oluşturur ama henüz WebView'ini yaratmaz/yüklemez -- çağıran
    // taraf (onCreateWindow) hedef URL'yi tab.url'e atayıp restoreCurrentTab()
    // çağırarak gerçek (yepyeni, hiçbir şeyle paylaşılmayan) WebView'i
    // oluşturup navigasyonu başlatıyor.
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

    // Geri tuşuyla sekme kapatılırken Chrome/Safari'nin davranışı: listede
    // sırayla önceki sekmeye değil, bu sekmeyi AÇAN (opener) sekmeye dönülür.
    // Bu, kullanıcı araya başka sekmeler açıp/gezip sonra geri tuşuna bassa
    // bile her zaman doğru sekmeye dönülmesini garantiliyor.
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

        // En son açılan sekme listede en üstte görünsün; ama gerçek index
        // (switchToTab/closeTab için) değişmiyor, sadece görüntüleme sırası.
        tabs.withIndex().toList().asReversed().forEach { (index, tab) ->
            container.addView(buildTabRow(tab, index, dialog))
        }
        container.addView(buildAddTabRow(dialog))
        container.addView(buildAddPrivateTabRow(dialog))

        dialog.setContentView(scrollView)
        dialog.show()
    }

    private fun buildTabRow(tab: TabInfo, index: Int, dialog: BottomSheetDialog): View {
        val isActive = index == currentTabIndex
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(
                when {
                    tab.isPrivate && isActive -> 0xFF3D3D3D.toInt()
                    tab.isPrivate -> 0xFF2B2B2B.toInt()
                    isActive -> 0xFFE8F0FE.toInt()
                    else -> Color.TRANSPARENT
                }
            )
            isClickable = true
            isFocusable = true
            setOnClickListener {
                switchToTab(index)
                dialog.dismiss()
            }
            setOnLongClickListener {
                showTabLongPressMenu(index, dialog)
                true
            }
        }

        val iconView: View = if (tab.isPrivate) {
            ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                background = circleDrawable(0xFF000000.toInt())
                setPadding(dp(6), dp(6), dp(6), dp(6))
                setImageResource(R.drawable.ic_incognito)
                setColorFilter(Color.WHITE)
            }
        } else if (tab.favicon != null) {
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

        val textContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { marginStart = dp(12) }
        }

        val titleView = TextView(this).apply {
            text = tab.title
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(if (tab.isPrivate) Color.WHITE else 0xFF1A1A1A.toInt())
        }

        val urlView = TextView(this).apply {
            text = tab.url?.removePrefix("https://")?.removePrefix("http://")?.removeSuffix("/") ?: ""
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
            setTextColor(if (tab.isPrivate) 0xFFAEAEAE.toInt() else 0xFF8E8E93.toInt())
            visibility = if (tab.url.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        textContainer.addView(titleView)
        textContainer.addView(urlView)

        val closeView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setImageResource(R.drawable.ic_close)
            setColorFilter(if (tab.isPrivate) Color.WHITE else 0xFF8E8E93.toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener {
                closeTab(index)
                dialog.dismiss()
            }
        }

        row.addView(iconView)
        row.addView(textContainer)
        row.addView(closeView)
        return row
    }

    private fun showTabLongPressMenu(index: Int, switcherDialog: BottomSheetDialog) {
        val options = arrayOf("Sekmeyi Kapat", "Diğer Sekmeleri Kapat", "Tüm Sekmeleri Kapat")
        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                switcherDialog.dismiss()
                when (which) {
                    0 -> closeTab(index)
                    1 -> closeOtherTabs(index)
                    2 -> closeAllTabsAction()
                }
            }
            .show()
    }

    private fun closeOtherTabs(index: Int) {
        val removed = tabManager.closeAllExcept(index)
        removed.forEach { destroyTabWebView(it) }
        restoreCurrentTab()
    }

    private fun closeAllTabsAction() {
        val removed = tabManager.closeAll()
        removed.forEach { destroyTabWebView(it) }
        restoreCurrentTab()
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
            text = "Yeni Sekme Aç"
            textSize = 15f
            setTextColor(0xFF1A1A1A.toInt())
        }

        row.addView(plusIcon)
        row.addView(label)
        return row
    }

    private fun buildAddPrivateTabRow(dialog: BottomSheetDialog): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(14), dp(12), dp(14))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                addNewPrivateTab()
                dialog.dismiss()
            }
        }

        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = circleDrawable(0xFF000000.toInt())
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setImageResource(R.drawable.ic_incognito)
            setColorFilter(Color.WHITE)
        }

        val label = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { marginStart = dp(12) }
            text = "Gizli Sekme Aç"
            textSize = 15f
            setTextColor(0xFF1A1A1A.toInt())
        }

        row.addView(icon)
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

    // ---- Arama önerileri ----

    private fun requestSuggestions(query: String) {
        val engine = getSharedPreferences("via_lite_prefs", MODE_PRIVATE).getString("search_engine", "google") ?: "google"
        searchSuggestionsProvider.fetchSuggestions(query, engine) { suggestions ->
            // Kullanıcı bu süre içinde yazmayı bitirip URL çubuğundan
            // ayrılmış olabilir; eski bir sonucu geç gösterme.
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


    // Geri tuşu: önce sekme içi geçmiş, yoksa (ve birden fazla sekme açıksa)
    // bu sekmeyi kapatıp önceki sekmeye dön -- özellikle target="_blank" ile
    // açılan yeni sekmelerde geçmiş olmadığından bu davranış olmazsa geri
    // tuşu uygulamayı kapatmaya çalışırdı.
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
        showExitConfirmationDialog()
        return true
    }

    private fun showExitConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Çıkmak istiyor musunuz?")
            .setMessage("Browsy'den çıkmak istediğinize emin misiniz?")
            .setPositiveButton("Evet") { _, _ -> finishAffinity() }
            .setNegativeButton("Hayır", null)
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (handleBackNavigation()) {
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
