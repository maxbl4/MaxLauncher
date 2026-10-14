package com.maxbl4.maxlauncher

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.maxbl4.maxlauncher.home.addWidget
import com.maxbl4.maxlauncher.ui.LauncherScreen
import com.maxbl4.maxlauncher.ui.LauncherTheme
import com.maxbl4.maxlauncher.ui.WidgetOption
import kotlinx.coroutines.flow.MutableSharedFlow

class MainActivity : ComponentActivity() {

    /** Home presses; true when the home screen was already in front (not coming back from an app). */
    private val homePresses = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    private val app get() = application as LauncherApplication

    /** A widget being added: waiting for the bind permission and/or its configuration screen. */
    private class PendingWidget(
        val appWidgetId: Int,
        val info: AppWidgetProviderInfo,
        val spanX: Int,
        val spanY: Int,
        val pageId: Long,
        val cell: Pair<Int, Int>?,
    )

    private var pendingWidget: PendingWidget? = null

    private val bindWidget = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val pending = pendingWidget ?: return@registerForActivityResult
        if (result.resultCode == RESULT_OK) configureOrPlace(pending) else cancelWidget(pending)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val transparent = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = transparent, navigationBarStyle = transparent)
        super.onCreate(savedInstanceState)
        // No scrim behind the 3-button navigation bar: the wallpaper shows through, as on Samsung.
        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false
        // The home activity must never finish on Back; screens register their own handlers on top.
        onBackPressedDispatcher.addCallback(this) {}

        setContent {
            LauncherTheme {
                LauncherScreen(
                    app = app,
                    homePresses = homePresses,
                    onAddWidget = ::addWidget,
                    onLockScreen = ::lockScreen,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        app.widgets.startListening()
    }

    override fun onStop() {
        super.onStop()
        app.widgets.stopListening()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            // As Launcher3: in front and focused, and not brought back from another app.
            val alreadyOnHome = hasWindowFocus() && (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) == 0
            homePresses.tryEmit(alreadyOnHome)
        }
    }

    private fun lockScreen() {
        if (!LockScreenService.lock()) {
            Toast.makeText(this, R.string.lock_service_off, Toast.LENGTH_LONG).show()
        }
    }

    // --- Adding widgets ---

    /** [cell]: where the widget was dropped, or null to use the first free spot. */
    private fun addWidget(option: WidgetOption, pageId: Long, cell: Pair<Int, Int>?) {
        val widgets = app.widgets
        val id = widgets.allocateId()
        val pending = PendingWidget(id, option.info, option.spanX, option.spanY, pageId, cell)
        pendingWidget = pending
        if (widgets.bindIfAllowed(id, option.info)) {
            configureOrPlace(pending)
        } else {
            bindWidget.launch(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, option.info.provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, option.info.profile),
            )
        }
    }

    private fun configureOrPlace(pending: PendingWidget) {
        val info = pending.info
        val optional = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
        if (info.configure != null && !optional) {
            try {
                app.widgets.host.startAppWidgetConfigureActivityForResult(this, pending.appWidgetId, 0, REQUEST_CONFIGURE, null)
                return
            } catch (e: Exception) {
                cancelWidget(pending)
                return
            }
        }
        placeWidget(pending)
    }

    @Deprecated("AppWidgetHost reports the configuration result through onActivityResult")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CONFIGURE) return
        val pending = pendingWidget ?: return
        if (resultCode == RESULT_OK) placeWidget(pending) else cancelWidget(pending)
    }

    private fun placeWidget(pending: PendingWidget) {
        pendingWidget = null
        val serial = app.widgets.userSerial(pending.info)
        app.homeRepository.update {
            it.addWidget(pending.appWidgetId, pending.info.provider.flattenToString(), serial, pending.spanX, pending.spanY, pending.pageId, pending.cell)
        }
    }

    private fun cancelWidget(pending: PendingWidget) {
        pendingWidget = null
        app.widgets.delete(pending.appWidgetId)
    }

    private companion object {
        const val REQUEST_CONFIGURE = 11
    }
}
