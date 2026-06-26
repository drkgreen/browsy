package com.viabrowser.lite

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Arama motorlarının kendi "autocomplete" uç noktalarından canlı öneri çeker.
 * Google, Bing ve DuckDuckGo'nun öneri API'leri aynı basit JSON dizi formatını
 * kullanıyor: ["sorgu", ["öneri1", "öneri2", ...]] -- bu yüzden tek bir
 * ayrıştırıcı üçüne de yetiyor.
 *
 * MainActivity'nin içine gömülmek yerine ayrı dosyada tutuluyor; ağ işini
 * kendi arka plan thread'inde yapıp sonucu ana thread'e taşıyarak teslim
 * ediyor, çağıran tarafın bunu hatırlamasına gerek kalmıyor.
 */
class SearchSuggestionsProvider {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun fetchSuggestions(query: String, engineKey: String, onResult: (List<String>) -> Unit) {
        if (query.isBlank()) {
            onResult(emptyList())
            return
        }
        Thread {
            val suggestions = try {
                fetchFromEngine(query, engineKey)
            } catch (e: Exception) {
                emptyList()
            }
            mainHandler.post { onResult(suggestions) }
        }.start()
    }

    private fun fetchFromEngine(query: String, engineKey: String): List<String> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = when (engineKey) {
            "bing" -> "https://www.bing.com/osjson.aspx?query=$encoded"
            "duckduckgo" -> "https://duckduckgo.com/ac/?q=$encoded&type=list"
            else -> "https://www.google.com/complete/search?client=firefox&q=$encoded"
        }

        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome Mobile Safari/537.36"
            )
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return emptyList()

            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val outer = JSONArray(body)
            val suggestionsArray = outer.optJSONArray(1) ?: return emptyList()

            val result = mutableListOf<String>()
            for (i in 0 until suggestionsArray.length()) {
                val item = suggestionsArray.optString(i)
                if (!item.isNullOrBlank()) result.add(item)
            }
            result.take(MAX_SUGGESTIONS)
        } catch (e: Exception) {
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        private const val MAX_SUGGESTIONS = 6
    }
}
