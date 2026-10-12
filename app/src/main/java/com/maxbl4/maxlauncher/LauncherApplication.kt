package com.maxbl4.maxlauncher

import android.app.Application
import com.maxbl4.maxlauncher.apps.AppRepository
import com.maxbl4.maxlauncher.drawer.DrawerLayout
import com.maxbl4.maxlauncher.home.HomeRepository
import com.maxbl4.maxlauncher.settings.JsonFileStore
import com.maxbl4.maxlauncher.settings.LauncherSettings
import com.maxbl4.maxlauncher.widgets.WidgetHostManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LauncherApplication : Application() {
    val appRepository by lazy { AppRepository(this) }
    val widgets by lazy { WidgetHostManager(this) }
    val homeRepository by lazy { HomeRepository(this, appRepository, widgets) }
    val settings by lazy { JsonFileStore(this, "settings.json", LauncherSettings.serializer()) { LauncherSettings() } }
    val drawer by lazy { JsonFileStore(this, "drawer.json", DrawerLayout.serializer()) { DrawerLayout() } }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        // Uninstalled apps leave drawer folders and the hidden list (home cleans up itself).
        scope.launch {
            appRepository.packageRemovals.collect { removal ->
                drawer.update { it.removePackage(removal.packageName, removal.userSerial) }
                settings.update { s ->
                    s.copy(hiddenApps = s.hiddenApps.filterNot {
                        it.userSerial == removal.userSerial && it.component.substringBefore('/') == removal.packageName
                    }.toSet())
                }
            }
        }
    }
}
