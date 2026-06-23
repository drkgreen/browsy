package com.viabrowser.lite

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.viabrowser.lite.databinding.ActivityMainBinding
import java.io.ByteArrayInputStream
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val homeUrl = "https://www.google.com"

    // Basit reklam/izleyici engelleme listesi
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

        binding.editUrl.setText(homeUrl)
        binding.webView.loadUrl(homeUrl)
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
                binding.editUrl.setText(url)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                binding.progressBar.progress = newProgress
                binding.progressBar.visibility =
                    if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener {
            if (binding.webView.canGoBack()) binding.webView.goBack()
        }
        binding.btnForward.setOnClickListener {
            if (binding.webView.canGoForward()) binding.webView.goForward()
        }
        binding.btnReload.setOnClickListener {
            binding.webView.reload()
        }
        binding.editUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                loadFromInput()
                true
            } else {
                false
            }
        }

        // Alt bar
        binding.btnBottomBack.setOnClickListener {
            if (binding.webView.canGoBack()) binding.webView.goBack()
        }
        binding.btnBottomForward.setOnClickListener {
            if (binding.webView.canGoForward()) binding.webView.goForward()
        }
        binding.btnHome.setOnClickListener {
            binding.webView.loadUrl(homeUrl)
        }
        binding.btnTabs.setOnClickListener {
            android.widget.Toast.makeText(this, "Sekmeler yakında eklenecek", android.widget.Toast.LENGTH_SHORT).show()
        }
        binding.btnClose.setOnClickListener {
            finishAffinity()
        }
    }

    private fun loadFromInput() {
        var input = binding.editUrl.text.toString().trim()
        if (input.isEmpty()) return

        val looksLikeUrl = input.contains(".") && !input.contains(" ")
        input = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            looksLikeUrl -> "https://$input"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(input, "UTF-8")
        }
        binding.webView.loadUrl(input)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && binding.webView.canGoBack()) {
            binding.webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
