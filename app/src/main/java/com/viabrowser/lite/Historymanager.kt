package com.viabrowser.lite

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog

data class HistoryEntry(val title: String, val url: String, val timestamp: Long)

/**
 * Ziyaret geçmişi listesi. MainActivity'den ayrıldı; bir sayfaya tıklanınca
 * onu açmak MainActivity'nin (WebView/sekme durumuna bağlı) işi olduğu için
 * bu, basit bir callback (onOpenUrl) ile dışarıdan veriliyor.
 *
 * Görünüm Chrome'un geçmiş ekranından esinlendi: aynı gün içinde aynı
 * domain'den birden fazla ziyaret tek bir genişleyebilir satıra toplanıyor
 * (sayı rozeti + "N sayfa • saat aralığı" + ok), tekil ziyaretler ayrı
 * satır olarak (saat + başlık + URL + sil menüsü) duruyor.
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

    private fun deleteHistoryEntry(entry: HistoryEntry) {
        val list = loadHistory()
        list.removeAll { it.timestamp == entry.timestamp && it.url == entry.url }
        saveHistory(list)
    }

    fun formatTimestamp(ts: Long): String {
        val fmt = java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale("tr"))
        return fmt.format(java.util.Date(ts))
    }

    private fun formatTimeOnly(ts: Long): String {
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale("tr"))
        return fmt.format(java.util.Date(ts))
    }

    private fun hostOf(url: String): String =
        try { Uri.parse(url).host ?: url } catch (e: Exception) { url }

    // "Bugün" / "Dün" / "25 Haziran 2026" gibi gün başlığı üretir; geçmiş
    // listesini bu başlıklara göre gruplamak için kullanılıyor.
    private fun dateLabelFor(timestamp: Long): String {
        val entryDay = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
        val today = java.util.Calendar.getInstance()
        val yesterday = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }

        fun sameDay(a: java.util.Calendar, b: java.util.Calendar): Boolean =
            a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) &&
                a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)

        return when {
            sameDay(entryDay, today) -> "Bugün"
            sameDay(entryDay, yesterday) -> "Dün"
            else -> {
                val fmt = java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale("tr"))
                fmt.format(java.util.Date(timestamp))
            }
        }
    }

    private fun buildDateSectionHeader(label: String): View {
        return TextView(context).apply {
            text = label
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.colorPrimary))
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
    }

    private fun circleDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
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
            // Liste zaten en yeni en üstte sıralı (addHistoryEntry add(0,...) ile
            // ekliyor); gün başlıklarını bu sırayla koruyoruz.
            val grouped = list.groupBy { dateLabelFor(it.timestamp) }
            grouped.forEach { (label, dayEntries) ->
                container.addView(buildDateSectionHeader(label))

                // Aynı gün içinde aynı domain'den birden fazla ziyareti
                // gruplandırıyoruz; tek ziyaretler ayrı satır olarak kalıyor.
                // Sıralama: her grubun en yeni ziyaretine göre (liste zaten
                // yeni-en-üstte olduğundan ilk görülen host o anki en yenisi).
                val byHost = LinkedHashMap<String, MutableList<HistoryEntry>>()
                dayEntries.forEach { entry ->
                    byHost.getOrPut(hostOf(entry.url)) { mutableListOf() }.add(entry)
                }

                byHost.values.forEach { hostEntries ->
                    if (hostEntries.size > 1) {
                        container.addView(buildHostGroupRow(hostEntries, dialog, onOpenUrl))
                    } else {
                        container.addView(buildHistoryRow(hostEntries[0], dialog, onOpenUrl) {
                            deleteHistoryEntry(hostEntries[0])
                            dialog.dismiss()
                            showHistoryList(onOpenUrl)
                        })
                    }
                }
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

    private fun buildHostGroupRow(
        entries: List<HistoryEntry>,
        dialog: BottomSheetDialog,
        onOpenUrl: (String) -> Unit
    ): View {
        // entries zaten yeni-en-üstte: ilk eleman en yeni, son eleman en eski.
        val host = hostOf(entries[0].url)
        val latest = entries.first().timestamp
        val earliest = entries.last().timestamp
        val timeRange = if (formatTimeOnly(latest) == formatTimeOnly(earliest)) {
            "${formatTimeOnly(latest)}'de"
        } else {
            "${formatTimeOnly(earliest)}–${formatTimeOnly(latest)}"
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        var expanded = false
        val expandedContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(36), 0, 0, 0)
        }

        val summaryRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            isFocusable = true
        }

        val avatar = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.WHITE)
            background = circleDrawable(ContextCompat.getColor(context, R.color.avatar_fallback))
            text = entries.size.toString()
        }

        val textContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(12) }
        }
        textContainer.addView(
            TextView(context).apply {
                text = host
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            }
        )
        textContainer.addView(
            TextView(context).apply {
                text = "${entries.size} sayfa • $timeRange"
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setPadding(0, dp(2), 0, 0)
            }
        )

        val chevron = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            setImageResource(R.drawable.ic_forward)
            rotation = 90f
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        summaryRow.addView(avatar)
        summaryRow.addView(textContainer)
        summaryRow.addView(chevron)

        summaryRow.setOnClickListener {
            expanded = !expanded
            expandedContainer.visibility = if (expanded) View.VISIBLE else View.GONE
            chevron.rotation = if (expanded) 270f else 90f
        }

        entries.forEach { entry ->
            expandedContainer.addView(buildHistoryRow(entry, dialog, onOpenUrl) {
                deleteHistoryEntry(entry)
                dialog.dismiss()
                showHistoryList(onOpenUrl)
            })
        }

        column.addView(summaryRow)
        column.addView(expandedContainer)
        return column
    }

    private fun buildHistoryRow(
        entry: HistoryEntry,
        dialog: BottomSheetDialog,
        onOpenUrl: (String) -> Unit,
        onDelete: () -> Unit
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dialog.dismiss()
                onOpenUrl(entry.url)
            }
        }

        val timeView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT)
            text = formatTimeOnly(entry.timestamp)
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        }

        val textContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(8) }
        }
        textContainer.addView(
            TextView(context).apply {
                text = entry.title
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            }
        )
        textContainer.addView(
            TextView(context).apply {
                text = entry.url
                textSize = 12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setPadding(0, dp(2), 0, 0)
            }
        )

        val deleteButton = TextView(context).apply {
            text = "✕"
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onDelete()
            }
        }

        row.addView(timeView)
        row.addView(textContainer)
        row.addView(deleteButton)
        return row
    }
}
