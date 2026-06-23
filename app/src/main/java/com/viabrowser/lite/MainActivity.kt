package com.viabrowser.lite

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.viabrowser.lite.databinding.ActivityMainBinding
import java.io.ByteArrayInputStream
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val bookmarks = mutableListOf<Pair<String, String>>()

    private val adHosts = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google.com", "ads.youtube.com", "adnxs.com",
        "popads.net", "taboola.com", "outbrain.com", "scorecardresearch.com"
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
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val host = request.url.host ?: ""
                if (adHosts.any { host.endsWith(it) }) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return super.shouldInterceptRequest(view, request)
            }
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                binding.editUrl.setText(url)
            }
        }
        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.progressBar.progress = newProgress
                binding.progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { if (binding.webView.canGoBack()) binding.webView.goBack() }
        binding.btnForward.setOnClickListener { if (binding.webView.canGoForward()) binding.webView.goForward() }
        binding.btnReload.setOnClickListener { binding.webView.reload() }
        binding.editUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) { loadFromInput(); true } else false
        }
        binding.btnBottomBack.setOnClickListener { if (binding.webView.canGoBack()) { showBrowser(); binding.webView.goBack() } }
        binding.btnBottomForward.setOnClickListener { if (binding.webView.canGoForward()) { showBrowser(); binding.webView.goForward() } }
        binding.btnHome.setOnClickListener { showHomeScreen() }
        binding.btnTabs.setOnClickListener { Toast.makeText(this, "Sekmeler yakında eklenecek", Toast.LENGTH_SHORT).show() }
        binding.btnClose.setOnClickListener { finishAffinity() }
        binding.homeSearchBox.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                val input = binding.homeSearchBox.text.toString().trim()
                if (input.isNotEmpty()) { showBrowser(); binding.webView.loadUrl(resolveUrl(input)) }
                true
            } else false
        }
    }

    private fun showHomeScreen() { binding.browserRoot.visibility = View.GONE; binding.homeContainer.visibility = View.VISIBLE; binding.homeSearchBox.setText("") }
    private fun showBrowser() { binding.homeContainer.visibility = View.GONE; binding.browserRoot.visibility = View.VISIBLE }
    private fun resolveUrl(raw: String): String {
        val looksLikeUrl = raw.contains(".") && !raw.contains(" ")
        return when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            looksLikeUrl -> "https://$raw"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(raw, "UTF-8")
        }
    }
    private fun loadFromInput() { val input = binding.editUrl.text.toString().trim(); if (input.isNotEmpty()) binding.webView.loadUrl(resolveUrl(input)) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun circleDrawable(color: Int): GradientDrawable = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun loadBookmarks() {
        bookmarks.clear()
        val raw = getSharedPreferences("via_lite_prefs", MODE_PRIVATE).getString("bookmarks", "") ?: ""
        if (raw.isNotBlank()) raw.split("\n").forEach { val parts = it.split("::"); if (parts.size == 2) bookmarks.add(parts[0] to parts[1]) }
    }
    private fun saveBookmarks() {
        val raw = bookmarks.joinToString("\n") { "${it.first}::${it.second}" }
        getSharedPreferences("via_lite_prefs", MODE_PRIVATE).edit().putString("bookmarks", raw).apply()
    }
    private fun refreshBookmarksGrid() {
        binding.bookmarksGrid.removeAllViews()
        bookmarks.forEach { (title, url) -> binding.bookmarksGrid.addView(buildBookmarkTile(title, url, false)) }
        binding.bookmarksGrid.addView(buildBookmarkTile("", null, true))
    }

    private fun buildBookmarkTile(title: String, url: String?, isAddTile: Boolean): View {
        val tileSize = resources.displayMetrics.widthPixels / 4
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true; isFocusable = true
            setPadding(dp(4), dp(12), dp(4), dp(12))
            layoutParams = GridLayout.LayoutParams().apply { width = tileSize; height = LinearLayout.LayoutParams.WRAP_CONTENT }
        }
        val iconView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            gravity = Gravity.CENTER; textSize = 18f; setTextColor(Color.WHITE)
            background = circleDrawable(if (isAddTile) 0xFF1976D2.toInt() else 0xFF9E9E9E.toInt())
            text = if (isAddTile) "+" else (title.firstOrNull()?.uppercase() ?: "?")
        }
        val labelView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            text = if (isAddTile) "Ekle" else title; textSize = 12f; gravity = Gravity.CENTER; maxLines = 1
            ellipsize = TextUtils.TruncateAt.END; setTextColor(0xFF424242.toInt())
        }
        container.addView(iconView); container.addView(labelView)
        container.setOnClickListener {
            if (isAddTile) showAddBookmarkDialog() else if (url != null) { showBrowser(); binding.editUrl.setText(url); binding.webView.loadUrl(url) }
        }
        return container
    }

    private fun showAddBookmarkDialog() {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), 0) }
        val titleInput = EditText(this).apply { hint = "Başlık" }
        val urlInput = EditText(this).apply { hint = "URL"; inputType = InputType.TYPE_TEXT_VARIATION_URI }
        container.addView(titleInput); container.addView(urlInput)
        AlertDialog.Builder(this)
            .setTitle("Yer İmi Ekle")
            .setView(container)
            .setPositiveButton("Ekle") { _, _ ->
                var url = urlInput.text.toString().trim()
                var title = titleInput.text.toString().trim()
                if (url.isEmpty()) return@setPositiveButton
                if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
                if (title.isEmpty()) title = url.removePrefix("https://").removePrefix("http://")
                bookmarks.add(title to url)
                saveBookmarks()
                refreshBookmarksGrid()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && binding.browserRoot.visibility == View.VISIBLE && binding.webView.canGoBack()) {
            binding.webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
