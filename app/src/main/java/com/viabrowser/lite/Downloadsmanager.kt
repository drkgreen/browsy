package com.viabrowser.lite

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.text.TextUtils
import android.view.View
import android.webkit.URLUtil
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog

data class DownloadRecord(val fileName: String, val url: String, val timestamp: Long)

/**
 * Dosya indirme akışı ve indirilenler listesi. MainActivity'den ayrıldı,
 * sadece bir Context'e ihtiyaç duyuyor -- WebView/sekme durumuna hiç
 * dokunmuyor.
 */
class DownloadsManager(private val context: Context) {

    private fun prefs() = context.getSharedPreferences("via_lite_prefs", Context.MODE_PRIVATE)

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun startFileDownload(url: String, userAgent: String, contentDisposition: String, mimeType: String) {
        val fileName = try {
            URLUtil.guessFileName(url, contentDisposition, mimeType)
        } catch (e: Exception) {
            "dosya"
        }

        val askBeforeDownload = prefs().getBoolean("ask_before_download", false)

        if (askBeforeDownload) {
            AlertDialog.Builder(context)
                .setTitle("Dosyayı İndir")
                .setMessage("\"$fileName\" indirilsin mi?")
                .setPositiveButton("İndir") { _, _ ->
                    enqueueDownload(url, userAgent, mimeType, fileName)
                }
                .setNegativeButton("Vazgeç", null)
                .show()
        } else {
            enqueueDownload(url, userAgent, mimeType, fileName)
        }
    }

    private fun enqueueDownload(url: String, userAgent: String, mimeType: String, fileName: String) {
        try {
            val showNotifications = prefs().getBoolean("download_notifications", true)

            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                addRequestHeader("User-Agent", userAgent)
                setTitle(fileName)
                setDescription("İndiriliyor...")
                setNotificationVisibility(
                    if (showNotifications) {
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    } else {
                        DownloadManager.Request.VISIBILITY_HIDDEN
                    }
                )
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)
            addDownloadRecord(fileName, url)
            Toast.makeText(context, "İndiriliyor: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "İndirme başlatılamadı", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadDownloads(): MutableList<DownloadRecord> {
        val raw = prefs().getString("downloads", "") ?: ""
        if (raw.isBlank()) return mutableListOf()
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split("::")
            if (parts.size == 3) DownloadRecord(parts[0], parts[1], parts[2].toLongOrNull() ?: 0L) else null
        }.toMutableList()
    }

    private fun saveDownloads(list: List<DownloadRecord>) {
        val raw = list.joinToString("\n") { "${it.fileName}::${it.url}::${it.timestamp}" }
        prefs().edit().putString("downloads", raw).apply()
    }

    private fun addDownloadRecord(fileName: String, url: String) {
        val list = loadDownloads()
        list.add(0, DownloadRecord(fileName, url, System.currentTimeMillis()))
        saveDownloads(list)
    }

    fun formatTimestamp(ts: Long): String {
        val fmt = java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale("tr"))
        return fmt.format(java.util.Date(ts))
    }

    fun showDownloadsList() {
        val list = loadDownloads()
        val dialog = BottomSheetDialog(context)
        val scrollView = ScrollView(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(16), dp(8), dp(16))
        }
        scrollView.addView(container)

        if (list.isEmpty()) {
            container.addView(
                TextView(context).apply {
                    text = "Henüz indirilen dosya yok"
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                    setTextColor(0xFF8E8E93.toInt())
                }
            )
        } else {
            list.forEach { item ->
                container.addView(buildDownloadRow(item))
                container.addView(
                    View(context).apply {
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
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        row.addView(
            TextView(context).apply {
                text = item.fileName
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(0xFF1A1A1A.toInt())
            }
        )
        row.addView(
            TextView(context).apply {
                text = formatTimestamp(item.timestamp)
                textSize = 12f
                setTextColor(0xFF8E8E93.toInt())
                setPadding(0, dp(2), 0, 0)
            }
        )
        return row
    }
}
