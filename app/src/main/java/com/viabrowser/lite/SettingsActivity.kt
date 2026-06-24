package com.viabrowser.lite

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.viabrowser.lite.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSettingsBack.setOnClickListener { finish() }

        buildSettingsList()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun prefs() = getSharedPreferences("via_lite_prefs", MODE_PRIVATE)

    private fun rippleBackground(): android.graphics.drawable.Drawable? {
        val typedValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
        return ContextCompat.getDrawable(this, typedValue.resourceId)
    }

    private fun buildSettingsList() {
        binding.settingsContainer.removeAllViews()
        binding.settingsContainer.addView(buildSectionHeader("Genel"))
        binding.settingsContainer.addView(buildSearchEngineRow())
        binding.settingsContainer.addView(buildDivider())
        binding.settingsContainer.addView(buildStartPageRow())
        binding.settingsContainer.addView(buildDivider())
        binding.settingsContainer.addView(buildDefaultBrowserRow())

        binding.settingsContainer.addView(buildSectionHeader("Görünüm"))
        binding.settingsContainer.addView(buildForceDarkRow())
        binding.settingsContainer.addView(buildDivider())
        binding.settingsContainer.addView(buildTextZoomRow())
    }

    private fun buildSectionHeader(text: String): View {
        return TextView(this).apply {
            setPadding(dp(16), dp(16), dp(16), dp(8))
            this.text = text
            textSize = 13f
            setTextColor(0xFF1976D2.toInt())
            setTypeface(typeface, Typeface.BOLD)
        }
    }

    private fun buildDivider(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(0xFFE0E0E0.toInt())
        }
    }

    private fun buildSettingsRow(title: String, subtitle: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            isFocusable = true
            background = rippleBackground()
            setOnClickListener { onClick() }
        }
        val titleView = TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(0xFF1A1A1A.toInt())
        }
        val subtitleView = TextView(this).apply {
            text = subtitle
            textSize = 13f
            setTextColor(0xFF8E8E93.toInt())
            setPadding(0, dp(4), 0, 0)
        }
        row.addView(titleView)
        row.addView(subtitleView)
        return row
    }

    // ---- Arama motoru ----

    private fun getSearchEngineKey(): String = prefs().getString("search_engine", "google") ?: "google"

    private fun searchEngineDisplayName(key: String): String = when (key) {
        "bing" -> "Bing"
        "duckduckgo" -> "DuckDuckGo"
        else -> "Google"
    }

    private fun buildSearchEngineRow(): View {
        return buildSettingsRow("Arama Motoru", searchEngineDisplayName(getSearchEngineKey())) {
            showSearchEngineDialog()
        }
    }

    private fun showSearchEngineDialog() {
        val options = arrayOf("Google", "Bing", "DuckDuckGo")
        val keys = arrayOf("google", "bing", "duckduckgo")
        val checkedIndex = keys.indexOf(getSearchEngineKey()).let { if (it == -1) 0 else it }

        AlertDialog.Builder(this)
            .setTitle("Arama Motoru")
            .setSingleChoiceItems(options, checkedIndex) { dialog, which ->
                prefs().edit().putString("search_engine", keys[which]).apply()
                dialog.dismiss()
                buildSettingsList()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    // ---- Açılış sayfası ----

    private fun isCustomStartPage(): Boolean = prefs().getString("home_mode", "default") == "custom"

    private fun getCustomStartUrl(): String = prefs().getString("home_custom_url", "") ?: ""

    private fun buildStartPageRow(): View {
        val subtitle = if (isCustomStartPage()) {
            "Özel sayfa: " + getCustomStartUrl()
        } else {
            "Ana ekranı göster"
        }
        return buildSettingsRow("Açılış Sayfası", subtitle) {
            showStartPageDialog()
        }
    }

    private fun showStartPageDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }

        val radioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        val optionDefault = RadioButton(this).apply {
            text = "Ana ekranı göster"
            id = 1
        }
        val optionCustom = RadioButton(this).apply {
            text = "Özel bir sayfa aç"
            id = 2
        }
        radioGroup.addView(optionDefault)
        radioGroup.addView(optionCustom)

        val urlInput = EditText(this).apply {
            hint = "https://..."
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(getCustomStartUrl())
            setPadding(0, dp(8), 0, 0)
        }

        container.addView(radioGroup)
        container.addView(urlInput)

        radioGroup.check(if (isCustomStartPage()) 2 else 1)

        AlertDialog.Builder(this)
            .setTitle("Açılış Sayfası")
            .setView(container)
            .setPositiveButton("Kaydet") { _, _ ->
                if (radioGroup.checkedRadioButtonId == 2) {
                    var url = urlInput.text.toString().trim()
                    if (url.isNotBlank()) {
                        if (!url.startsWith("http://") && !url.startsWith("https://")) {
                            url = "https://$url"
                        }
                        prefs().edit()
                            .putString("home_mode", "custom")
                            .putString("home_custom_url", url)
                            .apply()
                    } else {
                        prefs().edit().putString("home_mode", "default").apply()
                    }
                } else {
                    prefs().edit().putString("home_mode", "default").apply()
                }
                buildSettingsList()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun buildSwitchRow(
        title: String,
        subtitle: String,
        initialChecked: Boolean,
        onToggle: (Boolean) -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        val textContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titleView = TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(0xFF1A1A1A.toInt())
        }
        val subtitleView = TextView(this).apply {
            text = subtitle
            textSize = 13f
            setTextColor(0xFF8E8E93.toInt())
            setPadding(0, dp(4), 0, 0)
        }
        textContainer.addView(titleView)
        textContainer.addView(subtitleView)

        val switch = Switch(this).apply {
            isChecked = initialChecked
            setOnCheckedChangeListener { _, checked -> onToggle(checked) }
        }

        row.addView(textContainer)
        row.addView(switch)
        return row
    }

    // ---- Web sayfalarını karartma ----

    private fun isForceDarkEnabled(): Boolean = prefs().getBoolean("force_dark_web", false)

    private fun buildForceDarkRow(): View {
        return buildSwitchRow(
            "Web Sayfalarını Karartma",
            "Karanlık modu desteklemeyen sitelerde de karanlık görünüm uygular",
            isForceDarkEnabled()
        ) { checked ->
            prefs().edit().putBoolean("force_dark_web", checked).apply()
        }
    }

    // ---- Yazı boyutu ----

    private fun getTextZoom(): Int = prefs().getInt("text_zoom", 100)

    private fun textZoomDisplayName(zoom: Int): String = when (zoom) {
        80 -> "Küçük"
        100 -> "Normal"
        120 -> "Büyük"
        150 -> "Çok Büyük"
        else -> "%$zoom"
    }

    private fun buildTextZoomRow(): View {
        return buildSettingsRow("Yazı Boyutu", textZoomDisplayName(getTextZoom())) {
            showTextZoomDialog()
        }
    }

    private fun showTextZoomDialog() {
        val zooms = intArrayOf(80, 100, 120, 150)
        val labels = arrayOf("Küçük", "Normal", "Büyük", "Çok Büyük")
        val checkedIndex = zooms.indexOf(getTextZoom()).let { if (it == -1) 1 else it }

        AlertDialog.Builder(this)
            .setTitle("Yazı Boyutu")
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                prefs().edit().putInt("text_zoom", zooms[which]).apply()
                dialog.dismiss()
                buildSettingsList()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    // ---- Varsayılan tarayıcı ----

    private fun buildDefaultBrowserRow(): View {
        return buildSettingsRow("Varsayılan Tarayıcı Yap", "Sistem ayarlarını aç") {
            openDefaultAppsSettings()
        }
    }

    private fun openDefaultAppsSettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(this, "Sistem ayarları açılamadı", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
