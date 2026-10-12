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
import android.os.UserManager
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.maxbl4.maxlauncher.home.AppRef
import com.maxbl4.maxlauncher.home.ShortcutItem
import com.maxbl4.maxlauncher.home.ShortcutTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private val userManager = context.getSystemService(UserManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val entries = HashMap<AppKey, AppEntry>()
    private val myUser = Process.myUserHandle()
    private val densityDpi get() = context.resources.displayMetrics.densityDpi
    private val iconSizePx get() = (ICON_BITMAP_DP * context.resources.displayMetrics.density).roundToInt()

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    private val _packageRemovals = MutableSharedFlow<PackageRemoval>(extraBufferCapacity = 16)

    /** Packages uninstalled from a profile (not emitted for updates). */
    val packageRemovals: SharedFlow<PackageRemoval> = _packageRemovals.asSharedFlow()

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            _packageRemovals.tryEmit(PackageRemoval(packageName, userManager.getSerialNumberForUser(user)))
            reloadPackage(packageName, user)
        }
        override fun onPackageAdded(packageName: String, user: UserHandle) = reloadPackage(packageName, user)
        override fun onPackageChanged(packageName: String, user: UserHandle) = reloadPackage(packageName, user)
        override fun onPackagesAvailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) = reloadAll()
        override fun onPackagesUnavailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) = reloadAll()
        override fun onPackagesSuspended(packageNames: Array<String>, user: UserHandle) = reloadAll()
        override fun onPackagesUnsuspended(packageNames: Array<String>, user: UserHandle) = reloadAll()
    }

    private val _workPaused = MutableStateFlow(false)

    /** The work profile exists but is paused (quiet mode). */
    val workPaused: StateFlow<Boolean> = _workPaused.asStateFlow()

    // Labels are localized (the sort order depends on them); profiles come and go.
    private val reloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reloadAll()
    }

    init {
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_LOCALE_CHANGED)
            addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
            addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
            addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNLOCKED)
        }
        ContextCompat.registerReceiver(context, reloadReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        reloadAll()
    }

    private fun otherProfiles(): List<UserHandle> = launcherApps.profiles.filter { it != myUser }

    fun hasWorkProfile(): Boolean = otherProfiles().isNotEmpty()

    fun unpauseWork() {
        otherProfiles().forEach { user ->
            runCatching { userManager.requestQuietModeEnabled(false, user) }
                .onFailure { Log.w(TAG, "Unpausing $user failed", it) }
        }
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
            ref = AppRef(info.componentName.flattenToString(), userManager.getSerialNumberForUser(info.user)),
            label = info.label.toString().trim(),
            icon = drawable.toBitmap(iconSizePx, iconSizePx).asImageBitmap(),
            isSystem = info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            isWork = isWork,
        )
    }

    private fun publish() {
        _apps.value = entries.values.sortedWith(AppSorting.comparator { it.label })
        _workPaused.value = otherProfiles().any { userManager.isQuietModeEnabled(it) }
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

    /** Resolves pinned shortcuts placed on the home screen; missing or revoked ones are left out. */
    suspend fun pinnedShortcuts(items: List<ShortcutItem>): Map<Long, AppShortcut> = withContext(Dispatchers.IO) {
        if (items.isEmpty() || !launcherApps.hasShortcutHostPermission()) return@withContext emptyMap()
        val result = HashMap<Long, AppShortcut>()
        items.groupBy { it.packageName to it.userSerial }.forEach { (key, group) ->
            val (packageName, serial) = key
            val user = userManager.getUserForSerialNumber(serial) ?: return@forEach
            val query = LauncherApps.ShortcutQuery()
                .setPackage(packageName)
                .setShortcutIds(group.map { it.shortcutId })
                .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            val infos = runCatching { launcherApps.getShortcuts(query, user) }
                .onFailure { Log.w(TAG, "getShortcuts failed for $packageName", it) }
                .getOrNull().orEmpty()
                .associateBy { it.id }
            for (item in group) {
                val info = infos[item.shortcutId] ?: continue
                result[item.id] = AppShortcut(
                    info = info,
                    label = (info.shortLabel ?: info.longLabel ?: item.label).toString(),
                    icon = runCatching { launcherApps.getShortcutIconDrawable(info, densityDpi) }.getOrNull()
                        ?.toBitmap(iconSizePx, iconSizePx)?.asImageBitmap(),
                )
            }
        }
        result
    }

    /**
     * Static and dynamic shortcuts of all personal apps, with their short and long labels; used
     * to recognise shortcut icons when importing a layout. Empty unless we are the default launcher.
     */
    suspend fun allShortcuts(): List<Pair<Set<String>, ShortcutTarget>> = withContext(Dispatchers.IO) {
        if (!launcherApps.hasShortcutHostPermission()) return@withContext emptyList()
        val flags = LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
        val serial = userManager.getSerialNumberForUser(myUser)
        apps.value.filter { !it.isWork }.map { it.key.packageName }.distinct().flatMap { pkg ->
            val query = LauncherApps.ShortcutQuery().setPackage(pkg).setQueryFlags(flags)
            runCatching { launcherApps.getShortcuts(query, myUser) }.getOrNull().orEmpty()
                .filter { it.isEnabled }
                .map { info ->
                    val labels = setOfNotNull(info.shortLabel?.toString()?.trim(), info.longLabel?.toString()?.trim())
                    labels to ShortcutTarget(pkg, info.id, serial, info.shortLabel?.toString() ?: labels.firstOrNull().orEmpty())
                }
        }
    }

    /** Tells the system which shortcuts of a package are still pinned on the home screen. */
    fun setPinnedShortcuts(packageName: String, userSerial: Long, shortcutIds: List<String>) {
        val user = userManager.getUserForSerialNumber(userSerial) ?: return
        runCatching { launcherApps.pinShortcuts(packageName, shortcutIds, user) }
            .onFailure { Log.w(TAG, "pinShortcuts failed for $packageName", it) }
    }

    fun userSerial(user: UserHandle): Long = userManager.getSerialNumberForUser(user)

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
