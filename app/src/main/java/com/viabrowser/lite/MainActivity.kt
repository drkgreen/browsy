package com.viabrowser.lite

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
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
import com.viabrowser.lite.databinding.ActivityMainBinding
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class BookmarkItem(val title: String, val url: String, var icon: Bitmap? = null)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val bookmarks = mutableListOf<BookmarkItem>()

    private val longPressHandler = Handler(Looper.getMainLooper())

    private val adHosts = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "ads.youtube.com",
        "adnxs.com",
        "popads.net",
        "taboola.com",
        "outbrain.com",
        "scorecardresearch.com"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebView()
        setupControls()

        loadBookmarks()
        refreshBookmarksGrid()
        showHomeScreen()
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
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val host = request.url.host ?: ""
                val isAd = adHosts.any { host.endsWith(it) }
                if (isAd) {
                    return WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (!binding.editUrl.hasFocus()) {
                    binding.editUrl.setText(view.title?.takeIf { it.isNotBlank() } ?: url)
                }
                binding.swipeRefresh.isRefreshing = false
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
                if (!binding.editUrl.hasFocus() && title.isNotBlank()) {
                    binding.editUrl.setText(title)
                }
            }
        }

        binding.swipeRefresh.setOnRefreshListener {
            binding.webView.reload()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            webView.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                val delta = scrollY - oldScrollY
                if (delta > 10) {
                    hideBars()
                } else if (delta < -10) {
                    showBars()
                }
            }
        }
    }

    private fun hideBars() {
        binding.topToolbar.visibility = View.GONE
        binding.bottomNavBar.visibility = View.GONE
    }

    private fun showBars() {
        binding.topToolbar.visibility = View.VISIBLE
        binding.bottomNavBar.visibility = View.VISIBLE
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
            showHomeScreen()
        }
        binding.btnTabs.setOnClickListener {
            Toast.makeText(this, "Sekmeler yakında eklenecek", Toast.LENGTH_SHORT).show()
        }
        binding.btnClose.setOnClickListener {
            finishAffinity()
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

    private fun resolveUrl(raw: String): String {
        val looksLikeUrl = raw.contains(".") && !raw.contains(" ")
        return when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            looksLikeUrl -> "https://$raw"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(raw, "UTF-8")
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
