package com.maxbl4.maxlauncher.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Keeps the list of launchable apps of all user profiles up to date, sorted by [AppSorting].
 * Lives as long as the process (owned by [com.maxbl4.maxlauncher.LauncherApplication]).
 */
class AppRepository(private val context: Context) {

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val entries = HashMap<AppKey, AppEntry>()
    private val myUser = Process.myUserHandle()
    private val densityDpi get() = context.resources.displayMetrics.densityDpi
    private val iconSizePx get() = (ICON_BITMAP_DP * context.resources.displayMetrics.density).roundToInt()

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = reloadPackage(packageName, user)
        override fun onPackageAdded(packageName: String, user: UserHandle) = reloadPackage(packageName, user)
        override fun onPackageChanged(packageName: String, user: UserHandle) = reloadPackage(packageName, user)
        override fun onPackagesAvailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) = reloadAll()
        override fun onPackagesUnavailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) = reloadAll()
        override fun onPackagesSuspended(packageNames: Array<String>, user: UserHandle) = reloadAll()
        override fun onPackagesUnsuspended(packageNames: Array<String>, user: UserHandle) = reloadAll()
    }

    // Labels are localized, and the sort order depends on them.
    private val localeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reloadAll()
    }

    init {
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        ContextCompat.registerReceiver(
            context, localeReceiver, IntentFilter(Intent.ACTION_LOCALE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        reloadAll()
    }

    fun reloadAll() {
        scope.launch {
            mutex.withLock {
                entries.clear()
                for (user in launcherApps.profiles) {
                    launcherApps.getActivityList(null, user).forEach(::put)
                }
                publish()
            }
        }
    }

    private fun reloadPackage(packageName: String, user: UserHandle) {
        scope.launch {
            mutex.withLock {
                entries.keys.removeAll { it.packageName == packageName && it.user == user }
                launcherApps.getActivityList(packageName, user).forEach(::put)
                publish()
            }
        }
    }

    private fun put(info: LauncherActivityInfo) {
        val key = AppKey(info.componentName, info.user)
        val isWork = info.user != myUser
        val drawable = info.getIcon(densityDpi).let {
            if (isWork) context.packageManager.getUserBadgedIcon(it, info.user) else it
        }
        entries[key] = AppEntry(
            key = key,
            label = info.label.toString().trim(),
            icon = drawable.toBitmap(iconSizePx, iconSizePx).asImageBitmap(),
            isSystem = info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            isWork = isWork,
        )
    }

    private fun publish() {
        _apps.value = entries.values.sortedWith(AppSorting.comparator { it.label })
    }

    // --- Actions ---

    fun launch(key: AppKey, sourceBounds: Rect?, options: Bundle?) = runAction {
        launcherApps.startMainActivity(key.component, key.user, sourceBounds, options)
    }

    fun openAppInfo(key: AppKey, sourceBounds: Rect?) = runAction {
        launcherApps.startAppDetailsActivity(key.component, key.user, sourceBounds, null)
    }

    fun uninstall(key: AppKey) = runAction {
        val intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", key.packageName, null))
            .putExtra(Intent.EXTRA_USER, key.user)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun launchShortcut(shortcut: AppShortcut, sourceBounds: Rect?, options: Bundle?) = runAction {
        launcherApps.startShortcut(shortcut.info, sourceBounds, options)
    }

    /** Static and dynamic shortcuts of the app; empty unless we are the default launcher. */
    suspend fun shortcuts(key: AppKey): List<AppShortcut> = withContext(Dispatchers.IO) {
        if (!launcherApps.hasShortcutHostPermission()) return@withContext emptyList()
        val query = LauncherApps.ShortcutQuery()
            .setPackage(key.packageName)
            .setActivity(key.component)
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC)
        val found = runCatching { launcherApps.getShortcuts(query, key.user) }
            .onFailure { Log.w(TAG, "getShortcuts failed for ${key.packageName}", it) }
            .getOrNull().orEmpty()
        found
            .filter { it.isEnabled }
            .sortedWith(compareBy({ it.isDynamic }, { it.rank }))
            .take(MAX_SHORTCUTS)
            .map { info ->
                AppShortcut(
                    info = info,
                    label = (info.shortLabel ?: info.longLabel ?: "").toString(),
                    icon = runCatching { launcherApps.getShortcutIconDrawable(info, densityDpi) }.getOrNull()
                        ?.toBitmap(iconSizePx, iconSizePx)?.asImageBitmap(),
                )
            }
    }

    private fun runAction(action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            // ActivityNotFoundException, SecurityException for disabled/removed apps, etc.
            Log.w(TAG, "Action failed", e)
            Toast.makeText(context, e.localizedMessage ?: e.toString(), Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val TAG = "AppRepository"
        const val ICON_BITMAP_DP = 64
        const val MAX_SHORTCUTS = 4
    }
}
