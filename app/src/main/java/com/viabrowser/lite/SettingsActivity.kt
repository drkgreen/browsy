package com.viabrowser.lite

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
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
        binding.settingsContainer.addView(buildDivider())
        binding.settingsContainer.addView(buildTextReflowRow())

        binding.settingsContainer.addView(buildSectionHeader("Gizlilik ve Güvenlik"))
        binding.settingsContainer.addView(buildClearDataRow())
        binding.settingsContainer.addView(buildDivider())
        binding.settingsContainer.addView(buildThirdPartyCookiesRow())
        binding.settingsContainer.addView(buildDivider())
        binding.settingsContainer.addView(buildSitePermissionsRow())

        binding.settingsContainer.addView(buildSectionHeader("Otomatik Doldurma"))
        binding.settingsContainer.addView(buildSavedAddressesRow())
        binding.settingsContainer.addView(buildDivider())
        binding.settingsContainer.addView(buildSystemAutofillRow())

        binding.settingsContainer.addView(buildSectionHeader("İndirilenler"))
        binding.settingsContainer.addView(buildAskBeforeDownloadRow())
        binding.settingsContainer.addView(buildDivider())
        binding.settingsContainer.addView(buildDownloadNotificationsRow())
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
            setPadding(0, dp(16), 0, dp(20))
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
            prefs().edit()
                .putBoolean("force_dark_web", checked)
                .putBoolean("pending_appearance_reload", true)
                .apply()
        }
    }

    // ---- Yazı boyutu ----

    private fun getTextZoom(): Int = prefs().getInt("text_zoom", 100)

    private fun buildTextZoomRow(): View {
        return buildSettingsRow("Yazı Boyutu", "%${getTextZoom()}") {
            showTextZoomDialog()
        }
    }

    private fun buildTextReflowRow(): View {
        return buildSwitchRow(
            "Metni Sayfaya Sığdır",
            "Mobil uyumlu olmayan sitelerde yatay kaydırmayı önler",
            prefs().getBoolean("text_reflow", false)
        ) { checked ->
            prefs().edit()
                .putBoolean("text_reflow", checked)
                .putBoolean("pending_appearance_reload", true)
                .apply()
        }
    }

    private fun showTextZoomDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }

        val currentZoom = getTextZoom()

        val previewBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(0xFFF5F5F5.toInt())
                cornerRadius = dp(8).toFloat()
            }
        }

        val previewText = TextView(this).apply {
            text = "Örnek metin böyle görünecek."
            setTextColor(0xFF1A1A1A.toInt())
            textSize = 16f * (currentZoom / 100f)
        }
        previewBox.addView(previewText)

        val valueLabel = TextView(this).apply {
            text = "%$currentZoom"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFF8E8E93.toInt())
            setPadding(0, dp(12), 0, dp(4))
        }

        val seekBar = android.widget.SeekBar(this).apply {
            max = 150 // 50 ile 200 arası, +50 ekleyerek gerçek yüzdeye çeviriyoruz
            progress = (currentZoom - 50).coerceIn(0, 150)
        }
        seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                val zoom = progress + 50
                valueLabel.text = "%$zoom"
                previewText.textSize = 16f * (zoom / 100f)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) {}
        })

        container.addView(previewBox)
        container.addView(valueLabel)
        container.addView(seekBar)

        AlertDialog.Builder(this)
            .setTitle("Yazı Boyutu")
            .setView(container)
            .setPositiveButton("Kaydet") { _, _ ->
                val zoom = seekBar.progress + 50
                prefs().edit()
                    .putInt("text_zoom", zoom)
                    .putBoolean("pending_appearance_reload", true)
                    .apply()
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

    // ---- Verileri temizleme ----

    private fun buildClearDataRow(): View {
        return buildSettingsRow("Verileri Temizle", "Geçmiş, çerezler, önbellek ve site verileri") {
            showClearDataConfirmation()
        }
    }

    private fun showClearDataConfirmation() {
        val options = arrayOf("Geçmiş", "Çerezler", "Önbellek", "Site Verileri (depolama)")
        val checkedItems = booleanArrayOf(true, true, true, true)

        AlertDialog.Builder(this)
            .setTitle("Verileri Temizle")
            .setMultiChoiceItems(options, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("Temizle") { _, _ ->
                clearBrowsingData(
                    clearHistory = checkedItems[0],
                    clearCookies = checkedItems[1],
                    clearCache = checkedItems[2],
                    clearSiteData = checkedItems[3]
                )
                Toast.makeText(this, "Seçilen veriler temizlendi", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun clearBrowsingData(
        clearHistory: Boolean,
        clearCookies: Boolean,
        clearCache: Boolean,
        clearSiteData: Boolean
    ) {
        if (clearCookies) {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
        if (clearSiteData) {
            android.webkit.WebStorage.getInstance().deleteAllData()
        }
        if (clearHistory) {
            prefs().edit().remove("history").apply()
        }
        if (clearCache) {
            // WebView örneği MainActivity'de olduğu için önbellek temizliğini
            // bir bayrakla işaretliyoruz; MainActivity onResume'da bunu görüp uygular.
            prefs().edit().putBoolean("pending_clear_cache", true).apply()
        }
    }

    // ---- Üçüncü taraf çerezleri ----

    private fun isThirdPartyCookiesBlocked(): Boolean = prefs().getBoolean("block_third_party_cookies", false)

    private fun buildThirdPartyCookiesRow(): View {
        return buildSwitchRow(
            "Üçüncü Taraf Çerezlerini Engelle",
            "Farklı sitelerin sizi takip etmesini sınırlar",
            isThirdPartyCookiesBlocked()
        ) { checked ->
            prefs().edit().putBoolean("block_third_party_cookies", checked).apply()
        }
    }

    // ---- Site izinleri ----

    private fun buildSitePermissionsRow(): View {
        return buildSettingsRow("Site İzinleri", "Kamera, mikrofon, konum") {
            showSitePermissionsList()
        }
    }

    private fun loadSitePermissions(): MutableList<SitePermission> {
        val raw = prefs().getString("site_permissions", "") ?: ""
        if (raw.isBlank()) return mutableListOf()
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split("::")
            if (parts.size == 3) SitePermission(parts[0], parts[1], parts[2]) else null
        }.toMutableList()
    }

    private fun saveSitePermissions(list: List<SitePermission>) {
        val raw = list.joinToString("\n") { "${it.host}::${it.type}::${it.decision}" }
        prefs().edit().putString("site_permissions", raw).apply()
    }

    private fun removeSitePermission(host: String, type: String) {
        val list = loadSitePermissions()
        list.removeAll { it.host == host && it.type == type }
        saveSitePermissions(list)
    }

    private fun permissionDisplayName(type: String): String = when (type) {
        "camera" -> "Kamera"
        "microphone" -> "Mikrofon"
        "location" -> "Konum"
        else -> type
    }

    private fun showSitePermissionsList() {
        val list = loadSitePermissions()
        val dialog = BottomSheetDialog(this)
        val scrollView = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(16), dp(8), dp(16))
        }
        scrollView.addView(container)

        if (list.isEmpty()) {
            container.addView(
                TextView(this).apply {
                    text = "Henüz bir izin kaydı yok"
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                    setTextColor(0xFF8E8E93.toInt())
                }
            )
        } else {
            list.forEach { perm ->
                container.addView(buildPermissionEntryRow(perm, dialog))
            }
        }

        dialog.setContentView(scrollView)
        dialog.show()
    }

    private fun buildPermissionEntryRow(perm: SitePermission, dialog: BottomSheetDialog): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val textContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textContainer.addView(
            TextView(this).apply {
                text = perm.host
                textSize = 15f
                setTextColor(0xFF1A1A1A.toInt())
            }
        )
        textContainer.addView(
            TextView(this).apply {
                val statusText = if (perm.decision == "allow") "İzin verildi" else "Reddedildi"
                text = "${permissionDisplayName(perm.type)} — $statusText"
                textSize = 13f
                setTextColor(0xFF8E8E93.toInt())
                setPadding(0, dp(2), 0, 0)
            }
        )

        val removeButton = TextView(this).apply {
            text = "Kaldır"
            textSize = 14f
            setTextColor(0xFF1976D2.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                removeSitePermission(perm.host, perm.type)
                dialog.dismiss()
                showSitePermissionsList()
            }
        }

        row.addView(textContainer)
        row.addView(removeButton)
        return row
    }

    // ---- Otomatik doldurma ----

    private fun buildSavedAddressesRow(): View {
        val count = loadSavedAddresses().size
        return buildSettingsRow("Adreslerim", if (count == 0) "Kayıtlı adres yok" else "$count adres kayıtlı") {
            showSavedAddressesList()
        }
    }

    private fun buildSystemAutofillRow(): View {
        return buildSettingsRow(
            "Sistem Otomatik Doldurma Ayarları",
            "Şifre ve kart bilgileri için Android'in kendi servisini aç"
        ) {
            openSystemAutofillSettings()
        }
    }

    private fun openSystemAutofillSettings() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
                Toast.makeText(this, "Sistem Ayarları > Diller ve Giriş > Otomatik Doldurma'yı aç", Toast.LENGTH_LONG).show()
            } catch (e2: Exception) {
                Toast.makeText(this, "Sistem ayarları açılamadı", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadSavedAddresses(): MutableList<SavedAddress> {
        val raw = prefs().getString("saved_addresses", "") ?: ""
        if (raw.isBlank()) return mutableListOf()
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split("::")
            if (parts.size == 6) {
                SavedAddress(
                    id = parts[0].toLongOrNull() ?: 0L,
                    label = parts[1],
                    fullName = parts[2],
                    email = parts[3],
                    phone = parts[4],
                    address = parts[5]
                )
            } else null
        }.toMutableList()
    }

    private fun saveSavedAddresses(list: List<SavedAddress>) {
        val raw = list.joinToString("\n") {
            "${it.id}::${it.label}::${it.fullName}::${it.email}::${it.phone}::${it.address}"
        }
        prefs().edit().putString("saved_addresses", raw).apply()
    }

    private fun showSavedAddressesList() {
        val list = loadSavedAddresses()
        val dialog = BottomSheetDialog(this)
        val rootColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val addRow = TextView(this).apply {
            text = "+ Yeni Adres Ekle"
            textSize = 15f
            setTextColor(0xFF1976D2.toInt())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dialog.dismiss()
                showAddressEditDialog(null)
            }
        }
        rootColumn.addView(addRow)
        rootColumn.addView(
            View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                setBackgroundColor(0xFFEEEEEE.toInt())
            }
        )

        val scrollView = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(16))
        }
        scrollView.addView(container)

        if (list.isEmpty()) {
            container.addView(
                TextView(this).apply {
                    text = "Henüz kayıtlı adres yok"
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                    setTextColor(0xFF8E8E93.toInt())
                }
            )
        } else {
            list.forEach { addr ->
                container.addView(buildSavedAddressRow(addr, dialog))
            }
        }

        rootColumn.addView(scrollView)
        dialog.setContentView(rootColumn)
        dialog.show()
    }

    private fun buildSavedAddressRow(addr: SavedAddress, dialog: BottomSheetDialog): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dialog.dismiss()
                showAddressEditDialog(addr)
            }
        }
        val textContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textContainer.addView(
            TextView(this).apply {
                text = addr.label
                textSize = 15f
                setTextColor(0xFF1A1A1A.toInt())
            }
        )
        textContainer.addView(
            TextView(this).apply {
                text = addr.fullName
                textSize = 12f
                setTextColor(0xFF8E8E93.toInt())
                setPadding(0, dp(2), 0, 0)
            }
        )

        val deleteButton = TextView(this).apply {
            text = "Sil"
            textSize = 14f
            setTextColor(0xFFD32F2F.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val list = loadSavedAddresses()
                list.removeAll { it.id == addr.id }
                saveSavedAddresses(list)
                dialog.dismiss()
                buildSettingsList()
            }
        }

        row.addView(textContainer)
        row.addView(deleteButton)
        return row
    }

    private fun showAddressEditDialog(existing: SavedAddress?) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val labelInput = EditText(this).apply { hint = "Etiket (örn. Ev)"; setText(existing?.label ?: "") }
        val nameInput = EditText(this).apply { hint = "Ad Soyad"; setText(existing?.fullName ?: "") }
        val emailInput = EditText(this).apply {
            hint = "E-posta"
            inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(existing?.email ?: "")
        }
        val phoneInput = EditText(this).apply {
            hint = "Telefon"
            inputType = InputType.TYPE_CLASS_PHONE
            setText(existing?.phone ?: "")
        }
        val addressInput = EditText(this).apply { hint = "Adres"; setText(existing?.address ?: "") }

        container.addView(labelInput)
        container.addView(nameInput)
        container.addView(emailInput)
        container.addView(phoneInput)
        container.addView(addressInput)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Yeni Adres" else "Adresi Düzenle")
            .setView(container)
            .setPositiveButton("Kaydet") { _, _ ->
                val list = loadSavedAddresses()
                val label = labelInput.text.toString().trim().ifBlank { "Adres" }
                val name = nameInput.text.toString().trim()
                val email = emailInput.text.toString().trim()
                val phone = phoneInput.text.toString().trim()
                val address = addressInput.text.toString().trim()

                if (existing != null) {
                    val item = list.find { it.id == existing.id }
                    if (item != null) {
                        item.label = label
                        item.fullName = name
                        item.email = email
                        item.phone = phone
                        item.address = address
                    }
                } else {
                    list.add(SavedAddress(System.currentTimeMillis(), label, name, email, phone, address))
                }
                saveSavedAddresses(list)
                buildSettingsList()
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    // ---- İndirilenler ----

    private fun buildAskBeforeDownloadRow(): View {
        return buildSwitchRow(
            "İndirmeden Önce Sor",
            "Her indirmede onay iste",
            prefs().getBoolean("ask_before_download", false)
        ) { checked ->
            prefs().edit().putBoolean("ask_before_download", checked).apply()
        }
    }

    private fun buildDownloadNotificationsRow(): View {
        return buildSwitchRow(
            "İndirme Bildirimleri",
            "İndirme tamamlandığında bildirim göster",
            prefs().getBoolean("download_notifications", true)
        ) { checked ->
            prefs().edit().putBoolean("download_notifications", checked).apply()
        }
    }
}
