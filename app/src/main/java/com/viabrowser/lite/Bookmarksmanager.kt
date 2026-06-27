package com.viabrowser.lite

import android.content.ClipData
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.Base64
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class BookmarkItem(var title: String, var url: String, var icon: Bitmap? = null)

/**
 * Yer imi grid'i, favicon önbellekleme/indirme ve sürükle-bırak sıralama.
 * MainActivity'den ayrıldı; bir yer imine dokununca sayfayı açmak (WebView'e
 * bağlı olduğu için) MainActivity'nin işi olduğundan callback ile veriliyor.
 */
class BookmarksManager(
    private val context: Context,
    private val bookmarksGrid: ViewGroup,
    private val onOpenUrl: (String) -> Unit,
    private val onOpenInNewTab: (String) -> Unit
) {
    val bookmarks = mutableListOf<BookmarkItem>()
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val maxHtmlChars = 100_000

    private fun prefs() = context.getSharedPreferences("via_lite_prefs", Context.MODE_PRIVATE)

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun circleDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    // ---- Yer imi depolama ----

    fun loadBookmarks() {
        bookmarks.clear()
        val raw = prefs().getString("bookmarks", "") ?: ""
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
        prefs().edit().putString("bookmarks", raw).apply()
    }

    fun addCurrentPageToHome(url: String?, title: String?, placeholderIcon: Bitmap?) {
        if (url.isNullOrBlank()) {
            Toast.makeText(context, "Eklenecek bir sayfa yok", Toast.LENGTH_SHORT).show()
            return
        }
        val finalTitle = title?.takeIf { it.isNotBlank() }
            ?: url.removePrefix("https://").removePrefix("http://")

        bookmarks.add(BookmarkItem(finalTitle, url, placeholderIcon))
        saveBookmarksList()
        refreshBookmarksGrid()
        Toast.makeText(context, "Yer imlerine eklendi", Toast.LENGTH_SHORT).show()

        fetchFaviconAsync(url)
    }

    // ---- Favicon önbellekleme ----

    private fun faviconKey(url: String): String = "favicon::$url"

    private fun loadCachedFavicon(url: String): Bitmap? {
        val base64 = prefs().getString(faviconKey(url), null) ?: return null
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
            prefs().edit().putString(faviconKey(url), base64).apply()
        } catch (e: Exception) {
            // önbellekleme başarısız olursa sessizce yoksay
        }
    }

    /** WebView sayfanın kendi favicon'unu bildirince, eşleşen bir yer imi
     * varsa ve henüz ikonu yoksa onu günceller (MainActivity'nin
     * onReceivedIcon callback'inden çağrılır). */
    fun updateBookmarkIconIfMissing(url: String, icon: Bitmap) {
        val item = bookmarks.find { it.url == url }
        if (item != null && item.icon == null) {
            item.icon = icon
            cacheFavicon(url, icon)
            refreshBookmarksGrid()
        }
    }

    fun fetchFaviconAsync(url: String) {
        Thread {
            // 1) Önce sitenin kendi HTML'sindeki apple-touch-icon / manifest ikonunu dene
            // (Opera/Chrome'un yaptığı gibi) -- bunlar boşluksuz, yüksek çözünürlüklüdür.
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
                val finalBitmap = bitmap
                longPressHandler.post {
                    bookmarks.find { it.url == url }?.icon = finalBitmap
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

    fun refreshBookmarksGrid() {
        bookmarksGrid.removeAllViews()
        bookmarks.forEach { item ->
            bookmarksGrid.addView(buildBookmarkTile(item.title, item.url, item.icon, false))
        }
        bookmarksGrid.addView(buildBookmarkTile("", null, null, true))
    }

    private fun buildBookmarkTile(title: String, url: String?, icon: Bitmap?, isAddTile: Boolean): View {
        val tileSize = context.resources.displayMetrics.widthPixels / 4
        val iconSize = dp(60)

        val container = LinearLayout(context).apply {
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

        // Bazı siteler favicon isteğine geçerli ama anlamsız (1x1, bozuk)
        // bir görsel döndürüyor -- bu durumda boş bir alan göstermek yerine
        // baş harf fallback'ine düşmemiz gerekiyor.
        val hasUsableIcon = icon != null && icon.width >= 8 && icon.height >= 8

        val iconView: View = if (hasUsableIcon && !isAddTile) {
            ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = circleDrawable(Color.TRANSPARENT)
                clipToOutline = true
                setImageBitmap(icon)
            }
        } else {
            TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                gravity = Gravity.CENTER
                textSize = 22f
                setTextColor(Color.WHITE)
                background = circleDrawable(if (isAddTile) 0xFF1976D2.toInt() else 0xFF9E9E9E.toInt())
                text = if (isAddTile) "+" else (title.firstOrNull()?.uppercase() ?: "?")
            }
        }

        val labelView = TextView(context).apply {
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
                onOpenUrl(url)
            }
        }

        // ScrollView içinde standart uzun-basma algılayıcısı en ufak kaydırma
        // titremesinde iptal olabiliyor; bu yüzden kendi toleranslı algılayıcımızı kuruyoruz.
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

    // ---- Yer imi sürükle-bırak (sıralama ve silme) ----

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
        val options = arrayOf("Sil", "Düzenle", "Yeni Sekmede Aç")
        AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        bookmarks.removeAll { it.url == url }
                        prefs().edit().remove(faviconKey(url)).apply()
                        saveBookmarksList()
                        refreshBookmarksGrid()
                        Toast.makeText(context, "Yer imi silindi", Toast.LENGTH_SHORT).show()
                    }
                    1 -> showEditBookmarkDialog(url)
                    2 -> onOpenInNewTab(url)
                }
            }
            .show()
    }

    private fun showEditBookmarkDialog(url: String) {
        val bookmark = bookmarks.find { it.url == url } ?: return
        val oldUrl = bookmark.url

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), 0)
        }
        val titleInput = EditText(context).apply {
            hint = "Başlık"
            setText(bookmark.title)
        }
        val urlInput = EditText(context).apply {
            hint = "URL"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(bookmark.url)
        }
        container.addView(titleInput)
        container.addView(urlInput)

        AlertDialog.Builder(context)
            .setTitle("Yer İmini Düzenle")
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
                    val oldIcon = prefs().getString(faviconKey(oldUrl), null)
                    prefs().edit().remove(faviconKey(oldUrl)).apply()
                    if (oldIcon != null) {
                        prefs().edit().putString(faviconKey(newUrl), oldIcon).apply()
                    } else {
                        fetchFaviconAsync(newUrl)
                    }
                }

                saveBookmarksList()
                refreshBookmarksGrid()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun showAddBookmarkDialog() {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), 0)
        }
        val titleInput = EditText(context).apply { hint = "Başlık" }
        val urlInput = EditText(context).apply {
            hint = "URL"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        container.addView(titleInput)
        container.addView(urlInput)

        AlertDialog.Builder(context)
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

    companion object {
        private const val BOOKMARK_MIME = "application/x-via-bookmark"
        private const val BOOKMARK_DRAG_LABEL = "via_bookmark"
    }
}
