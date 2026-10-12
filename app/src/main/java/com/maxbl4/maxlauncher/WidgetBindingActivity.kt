package com.maxbl4.maxlauncher

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.maxbl4.maxlauncher.home.PendingWidget

/**
 * Creates widgets one at a time (import and restore), going through the "allow widget" and
 * widget setup screens where needed. Each created widget is handed to [onWidgetCreated]; once
 * all are handled, [onWidgetsDone] gets the report lines of the skipped ones.
 */
abstract class WidgetBindingActivity : ComponentActivity() {

    protected val app get() = application as LauncherApplication

    /** True while widgets are being created. */
    protected var creatingWidgets by mutableStateOf(false)
        private set

    private val queue = ArrayDeque<Pair<PendingWidget, AppWidgetProviderInfo>>()
    private val skipped = mutableListOf<String>()
    private var current: Triple<PendingWidget, Int, AppWidgetProviderInfo>? = null

    private val bindWidget = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val (widget, id, _) = current ?: return@registerForActivityResult
        if (result.resultCode == RESULT_OK) configureOrCreate() else skipCurrent(widget, id, R.string.import_widget_not_allowed)
    }

    protected abstract fun onWidgetCreated(widget: PendingWidget, appWidgetId: Int, provider: AppWidgetProviderInfo)

    protected abstract fun onWidgetsDone(skipped: List<String>)

    protected fun createWidgets(widgets: List<Pair<PendingWidget, AppWidgetProviderInfo>>) {
        queue.clear()
        queue.addAll(widgets)
        skipped.clear()
        creatingWidgets = true
        next()
    }

    private fun next() {
        val (widget, provider) = queue.removeFirstOrNull() ?: run {
            creatingWidgets = false
            return onWidgetsDone(skipped.toList())
        }
        val id = app.widgets.allocateId()
        current = Triple(widget, id, provider)
        if (app.widgets.bindIfAllowed(id, provider)) {
            configureOrCreate()
        } else {
            bindWidget.launch(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, provider.profile),
            )
        }
    }

    private fun configureOrCreate() {
        val (widget, id, provider) = current ?: return
        val optional = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            provider.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
        if (provider.configure != null && !optional) {
            try {
                app.widgets.host.startAppWidgetConfigureActivityForResult(this, id, 0, REQUEST_CONFIGURE, null)
            } catch (e: Exception) {
                skipCurrent(widget, id, R.string.import_widget_setup_failed)
            }
            return
        }
        createCurrent()
    }

    @Deprecated("AppWidgetHost reports the configuration result through onActivityResult")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CONFIGURE) return
        val (widget, id, _) = current ?: return
        if (resultCode == RESULT_OK) createCurrent() else skipCurrent(widget, id, R.string.import_widget_setup_cancelled)
    }

    private fun createCurrent() {
        val (widget, id, provider) = current ?: return
        current = null
        onWidgetCreated(widget, id, provider)
        next()
    }

    private fun skipCurrent(widget: PendingWidget, id: Int, reason: Int) {
        current = null
        app.widgets.delete(id)
        skipped += getString(reason, widget.label)
        next()
    }

    private companion object {
        const val REQUEST_CONFIGURE = 21
    }
}
