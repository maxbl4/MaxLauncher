package com.maxbl4.maxlauncher.widgets

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.UserManager
import android.util.Log

/** The launcher's AppWidgetHost and the widget providers available to it. */
class WidgetHostManager(private val context: Context) {

    val manager: AppWidgetManager = AppWidgetManager.getInstance(context)
    val host = AppWidgetHost(context, HOST_ID)
    private val userManager = context.getSystemService(UserManager::class.java)

    fun startListening() {
        runCatching { host.startListening() }.onFailure { Log.w(TAG, "startListening failed", it) }
    }

    fun stopListening() {
        runCatching { host.stopListening() }.onFailure { Log.w(TAG, "stopListening failed", it) }
    }

    /** All widget providers of all profiles. */
    fun providers(): List<AppWidgetProviderInfo> =
        userManager.userProfiles.flatMap { runCatching { manager.getInstalledProvidersForProfile(it) }.getOrDefault(emptyList()) }

    fun info(appWidgetId: Int): AppWidgetProviderInfo? = manager.getAppWidgetInfo(appWidgetId)

    fun allocateId(): Int = host.allocateAppWidgetId()

    fun delete(appWidgetId: Int) {
        runCatching { host.deleteAppWidgetId(appWidgetId) }
    }

    /** Binds without asking when the user already allowed this launcher to create widgets. */
    fun bindIfAllowed(appWidgetId: Int, info: AppWidgetProviderInfo): Boolean =
        manager.bindAppWidgetIdIfAllowed(appWidgetId, info.profile, info.provider, null)

    fun createView(context: Context, appWidgetId: Int, info: AppWidgetProviderInfo): AppWidgetHostView =
        host.createView(context, appWidgetId, info)

    fun userSerial(info: AppWidgetProviderInfo): Long = userManager.getSerialNumberForUser(info.profile)

    private companion object {
        const val HOST_ID = 1024
        const val TAG = "WidgetHostManager"
    }
}
