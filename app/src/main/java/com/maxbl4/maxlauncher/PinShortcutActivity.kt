package com.maxbl4.maxlauncher

import android.app.Activity
import android.content.pm.LauncherApps
import android.os.Bundle
import android.widget.Toast
import com.maxbl4.maxlauncher.home.addShortcut

/**
 * Receives "add to home screen" requests from apps (e.g. a browser bookmark or a contact).
 * The user already confirmed in the requesting app, so the shortcut is added to the first free
 * cell starting from the home page.
 */
class PinShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launcherApps = getSystemService(LauncherApps::class.java)
        val request = launcherApps.getPinItemRequest(intent)
        val info = request?.shortcutInfo
        if (request != null && info != null && request.isValid &&
            request.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT &&
            request.accept()
        ) {
            val app = application as LauncherApplication
            val serial = app.appRepository.userSerial(info.userHandle)
            val label = (info.shortLabel ?: info.longLabel ?: "").toString()
            app.homeRepository.update { it.addShortcut(info.`package`, info.id, serial, label) }
            Toast.makeText(this, R.string.shortcut_added, Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
