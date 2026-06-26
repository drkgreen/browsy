package com.viabrowser.lite

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

data class SitePermission(val host: String, val type: String, var decision: String)

/**
 * Site bazlı izin kararlarının (kamera/mikrofon/konum) saklanması ve
 * Android'in kendi izin diyaloğunu tetikleme akışı. ActivityCompat.requestPermissions
 * bir Activity gerektirdiği için Context değil doğrudan Activity alıyor;
 * MainActivity'nin onRequestPermissionsResult'ı sadece buraya delege ediyor.
 */
class SitePermissionsManager(private val activity: Activity) {

    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var pendingGeoPermissionCallback: GeolocationPermissions.Callback? = null
    private var pendingGeoPermissionOrigin: String? = null

    private fun prefs() = activity.getSharedPreferences("via_lite_prefs", Context.MODE_PRIVATE)

    fun resourceToType(resource: String): String? = when (resource) {
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "camera"
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "microphone"
        else -> null
    }

    private fun androidPermissionsFor(type: String): Array<String> = when (type) {
        "camera" -> arrayOf(Manifest.permission.CAMERA)
        "microphone" -> arrayOf(Manifest.permission.RECORD_AUDIO)
        "location" -> arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        else -> emptyArray()
    }

    private fun hasAndroidPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun permissionDisplayName(type: String): String = when (type) {
        "camera" -> "kamera"
        "microphone" -> "mikrofon"
        "location" -> "konum"
        else -> type
    }

    fun showSitePermissionDialog(host: String, types: List<String>, request: PermissionRequest) {
        val typeNames = types.joinToString(" ve ") { permissionDisplayName(it) }
        AlertDialog.Builder(activity)
            .setTitle("İzin İsteği")
            .setMessage("$host, $typeNames erişimi istiyor. İzin verilsin mi?")
            .setPositiveButton("İzin Ver") { _, _ ->
                types.forEach { setSitePermissionDecision(host, it, "allow") }
                resolveWebPermissionRequest(host, request)
            }
            .setNegativeButton("Reddet") { _, _ ->
                types.forEach { setSitePermissionDecision(host, it, "deny") }
                request.deny()
            }
            .setCancelable(false)
            .show()
    }

    fun resolveWebPermissionRequest(host: String, request: PermissionRequest) {
        val granted = mutableListOf<String>()
        for (resource in request.resources) {
            val type = resourceToType(resource) ?: continue
            val decision = getSitePermissionDecision(host, type)
            if (decision == "allow") {
                val androidPerms = androidPermissionsFor(type)
                if (androidPerms.all { hasAndroidPermission(it) }) {
                    granted.add(resource)
                } else {
                    pendingWebPermissionRequest = request
                    ActivityCompat.requestPermissions(activity, androidPerms, REQUEST_CODE_CAMERA_MIC)
                    return
                }
            }
        }
        if (granted.isNotEmpty()) {
            request.grant(granted.toTypedArray())
        } else {
            request.deny()
        }
    }

    fun resolveGeoPermission(origin: String, callback: GeolocationPermissions.Callback) {
        val host = Uri.parse(origin).host ?: origin
        val decision = getSitePermissionDecision(host, "location")
        if (decision == "allow") {
            val androidPerms = androidPermissionsFor("location")
            if (androidPerms.any { hasAndroidPermission(it) }) {
                callback.invoke(origin, true, false)
            } else {
                pendingGeoPermissionCallback = callback
                pendingGeoPermissionOrigin = origin
                ActivityCompat.requestPermissions(activity, androidPerms, REQUEST_CODE_LOCATION)
            }
        } else {
            callback.invoke(origin, false, false)
        }
    }

    /**
     * MainActivity'nin onRequestPermissionsResult'ından çağrılır.
     * requestCode bu sınıfa aitse true döner (MainActivity başka bir şey yapmasın).
     */
    fun onRequestPermissionsResult(requestCode: Int): Boolean {
        return when (requestCode) {
            REQUEST_CODE_CAMERA_MIC -> {
                val request = pendingWebPermissionRequest
                pendingWebPermissionRequest = null
                if (request != null) {
                    val host = Uri.parse(request.origin.toString()).host ?: request.origin.toString()
                    resolveWebPermissionRequest(host, request)
                }
                true
            }
            REQUEST_CODE_LOCATION -> {
                val callback = pendingGeoPermissionCallback
                val origin = pendingGeoPermissionOrigin
                pendingGeoPermissionCallback = null
                pendingGeoPermissionOrigin = null
                if (callback != null && origin != null) {
                    resolveGeoPermission(origin, callback)
                }
                true
            }
            else -> false
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

    fun getSitePermissionDecision(host: String, type: String): String? {
        return loadSitePermissions().find { it.host == host && it.type == type }?.decision
    }

    fun setSitePermissionDecision(host: String, type: String, decision: String) {
        val list = loadSitePermissions()
        val existing = list.find { it.host == host && it.type == type }
        if (existing != null) {
            existing.decision = decision
        } else {
            list.add(SitePermission(host, type, decision))
        }
        saveSitePermissions(list)
    }

    companion object {
        private const val REQUEST_CODE_CAMERA_MIC = 1001
        private const val REQUEST_CODE_LOCATION = 1002
    }
}
