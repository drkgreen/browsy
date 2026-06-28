package com.viabrowser.lite

import android.content.Context
import androidx.core.content.ContextCompat
import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog

data class HistoryEntry(val title: String, val url: String, val timestamp: Long)

/**
 * Ziyaret geçmişi listesi. MainActivity'den ayrıldı; bir sayfaya tıklanınca
 * onu açmak MainActivity'nin (WebView/sekme durumuna bağlı) işi olduğu için
 * bu, basit bir callback (onOpenUrl) ile dışarıdan veriliyor.
 */
class HistoryManager(private val context: Context) {

    private fun prefs() = context.getSharedPreferences("via_lite_prefs", Context.MODE_PRIVATE)

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun loadHistory(): MutableList<HistoryEntry> {
        val raw = prefs().getString("history", "") ?: ""
        if (raw.isBlank()) return mutableListOf()
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split("::")
            if (parts.size == 3) HistoryEntry(parts[0], parts[1], parts[2].toLongOrNull() ?: 0L) else null
        }.toMutableList()
    }

    private fun saveHistory(list: List<HistoryEntry>) {
        val raw = list.joinToString("\n") { "${it.title}::${it.url}::${it.timestamp}" }
        prefs().edit().putString("history", raw).apply()
    }

    fun addHistoryEntry(title: String, url: String) {
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

    fun clearHistory() {
        prefs().edit().remove("history").apply()
    }

    fun formatTimestamp(ts: Long): String {
        val fmt = java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale("tr"))
        return fmt.format(java.util.Date(ts))
    }

    fun showHistoryList(onOpenUrl: (String) -> Unit) {
        val list = loadHistory()
        val dialog = BottomSheetDialog(context)
        val rootColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val clearRow = TextView(context).apply {
            text = "Geçmişi Temizle"
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.danger))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                clearHistory()
                dialog.dismiss()
                Toast.makeText(context, "Geçmiş temizlendi", Toast.LENGTH_SHORT).show()
            }
        }

        val scrollView = ScrollView(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(16))
        }
        scrollView.addView(container)

        if (list.isEmpty()) {
            container.addView(
                TextView(context).apply {
                    text = "Geçmiş boş"
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                }
            )
        } else {
            list.forEach { entry ->
                container.addView(buildHistoryRow(entry, dialog, onOpenUrl))
            }
        }

        if (list.isNotEmpty()) {
            rootColumn.addView(clearRow)
            rootColumn.addView(
                View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                    setBackgroundColor(ContextCompat.getColor(context, R.color.divider))
                }
            )
        }
        rootColumn.addView(scrollView)

        dialog.setContentView(rootColumn)
        dialog.show()
    }

    private fun buildHistoryRow(entry: HistoryEntry, dialog: BottomSheetDialog, onOpenUrl: (String) -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dialog.dismiss()
                onOpenUrl(entry.url)
            }
        }
        row.addView(
            TextView(context).apply {
                text = entry.title
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            }
        )
        row.addView(
            TextView(context).apply {
                text = formatTimestamp(entry.timestamp)
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setPadding(0, dp(2), 0, 0)
            }
        )
        return row
    }
}
