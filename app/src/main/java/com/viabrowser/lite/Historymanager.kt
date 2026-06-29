package com.viabrowser.lite

import android.content.Context
import android.net.Uri

data class HistoryEntry(val title: String, val url: String, val timestamp: Long)

/**
 * Ziyaret geçmişinin veri katmanı (yükleme/kaydetme/silme/biçimlendirme).
 * Görünüm artık HistoryActivity'de -- bu sınıf sadece depolama mantığını
 * tutuyor, UI'ya hiç dokunmuyor.
 */
class HistoryManager(private val context: Context) {

    private fun prefs() = context.getSharedPreferences("via_lite_prefs", Context.MODE_PRIVATE)

    private fun loadHistory(): MutableList<HistoryEntry> {
        val raw = prefs().getString("history", "") ?: ""
        if (raw.isBlank()) return mutableListOf()
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split("::")
            if (parts.size == 3) HistoryEntry(parts[0], parts[1], parts[2].toLongOrNull() ?: 0L) else null
        }.toMutableList()
    }

    fun loadHistoryEntries(): List<HistoryEntry> = loadHistory()

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

    /** entryKey() ile üretilen "timestamp::url" anahtarlarına göre kayıtları siler. */
    fun deleteEntriesByKey(keys: Set<String>) {
        val list = loadHistory()
        list.removeAll { "${it.timestamp}::${it.url}" in keys }
        saveHistory(list)
    }

    fun formatTimestamp(ts: Long): String {
        val fmt = java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale("tr"))
        return fmt.format(java.util.Date(ts))
    }

    fun formatTimeOnly(ts: Long): String {
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale("tr"))
        return fmt.format(java.util.Date(ts))
    }

    fun hostOf(url: String): String =
        try { Uri.parse(url).host ?: url } catch (e: Exception) { url }

    // "Bugün" / "Dün" / "25 Haziran 2026" gibi gün başlığı üretir; geçmiş
    // listesini bu başlıklara göre gruplamak için kullanılıyor.
    fun dateLabelFor(timestamp: Long): String {
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
}
