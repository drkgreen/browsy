package com.viabrowser.lite

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.viabrowser.lite.databinding.ActivityHistoryBinding

/**
 * Tam ekran geçmiş ekranı. Eskiden MainActivity'nin alt menüsünden açılan
 * bir BottomSheetDialog'du; arama, çoklu seçim ve tam ekran istendiği için
 * ayrı bir Activity'ye taşındı. Veri katmanı (yükleme/kaydetme/silme) hâlâ
 * HistoryManager'da -- bu sınıf sadece görünümü inşa ediyor.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private val historyManager by lazy { HistoryManager(this) }

    private var isSearchMode = false
    private var isSelectionMode = false
    private val selectedKeys = mutableSetOf<String>()
    private var currentQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnHistoryBack.setOnClickListener { onBackPressedHandler() }

        binding.btnHistorySearch.setOnClickListener {
            if (isSelectionMode) exitSelectionMode()
            toggleSearchMode()
        }

        binding.btnHistoryDelete.setOnClickListener {
            if (isSelectionMode) {
                confirmDeleteSelected()
            } else {
                confirmClearAll()
            }
        }

        binding.historySearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentQuery = s?.toString()?.trim() ?: ""
                renderList()
            }
        })

        renderList()
    }

    override fun onBackPressed() {
        onBackPressedHandler()
    }

    private fun onBackPressedHandler() {
        when {
            isSelectionMode -> exitSelectionMode()
            isSearchMode -> toggleSearchMode()
            else -> finish()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toggleSearchMode() {
        isSearchMode = !isSearchMode
        if (isSearchMode) {
            binding.historyTitle.visibility = View.GONE
            binding.historySearchInput.visibility = View.VISIBLE
            binding.historySearchInput.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.historySearchInput, InputMethodManager.SHOW_IMPLICIT)
        } else {
            binding.historySearchInput.setText("")
            binding.historySearchInput.visibility = View.GONE
            binding.historyTitle.visibility = View.VISIBLE
            currentQuery = ""
            hideKeyboard()
            renderList()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    private fun entryKey(entry: HistoryEntry): String = "${entry.timestamp}::${entry.url}"

    private fun enterSelectionMode(entry: HistoryEntry) {
        isSelectionMode = true
        selectedKeys.clear()
        selectedKeys.add(entryKey(entry))
        updateToolbarForSelection()
        renderList()
    }

    private fun exitSelectionMode() {
        isSelectionMode = false
        selectedKeys.clear()
        updateToolbarForSelection()
        renderList()
    }

    private fun toggleSelection(entry: HistoryEntry) {
        val key = entryKey(entry)
        if (selectedKeys.contains(key)) {
            selectedKeys.remove(key)
        } else {
            selectedKeys.add(key)
        }
        if (selectedKeys.isEmpty()) {
            exitSelectionMode()
        } else {
            updateToolbarForSelection()
            renderList()
        }
    }

    private fun updateToolbarForSelection() {
        if (isSelectionMode) {
            binding.historySearchInput.visibility = View.GONE
            binding.btnHistorySearch.visibility = View.GONE
            binding.historyTitle.visibility = View.VISIBLE
            binding.historyTitle.text = "${selectedKeys.size} seçildi"
            binding.btnHistoryBack.setImageResource(R.drawable.ic_close)
        } else {
            binding.btnHistorySearch.visibility = View.VISIBLE
            binding.historyTitle.text = "Geçmiş"
            binding.btnHistoryBack.setImageResource(R.drawable.ic_back)
        }
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle("Geçmişi Temizle")
            .setMessage("Tüm gezinme geçmişi silinsin mi?")
            .setPositiveButton("Sil") { _, _ ->
                historyManager.clearHistory()
                renderList()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun confirmDeleteSelected() {
        AlertDialog.Builder(this)
            .setTitle("Seçilenleri Sil")
            .setMessage("${selectedKeys.size} kayıt silinsin mi?")
            .setPositiveButton("Sil") { _, _ ->
                historyManager.deleteEntriesByKey(selectedKeys)
                exitSelectionMode()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun openUrl(url: String) {
        // HistoryActivity ayrı bir Activity olduğu için, linki MainActivity'nin
        // "Şununla Aç" mekanizmasıyla (ACTION_VIEW) açıyoruz -- yeni bir
        // bağlantı kurmaya gerek yok, zaten var olan kod bunu karşılıyor.
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(url)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        finish()
    }

    private fun renderList() {
        binding.historyContainer.removeAllViews()
        val all = historyManager.loadHistoryEntries()
        val list = if (currentQuery.isBlank()) {
            all
        } else {
            all.filter {
                it.title.contains(currentQuery, ignoreCase = true) ||
                    it.url.contains(currentQuery, ignoreCase = true)
            }
        }

        if (list.isEmpty()) {
            binding.historyContainer.addView(
                TextView(this).apply {
                    text = if (currentQuery.isBlank()) "Geçmiş boş" else "Sonuç bulunamadı"
                    setPadding(dp(16), dp(24), dp(16), dp(16))
                    setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.text_secondary))
                }
            )
            return
        }

        // Arama yaparken gün gruplaması/host gruplaması kafa karıştırır;
        // sadece arama sırasında düz, tarih+saat etiketli liste gösteriyoruz.
        if (currentQuery.isNotBlank()) {
            list.forEach { entry ->
                binding.historyContainer.addView(buildFlatRow(entry))
            }
            return
        }

        val grouped = list.groupBy { historyManager.dateLabelFor(it.timestamp) }
        grouped.forEach { (label, dayEntries) ->
            binding.historyContainer.addView(buildDateSectionHeader(label))

            val byHost = LinkedHashMap<String, MutableList<HistoryEntry>>()
            dayEntries.forEach { entry ->
                byHost.getOrPut(historyManager.hostOf(entry.url)) { mutableListOf() }.add(entry)
            }

            byHost.values.forEach { hostEntries ->
                if (hostEntries.size > 1 && !isSelectionMode) {
                    binding.historyContainer.addView(buildHostGroupRow(hostEntries))
                } else {
                    hostEntries.forEach { entry ->
                        binding.historyContainer.addView(buildHistoryRow(entry))
                    }
                }
            }
        }
    }

    private fun buildDateSectionHeader(label: String): View {
        return TextView(this).apply {
            text = label
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.colorPrimary))
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
    }

    private fun circleDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    private fun buildHostGroupRow(entries: List<HistoryEntry>): View {
        val host = historyManager.hostOf(entries[0].url)
        val latest = entries.first().timestamp
        val earliest = entries.last().timestamp
        val timeRange = if (historyManager.formatTimeOnly(latest) == historyManager.formatTimeOnly(earliest)) {
            "${historyManager.formatTimeOnly(latest)}'de"
        } else {
            "${historyManager.formatTimeOnly(earliest)}–${historyManager.formatTimeOnly(latest)}"
        }

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        var expanded = false
        val expandedContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(36), 0, 0, 0)
        }

        val summaryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            isFocusable = true
        }

        val avatar = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.WHITE)
            background = circleDrawable(ContextCompat.getColor(this@HistoryActivity, R.color.avatar_fallback))
            text = entries.size.toString()
        }

        val textContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(12) }
        }
        textContainer.addView(
            TextView(this).apply {
                text = host
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.text_primary))
            }
        )
        textContainer.addView(
            TextView(this).apply {
                text = "${entries.size} sayfa • $timeRange"
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.text_secondary))
                setPadding(0, dp(2), 0, 0)
            }
        )

        val chevron = ImageView(this).apply {
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
            expandedContainer.addView(buildHistoryRow(entry))
        }

        column.addView(summaryRow)
        column.addView(expandedContainer)
        return column
    }

    private fun buildHistoryRow(entry: HistoryEntry): View {
        val key = entryKey(entry)
        val isSelected = selectedKeys.contains(key)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            if (isSelected) {
                setBackgroundColor(ContextCompat.getColor(this@HistoryActivity, R.color.active_tab_highlight))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(entry)
                } else {
                    openUrl(entry.url)
                }
            }
            setOnLongClickListener {
                if (!isSelectionMode) {
                    enterSelectionMode(entry)
                } else {
                    toggleSelection(entry)
                }
                true
            }
        }

        if (isSelectionMode) {
            row.addView(
                ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) }
                    setImageResource(if (isSelected) R.drawable.ic_check_circle else R.drawable.ic_circle_outline)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
            )
        } else {
            row.addView(
                TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT)
                    text = historyManager.formatTimeOnly(entry.timestamp)
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.text_secondary))
                }
            )
        }

        val textContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(8) }
        }
        textContainer.addView(
            TextView(this).apply {
                text = entry.title
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.text_primary))
            }
        )
        textContainer.addView(
            TextView(this).apply {
                text = entry.url
                textSize = 12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.text_secondary))
                setPadding(0, dp(2), 0, 0)
            }
        )
        row.addView(textContainer)

        if (!isSelectionMode) {
            row.addView(
                TextView(this).apply {
                    text = "✕"
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.text_secondary))
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        historyManager.deleteEntriesByKey(setOf(key))
                        renderList()
                    }
                }
            )
        }

        return row
    }

    private fun buildFlatRow(entry: HistoryEntry): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { openUrl(entry.url) }
        }
        row.addView(
            TextView(this).apply {
                text = entry.title
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.text_primary))
            }
        )
        row.addView(
            TextView(this).apply {
                text = "${entry.url} • ${historyManager.formatTimestamp(entry.timestamp)}"
                textSize = 12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(ContextCompat.getColor(this@HistoryActivity, R.color.text_secondary))
                setPadding(0, dp(2), 0, 0)
            }
        )
        return row
    }
}
