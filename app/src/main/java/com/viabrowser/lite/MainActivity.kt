package com.viabrowser.lite

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
[...]

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val bookmarks = mutableListOf<Pair<String, String>>()

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

    private fun showHomeScreen() {
        binding.browserRoot.visibility = View.GONE
        binding.homeContainer.visibility = View.VISIBLE
        binding.homeSearchBox.setText("")
    }

    private fun showBrowser() {
        binding.homeContainer.visibility = View.GONE
        binding.browserRoot.visibility = View.VISIBLE
    }

    private fun refreshBookmarksGrid() {
        binding.bookmarksGrid.removeAllViews()
        bookmarks.forEach { (title, url) →
            binding.bookmarksGrid.addView(buildBookmarkTile(title, url, false))
        }
        binding.bookmarksGrid.addView(buildBookmarkTile("", null, true))
    }

    private fun showAddBookmarkDialog() {
        [yer işareti ekleme dialog'u...]
    }
}
