package com.maxbl4.maxlauncher

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.geometry.Size
import com.maxbl4.maxlauncher.home.addShortcut
import com.maxbl4.maxlauncher.home.addWidget
import com.maxbl4.maxlauncher.ui.widgetSpans

/**
 * Receives "add to home screen" requests from apps: shortcuts (a browser bookmark, a contact)
 * and widgets (an app's own "add widget" button). The user already confirmed in the requesting
 * app, so the item goes to the first free spot, starting from the home page.
 */
class PinItemActivity : ComponentActivity() {

    private val app get() = application as LauncherApplication
    private var pendingWidget: Pair<LauncherApps.PinItemRequest, Int>? = null

    private val bindWidget = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val (request, appWidgetId) = pendingWidget ?: return@registerForActivityResult finish()
        if (result.resultCode == RESULT_OK) acceptWidget(request, appWidgetId) else {
            app.widgets.delete(appWidgetId)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = getSystemService(LauncherApps::class.java).getPinItemRequest(intent)
        if (request == null || !request.isValid) return finish()
        when (request.requestType) {
            LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT -> {
                addShortcut(request)
                finish()
            }
            LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET -> addWidget(request)
            else -> finish()
        }
    }

    private fun addShortcut(request: LauncherApps.PinItemRequest) {
        val info = request.shortcutInfo ?: return
        if (!request.accept()) return
        val serial = app.appRepository.userSerial(info.userHandle)
        val label = (info.shortLabel ?: info.longLabel ?: "").toString()
        app.homeRepository.update { it.addShortcut(info.`package`, info.id, serial, label) }
        Toast.makeText(this, R.string.shortcut_added, Toast.LENGTH_SHORT).show()
    }

    private fun addWidget(request: LauncherApps.PinItemRequest) {
        val info = request.getAppWidgetProviderInfo(this) ?: return finish()
        val appWidgetId = app.widgets.allocateId()
        pendingWidget = request to appWidgetId
        if (app.widgets.bindIfAllowed(appWidgetId, info)) {
            acceptWidget(request, appWidgetId)
        } else {
            bindWidget.launch(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile),
            )
        }
    }

    private fun acceptWidget(request: LauncherApps.PinItemRequest, appWidgetId: Int) {
        val info = request.getAppWidgetProviderInfo(this)
        val accepted = info != null && request.accept(Bundle().apply { putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId) })
        val layout = app.homeRepository.layout.value
        if (!accepted || layout == null) {
            app.widgets.delete(appWidgetId)
            return finish()
        }
        val (spanX, spanY) = widgetSpans(info, approximateCell(layout.columns, layout.rows), layout.columns, layout.rows)
        val serial = app.widgets.userSerial(info)
        app.homeRepository.update {
            it.addWidget(appWidgetId, info.provider.flattenToString(), serial, spanX, spanY, it.homePageId)
        }
        Toast.makeText(this, R.string.widget_added, Toast.LENGTH_SHORT).show()
        finish()
    }

    /** Home grid cell size estimated from the screen (the home screen is not on display here). */
    private fun approximateCell(columns: Int, rows: Int): Size {
        val metrics = resources.displayMetrics
        val chromeHeight = HOME_CHROME_DP * metrics.density // status bar, indicator, dock, nav bar
        return Size(metrics.widthPixels.toFloat() / columns, (metrics.heightPixels - chromeHeight) / rows)
    }

    private companion object {
        const val HOME_CHROME_DP = 220f
    }
}
