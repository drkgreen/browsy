package com.viabrowser.lite

import android.graphics.Bitmap

/**
 * Tek bir tarayıcı sekmesinin durumu. WebView nesnesi de burada tutuluyor
 * çünkü her sekme artık kendi WebView'ine sahip (paylaşılan tek WebView
 * mimarisi sekmeler arası geçmiş sızıntısına yol açtığı için kaldırıldı).
 */
data class TabInfo(
    val id: Long,
    var title: String = "Yeni Sekme",
    var url: String? = null,
    var favicon: Bitmap? = null,
    var isDesktopMode: Boolean = false,
    var openerTabId: Long? = null,
    var webView: NestedScrollWebView? = null
)

/**
 * Sekme listesinin "muhasebesini" tutar: hangi sekmeler var, hangisi aktif,
 * kapatınca hangisine dönülecek. WebView'in gerçek oluşturulması/yapılandırılması
 * (indirme, geçmiş, izinler, reklam engelleme gibi onlarca özelliğe bağlı
 * olduğu için) MainActivity'de kalıyor -- bu sınıf sadece saf liste/indeks
 * mantığını içeriyor, Android UI'sine hiç dokunmuyor.
 */
class TabManager {
    val tabs = mutableListOf<TabInfo>()
    var currentTabIndex = 0
    private var nextTabId = 1L

    fun nextId(): Long = nextTabId++

    fun currentTab(): TabInfo = tabs[currentTabIndex]

    /** Geçerli sekmeyi kapatır, kapatılan TabInfo'yu döner (çağıran tarafın WebView'ini temizlemesi için). */
    fun closeTab(index: Int, switchToIndex: Int? = null): TabInfo? {
        if (index !in tabs.indices) return null

        if (tabs.size <= 1) {
            val closed = tabs[index]
            tabs[0] = TabInfo(id = nextId())
            currentTabIndex = 0
            return closed
        }

        val wasCurrent = index == currentTabIndex
        val closed = tabs.removeAt(index)

        if (switchToIndex != null) {
            // Belirli bir sekmeye (örn. açan/opener sekme) kesin olarak dönülüyor;
            // index kaydırma listeden çıkarma sonrası yeniden hesaplanıyor.
            currentTabIndex = if (switchToIndex > index) switchToIndex - 1 else switchToIndex
        } else if (index < currentTabIndex) {
            currentTabIndex -= 1
        } else if (wasCurrent && currentTabIndex >= tabs.size) {
            currentTabIndex = tabs.size - 1
        }

        return closed
    }

    /** Verilen sekmeyi açan (opener) sekmenin index'ini bulur, yoksa -1 döner. */
    fun indexOfOpener(tab: TabInfo): Int {
        val openerId = tab.openerTabId ?: return -1
        return tabs.indexOfFirst { it.id == openerId }
    }
}
